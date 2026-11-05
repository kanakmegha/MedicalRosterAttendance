package com.example.rosterrebuild.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.rosterrebuild.ui.components.AppButton
import com.example.rosterrebuild.ui.components.AppOutlinedButton
import com.example.rosterrebuild.ui.components.AppTextButton
import com.example.rosterrebuild.ui.components.InfoCard
import com.example.rosterrebuild.ui.theme.*
import com.example.rosterrebuild.update.AppUpdateManager
import com.example.rosterrebuild.update.UpdateErrorType
import com.example.rosterrebuild.update.UpdateUiState
import java.io.File
import java.util.Locale

@Composable
fun AppUpdateDialog(
    state: UpdateUiState,
    updateManager: AppUpdateManager,
    onLaunchIntent: (Intent) -> Unit,
    onConfigureEndpoint: (() -> Unit)? = null
) {
    if (state is UpdateUiState.Idle) return

    val context = LocalContext.current
    val installedVersionCode = getInstalledVersionCode(context)
    val installedVersionName = getInstalledVersionName(context)

    val isMandatory = when (state) {
        is UpdateUiState.Available -> state.isMandatory
        is UpdateUiState.Downloading -> state.manifest.mandatory
        is UpdateUiState.Verifying -> state.manifest.mandatory
        is UpdateUiState.ReadyToInstall -> state.manifest.mandatory
        is UpdateUiState.PermissionRequired -> state.manifest.mandatory
        is UpdateUiState.Error -> state.manifest?.mandatory == true
        else -> false
    }

    Dialog(
        onDismissRequest = {
            if (!isMandatory) {
                when (state) {
                    is UpdateUiState.Available -> updateManager.deferUpdate(state.manifest.versionCode)
                    is UpdateUiState.Downloading -> updateManager.cancelDownload()
                    else -> updateManager.dismiss()
                }
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isMandatory,
            dismissOnClickOutside = !isMandatory,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header & Status Tag
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hospital Application Update",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = IndigoDark
                        )
                        Text(
                            text = "Installed: v$installedVersionName ($installedVersionCode)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    if (isMandatory) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = "MANDATORY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    } else if (state is UpdateUiState.Available) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = LavenderBorder,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = "RECOMMENDED",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = IndigoPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = LavenderBorder)

                // Body content based on state
                when (state) {
                    is UpdateUiState.Checking -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = IndigoPrimary)
                            Text(
                                text = "Checking for hospital release updates...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                    }

                    is UpdateUiState.UpToDate -> {
                        InfoCard(
                            containerColor = LavenderSurface,
                            borderColor = LavenderBorder
                        ) {
                            Text(
                                text = "You are running the latest version: v${state.currentVersionName} (${state.currentVersionCode}).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            AppButton(
                                text = "Close",
                                onClick = { updateManager.dismiss() }
                            )
                        }
                    }

                    is UpdateUiState.Available -> {
                        val manifest = state.manifest
                        InfoCard(
                            containerColor = LavenderSurface,
                            borderColor = LavenderBorder
                        ) {
                            Text(
                                text = "New Version Available: v${manifest.versionName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = IndigoDark
                            )
                            Text(
                                text = "Channel: ${manifest.releaseChannel} · Build: ${manifest.versionCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            if (manifest.releaseNotes.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Release Notes:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                manifest.releaseNotes.forEach { note ->
                                    Text(
                                        text = "• $note",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!state.isMandatory) {
                                AppOutlinedButton(
                                    text = "Remind Me Later",
                                    onClick = { updateManager.deferUpdate(manifest.versionCode) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            AppButton(
                                text = "Download & Verify",
                                onClick = { updateManager.startDownload(manifest) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is UpdateUiState.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Downloading update v${state.manifest.versionName}...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )

                            if (state.percent >= 0) {
                                LinearProgressIndicator(
                                    progress = { state.percent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp),
                                    color = IndigoPrimary,
                                    trackColor = LavenderBorder
                                )
                                val downloadedMb = state.bytesDownloaded / (1024f * 1024f)
                                val totalMb = state.totalBytes / (1024f * 1024f)
                                Text(
                                    text = String.format(Locale.ROOT, "%d%% (%.1f MB / %.1f MB)", state.percent, downloadedMb, totalMb),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                    color = IndigoPrimary
                                )
                                Text(
                                    text = "Downloading...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }

                            if (!isMandatory) {
                                AppOutlinedButton(
                                    text = "Cancel Download",
                                    onClick = { updateManager.cancelDownload() },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    is UpdateUiState.Verifying -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = IndigoPrimary)
                            Text(
                                text = "Verifying APK integrity and cryptographic signature...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Checking SHA-256 and certificate chain before installation",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    is UpdateUiState.PermissionRequired -> {
                        InfoCard(
                            containerColor = LavenderSurface,
                            borderColor = LavenderBorder
                        ) {
                            Text(
                                text = "Permission Required",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = IndigoDark
                            )
                            Text(
                                text = state.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!isMandatory) {
                                AppOutlinedButton(
                                    text = "Cancel",
                                    onClick = { updateManager.dismiss() },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            AppButton(
                                text = "Open Settings",
                                onClick = {
                                    onLaunchIntent(state.permissionIntent)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is UpdateUiState.ReadyToInstall -> {
                        InfoCard(
                            containerColor = LavenderSurface,
                            borderColor = LavenderBorder
                        ) {
                            Text(
                                text = "Update Verified & Ready",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = IndigoDark
                            )
                            Text(
                                text = "Cryptographic signature and package identity verified successfully. " +
                                       "Tap below to launch the Android package installer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!isMandatory) {
                                AppOutlinedButton(
                                    text = "Install Later",
                                    onClick = { updateManager.deferUpdate(state.manifest.versionCode) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            AppButton(
                                text = "Install Update",
                                onClick = {
                                    val intent = updateManager.getInstallIntent(state.apkFile)
                                    if (intent != null) {
                                        onLaunchIntent(intent)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is UpdateUiState.Error -> {
                        var showDiagnostics by remember { mutableStateOf(false) }

                        val isNotConfigured = state.errorType == UpdateErrorType.NOT_CONFIGURED
                        val isTlsError = state.errorType == UpdateErrorType.TLS_TRUST_FAILURE || state.errorType == UpdateErrorType.TLS_EXPIRED

                        InfoCard(
                            containerColor = if (isNotConfigured)
                                MaterialTheme.colorScheme.surfaceVariant
                            else
                                MaterialTheme.colorScheme.errorContainer,
                            borderColor = if (isNotConfigured)
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = when (state.errorType) {
                                    UpdateErrorType.NOT_CONFIGURED -> "ℹ️ Updates Not Configured"
                                    UpdateErrorType.TLS_TRUST_FAILURE -> "🔒 HTTPS Security Notice"
                                    UpdateErrorType.TLS_EXPIRED -> "⚠️ Security Certificate Expired"
                                    else -> "📡 Update Check"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isNotConfigured)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isNotConfigured)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.onErrorContainer
                            )

                            // Technical diagnostics separately for administrators
                            if (!state.technicalDetails.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                AppTextButton(
                                    text = if (showDiagnostics) "Hide Technical Diagnostics" else "View Technical Diagnostics",
                                    onClick = { showDiagnostics = !showDiagnostics }
                                )
                                if (showDiagnostics) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                    ) {
                                        Text(
                                            text = state.technicalDetails,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(8.dp),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppOutlinedButton(
                                text = "Dismiss",
                                onClick = { updateManager.dismiss() },
                                modifier = Modifier.weight(1f)
                            )
                            if (isNotConfigured && onConfigureEndpoint != null) {
                                AppButton(
                                    text = "⚙️ Configure (Admin)",
                                    onClick = {
                                        updateManager.dismiss()
                                        onConfigureEndpoint()
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            } else if (state.canRetry && state.manifest != null) {
                                AppButton(
                                    text = "Retry",
                                    onClick = { updateManager.startDownload(state.manifest) },
                                    modifier = Modifier.weight(1f)
                                )
                            } else if (state.canRetry) {
                                AppButton(
                                    text = "Retry Check",
                                    onClick = { updateManager.checkForUpdates(isManual = true) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}

private fun getInstalledVersionName(context: Context): String {
    return try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        pi.versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }
}

private fun getInstalledVersionCode(context: Context): Int {
    return try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (pi.longVersionCode and 0xFFFFFFFFL).toInt()
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode
        }
    } catch (_: Exception) {
        1
    }
}

