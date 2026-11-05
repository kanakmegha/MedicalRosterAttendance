package com.example.rosterrebuild.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.rosterrebuild.data.local.RosterEntity
import com.example.rosterrebuild.data.storage.StorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.*
import com.example.rosterrebuild.ui.components.AppButton
import com.example.rosterrebuild.ui.components.AppOutlinedButton
import com.example.rosterrebuild.ui.components.StatusBadge
import com.example.rosterrebuild.ui.components.StatusBadgeVariant
import com.example.rosterrebuild.ui.theme.*

@Composable
fun RosterDashboardScreen(
    rosters: List<RosterEntity>,
    onOpenCalendar: (RosterEntity) -> Unit,
    onViewDetails: (RosterEntity) -> Unit,
    onUploadNew: () -> Unit,
    onUploadCorrected: (RosterEntity) -> Unit,
    onDeleteRoster: (RosterEntity) -> Unit,
    storageManager: StorageManager,
    onCheckForUpdates: () -> Unit = {},
    onConfigureEndpoint: () -> Unit = {}
) {
    val context = LocalContext.current
    var rosterPendingDelete by remember { mutableStateOf<RosterEntity?>(null) }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Medical Rosters",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = onConfigureEndpoint,
                                    modifier = Modifier.size(32.dp).padding(start = 4.dp)
                                ) {
                                    Text("⚙️", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(
                                "Offline Local Storage · Private App Files",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        AppButton(
                            text = "+ Upload Roster",
                            onClick = onUploadNew
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    AppOutlinedButton(
                        text = "🔄 Check for Updates",
                        onClick = onCheckForUpdates,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (rosters.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📋", style = MaterialTheme.typography.displayMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No Saved Rosters",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Upload a photo or screenshot of your monthly medical roster. Extracted shifts, floor assignments, and attendance logs are saved offline with real-time Excel workbooks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    AppButton(
                        text = "Upload New Roster",
                        onClick = onUploadNew,
                        modifier = Modifier.fillMaxWidth(0.7f)
                    )
                }
            } else {
                val latestUploadedRosterId = remember(rosters) {
                    rosters.maxByOrNull { it.uploadTimestamp }?.id
                }

                val activeRosters = remember(rosters) { rosters.filter { it.status == "ACTIVE" } }
                val upcomingRosters = remember(rosters) { rosters.filter { it.status == "UPCOMING" } }
                val archivedRosters = remember(rosters) { rosters.filter { it.status == "ARCHIVED" || (it.status != "ACTIVE" && it.status != "UPCOMING") } }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    // 1. Currently Applicable Active Rosters
                    if (activeRosters.isNotEmpty()) {
                        item {
                            Column {
                                Text(
                                    "Active Roster",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Currently applicable clinical schedule",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(activeRosters, key = { it.id }) { roster ->
                            RosterCard(
                                roster = roster,
                                isLatestUpload = (roster.id == latestUploadedRosterId),
                                onOpenCalendar = { onOpenCalendar(roster) },
                                onViewDetails = { onViewDetails(roster) },
                                onUploadCorrected = { onUploadCorrected(roster) },
                                onOpenExcel = { openExcelFile(context, storageManager, roster) },
                                onDelete = { rosterPendingDelete = roster }
                            )
                        }
                    }

                    // 2. Future / Upcoming Rosters
                    if (upcomingRosters.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Column {
                                Text(
                                    "Upcoming Rosters",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = IndigoDark
                                )
                                Text(
                                    "Future scheduled rosters (retained ahead of their effective period)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(upcomingRosters, key = { it.id }) { roster ->
                            RosterCard(
                                roster = roster,
                                isLatestUpload = (roster.id == latestUploadedRosterId),
                                onOpenCalendar = { onOpenCalendar(roster) },
                                onViewDetails = { onViewDetails(roster) },
                                onUploadCorrected = { onUploadCorrected(roster) },
                                onOpenExcel = { openExcelFile(context, storageManager, roster) },
                                onDelete = { rosterPendingDelete = roster }
                            )
                        }
                    }

                    // 3. Historical / Superseded Rosters
                    if (archivedRosters.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Column {
                                Text(
                                    "Archived / Previous Rosters",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Historical months and superseded versions",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(archivedRosters, key = { it.id }) { roster ->
                            RosterCard(
                                roster = roster,
                                isLatestUpload = (roster.id == latestUploadedRosterId),
                                onOpenCalendar = { onOpenCalendar(roster) },
                                onViewDetails = { onViewDetails(roster) },
                                onUploadCorrected = { onUploadCorrected(roster) },
                                onOpenExcel = { openExcelFile(context, storageManager, roster) },
                                onDelete = { rosterPendingDelete = roster }
                            )
                        }
                    }
                }
            }
        }
    }

    // Deletion & Retention Warning Dialog
    rosterPendingDelete?.let { roster ->
        val isPastMonth = try {
            val now = LocalDate.now()
            roster.year < now.year || (roster.year == now.year && roster.month < now.monthValue)
        } catch (_: Exception) {
            false
        }

        AlertDialog(
            onDismissRequest = { rosterPendingDelete = null },
            title = {
                Text(if (isPastMonth) "⚠️ Month-End Retention Review" else "Delete Saved Roster")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Are you sure you want to delete this roster?")
                    Text(
                        "• Period: ${formatPeriod(roster.month, roster.year)} (v${roster.version})\n• Employee ID: ${roster.employeeId}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (isPastMonth) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Warning: This roster belongs to a completed month. Verify that you have exported your Excel workbook or shared it with your department before deleting.",
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = roster
                        rosterPendingDelete = null
                        onDeleteRoster(toDelete)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Confirm Delete")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        openExcelFile(context, storageManager, roster)
                    }) {
                        Text("Export / Share Excel")
                    }
                    TextButton(onClick = { rosterPendingDelete = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@Composable
private fun RosterCard(
    roster: RosterEntity,
    isLatestUpload: Boolean = false,
    onOpenCalendar: () -> Unit,
    onViewDetails: () -> Unit,
    onUploadCorrected: () -> Unit,
    onOpenExcel: () -> Unit,
    onDelete: () -> Unit
) {
    val monthName = formatPeriod(roster.month, roster.year)
    val uploadDate = remember(roster.uploadTimestamp) {
        val sdf = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        sdf.format(Date(roster.uploadTimestamp))
    }

    val isActive = roster.status == "ACTIVE"
    val isUpcoming = roster.status == "UPCOMING"

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive -> MaterialTheme.colorScheme.surface
                isUpcoming -> LavenderSurface
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        border = when {
            isActive -> androidx.compose.foundation.BorderStroke(1.5.dp, IndigoPrimary)
            isUpcoming -> androidx.compose.foundation.BorderStroke(1.dp, LavenderBorder)
            else -> null
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Period + Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = monthName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Employee: ${roster.employeeId} · ${roster.daysCount} days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLatestUpload) {
                        StatusBadge(
                            text = "Latest Upload",
                            variant = StatusBadgeVariant.WARNING
                        )
                    }
                    StatusBadge(
                        text = "v${roster.version}",
                        variant = StatusBadgeVariant.NEUTRAL
                    )
                    val statusVariant = when (roster.status) {
                        "ACTIVE" -> StatusBadgeVariant.SUCCESS
                        "UPCOMING" -> StatusBadgeVariant.PRIMARY
                        else -> StatusBadgeVariant.NEUTRAL
                    }
                    StatusBadge(
                        text = roster.status,
                        variant = statusVariant
                    )
                }
            }

            // Info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Floor: ${roster.floorExtracted ?: "Not detected"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Uploaded $uploadDate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // Primary Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppButton(
                    text = "📅 Calendar",
                    onClick = onOpenCalendar,
                    modifier = Modifier.weight(1f)
                )

                AppOutlinedButton(
                    text = "📊 Excel",
                    onClick = onOpenExcel,
                    modifier = Modifier.weight(1f)
                )
            }

            // Secondary Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = onViewDetails,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("🔍 Details", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick = onUploadCorrected,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("🔄 Corrected", style = MaterialTheme.typography.labelMedium)
                    }
                }

                IconButton(onClick = onDelete) {
                    Text("🗑️", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun formatPeriod(month: Int, year: Int): String {
    return try {
        val m = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        "$m $year"
    } catch (_: Exception) {
        "Month $month $year"
    }
}

private fun openExcelFile(context: Context, storageManager: StorageManager, roster: RosterEntity) {
    val file = roster.workbookFilePath?.let { File(it) } ?: storageManager.getWorkbookFile(
        roster.employeeId,
        roster.id,
        roster.month,
        roster.year,
        roster.version
    )

    if (!file.exists()) {
        Toast.makeText(context, "Workbook file not found on device", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val viewIntent = storageManager.createViewIntent(file)
        context.startActivity(viewIntent)
    } catch (_: Exception) {
        // Fallback to share intent if no default viewer
        try {
            val shareIntent = storageManager.createShareIntent(file)
            context.startActivity(android.content.Intent.createChooser(shareIntent, "Open / Share Excel Workbook"))
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open or share Excel: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

