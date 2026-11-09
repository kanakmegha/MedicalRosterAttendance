package com.example.rosterrebuild

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.activity.viewModels
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.rosterrebuild.data.local.RosterEntity
import com.example.rosterrebuild.data.repository.RosterRepository
import com.example.rosterrebuild.data.storage.StorageManager
import com.example.rosterrebuild.ui.ActiveScreen
import com.example.rosterrebuild.ui.DailyEditorScreen
import com.example.rosterrebuild.ui.ExtractionStatus
import com.example.rosterrebuild.ui.ExtractionViewModel
import com.example.rosterrebuild.ui.MyRostersScreen
import com.example.rosterrebuild.ui.RosterDashboardScreen
import com.example.rosterrebuild.ui.RosterReviewScreen
import com.example.rosterrebuild.ui.SettingsDialog
import com.example.rosterrebuild.update.UpdateConfigStore
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.ui.unit.sp
import com.example.rosterrebuild.ui.AppUpdateDialog
import com.example.rosterrebuild.ui.components.*
import com.example.rosterrebuild.ui.theme.*
import com.example.rosterrebuild.update.AppUpdateManager
import com.example.rosterrebuild.update.UpdateUiState
import androidx.lifecycle.lifecycleScope
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: ExtractionViewModel by viewModels {
        ExtractionViewModel.provideFactory(this, application)
    }
    private lateinit var updateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateManager = AppUpdateManager(this, lifecycleScope)
        val initialImagePath = intent?.getStringExtra("image_path")
        val initialEmployeeId = intent?.getStringExtra("employee_id") ?: "ABHL341"
        if (!initialImagePath.isNullOrBlank()) {
            viewModel.onImagePathPassed(initialImagePath, initialEmployeeId)
        }
        setContent {
            MedicalRosterTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RosterScreen(viewModel = viewModel, updateManager = updateManager)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) {
            updateManager.onAppForegrounded()
        }
        viewModel.refreshTodaySchedule()
    }
}

@Composable
private fun RosterScreen(
    viewModel: ExtractionViewModel,
    updateManager: AppUpdateManager
) {
    val state by viewModel.uiState.collectAsState()
    val todayScheduleState by viewModel.todayScheduleState.collectAsState()
    val context = LocalContext.current
    val repository = remember { RosterRepository.getInstance(context) }
    val storageManager = remember { StorageManager(context) }
    val savedRosters by repository.getAllRostersFlow().collectAsState(initial = emptyList())

    val scheduleState = state.schedule
    val result = state.result
    val sourceBitmap = state.sourceBitmap
    val diagnostics = state.diagnostics
    val error = state.error
    val busy = (state.status == ExtractionStatus.RUNNING)
    val busyMessage = state.currentStage
    val employeeId = state.employeeId
    val detectedPeriod = state.detectedPeriod
    val manualMonth = state.manualMonth
    val manualYear = state.manualYear
    val showManualPeriod = state.showManualPeriod
    val activeRoster = state.activeRoster
    val currentScreen = state.currentScreen

    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        repository.recalculateAllRosterStatuses()
        // Non-blocking background update check
        updateManager.checkForUpdates(isManual = false)
    }

    val onUpdateShift: (Int, String, ShiftChangeSource, String, String?) -> Unit = { day, newCode, source, author, reason ->
        viewModel.updateShift(day, newCode, source, author, reason)
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onImageSelected(uri)
        }
    }

    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    val onRunExtraction: () -> Unit = {
        val m = if (showManualPeriod) manualMonth.toIntOrNull() else detectedPeriod?.month
        val y = if (showManualPeriod) manualYear.toIntOrNull() else detectedPeriod?.year

        if (m == null || m !in 1..12) {
            validationError = "Please enter a valid month (1–12)."
        } else if (y == null || y < 2000) {
            validationError = "Please enter a valid 4-digit year (e.g. 2026)."
        } else {
            validationError = null
            viewModel.startExtraction()
        }
    }

    // Top control widgets reused in both initial state and grid header
    @Composable
    fun HeaderControls() {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppScreenHeader(
                title = "Medical Roster Extractor",
                subtitle = "Persistent local storage · Offline XLSX",
                action = {
                    AppOutlinedButton(
                        text = "🏠 Dashboard",
                        onClick = { viewModel.navigateTo(ActiveScreen.SAVED_ROSTERS_DASHBOARD) }
                    )
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppButton(
                    text = if (sourceBitmap == null) "Choose Image" else "Change Image",
                    onClick = {
                        validationError = null
                        picker.launch(arrayOf("image/jpeg", "image/png", "image/*"))
                    },
                    modifier = Modifier.weight(1f)
                )
                if (sourceBitmap != null) {
                    AppButton(
                        text = if (busy) "Extracting..." else "Run Extraction",
                        enabled = !busy,
                        onClick = onRunExtraction,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                    )
                }
            }

            // Validation error banner
            validationError?.let { msg ->
                InfoCard(
                    containerColor = ErrorContainer,
                    borderColor = ErrorRed.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = "⚠️ $msg",
                        color = OnErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Retry Save button if extraction succeeded but DB save failed
            if (result != null && activeRoster == null) {
                AppButton(
                    text = "⚠️ Retry Save to Database",
                    onClick = { viewModel.retrySave() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (scheduleState.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppButton(
                        text = "📅 View Calendar",
                        onClick = { viewModel.navigateTo(ActiveScreen.MONTHLY_CALENDAR) },
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoDark)
                    )
                    AppOutlinedButton(
                        text = "Export Schedule",
                        onClick = { showExportDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            sourceBitmap?.let { bmp ->
                Text(
                    text = "Source image: ${bmp.width} × ${bmp.height} px",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                // Detected period & floor display
                if (detectedPeriod != null && !showManualPeriod) {
                    InfoCard(
                        containerColor = LavenderSurface,
                        borderColor = LavenderBorder
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Roster Period & Floor", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                Text(detectedPeriod.displayText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                                val currentFloorDisplay = if (state.manualFloor.isNotBlank()) state.manualFloor else (state.detectedFloor ?: "Not detected")
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Floor: $currentFloorDisplay", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = IndigoDark)
                                    if (!state.detectedFloor.isNullOrBlank() && state.manualFloor == state.detectedFloor) {
                                        StatusBadge(text = "Original OCR", variant = StatusBadgeVariant.NEUTRAL)
                                    } else if (state.manualFloor.isNotBlank() && state.manualFloor != state.detectedFloor) {
                                        StatusBadge(text = "Corrected", variant = StatusBadgeVariant.PRIMARY)
                                    }
                                }
                            }
                            AppOutlinedButton(
                                text = "Edit",
                                onClick = { viewModel.setShowManualPeriod(true) }
                            )
                        }
                    }
                } else if (showManualPeriod) {
                    InfoCard(
                        containerColor = LavenderSurface,
                        borderColor = LavenderBorder
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (detectedPeriod == null)
                                    "Period or floor could not be detected automatically. Please confirm:"
                                else "Confirm or edit roster period and floor:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ConsistentTextField(
                                    value = manualMonth,
                                    onValueChange = {
                                        validationError = null
                                        viewModel.setManualPeriod(it, manualYear)
                                    },
                                    label = "Month (1–12)",
                                    modifier = Modifier.weight(1f)
                                )
                                ConsistentTextField(
                                    value = manualYear,
                                    onValueChange = {
                                        validationError = null
                                        viewModel.setManualPeriod(manualMonth, it)
                                    },
                                    label = "Year (e.g. 2026)",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Floor selection section
                            Text(
                                text = "Monthly Floor Default",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )

                            // Predefined floors
                            val rows = listOf(
                                listOf("Floor 1", "Floor 2", "Floor 3"),
                                listOf("Floor 4", "Floor 5", "ICU"),
                                listOf("Emergency", "OT", "Clear")
                            )

                            rows.forEach { rowFloors ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    rowFloors.forEach { fl ->
                                        val isClear = fl == "Clear"
                                        val isSelected = !state.isCustomFloor && state.manualFloor == fl
                                        OutlinedButton(
                                            onClick = {
                                                if (isClear) {
                                                    viewModel.setManualFloor("")
                                                } else {
                                                    viewModel.setManualFloor(fl)
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = if (isClear) {
                                                ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                                            } else if (isSelected) {
                                                ButtonDefaults.outlinedButtonColors(
                                                    containerColor = IndigoContainer,
                                                    contentColor = IndigoPrimary
                                                )
                                            } else {
                                                ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                                            },
                                            border = BorderStroke(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isClear) ErrorRed.copy(alpha = 0.5f) else if (isSelected) IndigoPrimary else LavenderBorder
                                            )
                                        ) {
                                            Text(
                                                fl,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }

                            ConsistentTextField(
                                value = if (state.isCustomFloor) state.customFloorText else "",
                                onValueChange = { viewModel.setCustomFloor(it) },
                                label = "Custom Floor / Unit (e.g. Ward 4B)",
                                placeholder = "Type custom location name"
                            )

                            if (!state.detectedFloor.isNullOrBlank()) {
                                Text(
                                    text = "Original OCR Floor: ${state.detectedFloor}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            ConsistentTextField(
                value = employeeId,
                onValueChange = { viewModel.setEmployeeId(it) },
                label = "Employee ID"
            )

            if (busy) {
                LoadingProgressComponent(message = busyMessage)
            }

            error?.let {
                InfoCard(
                    containerColor = ErrorContainer,
                    borderColor = ErrorRed.copy(alpha = 0.5f)
                ) {
                    Text(
                        "Error: $it",
                        color = OnErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    val isPrimaryTab = currentScreen in listOf(
        ActiveScreen.SAVED_ROSTERS_DASHBOARD,
        ActiveScreen.MONTHLY_CALENDAR
    )

    if (isPrimaryTab) {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    NavigationBarItem(
                        icon = { Text("🏠", fontSize = 18.sp) },
                        label = {
                            Text(
                                "Home",
                                fontWeight = if (currentScreen == ActiveScreen.SAVED_ROSTERS_DASHBOARD) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        selected = currentScreen == ActiveScreen.SAVED_ROSTERS_DASHBOARD,
                        onClick = { viewModel.navigateTo(ActiveScreen.SAVED_ROSTERS_DASHBOARD) }
                    )
                    NavigationBarItem(
                        icon = { Text("📅", fontSize = 18.sp) },
                        label = {
                            Text(
                                "My Calendar",
                                fontWeight = if (currentScreen == ActiveScreen.MONTHLY_CALENDAR) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        selected = currentScreen == ActiveScreen.MONTHLY_CALENDAR,
                        onClick = { viewModel.navigateTo(ActiveScreen.MONTHLY_CALENDAR) }
                    )
                }
            }
        ) { scaffoldPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding)
            ) {
                when (currentScreen) {
                    ActiveScreen.SAVED_ROSTERS_DASHBOARD -> {
                        RosterDashboardScreen(
                            rosters = savedRosters,
                            todayScheduleState = todayScheduleState,
                            storageManager = storageManager,
                            onOpenSettings = { showSettingsDialog = true },
                            onUploadNew = { viewModel.resetForNewUpload() },
                            onOpenCalendar = { roster ->
                                viewModel.loadRoster(roster)
                                viewModel.selectCalendarMonth(roster.year, roster.month)
                                viewModel.navigateTo(ActiveScreen.MONTHLY_CALENDAR)
                            },
                            onViewDetails = { roster ->
                                viewModel.loadRoster(roster)
                                viewModel.navigateTo(ActiveScreen.ROSTER_REVIEW)
                            },
                            onUploadCorrected = { roster ->
                                viewModel.prepareCorrectedUpload(roster)
                            },
                            onDeleteRoster = { roster ->
                                viewModel.deleteRoster(roster)
                            },
                            onSaveTodayShift = { rosterId, day, newShift, newFloor, author, reason ->
                                coroutineScope.launch {
                                    viewModel.saveDailyScheduleEditForRoster(rosterId, day, newShift, newFloor, author, reason)
                                }
                            },
                            onSaveTodayAttendance = { rosterId, date, status, notes, author ->
                                coroutineScope.launch {
                                    viewModel.saveAttendanceForRoster(rosterId, date, status, notes, author)
                                }
                            }
                        )
                    }
                    ActiveScreen.MONTHLY_CALENDAR -> {
                        val effectiveYear = state.calendarDisplayedYear
                            ?: activeRoster?.year ?: detectedPeriod?.year ?: result?.detectedPeriod?.year ?: manualYear.toIntOrNull() ?: 2026
                        val effectiveMonth = state.calendarDisplayedMonth
                            ?: activeRoster?.month ?: detectedPeriod?.month ?: result?.detectedPeriod?.month ?: manualMonth.toIntOrNull() ?: 6
                        MonthlyCalendarScreen(
                            schedule = scheduleState,
                            employeeId = employeeId,
                            initialYear = effectiveYear,
                            initialMonth = effectiveMonth,
                            onNavigateToReview = { viewModel.navigateTo(ActiveScreen.ROSTER_REVIEW) },
                            onExportClick = { showExportDialog = true },
                            onSaveEntry = { updated ->
                                viewModel.updateEntry(updated)
                            },
                            onUpdateShift = onUpdateShift,
                            onNavigateToDashboard = { viewModel.navigateTo(ActiveScreen.SAVED_ROSTERS_DASHBOARD) },
                            onOpenDailyEditor = { day ->
                                viewModel.openDailyEditor(day)
                            },
                            onUpdateAttendance = { date, status, notes, author ->
                                viewModel.updateAttendance(date, status, notes, author)
                            },
                            onSaveAttendance = { date, status, notes, author ->
                                viewModel.saveAttendance(date, status, notes, author)
                            },
                            onNavigateMonth = { year, month ->
                                viewModel.selectCalendarMonth(year, month)
                            },
                            onUploadNew = {
                                viewModel.resetForNewUpload()
                            },
                            clock = viewModel.clock
                        )
                    }
                    else -> Unit
                }
            }
        }
    } else {
        when (currentScreen) {
        ActiveScreen.MY_ROSTERS -> {
            MyRostersScreen(
                rosters = savedRosters,
                onOpenCalendar = { roster ->
                    viewModel.loadRoster(roster)
                    viewModel.selectCalendarMonth(roster.year, roster.month)
                    viewModel.navigateTo(ActiveScreen.MONTHLY_CALENDAR)
                },
                onViewDetails = { roster ->
                    viewModel.loadRoster(roster)
                    viewModel.navigateTo(ActiveScreen.ROSTER_REVIEW)
                },
                onUploadNew = {
                    viewModel.resetForNewUpload()
                },
                onUploadCorrected = { roster ->
                    viewModel.prepareCorrectedUpload(roster)
                },
                onDeleteRoster = { roster ->
                    viewModel.deleteRoster(roster)
                },
                storageManager = storageManager
            )
        }
        ActiveScreen.DAILY_EDITOR -> {
            val day = state.editingDay
            val entry = scheduleState.firstOrNull { it.day == day }
            val effectiveFloor = activeRoster?.getEffectiveMonthlyFloor() ?: result?.diagnostics?.extractedMonthlyFloor
            if (entry != null) {
                DailyEditorScreen(
                    entry = entry,
                    monthlyFloor = effectiveFloor,
                    onBack = { viewModel.closeDailyEditor() },
                    onSave = { newShift, newFloor, author, reason ->
                        viewModel.saveDailyScheduleEdit(entry.day, newShift, newFloor, author, reason)
                    }
                )
            } else {
                viewModel.closeDailyEditor()
            }
        }
        ActiveScreen.ROSTER_REVIEW -> {
            val effectiveFloor = activeRoster?.getEffectiveMonthlyFloor()
                ?: result?.diagnostics?.extractedMonthlyFloor
                ?: "Floor 1"
            val effectiveYear = activeRoster?.year ?: detectedPeriod?.year ?: result?.detectedPeriod?.year ?: manualYear.toIntOrNull() ?: 2026
            val effectiveMonth = activeRoster?.month ?: detectedPeriod?.month ?: result?.detectedPeriod?.month ?: manualMonth.toIntOrNull() ?: 6
            val periodDisplay = activeRoster?.let { CalendarDomain.formatMonthHeader(it.year, it.month) }
                ?: detectedPeriod?.displayText
                ?: result?.detectedPeriod?.displayText
                ?: CalendarDomain.formatMonthHeader(effectiveYear, effectiveMonth)

            RosterReviewScreen(
                employeeId = employeeId,
                periodDisplay = periodDisplay,
                activeRoster = activeRoster,
                effectiveFloor = effectiveFloor,
                schedule = scheduleState,
                result = result,
                diagnostics = diagnostics ?: result?.diagnostics,
                sourceBitmap = sourceBitmap,
                onNavigateToCalendar = { viewModel.navigateTo(ActiveScreen.MONTHLY_CALENDAR) },
                onNavigateToDashboard = { viewModel.navigateTo(ActiveScreen.SAVED_ROSTERS_DASHBOARD) },
                onExportClick = { showExportDialog = true },
                onOpenDailyEditor = { day -> viewModel.openDailyEditor(day) },
                onBulkFloorUpdate = if (activeRoster != null) {
                    { newFloor, author, reason ->
                        viewModel.bulkUpdateFloor(activeRoster.id, newFloor, author, reason)
                    }
                } else null
            )
        }
        ActiveScreen.ROSTER_UPLOAD -> {
            val currentResult = result
            if (currentResult != null && currentResult.cells.any { it.crop != null }) {
            // Unified scrollable grid when extraction succeeds and daily crops exist
            val nonNullCropsCount = currentResult.cells.count { it.crop != null }
            val missingCropsCount = currentResult.cells.count { it.crop == null }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 105.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Full-width Header Controls
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HeaderControls()
                }

                // Separate developer-facing diagnostics area (Approved design decision: preserves every metric and bitmap)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DeveloperDiagnosticsSection(
                        result = currentResult,
                        diag = currentResult.diagnostics,
                        schedule = scheduleState,
                        expectedDays = currentResult.expectedDays,
                        nonNullCropsCount = nonNullCropsCount,
                        missingCropsCount = missingCropsCount
                    )
                }

                // Clearly visible, full-width "View Monthly Calendar" button immediately above Daily Cell Crops
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AppButton(
                        text = "📅  View Monthly Calendar",
                        onClick = { viewModel.navigateTo(ActiveScreen.MONTHLY_CALENDAR) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                // Section title for crops
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeading(
                        title = "Daily Cell Crops",
                        subtitle = "$nonNullCropsCount / ${currentResult.expectedDays} verified"
                    )
                }

                // All daily cell crop items
                items(currentResult.cells) { cell ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Day number header above crop
                            Text(
                                "Day ${cell.day}",
                                style = MaterialTheme.typography.titleMedium
                            )

                            if (cell.crop != null) {
                                // Preserved original bitmap, scaled only for presentation
                                Image(
                                    bitmap = cell.crop.asImageBitmap(),
                                    contentDescription = "Original crop for day ${cell.day}",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(60.dp),
                                    contentScale = ContentScale.Fit
                                )
                                // Original crop dimensions
                                Text(
                                    "${cell.crop.width} × ${cell.crop.height} px",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(60.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No crop", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    "0 × 0 px",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            Text(
                                "Status: ${cell.status}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            cell.bounds?.let { b ->
                                Text(
                                    "Bounds: (${b.left},${b.top})-(${b.right},${b.bottom})",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            cell.enhancedCropDimensions?.let { (ew, eh) ->
                                Text(
                                    "OCR Image: $ew × $eh px",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                "Raw OCR: ${cell.rawOcrText ?: "—"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Normalized: ${cell.ocrText ?: "—"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Colour: ${cell.colorCode ?: "—"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            val d1 = cell.colorDistance?.let { "%.1f".format(it) } ?: "—"
                            val d2 = cell.secondColorDistance?.let { "%.1f".format(it) } ?: "—"
                            Text(
                                "Lab Dist: $d1 / $d2",
                                style = MaterialTheme.typography.labelSmall
                            )

                            val scheduleEntry = scheduleState.find { it.day == cell.day }
                            if (scheduleEntry != null) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                                Text(
                                    scheduleEntry.displayTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (scheduleEntry.currentShift == ShiftType.MANAGEMENT_ABSENCE)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (scheduleEntry.isProvisional) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                        )
                                    ) {
                                        Text(
                                            "Provisional · Management Assigned",
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }

                                if (scheduleEntry.isModified) {
                                    Text(
                                        "Orig: ${scheduleEntry.extractedShiftCode} → Curr: ${scheduleEntry.currentShiftCode}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Text(
                                        "Shift: ${scheduleEntry.currentShiftCode}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }

                                Text(
                                    "Floor: ${scheduleEntry.floorDisplay}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    "Att: ${scheduleEntry.attendance.status.displayName}",
                                    style = MaterialTheme.typography.labelSmall
                                )

                                OutlinedButton(
                                    onClick = { viewModel.openDailyEditor(cell.day) },
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text("Review / Change", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Initial / Loading / Error state before extraction succeeds
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeaderControls()

                diagnostics?.let { diag ->
                    DeveloperDiagnosticsSection(
                        result = result,
                        diag = diag,
                        schedule = scheduleState,
                        expectedDays = result?.expectedDays ?: 30,
                        nonNullCropsCount = result?.cells?.count { it.crop != null } ?: 0,
                        missingCropsCount = result?.cells?.count { it.crop == null } ?: 0
                    )
                }
            }
        }
    }
    else -> Unit
}
}

    if (showExportDialog && scheduleState.isNotEmpty()) {
        ExportScheduleDialog(
            schedule = scheduleState,
            employeeId = employeeId,
            periodDisplay = detectedPeriod?.displayText ?: result?.detectedPeriod?.displayText,
            activeRoster = activeRoster,
            storageManager = storageManager,
            onDismiss = { showExportDialog = false }
        )
    }

    val updateState by updateManager.uiState.collectAsState()
    val isEditingOrExtracting = (currentScreen == ActiveScreen.DAILY_EDITOR || state.status == ExtractionStatus.RUNNING)
    // Only display dialog when not actively interrupting critical editing / OCR workflows
    if (!isEditingOrExtracting || updateState !is UpdateUiState.Available) {
        AppUpdateDialog(
            state = updateState,
            updateManager = updateManager
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            onDismiss = { showSettingsDialog = false },
            onCheckForUpdates = { updateManager.checkForUpdates(isManual = true) }
        )
    }
}

@Composable
private fun DeveloperDiagnosticsSection(
    result: RosterResult?,
    diag: ExtractionDiagnostics?,
    schedule: List<DailyScheduleEntry>,
    expectedDays: Int,
    nonNullCropsCount: Int,
    missingCropsCount: Int
) {
    if (diag == null) return
    var expanded by rememberSaveable { mutableStateOf(false) }

    InfoCard(
        containerColor = LavenderSurface,
        borderColor = LavenderBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🛠️ Developer & Extraction Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnLavenderContainer
                )
                Text(
                    text = "$nonNullCropsCount/$expectedDays crops verified · ${diag.extractedMonthlyFloor ?: "No floor"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            StatusBadge(
                text = if (expanded) "Collapse ▲" else "Expand ▼",
                backgroundColor = IndigoContainer,
                textColor = OnIndigoContainer
            )
        }

        if (expanded) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Verification diagnostics
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceClinical),
                border = androidx.compose.foundation.BorderStroke(1.dp, LavenderBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Daily Crops Verification Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("• Expected days: $expectedDays")
                    Text("• Number of CellResult records: ${result?.cells?.size ?: 0}")
                    Text("• Number of non-null crop bitmaps: $nonNullCropsCount")
                    Text("• Number of missing crops: $missingCropsCount")
                    Text("• Matched employee ID: ${result?.matchedOcrToken ?: diag.matchedIdToken} (Row index: ${result?.employeeRowIndex ?: "—"})")
                    val rowH = diag.employeeRowBoundaries?.let { it.second - it.first }
                    val (padX, padY) = result?.cells?.firstOrNull()?.cropPaddingUsed ?: (null to null)
                    Text("• Full employee row height: ${rowH ?: "—"} px (y=${diag.employeeRowBoundaries?.first ?: "—"} to y=${diag.employeeRowBoundaries?.second ?: "—"})")
                    Text("• Crop padding used: insetX=${padX ?: "—"} px, insetY=${padY ?: "—"} px")
                    Text("• Selected day boundaries: ${result?.geometry?.selectedX?.size ?: 0}")
                    Text("• Extracted Monthly Floor: ${result?.extractedFloor ?: diag.extractedMonthlyFloor ?: "Floor not assigned"}")
                }
            }

            // Dedicated Floor Extraction Diagnostics
            FloorDiagnosticsCard(
                result = result,
                diag = diag,
                schedule = schedule
            )

            // Technical Diagnostics Card
            DiagnosticsCard(diag, initiallyExpanded = true)
        }
    }
}

@Composable
private fun FloorDiagnosticsCard(
    result: RosterResult?,
    diag: ExtractionDiagnostics,
    schedule: List<DailyScheduleEntry>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Floor Extraction Runtime Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 1. Bitmaps display (Original crop + Enhanced crop)
            Text(
                "Floor Cell Bitmaps Passed to Pipeline:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Original crop
                val origBmp = result?.originalFloorCropBitmap ?: diag.originalFloorCropBitmap
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (origBmp != null) {
                        Image(
                            bitmap = origBmp.asImageBitmap(),
                            contentDescription = "Original Floor Crop",
                            modifier = Modifier
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .height(48.dp)
                                .widthIn(min = 90.dp),
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.None
                        )
                        Text(
                            "Original (${origBmp.width}×${origBmp.height} px)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Text(
                            "Original: None",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }

                // Enhanced crop
                val enhBmp = result?.enhancedFloorCropBitmap ?: diag.enhancedFloorCropBitmap
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (enhBmp != null) {
                        Image(
                            bitmap = enhBmp.asImageBitmap(),
                            contentDescription = "Enhanced Floor Crop",
                            modifier = Modifier
                                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                                .height(48.dp)
                                .widthIn(min = 130.dp),
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.None
                        )
                        Text(
                            "Enhanced (${enhBmp.width}×${enhBmp.height} px)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Text(
                            "Enhanced: None",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 2. All 14 Detailed Diagnostic Lines
            Text("• FLOOR Header Detected: ${if (diag.floorHeaderFound) "Yes" else "No"}")
            Text("• Header Raw OCR Text: '${diag.floorHeaderRawText ?: "None"}'")
            Text("• Header Bounding Box: ${diag.floorHeaderBounds?.let { "[${it.left}, ${it.top} to ${it.right}, ${it.bottom}]" } ?: "None"}")
            Text("• Day 1 Left Boundary: x = ${diag.day1LeftBoundary ?: "None"}")
            Text("• Calculated Floor Column Bounds: ${diag.floorColumnBounds?.let { "x = ${it.first} to ${it.second} (width: ${it.second - it.first} px)" } ?: "None"}")
            Text("• Selected Employee ID: ${result?.employeeId ?: diag.matchedIdToken ?: "None"} (Matched: ${result?.matchedOcrToken ?: diag.matchedIdToken ?: "None"})")
            Text("• Selected Employee Row Boundaries: ${diag.employeeRowBoundaries?.let { "y = ${it.first} to ${it.second} (height: ${it.second - it.first} px)" } ?: "None"}")
            Text("• Calculated Floor Crop Coordinates: ${diag.floorCropBounds?.let { "[left=${it.left}, top=${it.top}, right=${it.right}, bottom=${it.bottom}]" } ?: "None"}")
            Text("• Floor Crop Image Dimensions: ${diag.floorCropDimensions?.let { "${it.first} × ${it.second} px" } ?: "None"}")
            Text("• Raw OCR Result: '${diag.rawFloorOcrText ?: "None"}'")
            Text("• Normalized Floor Result: '${diag.extractedMonthlyFloor ?: "None"}'")
            Text(
                "• Final RosterResult.extractedFloor: '${result?.extractedFloor ?: diag.extractedMonthlyFloor ?: "None"}'",
                fontWeight = FontWeight.Bold
            )
            Text("• Final DailyScheduleEntry.extractedFloor: '${schedule.firstOrNull()?.extractedFloor ?: "None"}'")
            Text("• Final DailyScheduleEntry.currentFloor: '${schedule.firstOrNull()?.currentFloor ?: "None"}'")
        }
    }
}

@Composable
private fun DiagnosticsCard(diag: ExtractionDiagnostics, initiallyExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    var expandedOcr by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Technical Diagnostics ${if (expanded) "▲" else "▼"}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (expanded) "Tap to collapse" else "Tap to expand details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (expanded) {
                HorizontalDivider()

                Text("• Source Dimensions: ${diag.sourceDimensions.first} × ${diag.sourceDimensions.second}")
                Text("• Detected Period: ${diag.detectedPeriodDisplay ?: "Not detected"} (Month: ${diag.detectedMonth ?: "—"}, Year: ${diag.detectedYear ?: "—"})")
                Text("• Employee ID Exact Match: ${if (diag.employeeIdExactMatch) "Yes" else "No"}")
                Text("• Confirmed Token: ${diag.matchedIdToken ?: "None"}")
                Text("• Employee ID Bounds: ${diag.employeeIdBounds?.let { "${it.left},${it.top} – ${it.right},${it.bottom}" } ?: "None"}")
                Text("• Detected Day Columns: ${diag.dayColumnsCount}")
                Text("• Employee Row Boundaries: ${diag.employeeRowBoundaries?.let { "y=${it.first} to y=${it.second}" } ?: "None"}")
                Text("• Daily Cell Crops Generated: ${diag.cellCropsCount}")
                Text("• Floor Header Detected: ${if (diag.floorHeaderFound) "Yes" else "No"} (${diag.floorHeaderBounds?.let { "${it.left},${it.top} – ${it.right},${it.bottom}" } ?: "Not found"})")
                Text("• Floor Column X-Bounds: ${diag.floorColumnBounds?.let { "x=${it.first} to x=${it.second}" } ?: "Not detected"}")
                Text("• Extracted Monthly Floor: ${diag.extractedMonthlyFloor ?: "None"} (Raw OCR: '${diag.rawFloorOcrText ?: ""}')")

                if (diag.idCandidates.isNotEmpty()) {
                    Text(
                        "• Possible ID Candidates (${diag.idCandidates.size}):",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    diag.idCandidates.forEach { c ->
                        Text(
                            "    → '${c.text}' (norm: '${c.normalizedText}') at [${c.bounds.left},${c.bounds.top}–${c.bounds.right},${c.bounds.bottom}] — ${c.reason}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Text("• Possible ID Candidates: None")
                }

                // Collapsible All OCR text
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedOcr = !expandedOcr }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "All OCR Text Detected (${diag.allOcrLines.size} lines) ${if (expandedOcr) "▲" else "▼"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (expandedOcr) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(8.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        Column(modifier = Modifier.verticalScroll(scrollState)) {
                            diag.allOcrLines.forEachIndexed { idx, line ->
                                Text(
                                    "[$idx] $line",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditShiftDialog(
    entry: DailyScheduleEntry,
    onDismiss: () -> Unit,
    onSave: (String, ShiftChangeSource, String, String?) -> Unit
) {
    val shiftOptions = ShiftType.ALLOWED_EDIT_CODES
    var selectedShift by remember { mutableStateOf(entry.currentShiftCode) }
    var selectedSource by remember { mutableStateOf(ShiftChangeSource.MANAGEMENT_UPDATE) }
    var author by remember { mutableStateOf("Clinical Supervisor") }
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review / Update Day ${entry.day} Shift") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Original Extracted Shift: ${entry.extractedShiftCode} (${entry.extractedShift.displayName})",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Note: The original extracted shift is permanently preserved.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()

                Text("Select Current Shift:", style = MaterialTheme.typography.titleSmall)
                shiftOptions.forEach { code ->
                    val type = ShiftType.fromCode(code)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedShift = code }
                    ) {
                        RadioButton(
                            selected = (selectedShift == code),
                            onClick = { selectedShift = code }
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text("$code — ${type.displayName}", style = MaterialTheme.typography.bodyMedium)
                            if (code == "A") {
                                Text("Provisional management absence", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text("Change Source:", style = MaterialTheme.typography.titleSmall)
                ShiftChangeSource.entries.filter { it != ShiftChangeSource.INITIAL_EXTRACTION }.forEach { src ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSource = src }
                    ) {
                        RadioButton(
                            selected = (selectedSource == src),
                            onClick = { selectedSource = src }
                        )
                        Text(src.displayName, modifier = Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author / Authority") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason for Change (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(selectedShift, selectedSource, author, reason.ifBlank { null })
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ExportScheduleDialog(
    schedule: List<DailyScheduleEntry>,
    employeeId: String,
    periodDisplay: String?,
    activeRoster: RosterEntity? = null,
    storageManager: StorageManager? = null,
    onDismiss: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf("Excel (.xlsx)") }
    val context = LocalContext.current

    val saveDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null && activeRoster != null && storageManager != null) {
            val file = activeRoster.workbookFilePath?.let { File(it) } ?: storageManager.getWorkbookFile(
                activeRoster.employeeId,
                activeRoster.id,
                activeRoster.month,
                activeRoster.year,
                activeRoster.version
            )
            if (file.exists()) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    Toast.makeText(context, "Workbook exported successfully", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val exportText = remember(selectedFormat, schedule) {
        if (selectedFormat == "CSV") {
            RosterExport.toCsv(schedule, employeeId, periodDisplay)
        } else {
            RosterExport.toExcelXml(schedule, employeeId, periodDisplay)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Schedule & Workbooks") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (activeRoster != null && storageManager != null) {
                    val file = activeRoster.workbookFilePath?.let { File(it) } ?: storageManager.getWorkbookFile(
                        activeRoster.employeeId,
                        activeRoster.id,
                        activeRoster.month,
                        activeRoster.year,
                        activeRoster.version
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("📊 Genuine Excel Workbook (.xlsx)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text("Real 6-sheet workbook automatically maintained locally with audit logs and attendance summary.", style = MaterialTheme.typography.bodySmall)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (file.exists()) {
                                            val suggestedName = "Medical_Roster_${activeRoster.employeeId}_${activeRoster.year}_%02d_v%d.xlsx".format(activeRoster.month, activeRoster.version)
                                            saveDocLauncher.launch(suggestedName)
                                        } else {
                                            Toast.makeText(context, "File generating, please retry", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Text("💾 Save As...")
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (file.exists()) {
                                            context.startActivity(android.content.Intent.createChooser(storageManager.createShareIntent(file), "Share .xlsx Workbook"))
                                        }
                                    }
                                ) {
                                    Text("Share .xlsx")
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (file.exists()) {
                                            try {
                                                context.startActivity(storageManager.createViewIntent(file))
                                            } catch (_: Exception) {
                                                context.startActivity(android.content.Intent.createChooser(storageManager.createShareIntent(file), "Open Workbook"))
                                            }
                                        } else {
                                            Toast.makeText(context, "File generating, please retry", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Text("Open .xlsx")
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }

                Text("Text / Clipboard Export:", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { selectedFormat = "Excel (SpreadsheetML)" }
                    ) {
                        RadioButton(
                            selected = selectedFormat == "Excel (SpreadsheetML)",
                            onClick = { selectedFormat = "Excel (SpreadsheetML)" }
                        )
                        Text("Excel XML")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { selectedFormat = "CSV" }
                    ) {
                        RadioButton(
                            selected = selectedFormat == "CSV",
                            onClick = { selectedFormat = "CSV" }
                        )
                        Text("CSV")
                    }
                }

                Text(
                    "Rule 10: Code 'A' is exported as 'Management Absence' with the original extracted code preserved in a separate column.",
                    style = MaterialTheme.typography.labelSmall
                )

                HorizontalDivider()

                Text("Preview (first 1500 chars):", style = MaterialTheme.typography.titleSmall)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    val scroll = rememberScrollState()
                    Text(
                        text = exportText.take(1500),
                        modifier = Modifier
                            .padding(8.dp)
                            .verticalScroll(scroll),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Roster Export", exportText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "$selectedFormat copied to clipboard!", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) {
                Text("Copy to Clipboard")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
