package com.example.rosterrebuild.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.rosterrebuild.BuildConfig
import com.example.rosterrebuild.ui.components.AppButton
import com.example.rosterrebuild.ui.components.AppOutlinedButton
import com.example.rosterrebuild.ui.components.AppTextButton
import com.example.rosterrebuild.ui.components.InfoCard
import com.example.rosterrebuild.ui.theme.*

/**
 * Settings, Diagnostics & About Dialog.
 *
 * Keeps technical and administrative information out of everyday workflows
 * while providing easy access to app version, update checking, storage privacy info,
 * and developer diagnostics.
 */
@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onCheckForUpdates: () -> Unit
) {
    var showDiagnostics by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Settings & About",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Medical Roster Companion",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Text("✕", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Application Version & Update Check
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = LavenderSurface),
                        border = BorderStroke(1.dp, LavenderBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Application Version",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = IndigoDark
                                )
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = IndigoPrimary
                                )
                            }

                            Text(
                                text = "Channel: Hospital Pilot · Signed Release Build",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            AppButton(
                                text = "🔄 Check for Updates",
                                onClick = {
                                    onDismiss()
                                    onCheckForUpdates()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                            )
                        }
                    }

                    // Local Storage & Privacy Information
                    InfoCard(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        borderColor = MaterialTheme.colorScheme.outlineVariant
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("🔒", fontSize = 18.sp)
                            Text(
                                text = "Local Storage & Privacy",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• All roster images, extracted schedules, attendance logs, and audit records remain strictly on this device in private Room databases.\n• Cloud backups, device-to-device transfers, and telemetry are completely disabled.\n• No personal medical roster data is ever transmitted off the device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            lineHeight = 18.sp
                        )
                    }

                    // About Product
                    InfoCard(
                        containerColor = MaterialTheme.colorScheme.surface,
                        borderColor = MaterialTheme.colorScheme.outlineVariant
                    ) {
                        Text(
                            text = "About Medical Roster",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Personal, offline-first medical roster and attendance companion designed for individual clinical staff.\nSupports on-device OCR extraction, interactive 7-column monthly calendars, self-reported attendance recording, versioned historical rosters, and Excel report generation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            lineHeight = 18.sp
                        )
                    }

                    // Technical Diagnostics Section (collapsible)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AppTextButton(
                            text = if (showDiagnostics) "Hide Technical Diagnostics" else "View Technical Diagnostics",
                            onClick = { showDiagnostics = !showDiagnostics }
                        )

                        if (showDiagnostics) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Diagnostic Information",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Package: ${BuildConfig.APPLICATION_ID}\nVersion: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\nBuild Type: ${BuildConfig.BUILD_TYPE}\nDatabase: Room Schema v2\nStorage: App-Private Internal Storage",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // Close Button
                AppOutlinedButton(
                    text = "Close",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

