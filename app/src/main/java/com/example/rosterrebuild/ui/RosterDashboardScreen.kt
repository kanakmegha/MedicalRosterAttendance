package com.example.rosterrebuild.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rosterrebuild.*
import com.example.rosterrebuild.data.local.RosterEntity
import com.example.rosterrebuild.data.storage.StorageManager
import com.example.rosterrebuild.ui.components.AppButton
import com.example.rosterrebuild.ui.components.AppOutlinedButton
import com.example.rosterrebuild.ui.components.AppTextButton
import com.example.rosterrebuild.ui.components.ConsistentTextField
import com.example.rosterrebuild.ui.components.InfoCard
import com.example.rosterrebuild.ui.components.StatusBadge
import com.example.rosterrebuild.ui.components.StatusBadgeVariant
import com.example.rosterrebuild.ui.theme.*
import java.io.File
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.*

/**
 * Destination 1: Home Screen.
 *
 * Implements Section 3 of the Medical Roster Product UX Specification 1.0:
 * 1. Header: Medical Roster (with settings icon).
 * 2. Primary action: Upload Roster.
 * 3. Today's Schedule card.
 * 4. Upcoming Shift card (when available).
 * 5. My Current Roster card.
 * 6. Secondary access to My Calendar and My Rosters.
 */
@Composable
fun RosterDashboardScreen(
    rosters: List<RosterEntity> = emptyList(),
    todayScheduleState: TodayScheduleUiState? = null,
    onOpenCalendar: (RosterEntity) -> Unit = {},
    onViewCalendar: () -> Unit = {},
    onViewMyRosters: () -> Unit = {},
    onViewDetails: (RosterEntity) -> Unit = {},
    onUploadNew: () -> Unit = {},
    onUploadCorrected: (RosterEntity) -> Unit = {},
    onDeleteRoster: (RosterEntity) -> Unit = {},
    storageManager: StorageManager,
    onOpenSettings: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onConfigureEndpoint: () -> Unit = {},
    onSaveTodayShift: ((rosterId: String, day: Int, newShift: String, newFloor: String?, author: String, reason: String?) -> Unit)? = null,
    onSaveTodayAttendance: ((rosterId: String, date: String, status: AttendanceStatus, notes: String?, author: String) -> Unit)? = null
) {
    val context = LocalContext.current
    var rosterPendingDelete by remember { mutableStateOf<RosterEntity?>(null) }
    var editingTodayEntry by remember { mutableStateOf<DailyScheduleEntry?>(null) }
    var attendanceTodayEntry by remember { mutableStateOf<DailyScheduleEntry?>(null) }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Medical Roster",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Personal Roster & Attendance",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Text("⚙️", fontSize = 20.sp)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            // 1. Primary Action: Upload New Roster
            item(key = "upload_roster_action") {
                AppButton(
                    text = "📷  Upload New Roster",
                    onClick = onUploadNew,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                )
            }

            // 2. Today's Clinical Schedule Card
            item(key = "today_schedule_card") {
                TodayScheduleCard(
                    todayState = todayScheduleState,
                    onEditShift = {
                        todayScheduleState?.entry?.let { editingTodayEntry = it }
                    },
                    onMarkAttendance = {
                        todayScheduleState?.entry?.let { attendanceTodayEntry = it }
                    }
                )
            }

            // 3. Available Rosters
            if (rosters.isNotEmpty()) {
                item(key = "available_rosters_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Available Rosters",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${rosters.size} saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                items(rosters, key = { it.id }) { roster ->
                    CurrentRosterCard(
                        roster = roster,
                        onOpenCalendar = { onOpenCalendar(roster) },
                        onExportExcel = { openExcelFile(context, storageManager, roster) },
                        onViewDetails = { onViewDetails(roster) },
                        onUploadCorrected = { onUploadCorrected(roster) },
                        onDelete = { rosterPendingDelete = roster }
                    )
                }
            }
        }
    }

    // Today's Shift/Floor Direct Editor Dialog
    editingTodayEntry?.let { entry ->
        val roster = todayScheduleState?.roster
        if (roster != null) {
            EditTodayScheduleDialog(
                entry = entry,
                roster = roster,
                onDismiss = { editingTodayEntry = null },
                onSave = { newShift, newFloor, author, reason ->
                    onSaveTodayShift?.invoke(roster.id, entry.day, newShift, newFloor, author, reason)
                    editingTodayEntry = null
                }
            )
        }
    }

    // Today's Self-Reported Attendance Dialog
    attendanceTodayEntry?.let { entry ->
        val roster = todayScheduleState?.roster
        if (roster != null) {
            CalendarAttendanceDialog(
                entry = entry,
                onDismiss = { attendanceTodayEntry = null },
                onSave = { newStatus, notes, author ->
                    val dateStr = entry.date ?: "%04d-%02d-%02d".format(roster.year, roster.month, entry.day)
                    onSaveTodayAttendance?.invoke(roster.id, dateStr, newStatus, notes, author)
                    attendanceTodayEntry = null
                    Result.success(Unit)
                }
            )
        }
    }

    // Delete Roster Confirmation Dialog
    rosterPendingDelete?.let { roster ->
        val periodName = formatPeriod(roster.month, roster.year)
        AlertDialog(
            onDismissRequest = { rosterPendingDelete = null },
            title = { Text("Delete Roster?") },
            text = {
                Text(
                    "Are you sure you want to delete the roster for $periodName (Version ${roster.version})?\n\nThis will remove the local schedule, image, and attendance records associated with this roster from your device.",
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
 * Prominent Today's Schedule Card.
 */
@Composable
private fun TodayScheduleCard(
    todayState: TodayScheduleUiState?,
    onEditShift: () -> Unit,
    onMarkAttendance: () -> Unit
) {
    val date = todayState?.date ?: LocalDate.now()
    val formattedDate = remember(date) {
        date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
    }

    val result = todayState?.result
    val entry = todayState?.entry

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry != null) LavenderSurface else SurfaceClinical
        ),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (entry != null) IndigoPrimary else LavenderBorder
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: TODAY Pill + Full Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(IndigoPrimary)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "TODAY",
                            style = TextStyle(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color.White
                        )
                    }
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                if (todayState != null) {
                    StatusBadge(
                        text = todayState.employeeId,
                        variant = StatusBadgeVariant.PRIMARY
                    )
                }
            }

            HorizontalDivider(color = LavenderBorder)

            when {
                entry != null -> {
                    val shift = entry.currentShift
                    val colors = getShiftColors(shift)
                    val attendance = entry.attendance
                    val floorDisplay = entry.floorDisplay

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Large Shift Code Badge
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.background)
                                    .border(1.5.dp, colors.border, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = entry.currentShiftCode,
                                    style = TextStyle(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = colors.text
                                )
                            }

                            Column {
                                Text(
                                    text = shift.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "🏢 $floorDisplay",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        // Attendance status pill
                        val badgeVariant = when (attendance.status) {
                            AttendanceStatus.PRESENT -> StatusBadgeVariant.SUCCESS
                            AttendanceStatus.ABSENT -> StatusBadgeVariant.ERROR
                            AttendanceStatus.NOT_MARKED -> StatusBadgeVariant.WARNING
                            AttendanceStatus.NOT_APPLICABLE -> StatusBadgeVariant.NEUTRAL
                        }
                        StatusBadge(
                            text = attendance.status.displayName,
                            variant = badgeVariant
                        )
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppOutlinedButton(
                            text = "✏️ Edit Shift",
                            onClick = onEditShift,
                            modifier = Modifier.weight(1f)
                        )
                        AppButton(
                            text = "📝 Attendance",
                            onClick = onMarkAttendance,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                        )
                    }
                }

                result is TodayScheduleResult.NoRoster -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "No schedule for today",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "No clinical duties scheduled for today.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                result is TodayScheduleResult.NoEntryInRoster -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "No schedule for today",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = result.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "No schedule for today",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "No clinical duties scheduled for today.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Next Upcoming Shift Card.
 */
@Composable
private fun UpcomingShiftCard(
    upcoming: UpcomingShiftResult,
    onOpenCalendar: () -> Unit
) {
    val dateStr = remember(upcoming.date) {
        val today = LocalDate.now()
        val daysDiff = java.time.temporal.ChronoUnit.DAYS.between(today, upcoming.date)
        val prefix = when (daysDiff) {
            1L -> "Tomorrow · "
            2L -> "In 2 days · "
            else -> ""
        }
        prefix + upcoming.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
    }

    val shift = upcoming.entry.currentShift
    val colors = getShiftColors(shift)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceClinical),
        border = BorderStroke(1.dp, LavenderBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Next Upcoming Shift",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = IndigoDark
                )
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.background)
                            .border(1.dp, colors.border, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = upcoming.entry.currentShiftCode,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            color = colors.text
                        )
                    }
                    Column {
                        Text(
                            text = shift.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "🏢 ${upcoming.entry.floorDisplay}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                AppTextButton(
                    text = "View in Calendar →",
                    onClick = onOpenCalendar
                )
            }
        }
    }
}

/**
 * My Current Roster Summary Card for Home screen.
 */
@Composable
private fun CurrentRosterCard(
    roster: RosterEntity,
    onOpenCalendar: () -> Unit,
    onExportExcel: () -> Unit,
    onViewDetails: () -> Unit,
    onUploadCorrected: () -> Unit,
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
            // Header
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
                            text = formatPeriod(roster.month, roster.year),
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

                // Secondary Overflow Menu
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge, color = TextSecondary)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("👁️ Extraction Details / Review") },
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

            // Floor & Day Count
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
                    text = "📅 ${CalendarDomain.getDaysInMonth(roster.year, roster.month)} Days Scheduled",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            // Primary Actions: Calendar + Export
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

/**
 * Direct shift and floor editor for today's card.
 */
@Composable
fun EditTodayScheduleDialog(
    entry: DailyScheduleEntry,
    roster: RosterEntity,
    onDismiss: () -> Unit,
    onSave: (newShift: String, newFloor: String?, author: String, reason: String?) -> Unit
) {
    var selectedShiftCode by remember { mutableStateOf(entry.currentShiftCode) }
    var selectedFloor by remember { mutableStateOf(entry.currentFloor ?: roster.getEffectiveMonthlyFloor() ?: "Floor 1") }
    var customFloorText by remember { mutableStateOf("") }
    var isCustomFloor by remember { mutableStateOf(false) }
    var author by remember { mutableStateOf("Self-reported") }
    var reason by remember { mutableStateOf("") }

    val standardShifts = listOf(
        Triple("M", "Morning Shift", ShiftType.MORNING),
        Triple("E", "Evening Shift", ShiftType.EVENING),
        Triple("N", "Night Shift", ShiftType.NIGHT),
        Triple("OFF", "Rest Day", ShiftType.OFF),
        Triple("A", "Management Absence", ShiftType.MANAGEMENT_ABSENCE)
    )

    val commonFloors = listOf("Floor 1", "Floor 2", "Floor 3", "Floor 4", "ICU", "Emergency", "NICU")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Edit Today's Scheduled Duty",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Day ${entry.day} · ${entry.date ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Transparency Card: Original OCR Values
                InfoCard(
                    containerColor = LavenderSurface,
                    borderColor = LavenderBorder
                ) {
                    Text(
                        text = "Original OCR Extraction",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = IndigoDark
                    )
                    Text(
                        text = "Shift: ${entry.extractedShiftCode} (${entry.extractedShift.displayName}) · Floor: ${entry.extractedFloor ?: "Unassigned"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Edits are audited and recorded in local Room database and Excel history.",
                        style = TextStyle(fontSize = 10.sp),
                        color = TextSecondary
                    )
                }

                // Shift Selection Radio Group
                Text(
                    text = "Select Planned Shift",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                standardShifts.forEach { (code, name, type) ->
                    val isSelected = selectedShiftCode == code
                    val palette = getShiftColors(type)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedShiftCode = code }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { selectedShiftCode = code },
                            colors = RadioButtonDefaults.colors(selectedColor = IndigoPrimary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(palette.background, RoundedCornerShape(4.dp))
                                .border(1.dp, palette.border, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(code, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = palette.text)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Floor Selection
                Text(
                    text = "Select Floor / Ward",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    commonFloors.take(4).forEach { fl ->
                        val isSel = !isCustomFloor && selectedFloor == fl
                        OutlinedButton(
                            onClick = {
                                selectedFloor = fl
                                isCustomFloor = false
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            colors = if (isSel) ButtonDefaults.outlinedButtonColors(containerColor = IndigoContainer, contentColor = IndigoPrimary)
                            else ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text(fl, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Reason Field
                ConsistentTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = "Reason for Change (Optional)",
                    placeholder = "e.g. Schedule swap, clinical redeployment"
                )
            }
        },
        confirmButton = {
            AppButton(
                text = "Save Changes",
                onClick = {
                    val finalFloor = if (isCustomFloor) customFloorText.trim().ifBlank { selectedFloor } else selectedFloor
                    onSave(selectedShiftCode, finalFloor, author, reason.trim().ifBlank { null })
                }
            )
        },
        dismissButton = {
            AppOutlinedButton(
                text = "Cancel",
                onClick = onDismiss
            )
        }
    )
}

private fun formatPeriod(month: Int, year: Int): String {
    return try {
        val monthName = Month.of(month).getDisplayName(JavaTextStyle.FULL, Locale.ENGLISH)
        "$monthName $year"
    } catch (_: Exception) {
        "Month $month, $year"
    }
}

private fun openExcelFile(context: Context, storageManager: StorageManager, roster: RosterEntity) {
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
