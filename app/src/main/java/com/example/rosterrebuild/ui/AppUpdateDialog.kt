package com.example.rosterrebuild.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.rosterrebuild.BuildConfig
import com.example.rosterrebuild.ui.components.AppButton
import com.example.rosterrebuild.ui.components.AppOutlinedButton
import com.example.rosterrebuild.ui.components.InfoCard
import com.example.rosterrebuild.ui.theme.*
import com.example.rosterrebuild.update.AppUpdateManager
import com.example.rosterrebuild.update.UpdateUiState

/**
 * Update prompt dialog implementing system-delegated APK updates.
 *
 * Dispatches downloads to Android DownloadManager upon user approval.
 * Never requests package installer permissions or installs APKs directly.
 */
@Composable
fun AppUpdateDialog(
    state: UpdateUiState,
    updateManager: AppUpdateManager,
    onOpenUrl: (String) -> Unit = {}
) {
    if (state is UpdateUiState.Idle) return

    val context = LocalContext.current

    fun openBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            onOpenUrl(url)
        }
    }

    Dialog(
        onDismissRequest = {
            when (state) {
                is UpdateUiState.Available -> updateManager.dismissUpdate(state.update.versionName)
                else -> updateManager.dismissDialog()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
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
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (state) {
                                is UpdateUiState.Available -> "Medical Roster update available"
                                is UpdateUiState.DownloadEnqueued -> "Download Started"
                                is UpdateUiState.UpToDate -> "Medical Roster is Up to Date"
                                is UpdateUiState.Checking -> "Checking for updates…"
                                is UpdateUiState.Error -> "Update Check"
                                else -> "Application Update"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = IndigoDark
                        )
                        Text(
                            text = "Installed: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
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
                                text = "Checking the latest GitHub release…",
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
                                text = "You are using the latest version (${state.latestVersion}). All your local rosters and shifts are up to date.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            AppButton(
                                text = "OK",
                                onClick = { updateManager.dismissDialog() }
                            )
                        }
                    }

                    is UpdateUiState.Available -> {
                        val update = state.update
                        InfoCard(
                            containerColor = LavenderSurface,
                            borderColor = LavenderBorder
                        ) {
                            Text(
                                text = "Version ${update.versionName} is available.",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = IndigoDark
                            )

                            if (update.versionCode != null) {
                                Text(
                                    text = "Build: ${update.versionCode}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Download the APK update now? When the download completes, tap the download notification and approve the Android installer. Android will keep all your existing data.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )

                            if (!update.releaseNotes.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Release Notes:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = update.releaseNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }

                            update.sha256?.let { publishedDigest ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Published integrity (GitHub): $publishedDigest",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppOutlinedButton(
                                text = "Later",
                                onClick = { updateManager.dismissUpdate(update.versionName) },
                                modifier = Modifier.weight(1f)
                            )
                            AppButton(
                                text = "Download APK",
                                onClick = { updateManager.enqueueDownload(update) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is UpdateUiState.DownloadEnqueued -> {
                        val update = state.update
                        InfoCard(
                            containerColor = LavenderSurface,
                            borderColor = LavenderBorder
                        ) {
                            Text(
                                text = "Medical Roster v${update.versionName} is downloading in the background.",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = IndigoDark
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "When the download finishes, tap the system download notification to install the update. Your local rosters, shifts, and attendance records will remain intact.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppOutlinedButton(
                                text = "Releases Page",
                                onClick = {
                                    openBrowser(update.releasePageUrl)
                                    updateManager.dismissDialog()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AppButton(
                                text = "OK",
                                onClick = { updateManager.dismissDialog() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is UpdateUiState.Error -> {
                        InfoCard(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "Update notice",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppOutlinedButton(
                                text = "Releases Page",
                                onClick = {
                                    openBrowser(state.releasePageUrl)
                                    updateManager.dismissDialog()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            AppButton(
                                text = "OK",
                                onClick = { updateManager.dismissDialog() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}
