package com.example.rosterrebuild

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rosterrebuild.ui.components.*
import com.example.rosterrebuild.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Visual styling definition for compact calendar attendance indicators.
 */
data class AttendanceBadgeStyle(
    val background: Color,
    val border: Color,
    val text: Color,
    val label: String
)

fun getAttendanceBadgeStyle(status: AttendanceStatus?): AttendanceBadgeStyle = when (status) {
    AttendanceStatus.PRESENT -> AttendanceBadgeStyle(
        background = Color(0xFFE8F5E9),
        border = Color(0xFF81C784),
        text = Color(0xFF2E7D32),
        label = "✓"
    )
    AttendanceStatus.ABSENT -> AttendanceBadgeStyle(
        background = Color(0xFFFFEBEE),
        border = Color(0xFFE57373),
        text = Color(0xFFC62828),
        label = "✕"
    )
    AttendanceStatus.NOT_MARKED -> AttendanceBadgeStyle(
        background = Color(0xFFFFF8E1),
        border = Color(0xFFFFD54F),
        text = Color(0xFFF57F17),
        label = "●"
    )
    AttendanceStatus.NOT_APPLICABLE, null -> AttendanceBadgeStyle(
        background = Color.Transparent,
        border = Color.Transparent,
        text = Color.Transparent,
        label = ""
    )
}

/**
 * Dedicated visual palette for roster shift types.
 *
 * M = Morning Shift (Rose / Pink)
 * E = Evening Shift (Yellow / Amber)
 * N = Night Shift (Orange)
 * OFF = Off (Red)
 * A = Management Absence (Sky Blue / Cyan)
 */
data class ShiftColorPalette(
    val background: Color,
    val border: Color,
    val text: Color
)

fun getShiftColors(shiftType: ShiftType): ShiftColorPalette = when (shiftType) {
    ShiftType.MORNING -> ShiftColorPalette(
        background = Color(0xFFFCE4EC), // Pink 50
        border = Color(0xFFF48FB1),     // Pink 200
        text = Color(0xFFAD1457)        // Pink 800
    )
    ShiftType.EVENING -> ShiftColorPalette(
        background = Color(0xFFFFFDE7), // Yellow 50
        border = Color(0xFFFFF59D),     // Yellow 200
        text = Color(0xFFF57F17)        // Yellow 900
    )
    ShiftType.NIGHT -> ShiftColorPalette(
        background = Color(0xFFFFF3E0), // Orange 50
        border = Color(0xFFFFCC80),     // Orange 200
        text = Color(0xFFE65100)        // Orange 900
    )
    ShiftType.OFF -> ShiftColorPalette(
        background = Color(0xFFFFEBEE), // Red 50
        border = Color(0xFFEF9A9A),     // Red 200
        text = Color(0xFFC62828)        // Red 800
    )
    ShiftType.MANAGEMENT_ABSENCE -> ShiftColorPalette(
        background = Color(0xFFE1F5FE), // Light Blue 50
        border = Color(0xFF81D4FA),     // Light Blue 200
        text = Color(0xFF0277BD)        // Light Blue 800
    )
    ShiftType.UNKNOWN -> ShiftColorPalette(
        background = Color(0xFFF5F5F5),
        border = Color(0xFFE0E0E0),
        text = Color(0xFF616161)
    )
}

/**
 * Interactive Monthly Calendar Screen.
 *
 * Displays the monthly calendar for the detected roster month and year,
 * calculates weekday alignment dynamically, indicates today's date,
 * allows direct tap-to-edit for any date cell, and links to the schedule review.
 */
@Composable
fun MonthlyCalendarScreen(
    schedule: List<DailyScheduleEntry>,
    employeeId: String,
    initialYear: Int,
    initialMonth: Int,
    onNavigateToReview: () -> Unit,
    onExportClick: () -> Unit,
    onSaveEntry: (DailyScheduleEntry) -> Unit,
    onUpdateShift: ((Int, String, ShiftChangeSource, String, String?) -> Unit)? = null,
    onNavigateToDashboard: (() -> Unit)? = null,
    onOpenDailyEditor: ((Int) -> Unit)? = null,
    onUpdateAttendance: ((date: String, newStatus: AttendanceStatus, notes: String?, author: String) -> Unit)? = null,
    onSaveAttendance: (suspend (date: String, newStatus: AttendanceStatus, notes: String?, author: String) -> Result<Unit>)? = null,
    onNavigateMonth: ((year: Int, month: Int) -> Unit)? = null,
    onUploadNew: (() -> Unit)? = null,
    clock: java.time.Clock = java.time.Clock.systemDefaultZone()
) {
    var displayedYear by remember(initialYear) { mutableStateOf(initialYear) }
    var displayedMonth by remember(initialMonth) { mutableStateOf(initialMonth) }

    LaunchedEffect(initialYear, initialMonth) {
        displayedYear = initialYear
        displayedMonth = initialMonth
    }
    var navNotice by remember { mutableStateOf<String?>(null) }
    var editingEntry by remember { mutableStateOf<DailyScheduleEntry?>(null) }
    var attendanceEntryToEdit by remember { mutableStateOf<DailyScheduleEntry?>(null) }

    val today = remember(clock, displayedYear, displayedMonth) { LocalDate.now(clock) }
    val isViewingCurrentMonth = (displayedYear == today.year && displayedMonth == today.monthValue)

    val daysInMonth = remember(displayedYear, displayedMonth) {
        CalendarDomain.getDaysInMonth(displayedYear, displayedMonth)
    }
    val firstDayOffset = remember(displayedYear, displayedMonth) {
        CalendarDomain.getFirstDayOfWeekOffset(displayedYear, displayedMonth)
    }
    val calendarDays = remember(displayedYear, displayedMonth, schedule, today) {
        CalendarDomain.buildMonthDays(displayedYear, displayedMonth, schedule, today)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Top App Bar / Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onNavigateToDashboard != null) {
                    AppOutlinedButton(
                        text = "🏠 Dashboard",
                        onClick = onNavigateToDashboard
                    )
                }
                AppOutlinedButton(
                    text = "← Review",
                    onClick = onNavigateToReview
                )
            }
            AppButton(
                text = "📊 Export",
                onClick = onExportClick
            )
        }

        // Month and Navigation Header
        InfoCard(
            containerColor = LavenderSurface,
            borderColor = LavenderBorder
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val prevMonth = if (displayedMonth == 1) 12 else displayedMonth - 1
                        val prevYear = if (displayedMonth == 1) displayedYear - 1 else displayedYear
                        displayedYear = prevYear
                        displayedMonth = prevMonth
                        navNotice = null
                        onNavigateMonth?.invoke(prevYear, prevMonth)
                    }
                ) {
                    Text("◀", style = MaterialTheme.typography.titleMedium, color = IndigoPrimary, fontWeight = FontWeight.Bold)
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        CalendarDomain.formatMonthHeader(displayedYear, displayedMonth),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "Employee: $employeeId · ${daysInMonth} Days",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = {
                        val nextMonth = if (displayedMonth == 12) 1 else displayedMonth + 1
                        val nextYear = if (displayedMonth == 12) displayedYear + 1 else displayedYear
                        displayedYear = nextYear
                        displayedMonth = nextMonth
                        navNotice = null
                        onNavigateMonth?.invoke(nextYear, nextMonth)
                    }
                ) {
                    Text("▶", style = MaterialTheme.typography.titleMedium, color = IndigoPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Contextual Banner when viewing another month's roster
        if (!isViewingCurrentMonth) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = LavenderSurface,
                border = BorderStroke(1.dp, LavenderBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val todayFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
                    Text(
                        text = "📅 Today: ${today.format(todayFmt)} (Viewing other month)",
                        style = MaterialTheme.typography.labelMedium,
                        color = IndigoDark,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            displayedYear = today.year
                            displayedMonth = today.monthValue
                            onNavigateMonth?.invoke(today.year, today.monthValue)
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("Go to Today", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = IndigoPrimary)
                    }
                }
            }
        }

        // Explanation when navigating to a month without a saved roster
        if (schedule.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "No saved roster for ${CalendarDomain.formatMonthHeader(displayedYear, displayedMonth)} (Employee: $employeeId)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "The calendar grid displays dates for this month without simulated shifts.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Navigation Notice if present
        navNotice?.let { notice ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { navNotice = null }) {
                        Text("Dismiss")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Weekday Headers (Mon - Sun)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CalendarDomain.WEEKDAY_HEADERS.forEach { dayName ->
                Text(
                    text = dayName,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (dayName == "Sat" || dayName == "Sun")
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(bottom = 6.dp))

        // 7-Column Calendar Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Leading empty cells before Day 1
            items(firstDayOffset) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 84.dp)
                        .background(Color.Transparent)
                )
            }

            // Calendar Day Cells
            items(calendarDays) { day ->
                CalendarDateCell(
                    day = day,
                    onClick = {
                        if (onOpenDailyEditor != null) {
                            onOpenDailyEditor(day.dayOfMonth)
                        } else {
                            day.scheduleEntry?.let { entry ->
                                editingEntry = entry
                            }
                        }
                    },
                    onAttendanceClick = {
                        day.scheduleEntry?.let { entry ->
                            attendanceEntryToEdit = entry
                        }
                    }
                )
            }

            // Legend at bottom of grid
            item(span = { GridItemSpan(7) }) {
                CalendarLegend(modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            }
        }
    }

    // Attendance Dialog exclusively for recording attendance from Monthly Calendar view
    attendanceEntryToEdit?.let { entry ->
        CalendarAttendanceDialog(
            entry = entry,
            onDismiss = { attendanceEntryToEdit = null },
            onSave = { newStatus, notes, author ->
                val date = entry.date
                if (date != null) {
                    if (onSaveAttendance != null) {
                        onSaveAttendance(date, newStatus, notes, author)
                    } else if (onUpdateAttendance != null) {
                        onUpdateAttendance(date, newStatus, notes, author)
                        Result.success(Unit)
                    } else {
                        val updated = entry.updateAttendance(newStatus, notes, author)
                        onSaveEntry(updated)
                        Result.success(Unit)
                    }
                } else {
                    val updated = entry.updateAttendance(newStatus, notes, author)
                    onSaveEntry(updated)
                    Result.success(Unit)
                }
            }
        )
    }

    // Date Cell Editing Dialog (fallback when full-screen daily editor callback is not supplied)
    editingEntry?.let { entry ->
        CalendarEditShiftDialog(
            entry = entry,
            onDismiss = { editingEntry = null },
            onSave = { updatedEntry ->
                onSaveEntry(updatedEntry)
                onUpdateShift?.invoke(
                    updatedEntry.day,
                    updatedEntry.currentShiftCode,
                    updatedEntry.changeHistory.lastOrNull()?.source ?: ShiftChangeSource.USER_EDIT,
                    updatedEntry.changeHistory.lastOrNull()?.author ?: "User",
                    updatedEntry.changeHistory.lastOrNull()?.reason
                )
                editingEntry = null
            }
        )
    }
}

/**
 * Individual Calendar Day Cell.
 *
 * Displays two independent interactive areas:
 * A. Top area (Date + Today badge + Review Warning + Shift Code + Floor):
 *    Click triggers [onClick] which navigates exclusively to full-screen [DailyEditorScreen].
 * B. Bottom area (Attendance badge strip):
 *    Click triggers [onAttendanceClick] which opens the dedicated attendance editor dialog.
 *    Does NOT propagate clicks to Area A or parent calendar views.
 */
@Composable
fun CalendarDateCell(
    day: CalendarDay,
    onClick: () -> Unit,
    onAttendanceClick: (() -> Unit)? = null
) {
    val entry = day.scheduleEntry
    val shiftType = entry?.currentShift ?: ShiftType.UNKNOWN
    val colors = getShiftColors(shiftType)
    val attendance = entry?.attendance
    val badgeStyle = getAttendanceBadgeStyle(attendance?.status)

    val hasEntry = entry != null
    val defaultCellBg = if (hasEntry) colors.background else Color(0xFFFAFAFA)
    val defaultCellBorder = if (hasEntry) colors.border else Color(0xFFE2E8F0)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 84.dp),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (day.isToday) defaultCellBg.copy(alpha = 0.95f) else defaultCellBg
        ),
        border = BorderStroke(
            width = if (day.isToday) 2.dp else 1.dp,
            color = if (day.isToday) IndigoPrimary else defaultCellBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ========================================================
            // AREA A: Planned Shift & Floor Area (Click opens DailyEditorScreen)
            // ========================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .then(if (hasEntry) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header: Day number + Today badge + Review Warning icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${day.dayOfMonth}",
                        style = TextStyle(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = if (day.isToday) IndigoPrimary else Color(0xFF1E293B)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (attendance?.isFlaggedForReview == true) {
                            Text("⚠️", style = TextStyle(fontSize = 8.sp))
                        }
                        if (day.isToday) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(IndigoPrimary)
                                    .padding(horizontal = 3.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    "TODAY",
                                    style = TextStyle(
                                        fontSize = 6.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // Shift Code prominent + Provisional badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val displayCode = entry?.currentShiftCode?.takeIf { it.isNotBlank() }
                        ?: entry?.extractedShiftCode?.takeIf { it.isNotBlank() }
                        ?: ""
                    Text(
                        text = displayCode,
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = colors.text
                    )
                    if (entry?.isProvisional == true) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF0288D1))
                                .padding(horizontal = 2.dp)
                        ) {
                            Text(
                                "Prov",
                                style = TextStyle(fontSize = 6.sp, fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                    }
                }

                // If modified, visibly display the original extracted shift code
                if (entry?.isModified == true) {
                    Text(
                        text = "orig: ${entry.extractedShiftCode}",
                        style = TextStyle(fontSize = 7.5.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Floor display: displayed on working dates, or when manually overridden
                val floorText = if (entry?.currentShift?.isWorkShift == true) {
                    entry.currentFloor?.takeIf { it.isNotBlank() } ?: "No floor"
                } else if (entry?.isFloorModified == true && !entry.currentFloor.isNullOrBlank()) {
                    entry.currentFloor ?: ""
                } else {
                    ""
                }
                if (floorText.isNotBlank()) {
                    Text(
                        text = floorText,
                        style = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Normal),
                        color = if (entry?.currentShift?.isWorkShift == true && entry.currentFloor != null) Color.DarkGray else Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // ========================================================
            // AREA B: Independent Attendance Area (Click opens Attendance Dialog)
            // ========================================================
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .then(
                        if (hasEntry && onAttendanceClick != null) {
                            Modifier.clickable(onClick = onAttendanceClick)
                        } else Modifier
                    ),
                shape = RoundedCornerShape(3.dp),
                color = badgeStyle.background,
                border = if (badgeStyle.label.isNotEmpty()) BorderStroke(0.5.dp, badgeStyle.border) else null
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (badgeStyle.label.isNotEmpty()) {
                        Text(
                            text = badgeStyle.label,
                            style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold),
                            color = badgeStyle.text,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Attendance Dialog for Monthly Calendar view.
 *
 * Exclusively manages daily attendance status (Present, Absent, Not Marked, Not Applicable)
 * and optional attendance notes. Shift and floor editing are handled on the full-screen daily editor.
 */
@Composable
fun CalendarAttendanceDialog(
    entry: DailyScheduleEntry,
    onDismiss: () -> Unit,
    onSave: suspend (status: AttendanceStatus, notes: String?, author: String) -> Result<Unit>
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedStatus by remember { mutableStateOf(entry.attendance.status) }
    var notes by remember { mutableStateOf(entry.attendance.notes ?: "") }
    val author = "Self-reported"

    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val formattedDate = remember(entry.date, entry.day) {
        if (!entry.date.isNullOrBlank()) {
            try {
                val parsed = LocalDate.parse(entry.date)
                parsed.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
            } catch (_: Exception) {
                "Day ${entry.day} · ${entry.date}"
            }
        } else {
            "Day ${entry.day}"
        }
    }

    val shiftColors = remember(entry.currentShift) {
        getShiftColors(entry.currentShift)
    }

    val isNonWork = !entry.currentShift.isWorkShift
    val isInvalidNonWorkSelection = isNonWork && (selectedStatus == AttendanceStatus.PRESENT || selectedStatus == AttendanceStatus.ABSENT)

    val statusChanged = selectedStatus != entry.attendance.status
    val notesChanged = notes.trim() != (entry.attendance.notes ?: "")
    val hasChanges = (statusChanged || notesChanged || entry.attendance.isFlaggedForReview) && !isInvalidNonWorkSelection

    AlertDialog(
        onDismissRequest = {
            if (!isSaving) onDismiss()
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Record Attendance",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = formattedDate,
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Error banner if save failed
                errorMessage?.let { err ->
                    InfoCard(
                        containerColor = ErrorContainer,
                        borderColor = ErrorRed.copy(alpha = 0.5f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("⚠️", fontSize = 16.sp)
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnErrorContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Invalid non-work selection warning
                if (isInvalidNonWorkSelection) {
                    InfoCard(
                        containerColor = ErrorContainer,
                        borderColor = ErrorRed.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = "Rest days (OFF) and Absences (A) cannot be marked Present or Absent. Please select Not Applicable, or change the scheduled duty via Daily Editor.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnErrorContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Review required banner if flagged
                if (entry.attendance.isFlaggedForReview) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("⚠️", fontSize = 18.sp)
                            Column {
                                Text(
                                    text = "Attendance Review Required",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = entry.attendance.reviewReason
                                        ?: "Shift or floor was modified after attendance was marked.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // Card 1: Planned Shift Context (Read-Only)
                InfoCard(
                    containerColor = LavenderSurface,
                    borderColor = LavenderBorder
                ) {
                    Text(
                        text = "Scheduled Duty (Read-Only)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = IndigoDark
                    )
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
                                    .size(28.dp)
                                    .background(shiftColors.background, RoundedCornerShape(4.dp))
                                    .border(1.dp, shiftColors.border, RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = entry.currentShiftCode,
                                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                    color = shiftColors.text
                                )
                            }
                            Column {
                                Text(
                                    text = entry.currentShift.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = entry.floorDisplay,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                // Card 2: Attendance Status Selection (4 compact choices)
                Text(
                    text = "Select Attendance Status",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                val options = listOf(
                    Triple(AttendanceStatus.PRESENT, "✓", "Present — Attended scheduled clinical duty"),
                    Triple(AttendanceStatus.ABSENT, "✕", "Absent — Did not attend scheduled clinical duty"),
                    Triple(AttendanceStatus.NOT_MARKED, "●", "Not Marked — Attendance not yet recorded"),
                    Triple(AttendanceStatus.NOT_APPLICABLE, "—", "Not Applicable — Rest day (OFF) or absence (A)")
                )

                options.forEach { (status, icon, desc) ->
                    val isSelected = selectedStatus == status
                    val style = getAttendanceBadgeStyle(status)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedStatus = status
                                errorMessage = null
                            },
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) LavenderSurface else SurfaceClinical
                        ),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) IndigoPrimary else LavenderBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            if (style.label.isNotEmpty()) style.background else Color(0xFFF1F5F9),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            0.5.dp,
                                            if (style.label.isNotEmpty()) style.border else Color(0xFFCBD5E1),
                                            RoundedCornerShape(4.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = icon,
                                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                        color = if (style.label.isNotEmpty()) style.text else Color(0xFF64748B)
                                    )
                                }

                                Column {
                                    Text(
                                        text = status.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    selectedStatus = status
                                    errorMessage = null
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = IndigoPrimary)
                            )
                        }
                    }
                }

                // Card 3: Optional Notes
                ConsistentTextField(
                    value = notes,
                    onValueChange = {
                        notes = it
                        errorMessage = null
                    },
                    label = "Attendance Notes (Optional)",
                    placeholder = "e.g. Arrived on time, emergency cover",
                    singleLine = false
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    coroutineScope.launch {
                        isSaving = true
                        errorMessage = null
                        val res = onSave(
                            selectedStatus,
                            notes.trim().ifBlank { null },
                            author
                        )
                        isSaving = false
                        res.onSuccess {
                            onDismiss()
                        }.onFailure { err ->
                            errorMessage = err.localizedMessage ?: "Failed to save attendance record."
                        }
                    }
                },
                enabled = hasChanges && !isSaving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = IndigoPrimary,
                    contentColor = Color.White,
                    disabledContainerColor = IndigoPrimary.copy(alpha = 0.38f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Saving...", style = MaterialTheme.typography.labelMedium)
                } else {
                    Text("Save Attendance", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Visual Legend explaining shift codes and colors.
 */
@Composable
fun CalendarLegend(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Shift Legend",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LegendChip("M", "Morning", ShiftType.MORNING)
                LegendChip("E", "Evening", ShiftType.EVENING)
                LegendChip("N", "Night", ShiftType.NIGHT)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LegendChip("OFF", "Off Duty", ShiftType.OFF)
                LegendChip("A", "Management Absence (Provisional)", ShiftType.MANAGEMENT_ABSENCE)
            }
        }
    }
}

@Composable
private fun LegendChip(code: String, label: String, type: ShiftType) {
    val colors = getShiftColors(type)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(colors.background, RoundedCornerShape(3.dp))
                .border(1.dp, colors.border, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                code,
                style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                color = colors.text
            )
        }
        Text(
            "$code: $label",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Detailed Edit Dialog for the Calendar screen.
 *
 * Provides 4 separate editable sections:
 * 1. Planned Shift (M, E, N, OFF, A, change source, authority, reason)
 * 2. Floor Assignment (Predefined options: Floor 1..5, ICU, Emergency, OT, or custom input)
 * 3. Actual Attendance (PRESENT, ABSENT, NOT_MARKED, NOT_APPLICABLE)
 * 4. Attendance Notes (Free-text notes)
 */
@Composable
fun CalendarEditShiftDialog(
    entry: DailyScheduleEntry,
    onDismiss: () -> Unit,
    onSave: (DailyScheduleEntry) -> Unit
) {
    var selectedShift by remember { mutableStateOf(entry.currentShiftCode) }
    var selectedSource by remember { mutableStateOf(ShiftChangeSource.MANAGEMENT_UPDATE) }
    var shiftAuthor by remember { mutableStateOf("Clinical Supervisor") }
    var shiftReason by remember { mutableStateOf("") }

    var selectedFloor by remember { mutableStateOf(entry.currentFloor ?: "") }
    var customFloor by remember { mutableStateOf("") }
    var isCustomFloorMode by remember {
        mutableStateOf(entry.currentFloor != null && !PREDEFINED_FLOORS.contains(entry.currentFloor))
    }

    var selectedAttendance by remember { mutableStateOf(entry.attendance.status) }
    var attendanceNotes by remember { mutableStateOf(entry.attendance.notes ?: "") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        title = {
            Text(
                "Edit Schedule & Attendance · ${entry.date ?: "Day ${entry.day}"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Review required banner if flagged
                if (entry.attendance.isFlaggedForReview) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("⚠️", style = TextStyle(fontSize = 16.sp))
                            Column {
                                Text(
                                    "Review Required",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    entry.attendance.reviewReason ?: "Shift or floor was modified after attendance was marked.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // ==========================================
                // SECTION 1: PLANNED SHIFT
                // ==========================================
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "1. Planned Shift",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Original Extracted: ${entry.extractedShiftCode} (${entry.extractedShift.displayName})",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray
                        )
                        Text(
                            "Current: ${entry.currentShiftCode} (${entry.currentShift.displayName})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text("Select Shift:", style = MaterialTheme.typography.labelMedium)
                        ShiftType.ALLOWED_EDIT_CODES.forEach { code ->
                            val type = ShiftType.fromCode(code)
                            val colors = getShiftColors(type)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedShift = code; validationError = null }
                                    .padding(vertical = 1.dp)
                            ) {
                                RadioButton(
                                    selected = (selectedShift == code),
                                    onClick = { selectedShift = code; validationError = null }
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(colors.background, RoundedCornerShape(3.dp))
                                            .border(1.dp, colors.border, RoundedCornerShape(3.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            code,
                                            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                            color = colors.text
                                        )
                                    }
                                    Text(type.displayName, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        OutlinedTextField(
                            value = shiftAuthor,
                            onValueChange = { shiftAuthor = it },
                            label = { Text("Author / Supervisor") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = shiftReason,
                            onValueChange = { shiftReason = it },
                            label = { Text("Reason for Change (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ==========================================
                // SECTION 2: FLOOR ASSIGNMENT
                // ==========================================
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "2. Floor Assignment",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Original Extracted: ${entry.extractedFloor ?: "Floor not assigned"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray
                        )
                        Text(
                            "Current Floor: ${entry.currentFloor ?: "Floor not assigned"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text("Select Predefined Floor:", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("Floor 1", "Floor 2", "Floor 3").forEach { fl ->
                                OutlinedButton(
                                    onClick = { selectedFloor = fl; isCustomFloorMode = false },
                                    modifier = Modifier.weight(1f),
                                    colors = if (selectedFloor == fl && !isCustomFloorMode)
                                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    else androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(fl, style = TextStyle(fontSize = 11.sp))
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("Floor 4", "Floor 5", "ICU").forEach { fl ->
                                OutlinedButton(
                                    onClick = { selectedFloor = fl; isCustomFloorMode = false },
                                    modifier = Modifier.weight(1f),
                                    colors = if (selectedFloor == fl && !isCustomFloorMode)
                                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    else androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(fl, style = TextStyle(fontSize = 11.sp))
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("Emergency", "OT", "Clear").forEach { fl ->
                                OutlinedButton(
                                    onClick = {
                                        if (fl == "Clear") {
                                            selectedFloor = ""
                                            customFloor = ""
                                            isCustomFloorMode = false
                                        } else {
                                            selectedFloor = fl
                                            isCustomFloorMode = false
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = if (selectedFloor == fl && !isCustomFloorMode)
                                        androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    else androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(fl, style = TextStyle(fontSize = 11.sp))
                                }
                            }
                        }

                        OutlinedTextField(
                            value = if (isCustomFloorMode) customFloor else "",
                            onValueChange = {
                                customFloor = it
                                isCustomFloorMode = true
                                selectedFloor = it
                            },
                            label = { Text("Or enter custom floor (e.g. Ward 4B)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ==========================================
                // SECTION 3: ACTUAL ATTENDANCE
                // ==========================================
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "3. Actual Attendance",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Attendance is tracked independently and does not alter planned shifts.",
                            style = TextStyle(fontSize = 11.sp),
                            color = Color.DarkGray
                        )

                        AttendanceStatus.entries.forEach { status ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedAttendance = status }
                                    .padding(vertical = 1.dp)
                            ) {
                                RadioButton(
                                    selected = (selectedAttendance == status),
                                    onClick = { selectedAttendance = status }
                                )
                                Text(
                                    status.displayName,
                                    modifier = Modifier.padding(start = 4.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selectedAttendance == status) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // ==========================================
                // SECTION 4: ATTENDANCE NOTES
                // ==========================================
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "4. Attendance Notes",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = attendanceNotes,
                            onValueChange = { attendanceNotes = it },
                            label = { Text("Optional notes (e.g. Arrived on time / Sick leave)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 3
                        )
                    }
                }

                validationError?.let { err ->
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!ShiftType.isValidShiftCode(selectedShift)) {
                        validationError = "Invalid shift code. Allowed: ${ShiftType.ALLOWED_EDIT_CODES.joinToString()}"
                        return@Button
                    }

                    var updated = entry

                    // 1. Update Shift if changed
                    if (selectedShift != entry.currentShiftCode) {
                        updated = updated.updateSchedule(
                            newCode = selectedShift,
                            source = selectedSource,
                            author = shiftAuthor,
                            reason = shiftReason.ifBlank { null }
                        )
                    }

                    // 2. Update Floor if changed
                    val effectiveFloor = if (isCustomFloorMode) {
                        customFloor.trim().ifBlank { null }
                    } else {
                        selectedFloor.trim().ifBlank { null }
                    }
                    if (effectiveFloor != entry.currentFloor) {
                        updated = updated.updateFloor(
                            newFloor = effectiveFloor,
                            author = shiftAuthor
                        )
                    }

                    // 3. Update Attendance if changed or notes changed
                    val notesToSave = attendanceNotes.trim().ifBlank { null }
                    if (selectedAttendance != entry.attendance.status || notesToSave != entry.attendance.notes || entry.attendance.isFlaggedForReview) {
                        updated = updated.updateAttendance(
                            newStatus = selectedAttendance,
                            notes = notesToSave,
                            author = shiftAuthor
                        )
                    }

                    onSave(updated)
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
