package com.example.rosterrebuild.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.rosterrebuild.ui.components.AppButton
import com.example.rosterrebuild.ui.components.AppOutlinedButton
import com.example.rosterrebuild.ui.components.AppTextButton
import com.example.rosterrebuild.ui.components.ConsistentTextField
import com.example.rosterrebuild.ui.theme.*
import com.example.rosterrebuild.update.UpdateConfigStore
import com.example.rosterrebuild.update.UpdateManifestValidator

@Composable
fun UpdateEndpointDialog(
    configStore: UpdateConfigStore,
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf(configStore.getManifestUrl()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Hospital Update Endpoint (Admin)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = IndigoDark
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Configure the secure HTTPS release manifest server URL. This allows devices to check for updates against an internal hospital server or secure staging host. Leave empty if no update server is used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )

                ConsistentTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        errorMessage = null
                    },
                    label = "Manifest URL (must be HTTPS)"
                )

                errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    "Security: Only standard HTTPS endpoints with trusted TLS certificates are accepted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        },
        confirmButton = {
            AppButton(
                text = "Save Endpoint",
                onClick = {
                    val trimmed = urlText.trim()
                    if (trimmed.isEmpty()) {
                        configStore.clearManifestUrl()
                        onDismiss()
                    } else if (!UpdateManifestValidator.isHttpsUrl(trimmed)) {
                        errorMessage = "Security violation: Endpoint must use HTTPS."
                    } else {
                        configStore.setManifestUrl(trimmed)
                        onDismiss()
                    }
                }
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppTextButton(
                    text = "Clear Endpoint",
                    onClick = {
                        configStore.clearManifestUrl()
                        urlText = ""
                        errorMessage = null
                    }
                )
                AppOutlinedButton(
                    text = "Cancel",
                    onClick = onDismiss
                )
            }
        }
    )
}
