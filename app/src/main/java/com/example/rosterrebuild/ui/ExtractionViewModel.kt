package com.example.rosterrebuild.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rosterrebuild.*
import com.example.rosterrebuild.data.local.RosterEntity
import com.example.rosterrebuild.data.repository.RosterRepository
import com.example.rosterrebuild.data.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class ActiveScreen {
    SAVED_ROSTERS_DASHBOARD,
    ROSTER_UPLOAD,
    ROSTER_REVIEW,
    MONTHLY_CALENDAR,
    DAILY_EDITOR
}

enum class ExtractionStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    ERROR
}

data class ExtractionUiState(
    val status: ExtractionStatus = ExtractionStatus.IDLE,
    val currentStage: String = "",
    val progressPercent: Int? = null,
    val selectedImageUri: Uri? = null,
    val cachedImagePath: String? = null,
    val sourceBitmap: Bitmap? = null,
    val employeeId: String = "ABHL341",
    val detectedPeriod: DetectedPeriod? = null,
    val manualMonth: String = "",
    val manualYear: String = "",
    val manualFloor: String = "",
    val isCustomFloor: Boolean = false,
    val customFloorText: String = "",
    val detectedFloor: String? = null,
    val showManualPeriod: Boolean = false,
    val result: RosterResult? = null,
    val schedule: List<DailyScheduleEntry> = emptyList(),
    val diagnostics: ExtractionDiagnostics? = null,
    val error: String? = null,
    val isSaved: Boolean = false,
    val activeRoster: RosterEntity? = null,
    val currentScreen: ActiveScreen = ActiveScreen.SAVED_ROSTERS_DASHBOARD,
    val pendingRosterId: String? = null,
    val editingDay: Int? = null
)

class ExtractionViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val repository: RosterRepository,
    private val storageManager: StorageManager,
    private val rosterProcessor: RosterProcessor = RosterProcessor()
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_IMAGE_URI = "key_image_uri"
        private const val KEY_CACHED_IMAGE_PATH = "key_cached_image_path"
        private const val KEY_EMPLOYEE_ID = "key_employee_id"
        private const val KEY_MANUAL_MONTH = "key_manual_month"
        private const val KEY_MANUAL_YEAR = "key_manual_year"
        private const val KEY_MANUAL_FLOOR = "key_manual_floor"
        private const val KEY_CUSTOM_FLOOR_TEXT = "key_custom_floor_text"
        private const val KEY_IS_CUSTOM_FLOOR = "key_is_custom_floor"
        private const val KEY_SHOW_MANUAL = "key_show_manual"
        private const val KEY_LAST_STAGE = "key_last_stage"
        private const val KEY_ACTIVE_ROSTER_ID = "key_active_roster_id"
        private const val KEY_PENDING_ROSTER_ID = "key_pending_roster_id"
        private const val KEY_STATUS = "key_status"
        private const val KEY_CURRENT_SCREEN = "key_current_screen"
        private const val KEY_EDITING_DAY = "key_editing_day"

        fun provideFactory(
            owner: androidx.savedstate.SavedStateRegistryOwner,
            application: Application,
            defaultArgs: android.os.Bundle? = null
        ): ViewModelProvider.Factory = object : androidx.lifecycle.AbstractSavedStateViewModelFactory(owner, defaultArgs) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                key: String,
                modelClass: Class<T>,
                handle: SavedStateHandle
            ): T {
                val repository = RosterRepository.getInstance(application)
                val storageManager = StorageManager(application)
                return ExtractionViewModel(application, handle, repository, storageManager) as T
            }
        }
    }

    private val initialScreen = savedStateHandle.get<String>(KEY_CURRENT_SCREEN)?.let {
        try { ActiveScreen.valueOf(it) } catch (_: Exception) { ActiveScreen.SAVED_ROSTERS_DASHBOARD }
    } ?: ActiveScreen.SAVED_ROSTERS_DASHBOARD

    private val _uiState = MutableStateFlow(
        ExtractionUiState(
            employeeId = savedStateHandle[KEY_EMPLOYEE_ID] ?: "ABHL341",
            cachedImagePath = savedStateHandle[KEY_CACHED_IMAGE_PATH],
            selectedImageUri = savedStateHandle.get<String>(KEY_IMAGE_URI)?.let { Uri.parse(it) },
            manualMonth = savedStateHandle[KEY_MANUAL_MONTH] ?: "",
            manualYear = savedStateHandle[KEY_MANUAL_YEAR] ?: "",
            manualFloor = savedStateHandle[KEY_MANUAL_FLOOR] ?: "",
            isCustomFloor = savedStateHandle[KEY_IS_CUSTOM_FLOOR] ?: false,
            customFloorText = savedStateHandle[KEY_CUSTOM_FLOOR_TEXT] ?: "",
            showManualPeriod = savedStateHandle[KEY_SHOW_MANUAL] ?: false,
            currentStage = savedStateHandle[KEY_LAST_STAGE] ?: "",
            currentScreen = initialScreen,
            pendingRosterId = savedStateHandle[KEY_PENDING_ROSTER_ID],
            editingDay = savedStateHandle[KEY_EDITING_DAY]
        )
    )
    val uiState: StateFlow<ExtractionUiState> = _uiState.asStateFlow()

    private var extractionJob: Job? = null
    // In-memory cache for the decoded Bitmap during active session
    private var inMemorySourceBitmap: Bitmap? = null
    // Flag preventing bitmap recycling while an active OCR or image-processing task is executing
    @Volatile
    private var isBitmapInUseByOcr: Boolean = false

    fun isBitmapInUse(): Boolean = isBitmapInUseByOcr

    init {
        // Detect if process was recreated during an incomplete extraction
        val savedStatus = savedStateHandle.get<String>(KEY_STATUS)
        val savedRosterId = savedStateHandle.get<String>(KEY_ACTIVE_ROSTER_ID)

        if (savedStatus == ExtractionStatus.RUNNING.name) {
            _uiState.update {
                it.copy(
                    status = ExtractionStatus.ERROR,
                    error = "Process was interrupted during extraction. You can resume extraction below.",
                    currentStage = "Interrupted"
                )
            }
        } else if (!savedRosterId.isNullOrBlank()) {
            viewModelScope.launch {
                loadRosterById(savedRosterId)
            }
        } else {
            val savedPath = savedStateHandle.get<String>(KEY_CACHED_IMAGE_PATH)
            if (!savedPath.isNullOrBlank() && File(savedPath).exists()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val bmp = BitmapFactory.decodeFile(savedPath)
                        if (bmp != null) {
                            inMemorySourceBitmap = bmp
                            _uiState.update { it.copy(sourceBitmap = bmp) }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ExtractionViewModel", "Could not restore bitmap from cached path", e)
                    }
                }
            }
        }
    }

    private var screenBeforeEditor: ActiveScreen = ActiveScreen.MONTHLY_CALENDAR

    fun navigateTo(screen: ActiveScreen) {
        savedStateHandle[KEY_CURRENT_SCREEN] = screen.name
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun openDailyEditor(day: Int) {
        screenBeforeEditor = _uiState.value.currentScreen
        savedStateHandle[KEY_EDITING_DAY] = day
        savedStateHandle[KEY_CURRENT_SCREEN] = ActiveScreen.DAILY_EDITOR.name
        _uiState.update { it.copy(editingDay = day, currentScreen = ActiveScreen.DAILY_EDITOR) }
    }

    fun closeDailyEditor() {
        val returnScreen = if (screenBeforeEditor == ActiveScreen.ROSTER_REVIEW) {
            ActiveScreen.ROSTER_REVIEW
        } else {
            ActiveScreen.MONTHLY_CALENDAR
        }
        savedStateHandle.remove<Int>(KEY_EDITING_DAY)
        savedStateHandle[KEY_CURRENT_SCREEN] = returnScreen.name
        _uiState.update { it.copy(editingDay = null, currentScreen = returnScreen) }
    }

    fun setEmployeeId(id: String) {
        val trimmed = id.trim()
        savedStateHandle[KEY_EMPLOYEE_ID] = trimmed
        _uiState.update { it.copy(employeeId = trimmed) }
    }

    fun setManualPeriod(month: String, year: String) {
        savedStateHandle[KEY_MANUAL_MONTH] = month
        savedStateHandle[KEY_MANUAL_YEAR] = year
        _uiState.update { it.copy(manualMonth = month, manualYear = year) }
    }

    fun setManualFloor(floor: String) {
        val trimmed = floor.trim()
        savedStateHandle[KEY_MANUAL_FLOOR] = trimmed
        savedStateHandle[KEY_IS_CUSTOM_FLOOR] = false
        savedStateHandle[KEY_CUSTOM_FLOOR_TEXT] = ""
        _uiState.update {
            it.copy(
                manualFloor = trimmed,
                isCustomFloor = false,
                customFloorText = ""
            )
        }
    }

    fun setCustomFloor(customFloor: String) {
        val trimmed = customFloor.trim()
        savedStateHandle[KEY_MANUAL_FLOOR] = trimmed
        savedStateHandle[KEY_IS_CUSTOM_FLOOR] = true
        savedStateHandle[KEY_CUSTOM_FLOOR_TEXT] = customFloor
        _uiState.update {
            it.copy(
                manualFloor = trimmed,
                isCustomFloor = true,
                customFloorText = customFloor
            )
        }
    }

    fun setManualMetadata(month: String, year: String, floor: String) {
        setManualPeriod(month, year)
        if (floor.isNotBlank() && !PREDEFINED_FLOORS.contains(floor)) {
            setCustomFloor(floor)
        } else {
            setManualFloor(floor)
        }
    }

    suspend fun bulkUpdateFloor(
        rosterId: String,
        newFloor: String,
        author: String = "Clinical Supervisor",
        reason: String? = "Bulk floor update"
    ): Result<Unit> {
        return try {
            val result = repository.bulkUpdateFloor(rosterId, newFloor, author, reason)
            if (result.isSuccess) {
                val updatedRoster = repository.getRoster(rosterId)
                val updatedSchedule = repository.getRosterSchedule(rosterId)
                _uiState.update {
                    it.copy(
                        activeRoster = updatedRoster,
                        schedule = updatedSchedule
                    )
                }
            }
            result
        } catch (e: Exception) {
            android.util.Log.e("ExtractionViewModel", "Failed to bulk update floor", e)
            Result.failure(e)
        }
    }

    fun setShowManualPeriod(show: Boolean) {
        savedStateHandle[KEY_SHOW_MANUAL] = show
        _uiState.update { it.copy(showManualPeriod = show) }
    }

    /**
     * Called when the user picks an image or provides a file path.
     * Caches the image into private cacheDir to ensure stability across configuration changes.
     */
    fun onImageSelected(uri: Uri) {
        if (_uiState.value.status == ExtractionStatus.RUNNING) return

        val currentEmpId = _uiState.value.employeeId
        val currentCachedPath = _uiState.value.cachedImagePath

        // Case 1: Same image selected twice - reuse existing canonical copy
        if (_uiState.value.selectedImageUri == uri && !currentCachedPath.isNullOrBlank() && File(currentCachedPath).exists()) {
            android.util.Log.d("ExtractionViewModel", "Same image selected twice; reusing canonical file: $currentCachedPath")
            return
        }

        // Case 2: User starts another upload before saving previous one - clean up superseded uncommitted directory
        val previousPendingId = savedStateHandle.get<String>(KEY_PENDING_ROSTER_ID)
        if (previousPendingId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (repository.getRoster(previousPendingId) == null) {
                        val prevDir = storageManager.getRosterDirectory(currentEmpId, previousPendingId)
                        if (prevDir.exists()) {
                            prevDir.deleteRecursively()
                            android.util.Log.d("ExtractionViewModel", "Cleaned up superseded uncommitted roster dir: ${prevDir.absolutePath}")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ExtractionViewModel", "Could not clean up superseded roster dir", e)
                }
            }
        }

        val pendingId = java.util.UUID.randomUUID().toString()
        savedStateHandle[KEY_PENDING_ROSTER_ID] = pendingId
        savedStateHandle[KEY_IMAGE_URI] = uri.toString()

        _uiState.update {
            it.copy(
                selectedImageUri = uri,
                pendingRosterId = pendingId,
                error = null,
                result = null,
                schedule = emptyList(),
                diagnostics = null,
                isSaved = false
            )
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = ExtractionStatus.RUNNING,
                    currentStage = "Storing selected image in canonical roster storage..."
                )
            }

            try {
                // Release any previously held bitmap safely before allocating a new one
                releaseSourceBitmap()

                val canonicalFile = withContext(Dispatchers.IO) {
                    storageManager.copyUriToCanonicalImage(uri, currentEmpId, pendingId)
                }

                savedStateHandle[KEY_CACHED_IMAGE_PATH] = canonicalFile.absolutePath

                val bmp = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(canonicalFile.absolutePath)
                }
                requireNotNull(bmp) { "Failed to decode canonical bitmap from file" }
                inMemorySourceBitmap = bmp

                _uiState.update {
                    it.copy(
                        cachedImagePath = canonicalFile.absolutePath,
                        sourceBitmap = bmp,
                        currentStage = "Detecting roster period..."
                    )
                }

                // Detect period and initial floor automatically while protecting bitmap from concurrent recycling
                val (period, initialFloor) = try {
                    isBitmapInUseByOcr = true
                    rosterProcessor.detectPeriodAndFloor(bmp)
                } finally {
                    isBitmapInUseByOcr = false
                }
                val prefillFloor = initialFloor ?: ""
                val isCustom = prefillFloor.isNotBlank() && !PREDEFINED_FLOORS.contains(prefillFloor)

                savedStateHandle[KEY_MANUAL_MONTH] = period?.month?.toString() ?: ""
                savedStateHandle[KEY_MANUAL_YEAR] = period?.year?.toString() ?: ""
                savedStateHandle[KEY_MANUAL_FLOOR] = prefillFloor
                savedStateHandle[KEY_IS_CUSTOM_FLOOR] = isCustom
                savedStateHandle[KEY_CUSTOM_FLOOR_TEXT] = if (isCustom) prefillFloor else ""
                savedStateHandle[KEY_SHOW_MANUAL] = (period == null)

                _uiState.update {
                    it.copy(
                        status = ExtractionStatus.IDLE,
                        currentStage = if (period != null) "Detected: ${period.displayText}" else "Period not detected",
                        detectedPeriod = period,
                        detectedFloor = initialFloor,
                        showManualPeriod = (period == null),
                        manualMonth = period?.month?.toString() ?: "",
                        manualYear = period?.year?.toString() ?: "",
                        manualFloor = prefillFloor,
                        isCustomFloor = isCustom,
                        customFloorText = if (isCustom) prefillFloor else ""
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ExtractionViewModel", "Failed to store canonical image and detect period", e)
                _uiState.update {
                    it.copy(
                        status = ExtractionStatus.ERROR,
                        error = e.message ?: "Could not load selected image",
                        currentStage = "Image load failed"
                    )
                }
            }
        }
    }

    /**
     * Called for intent-passed image path (e.g. automated test runs).
     */
    fun onImagePathPassed(path: String, employeeId: String) {
        if (_uiState.value.status == ExtractionStatus.RUNNING) return
        if (_uiState.value.cachedImagePath == path && _uiState.value.result != null) {
            // Already extracted
            return
        }

        setEmployeeId(employeeId)
        val pendingId = savedStateHandle[KEY_PENDING_ROSTER_ID] ?: java.util.UUID.randomUUID().toString()
        savedStateHandle[KEY_PENDING_ROSTER_ID] = pendingId

        val sourceFile = File(path)
        val canonicalFile = if (path.contains("rosters/$employeeId") && sourceFile.exists()) {
            sourceFile
        } else if (sourceFile.exists()) {
            try {
                storageManager.copyFileToCanonicalImage(sourceFile, employeeId, pendingId)
            } catch (_: Exception) {
                sourceFile
            }
        } else {
            sourceFile
        }

        savedStateHandle[KEY_CACHED_IMAGE_PATH] = canonicalFile.absolutePath
        navigateTo(ActiveScreen.ROSTER_REVIEW)
        _uiState.update { it.copy(cachedImagePath = canonicalFile.absolutePath, pendingRosterId = pendingId, error = null) }

        startExtraction(canonicalFile.absolutePath)
    }

    fun startExtraction(filePathOverride: String? = null) {
        // Prevent duplicate requests while extraction is already running
        if (_uiState.value.status == ExtractionStatus.RUNNING) {
            android.util.Log.d("ExtractionViewModel", "Extraction already in progress. Ignoring duplicate request.")
            return
        }

        val targetPath = filePathOverride ?: _uiState.value.cachedImagePath
        if (targetPath.isNullOrBlank()) {
            _uiState.update { it.copy(status = ExtractionStatus.ERROR, error = "No image selected") }
            return
        }

        savedStateHandle[KEY_STATUS] = ExtractionStatus.RUNNING.name
        extractionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = ExtractionStatus.RUNNING,
                    currentStage = "Decoding high-resolution image...",
                    error = null
                )
            }

            try {
                // Ensure bitmap is in memory
                var bmp = inMemorySourceBitmap
                if (bmp == null || bmp.isRecycled) {
                    bmp = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(targetPath)
                    }
                    requireNotNull(bmp) { "Unable to decode image file at $targetPath" }
                    inMemorySourceBitmap = bmp
                }

                _uiState.update {
                    it.copy(currentStage = "Analyzing table grid, headers & employee row...")
                }

                val state = _uiState.value
                val m = if (state.showManualPeriod) state.manualMonth.toIntOrNull() else state.detectedPeriod?.month
                val y = if (state.showManualPeriod) state.manualYear.toIntOrNull() else state.detectedPeriod?.year

                // Run extraction while protecting source bitmap from concurrent recycling
                val r = try {
                    isBitmapInUseByOcr = true
                    rosterProcessor.extract(bmp, state.employeeId, m, y)
                } finally {
                    isBitmapInUseByOcr = false
                }

                val detectedOcrFloor = r.diagnostics.extractedMonthlyFloor ?: r.extractedFloor
                val confirmedFloor = if (state.manualFloor.isNotBlank()) {
                    state.manualFloor.trim()
                } else null
                val isUserCorrectedFloor = confirmedFloor != null && (detectedOcrFloor == null || confirmedFloor != detectedOcrFloor)

                _uiState.update {
                    it.copy(
                        result = r,
                        schedule = r.schedule,
                        diagnostics = r.diagnostics,
                        detectedFloor = detectedOcrFloor,
                        manualFloor = confirmedFloor ?: detectedOcrFloor ?: it.manualFloor,
                        currentStage = "Saving roster and generating Excel workbook..."
                    )
                }

                // Persist extracted roster to database inside atomic transaction
                val effectiveMonth = m ?: r.detectedPeriod?.month ?: 6
                val effectiveYear = y ?: r.detectedPeriod?.year ?: 2026
                val pendingId = savedStateHandle.get<String>(KEY_PENDING_ROSTER_ID) ?: java.util.UUID.randomUUID().toString()

                val savedRoster = repository.saveExtractedRoster(
                    employeeId = state.employeeId,
                    month = effectiveMonth,
                    year = effectiveYear,
                    rosterResult = r,
                    sourceBitmap = bmp,
                    existingRosterId = pendingId,
                    canonicalImagePath = targetPath,
                    confirmedFloor = confirmedFloor,
                    floorAuthor = if (isUserCorrectedFloor) "User" else "OCR",
                    floorReason = if (isUserCorrectedFloor) "Confirmed during upload" else null
                )

                savedStateHandle[KEY_STATUS] = ExtractionStatus.SUCCESS.name
                savedStateHandle[KEY_ACTIVE_ROSTER_ID] = savedRoster.id
                savedStateHandle.remove<String>(KEY_PENDING_ROSTER_ID)
                savedStateHandle[KEY_CURRENT_SCREEN] = ActiveScreen.ROSTER_REVIEW.name
                if (savedRoster.originalFilePath != null) {
                    savedStateHandle[KEY_CACHED_IMAGE_PATH] = savedRoster.originalFilePath
                }

                _uiState.update {
                    it.copy(
                        status = ExtractionStatus.SUCCESS,
                        currentStage = "Extraction and persistence complete",
                        isSaved = true,
                        activeRoster = savedRoster,
                        cachedImagePath = savedRoster.originalFilePath ?: it.cachedImagePath,
                        currentScreen = ActiveScreen.ROSTER_REVIEW,
                        pendingRosterId = null
                    )
                }

                // Safely clean up old temporary files now that permanent image is stored
                cleanUpTempFiles(forceAll = false)
            } catch (e: ExtractionException) {
                android.util.Log.e("ExtractionViewModel", "Extraction failed with domain exception", e)
                savedStateHandle[KEY_STATUS] = ExtractionStatus.ERROR.name
                _uiState.update {
                    it.copy(
                        status = ExtractionStatus.ERROR,
                        error = e.message ?: "Extraction failed",
                        diagnostics = e.diagnostics,
                        currentStage = "Extraction failed"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ExtractionViewModel", "Extraction failed unexpectedly", e)
                savedStateHandle[KEY_STATUS] = ExtractionStatus.ERROR.name
                _uiState.update {
                    it.copy(
                        status = ExtractionStatus.ERROR,
                        error = e.message ?: e.javaClass.simpleName,
                        currentStage = "Error encountered"
                    )
                }
            }
        }
    }

    /**
     * Retries database saving if extraction succeeded but saving failed or was interrupted.
     */
    fun retrySave() {
        val r = _uiState.value.result ?: return
        if (_uiState.value.status == ExtractionStatus.RUNNING) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = ExtractionStatus.RUNNING,
                    currentStage = "Retrying database save...",
                    error = null
                )
            }

            try {
                val bmp = inMemorySourceBitmap ?: _uiState.value.cachedImagePath?.let {
                    withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it) }
                }
                val state = _uiState.value
                val m = if (state.showManualPeriod) state.manualMonth.toIntOrNull() else state.detectedPeriod?.month ?: 6
                val y = if (state.showManualPeriod) state.manualYear.toIntOrNull() else state.detectedPeriod?.year ?: 2026

                val detectedOcrFloor = r.diagnostics.extractedMonthlyFloor ?: r.extractedFloor
                val confirmedFloor = if (state.manualFloor.isNotBlank()) {
                    state.manualFloor.trim()
                } else null
                val isUserCorrectedFloor = confirmedFloor != null && (detectedOcrFloor == null || confirmedFloor != detectedOcrFloor)

                val pendingId = savedStateHandle.get<String>(KEY_PENDING_ROSTER_ID)
                    ?: savedStateHandle.get<String>(KEY_ACTIVE_ROSTER_ID)
                    ?: java.util.UUID.randomUUID().toString()

                val savedRoster = repository.saveExtractedRoster(
                    employeeId = state.employeeId,
                    month = m ?: 6,
                    year = y ?: 2026,
                    rosterResult = r,
                    sourceBitmap = bmp,
                    existingRosterId = pendingId,
                    canonicalImagePath = state.cachedImagePath,
                    confirmedFloor = confirmedFloor,
                    floorAuthor = if (isUserCorrectedFloor) "User" else "OCR",
                    floorReason = if (isUserCorrectedFloor) "Confirmed during upload" else null
                )

                savedStateHandle[KEY_STATUS] = ExtractionStatus.SUCCESS.name
                savedStateHandle[KEY_ACTIVE_ROSTER_ID] = savedRoster.id
                savedStateHandle.remove<String>(KEY_PENDING_ROSTER_ID)
                savedStateHandle[KEY_CURRENT_SCREEN] = ActiveScreen.ROSTER_REVIEW.name
                if (savedRoster.originalFilePath != null) {
                    savedStateHandle[KEY_CACHED_IMAGE_PATH] = savedRoster.originalFilePath
                }

                _uiState.update {
                    it.copy(
                        status = ExtractionStatus.SUCCESS,
                        currentStage = "Saved successfully",
                        isSaved = true,
                        activeRoster = savedRoster,
                        cachedImagePath = savedRoster.originalFilePath ?: it.cachedImagePath,
                        currentScreen = ActiveScreen.ROSTER_REVIEW,
                        pendingRosterId = null
                    )
                }

                // Safely clean up old temporary files now that permanent image is stored
                cleanUpTempFiles(forceAll = false)
            } catch (e: Exception) {
                android.util.Log.e("ExtractionViewModel", "Retry save failed", e)
                _uiState.update {
                    it.copy(
                        status = ExtractionStatus.ERROR,
                        error = "Retry save failed: ${e.message ?: e.javaClass.simpleName}",
                        currentStage = "Save failed"
                    )
                }
            }
        }
    }

    fun loadRoster(roster: RosterEntity) {
        savedStateHandle[KEY_ACTIVE_ROSTER_ID] = roster.id
        savedStateHandle[KEY_EMPLOYEE_ID] = roster.employeeId
        // Immediately sync state with the loaded roster metadata so subsequent navigation reflects the active roster
        _uiState.update {
            it.copy(
                activeRoster = roster,
                employeeId = roster.employeeId,
                isSaved = true
            )
        }
        viewModelScope.launch {
            loadRosterById(roster.id)
        }
    }

    private suspend fun loadRosterById(rosterId: String) {
        val roster = repository.getRoster(rosterId) ?: return
        val schedule = repository.getRosterSchedule(rosterId)

        // Release previously held bitmap before allocating a new one
        releaseSourceBitmap()

        var bmp: Bitmap? = null
        if (roster.originalFilePath != null) {
            bmp = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(roster.originalFilePath)
            }
        }
        if (bmp != null) {
            inMemorySourceBitmap = bmp
        }

        savedStateHandle[KEY_ACTIVE_ROSTER_ID] = roster.id
        savedStateHandle[KEY_EMPLOYEE_ID] = roster.employeeId

        _uiState.update {
            it.copy(
                activeRoster = roster,
                employeeId = roster.employeeId,
                schedule = schedule,
                sourceBitmap = bmp,
                isSaved = true,
                status = ExtractionStatus.SUCCESS,
                cachedImagePath = roster.originalFilePath
            )
        }
    }

    fun updateShift(day: Int, newCode: String, source: ShiftChangeSource, author: String = "User", reason: String? = null) {
        val rosterId = _uiState.value.activeRoster?.id
        if (rosterId != null) {
            viewModelScope.launch {
                repository.updateShift(rosterId, day, newCode, source, author, reason)
                val updated = repository.getRosterSchedule(rosterId)
                _uiState.update { it.copy(schedule = updated) }
            }
        } else {
            _uiState.update { current ->
                current.copy(
                    schedule = current.schedule.map { entry ->
                        if (entry.day == day) entry.updateSchedule(newCode, source, author, reason) else entry
                    }
                )
            }
        }
    }

    fun updateFloor(day: Int, newFloor: String?, author: String = "User") {
        val rosterId = _uiState.value.activeRoster?.id
        if (rosterId != null) {
            viewModelScope.launch {
                repository.updateFloor(rosterId, day, newFloor, author)
                val updated = repository.getRosterSchedule(rosterId)
                _uiState.update { it.copy(schedule = updated) }
            }
        } else {
            _uiState.update { current ->
                current.copy(
                    schedule = current.schedule.map { entry ->
                        if (entry.day == day) entry.updateFloor(newFloor, author) else entry
                    }
                )
            }
        }
    }

    suspend fun saveAttendance(
        date: String,
        newStatus: AttendanceStatus,
        notes: String? = null,
        author: String = "Clinical Supervisor"
    ): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val rosterId = _uiState.value.activeRoster?.id
            val employeeId = _uiState.value.employeeId
            if (rosterId != null) {
                repository.updateAttendance(employeeId, date, rosterId, newStatus, notes, author)
                val updated = repository.getRosterSchedule(rosterId)
                _uiState.update { it.copy(schedule = updated) }
            } else {
                _uiState.update { current ->
                    current.copy(
                        schedule = current.schedule.map { entry ->
                            if (entry.date == date) entry.updateAttendance(newStatus, notes, author) else entry
                        }
                    )
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("ExtractionViewModel", "Failed to save attendance record", e)
            Result.failure(e)
        }
    }

    fun updateAttendance(date: String, newStatus: AttendanceStatus, notes: String? = null, author: String = "User") {
        viewModelScope.launch {
            saveAttendance(date, newStatus, notes, author)
        }
    }

    fun getSourceBitmap(): Bitmap? = inMemorySourceBitmap

    fun updateEntry(updated: DailyScheduleEntry) {
        val rId = _uiState.value.activeRoster?.id
        if (rId != null) {
            viewModelScope.launch {
                if (updated.isModified) {
                    repository.updateShift(
                        rosterId = rId,
                        day = updated.day,
                        newCode = updated.currentShiftCode,
                        source = ShiftChangeSource.USER_EDIT
                    )
                }
                if (updated.isFloorModified) {
                    repository.updateFloor(
                        rosterId = rId,
                        day = updated.day,
                        newFloor = updated.currentFloor
                    )
                }
                updated.date?.let { d ->
                    repository.updateAttendance(
                        employeeId = _uiState.value.employeeId,
                        date = d,
                        rosterId = rId,
                        newStatus = updated.attendance.status,
                        notes = updated.attendance.notes
                    )
                }
                val updatedSchedule = repository.getRosterSchedule(rId)
                _uiState.update { it.copy(schedule = updatedSchedule) }
            }
        } else {
            _uiState.update { current ->
                current.copy(
                    schedule = current.schedule.map { if (it.day == updated.day) updated else it }
                )
            }
        }
    }

    suspend fun saveDailyScheduleEdit(
        day: Int,
        newShiftCode: String,
        newFloor: String?,
        author: String = "Clinical Supervisor",
        reason: String? = null
    ): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            val rosterId = _uiState.value.activeRoster?.id
            if (rosterId != null) {
                repository.saveDailyScheduleEdit(
                    rosterId = rosterId,
                    day = day,
                    newShiftCode = newShiftCode,
                    newFloor = newFloor,
                    author = author,
                    reason = reason
                )
                val updatedSchedule = repository.getRosterSchedule(rosterId)
                _uiState.update { it.copy(schedule = updatedSchedule) }
            } else {
                // In-memory update if unsaved roster (e.g. preview)
                _uiState.update { current ->
                    current.copy(
                        schedule = current.schedule.map { entry ->
                            if (entry.day == day) {
                                var res = entry
                                if (res.currentShiftCode != newShiftCode) {
                                    res = res.updateSchedule(
                                        newCode = newShiftCode,
                                        source = ShiftChangeSource.USER_EDIT,
                                        author = author,
                                        reason = reason
                                    )
                                }
                                if ((res.currentFloor ?: "") != (newFloor ?: "")) {
                                    res = res.updateFloor(
                                        newFloor = newFloor,
                                        author = author
                                    )
                                }
                                res
                            } else entry
                        }
                    )
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("ExtractionViewModel", "Failed to save daily schedule edit", e)
            Result.failure(e)
        }
    }

    fun deleteRoster(roster: RosterEntity) {
        viewModelScope.launch {
            repository.deleteRoster(roster.id)
            if (_uiState.value.activeRoster?.id == roster.id) {
                _uiState.update {
                    it.copy(
                        activeRoster = null,
                        schedule = emptyList(),
                        result = null,
                        sourceBitmap = null
                    )
                }
            }
        }
    }

    fun prepareCorrectedUpload(roster: RosterEntity) {
        releaseSourceBitmap()
        cleanUpUnpersistedPendingRoster()
        cleanUpTempFiles(forceAll = true)
        val newPendingId = java.util.UUID.randomUUID().toString()
        savedStateHandle.remove<String>(KEY_IMAGE_URI)
        savedStateHandle.remove<String>(KEY_CACHED_IMAGE_PATH)
        savedStateHandle.remove<String>(KEY_STATUS)
        savedStateHandle.remove<String>(KEY_ACTIVE_ROSTER_ID)
        savedStateHandle[KEY_PENDING_ROSTER_ID] = newPendingId
        savedStateHandle[KEY_EMPLOYEE_ID] = roster.employeeId
        savedStateHandle[KEY_MANUAL_MONTH] = roster.month.toString()
        savedStateHandle[KEY_MANUAL_YEAR] = roster.year.toString()
        val effFloor = roster.getEffectiveMonthlyFloor() ?: ""
        val isCustom = effFloor.isNotBlank() && !PREDEFINED_FLOORS.contains(effFloor)
        savedStateHandle[KEY_MANUAL_FLOOR] = effFloor
        savedStateHandle[KEY_IS_CUSTOM_FLOOR] = isCustom
        savedStateHandle[KEY_CUSTOM_FLOOR_TEXT] = if (isCustom) effFloor else ""
        savedStateHandle[KEY_SHOW_MANUAL] = true
        savedStateHandle[KEY_CURRENT_SCREEN] = ActiveScreen.ROSTER_UPLOAD.name
        _uiState.update {
            it.copy(
                status = ExtractionStatus.IDLE,
                currentStage = "",
                selectedImageUri = null,
                cachedImagePath = null,
                sourceBitmap = null,
                result = null,
                schedule = emptyList(),
                diagnostics = null,
                error = null,
                isSaved = false,
                activeRoster = null,
                employeeId = roster.employeeId,
                showManualPeriod = true,
                manualMonth = roster.month.toString(),
                manualYear = roster.year.toString(),
                manualFloor = effFloor,
                isCustomFloor = isCustom,
                customFloorText = if (isCustom) effFloor else "",
                detectedFloor = roster.floorExtracted,
                pendingRosterId = newPendingId,
                currentScreen = ActiveScreen.ROSTER_UPLOAD
            )
        }
    }

    fun resetForNewUpload() {
        releaseSourceBitmap()
        cleanUpUnpersistedPendingRoster()
        cleanUpTempFiles(forceAll = true)
        val newPendingId = java.util.UUID.randomUUID().toString()
        savedStateHandle.remove<String>(KEY_IMAGE_URI)
        savedStateHandle.remove<String>(KEY_CACHED_IMAGE_PATH)
        savedStateHandle.remove<String>(KEY_STATUS)
        savedStateHandle.remove<String>(KEY_ACTIVE_ROSTER_ID)
        savedStateHandle.remove<String>(KEY_MANUAL_MONTH)
        savedStateHandle.remove<String>(KEY_MANUAL_YEAR)
        savedStateHandle.remove<String>(KEY_MANUAL_FLOOR)
        savedStateHandle.remove<Boolean>(KEY_IS_CUSTOM_FLOOR)
        savedStateHandle.remove<String>(KEY_CUSTOM_FLOOR_TEXT)
        savedStateHandle.remove<Boolean>(KEY_SHOW_MANUAL)
        savedStateHandle[KEY_PENDING_ROSTER_ID] = newPendingId
        savedStateHandle[KEY_CURRENT_SCREEN] = ActiveScreen.ROSTER_UPLOAD.name
        _uiState.update {
            it.copy(
                status = ExtractionStatus.IDLE,
                currentStage = "",
                selectedImageUri = null,
                cachedImagePath = null,
                sourceBitmap = null,
                result = null,
                schedule = emptyList(),
                diagnostics = null,
                error = null,
                isSaved = false,
                activeRoster = null,
                showManualPeriod = false,
                manualMonth = "",
                manualYear = "",
                manualFloor = "",
                isCustomFloor = false,
                customFloorText = "",
                detectedFloor = null,
                pendingRosterId = newPendingId,
                currentScreen = ActiveScreen.ROSTER_UPLOAD
            )
        }
    }

    private fun cleanUpUnpersistedPendingRoster() {
        val pendingId = savedStateHandle.get<String>(KEY_PENDING_ROSTER_ID) ?: return
        val empId = savedStateHandle.get<String>(KEY_EMPLOYEE_ID) ?: "ABHL341"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (repository.getRoster(pendingId) == null) {
                    val dir = storageManager.getRosterDirectory(empId, pendingId)
                    if (dir.exists()) {
                        dir.deleteRecursively()
                        android.util.Log.d("ExtractionViewModel", "Cleaned up unpersisted pending roster dir: ${dir.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ExtractionViewModel", "Error cleaning unpersisted pending roster", e)
            }
        }
    }

    /**
     * Safely releases or recycles the in-memory source bitmap.
     *
     * Verification 6: Large bitmaps are recycled or released safely when ViewModel is cleared or reset.
     * Verification 7: Ensures bitmap is NEVER recycled while an OCR or image-processing task is accessing it.
     */
    fun releaseSourceBitmap() {
        val bmp = inMemorySourceBitmap
        inMemorySourceBitmap = null
        _uiState.update { it.copy(sourceBitmap = null) }

        if (bmp != null && !bmp.isRecycled) {
            if (!isBitmapInUseByOcr) {
                try {
                    bmp.recycle()
                } catch (e: Exception) {
                    android.util.Log.w("ExtractionViewModel", "Error recycling source bitmap", e)
                }
            } else {
                android.util.Log.d("ExtractionViewModel", "Bitmap in use by OCR task; dereferenced for GC without calling recycle()")
            }
        }
    }

    /**
     * Cleans up temporary image files in cacheDir/roster_temp.
     *
     * Verification 1: Does NOT delete files if extraction is currently RUNNING.
     * Verification 3: Cleans up temp input image after successful persistence or on explicit reset.
     * Verification 4 & 5: Does NOT delete the source image of a failed extraction or failed save.
     */
    fun cleanUpTempFiles(forceAll: Boolean = false) {
        if (_uiState.value.status == ExtractionStatus.RUNNING) {
            android.util.Log.d("ExtractionViewModel", "Skipping temp cleanup: extraction is RUNNING")
            return
        }

        try {
            val app = getApplication<Application>()
            val cacheDir = File(app.cacheDir, "roster_temp")
            if (!cacheDir.exists()) return

            val currentPath = _uiState.value.cachedImagePath
            val isSaved = _uiState.value.isSaved

            cacheDir.listFiles()?.forEach { file ->
                if (forceAll || isSaved || currentPath != file.absolutePath) {
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        android.util.Log.w("ExtractionViewModel", "Failed to delete temp file: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ExtractionViewModel", "Error in cleanUpTempFiles", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Cancel any active extraction job
        extractionJob?.cancel()
        // Clean up temporary files safely
        cleanUpTempFiles(forceAll = false)
        // Safely recycle or release bitmap
        releaseSourceBitmap()
    }

    class Factory(
        private val application: Application,
        private val savedStateHandle: SavedStateHandle,
        private val repository: RosterRepository,
        private val storageManager: StorageManager,
        private val rosterProcessor: RosterProcessor = RosterProcessor()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ExtractionViewModel::class.java)) {
                return ExtractionViewModel(
                    application,
                    savedStateHandle,
                    repository,
                    storageManager,
                    rosterProcessor
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
