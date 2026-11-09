package com.example.rosterrebuild.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateUiState()
    data class Available(val update: AvailableAppUpdate) : UpdateUiState()
    data class DownloadEnqueued(val update: AvailableAppUpdate, val downloadId: Long) : UpdateUiState()
    data class Error(
        val message: String,
        val releasePageUrl: String = AppUpdateChecker.RELEASES_PAGE_URL
    ) : UpdateUiState()
}

/**
 * Coordinates update checks, user prompts, and DownloadManager enqueueing.
 *
 * @param checkNow Suspended seam returning a check result; defaults to a live GitHub check.
 *                 Kept injectable so update-UI behaviour can be unit tested without network.
 */
class AppUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val checkNow: suspend () -> AppUpdateCheckResult = {
        AppUpdateChecker.checkForUpdate()
    }
) {
    val configStore = UpdateConfigStore(context)

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    /**
     * Checks for updates against GitHub Releases.
     *
     * @param isManual True if initiated explicitly by the user from Settings.
     */
    fun checkForUpdates(isManual: Boolean = false) {
        if (!isManual && configStore.shouldThrottleCheck()) {
            return
        }

        // Prevent duplicate concurrent checks (synchronously, so overlapping calls
        // cannot both pass this guard before the check coroutine is scheduled).
        if (_uiState.value is UpdateUiState.Checking) {
            return
        }
        _uiState.value = UpdateUiState.Checking
        configStore.recordCheckTimestamp()

        scope.launch {
            when (val result = checkNow()) {
                is AppUpdateCheckResult.UpdateAvailable -> {
                    val update = result.update
                    if (!isManual && configStore.isVersionDismissed(update.versionName)) {
                        _uiState.value = UpdateUiState.Idle
                    } else {
                        _uiState.value = UpdateUiState.Available(update)
                    }
                }
                is AppUpdateCheckResult.UpToDate -> {
                    _uiState.value = if (isManual) {
                        UpdateUiState.UpToDate(
                            currentVersion = result.currentVersion,
                            latestVersion = result.latestVersion
                        )
                    } else {
                        UpdateUiState.Idle
                    }
                }
                is AppUpdateCheckResult.Failed -> {
                    _uiState.value = if (isManual) {
                        UpdateUiState.Error(result.message)
                    } else {
                        // Silent in background check to avoid disturbing staff
                        UpdateUiState.Idle
                    }
                }
            }
        }
    }

    /**
     * Enqueues the APK download via Android's system DownloadManager after user approval.
     */
    fun enqueueDownload(update: AvailableAppUpdate) {
        try {
            val downloadId = AppUpdateDownloader.enqueue(context, update)
            _uiState.value = UpdateUiState.DownloadEnqueued(update, downloadId)
        } catch (e: Exception) {
            _uiState.value = UpdateUiState.Error(
                message = "Could not start download: ${e.message ?: "DownloadManager unavailable"}. " +
                        "You can download the update directly from GitHub Releases."
            )
        }
    }

    /**
     * Dismisses the prompt and records the version as dismissed ("Later").
     */
    fun dismissUpdate(versionName: String) {
        configStore.dismissVersion(versionName)
        _uiState.value = UpdateUiState.Idle
    }

    /**
     * Dismisses the current dialog without permanently suppressing future notifications.
     */
    fun dismissDialog() {
        _uiState.value = UpdateUiState.Idle
    }

    /**
     * Called when the application returns to foreground.
     */
    fun onAppForegrounded() {
        if (_uiState.value is UpdateUiState.Idle) {
            checkForUpdates(isManual = false)
        }
    }
}
