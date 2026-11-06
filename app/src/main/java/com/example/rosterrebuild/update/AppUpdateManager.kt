package com.example.rosterrebuild.update

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class UpToDate(val currentVersionCode: Int, val currentVersionName: String) : UpdateUiState()
    data class Available(val manifest: UpdateManifest, val isMandatory: Boolean) : UpdateUiState()
    data class Downloading(
        val manifest: UpdateManifest,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percent: Int
    ) : UpdateUiState()
    data class Verifying(val manifest: UpdateManifest) : UpdateUiState()
    data class ReadyToInstall(val manifest: UpdateManifest, val apkFile: File) : UpdateUiState()
    data class PermissionRequired(
        val manifest: UpdateManifest,
        val apkFile: File,
        val permissionIntent: Intent,
        val explanation: String
    ) : UpdateUiState()
    data class Error(
        val message: String,
        val technicalDetails: String? = null,
        val errorType: UpdateErrorType = UpdateErrorType.SERVER_UNAVAILABLE,
        val manifest: UpdateManifest? = null,
        val canRetry: Boolean = true
    ) : UpdateUiState()
}

/**
 * Orchestrates update checking, downloading, identity verification, and installation handoff.
 */
class AppUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    val configStore = UpdateConfigStore(context)
    private val fetcher = UpdateManifestFetcher(context, configStore)
    private val downloadManager = ApkDownloadManager(context)

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var currentDownloadJob: Job? = null
    private var deferredVersionCode: Int = -1

    /**
     * Checks for updates.
     *
     * @param isManual True if initiated by the user explicitly clicking "Check for Updates"
     */
    fun checkForUpdates(isManual: Boolean = false) {
        if (!isManual && configStore.shouldThrottleForegroundCheck()) {
            return
        }

        if (_uiState.value is UpdateUiState.Checking ||
            _uiState.value is UpdateUiState.Downloading ||
            _uiState.value is UpdateUiState.Verifying) {
            return
        }

        scope.launch(Dispatchers.IO) {
            _uiState.value = UpdateUiState.Checking

            when (val checkResult = fetcher.checkForUpdates()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    configStore.saveLastKnownManifest(checkResult.manifest)

                    // Check if this version was deferred in this session and not manual
                    if (!isManual && !checkResult.isMandatory && checkResult.manifest.versionCode == deferredVersionCode) {
                        _uiState.value = UpdateUiState.Idle
                        return@launch
                    }

                    _uiState.value = UpdateUiState.Available(
                        manifest = checkResult.manifest,
                        isMandatory = checkResult.isMandatory
                    )
                }
                is UpdateCheckResult.UpToDate -> {
                    configStore.saveLastKnownManifest(checkResult.manifest)

                    _uiState.value = if (isManual) {
                        UpdateUiState.UpToDate(
                            currentVersionCode = checkResult.currentVersionCode,
                            currentVersionName = checkResult.manifest.versionName
                        )
                    } else {
                        UpdateUiState.Idle
                    }
                }
                is UpdateCheckResult.Error -> {
                    if (isManual) {
                        _uiState.value = UpdateUiState.Error(
                            message = checkResult.message,
                            technicalDetails = checkResult.technicalDetails,
                            errorType = checkResult.errorType,
                            canRetry = checkResult.errorType != UpdateErrorType.NOT_CONFIGURED
                        )
                    } else {
                        // Offline or network error during background check is silent to avoid disturbing clinical user
                        _uiState.value = UpdateUiState.Idle
                    }
                }
            }
        }
    }

    /**
     * Called when the app returns to the foreground.
     * Handles pending permission return or triggers throttled automatic check.
     */
    fun onAppForegrounded() {
        val currentState = _uiState.value
        if (currentState is UpdateUiState.PermissionRequired) {
            recheckInstallPermission(currentState.apkFile, currentState.manifest)
            return
        }
        if (currentState is UpdateUiState.Idle) {
            checkForUpdates(isManual = false)
        }
    }

    /**
     * Initiates APK download for [manifest].
     */
    fun startDownload(manifest: UpdateManifest) {
        currentDownloadJob?.cancel()
        currentDownloadJob = scope.launch(Dispatchers.IO) {
            _uiState.value = UpdateUiState.Downloading(
                manifest = manifest,
                bytesDownloaded = 0,
                totalBytes = -1,
                percent = 0
            )

            val downloadResult = downloadManager.downloadApk(manifest) { bytes, total, percent ->
                _uiState.value = UpdateUiState.Downloading(
                    manifest = manifest,
                    bytesDownloaded = bytes,
                    totalBytes = total,
                    percent = percent
                )
            }

            when (downloadResult) {
                is DownloadResult.Success -> {
                    // Download complete, now perform strict identity and signature verification
                    _uiState.value = UpdateUiState.Verifying(manifest)

                    when (val verification = ApkIdentityVerifier.verifyApk(context, downloadResult.apkFile, manifest)) {
                        is ApkVerificationResult.Success -> {
                            // Verify package install permission
                            if (!PackageInstallerHelper.canRequestPackageInstalls(context)) {
                                val permissionIntent = PackageInstallerHelper.createPermissionIntent(context)
                                _uiState.value = UpdateUiState.PermissionRequired(
                                    manifest = manifest,
                                    apkFile = downloadResult.apkFile,
                                    permissionIntent = permissionIntent,
                                    explanation = "Private hospital distribution requires granting 'Install unknown apps' permission " +
                                                  "for Medical Roster Extractor to proceed with this verified update."
                                )
                            } else {
                                _uiState.value = UpdateUiState.ReadyToInstall(
                                    manifest = manifest,
                                    apkFile = downloadResult.apkFile
                                )
                            }
                        }
                        is ApkVerificationResult.Failure -> {
                            downloadResult.apkFile.delete()
                            _uiState.value = UpdateUiState.Error(
                                message = "Verification failed: ${verification.reason}",
                                manifest = manifest,
                                canRetry = false
                            )
                        }
                    }
                }
                is DownloadResult.Failure -> {
                    _uiState.value = UpdateUiState.Error(
                        message = downloadResult.reason,
                        manifest = manifest,
                        canRetry = true
                    )
                }
                is DownloadResult.Cancelled -> {
                    _uiState.value = UpdateUiState.Available(
                        manifest = manifest,
                        isMandatory = manifest.mandatory
                    )
                }
            }
        }
    }

    /**
     * Cancels an ongoing download.
     */
    fun cancelDownload() {
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        _uiState.value = UpdateUiState.Idle
    }

    /**
     * Re-checks install permission after the user returns from system settings.
     */
    fun recheckInstallPermission(apkFile: File, manifest: UpdateManifest) {
        if (PackageInstallerHelper.canRequestPackageInstalls(context)) {
            _uiState.value = UpdateUiState.ReadyToInstall(manifest, apkFile)
        }
    }

    /**
     * Prepares install intent.
     */
    fun getInstallIntent(apkFile: File): Intent? {
        return when (val res = PackageInstallerHelper.prepareInstall(context, apkFile)) {
            is InstallHandoffResult.LaunchInstaller -> res.intent
            else -> null
        }
    }

    /**
     * Defers the current optional update for this session.
     */
    fun deferUpdate(versionCode: Int) {
        deferredVersionCode = versionCode
        _uiState.value = UpdateUiState.Idle
    }

    /**
     * Dismisses the dialog/state.
     */
    fun dismiss() {
        _uiState.value = UpdateUiState.Idle
    }
}

