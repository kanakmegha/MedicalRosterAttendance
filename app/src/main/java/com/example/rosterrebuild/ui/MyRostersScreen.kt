package com.example.rosterrebuild.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rosterrebuild.CalendarDomain
import com.example.rosterrebuild.data.local.RosterEntity
import com.example.rosterrebuild.data.storage.StorageManager
import com.example.rosterrebuild.ui.components.AppButton
import com.example.rosterrebuild.ui.components.AppOutlinedButton
import com.example.rosterrebuild.ui.components.InfoCard
import com.example.rosterrebuild.ui.components.StatusBadge
import com.example.rosterrebuild.ui.components.StatusBadgeVariant
import com.example.rosterrebuild.ui.theme.*
import java.io.File
import java.time.Month
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Destination 3: My Rosters Screen.
 *
 * Displays all uploaded monthly rosters, sorted by month and version.
 * Provides clear access to monthly calendar, Excel export, extraction details,
 * corrected uploads, and deletion with confirmation.
 */
@Composable
fun MyRostersScreen(
    rosters: List<RosterEntity>,
    onOpenCalendar: (RosterEntity) -> Unit,
    onViewDetails: (RosterEntity) -> Unit,
    onUploadNew: () -> Unit,
    onUploadCorrected: (RosterEntity) -> Unit,
    onDeleteRoster: (RosterEntity) -> Unit,
    storageManager: StorageManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var rosterPendingDelete by remember { mutableStateOf<RosterEntity?>(null) }

    // Sorted by year desc, month desc, version desc
    val sortedRosters = remember(rosters) {
        rosters.sortedWith(
            compareByDescending<RosterEntity> { it.year }
                .thenByDescending { it.month }
                .thenByDescending { it.version }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Header Summary & Upload Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "My Rosters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "${sortedRosters.size} saved monthly ${if (sortedRosters.size == 1) "roster" else "rosters"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            AppButton(
                text = "+ Upload Roster",
                onClick = onUploadNew
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (sortedRosters.isEmpty()) {
            // Empty State
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceClinical),
                    border = BorderStroke(1.dp, LavenderBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("📋", fontSize = 36.sp)
                        Text(
                            text = "No Rosters Uploaded Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Upload your personal roster image to begin viewing scheduled duties, monthly calendars, and tracking attendance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AppButton(
                            text = "+ Upload First Roster",
                            onClick = onUploadNew
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(sortedRosters, key = { it.id }) { roster ->
                    MyRosterItemCard(
                        roster = roster,
                        onOpenCalendar = { onOpenCalendar(roster) },
                        onViewDetails = { onViewDetails(roster) },
                        onUploadCorrected = { onUploadCorrected(roster) },
                        onExportExcel = { exportRosterExcel(context, roster, storageManager) },
                        onDelete = { rosterPendingDelete = roster }
                    )
                }
            }
        }
    }

    // Delete Confirmation Dialog
    rosterPendingDelete?.let { roster ->
        val periodName = formatRosterPeriod(roster.month, roster.year)
        AlertDialog(
            onDismissRequest = { rosterPendingDelete = null },
            title = {
                Text(
                    text = "Delete Roster?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete the roster for $periodName (Version ${roster.version})?\n\nThis will remove the local schedule, image, and attendance records associated with this specific version from your device.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteRoster(roster)
                        rosterPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                AppOutlinedButton(
                    text = "Cancel",
                    onClick = { rosterPendingDelete = null }
                )
            }
        )
    }
}

/**
 * Individual Roster Item Card for My Rosters list.
 */
@Composable
private fun MyRosterItemCard(
    roster: RosterEntity,
    onOpenCalendar: () -> Unit,
    onViewDetails: () -> Unit,
    onUploadCorrected: () -> Unit,
    onExportExcel: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, LavenderBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Period + Status Badge + Secondary Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatRosterPeriod(roster.month, roster.year),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        when (roster.status) {
                            "ACTIVE" -> StatusBadge(text = "CURRENT", variant = StatusBadgeVariant.SUCCESS)
                            "UPCOMING" -> StatusBadge(text = "UPCOMING", variant = StatusBadgeVariant.PRIMARY)
                            else -> StatusBadge(text = "ARCHIVED", variant = StatusBadgeVariant.NEUTRAL)
                        }
                    }
                    Text(
                        text = "Employee: ${roster.employeeId} · Version ${roster.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Overflow menu for secondary actions
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge, color = TextSecondary)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("👁️ View Extraction Details") },
                            onClick = {
                                showMenu = false
                                onViewDetails()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🔄 Upload Corrected Version") },
                            onClick = {
                                showMenu = false
                                onUploadCorrected()
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("🗑️ Delete Roster", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            // Summary Info Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val floorText = roster.currentMonthlyFloor ?: roster.floorExtracted ?: "Unassigned"
                Text(
                    text = "🏢 $floorText",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
                Text(
                    text = "📅 ${CalendarDomain.getDaysInMonth(roster.year, roster.month)} Days",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            // Primary & Secondary Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppButton(
                    text = "📅 View Calendar",
                    onClick = onOpenCalendar,
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                )
                AppOutlinedButton(
                    text = "📊 Export",
                    onClick = onExportExcel,
                    modifier = Modifier.weight(0.8f)
                )
            }
        }
    }
}

private fun formatRosterPeriod(month: Int, year: Int): String {
    return try {
        val monthName = Month.of(month).getDisplayName(JavaTextStyle.FULL, Locale.ENGLISH)
        "$monthName $year"
    } catch (_: Exception) {
        "Month $month, $year"
    }
}

private fun exportRosterExcel(context: Context, roster: RosterEntity, storageManager: StorageManager) {
    val file = roster.workbookFilePath?.let { File(it) }
    if (file != null && file.exists()) {
        try {
            val intent = storageManager.createShareIntent(file)
            context.startActivity(android.content.Intent.createChooser(intent, "Share Roster Excel"))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open Excel file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Workbook file not found on disk.", Toast.LENGTH_SHORT).show()
    }
}
