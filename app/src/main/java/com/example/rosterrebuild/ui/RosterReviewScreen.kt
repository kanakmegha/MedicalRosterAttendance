package com.example.rosterrebuild.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rosterrebuild.*
import com.example.rosterrebuild.data.local.RosterEntity
import com.example.rosterrebuild.ui.components.*
import com.example.rosterrebuild.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Dedicated Roster Review Screen.
 *
 * Displays detailed extracted and operational information for the selected roster:
 * - Employee ID, period, version, status, and extracted monthly floor
 * - Original uploaded/scanned roster image preview
 * - Day-by-day table of extracted OCR shifts versus current operational shifts
 * - Visual badges for modified / supervisor-overridden shifts
 * - Direct navigation back to Monthly Calendar or Saved Rosters Dashboard
 * - Direct action to open the full-screen Daily Schedule Editor for any day
 *
 * Crucially, does NOT initiate extraction, does NOT clear roster state, and does NOT
 * display initial upload controls.
 */
@Composable
fun RosterReviewScreen(
    employeeId: String,
    periodDisplay: String,
    activeRoster: RosterEntity?,
    effectiveFloor: String,
    schedule: List<DailyScheduleEntry>,
    result: RosterResult?,
    diagnostics: ExtractionDiagnostics?,
    sourceBitmap: Bitmap?,
    onNavigateToCalendar: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    onExportClick: () -> Unit,
    onOpenDailyEditor: (Int) -> Unit,
    onBulkFloorUpdate: (suspend (newFloor: String, author: String, reason: String?) -> Result<Unit>)? = null,
    modifier: Modifier = Modifier
) {
    var showImagePreview by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showCrops by rememberSaveable { mutableStateOf(false) }
    var showBulkFloorDialog by rememberSaveable { mutableStateOf(false) }

    val workingShiftsCount = remember(schedule) { schedule.count { it.currentShift.isWorkShift } }
    val offShiftsCount = remember(schedule) { schedule.count { it.currentShift == ShiftType.OFF } }
    val mgmtAbsenceCount = remember(schedule) { schedule.count { it.currentShift == ShiftType.MANAGEMENT_ABSENCE } }
    val modifiedCount = remember(schedule) { schedule.count { it.isModified } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Navigation & Actions Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppOutlinedButton(
                        text = "🏠 Dashboard",
                        onClick = onNavigateToDashboard
                    )
                    AppButton(
                        text = "📅 View Calendar",
                        onClick = onNavigateToCalendar,
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                    )
                }
                AppOutlinedButton(
                    text = "📊 Export",
                    onClick = onExportClick
                )
            }
        }

        // 2. Roster Metadata Overview Card
        item {
            InfoCard(
                containerColor = LavenderSurface,
                borderColor = LavenderBorder
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Roster Review & Audit",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = IndigoPrimary
                            )
                            Text(
                                text = "Employee ID: ${employeeId.ifBlank { "Not Assigned" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = IndigoPrimary.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, IndigoPrimary.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "v${activeRoster?.version ?: 1} · ${activeRoster?.status ?: "ACTIVE"}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = IndigoPrimary
                            )
                        }
                    }

                    HorizontalDivider(color = LavenderBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Period", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text(periodDisplay, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Column {
                            Text("Monthly Floor", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(effectiveFloor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                                if (onBulkFloorUpdate != null && activeRoster != null) {
                                    AppTextButton(
                                        text = "[Change for all days]",
                                        onClick = { showBulkFloorDialog = true }
                                    )
                                }
                            }
                            if (!activeRoster?.floorExtracted.isNullOrBlank() && activeRoster?.floorExtracted != effectiveFloor) {
                                Text("OCR: ${activeRoster?.floorExtracted}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                        Column {
                            Text("Schedule", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text("${schedule.size} Days", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    }

                    // Shift Breakdown Stats
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Text(
                            text = "Work: $workingShiftsCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF2E7D32)
                        )
                        Text(
                            text = "Off: $offShiftsCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFC62828)
                        )
                        Text(
                            text = "Mgmt (A): $mgmtAbsenceCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF0277BD)
                        )
                        if (modifiedCount > 0) {
                            Text(
                                text = "Edited: $modifiedCount",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                }
            }
        }

        // 3. Original Roster Image (Collapsible)
        if (sourceBitmap != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showImagePreview = !showImagePreview },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📷", style = TextStyle(fontSize = 16.sp))
                                Column {
                                    Text(
                                        "Original Roster Image",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${sourceBitmap.width} × ${sourceBitmap.height} px",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Text(
                                if (showImagePreview) "Hide ▲" else "Show ▼",
                                style = MaterialTheme.typography.labelMedium,
                                color = IndigoPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        AnimatedVisibility(visible = showImagePreview) {
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                Image(
                                    bitmap = sourceBitmap.asImageBitmap(),
                                    contentDescription = "Original Roster Image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 300.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Developer Diagnostics (Collapsible)
        if (diagnostics != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDiagnostics = !showDiagnostics },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🔬", style = TextStyle(fontSize = 16.sp))
                                Text(
                                    "Extraction Diagnostics & Confidence",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                if (showDiagnostics) "Hide ▲" else "Show ▼",
                                style = MaterialTheme.typography.labelMedium,
                                color = IndigoPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        AnimatedVisibility(visible = showDiagnostics) {
                            Column(
                                modifier = Modifier.padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "Matched ID Token: ${diagnostics.matchedIdToken ?: "Auto-matched"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Extracted Monthly Floor: ${diagnostics.extractedMonthlyFloor ?: "None"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Row Boundaries: ${diagnostics.employeeRowBoundaries ?: "—"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Detected Period: ${diagnostics.detectedPeriodDisplay ?: "—"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (diagnostics.warnings.isNotEmpty()) {
                                    Text(
                                        "Warnings (${diagnostics.warnings.size}):",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    diagnostics.warnings.forEach { w ->
                                        Text("• $w", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Daily Schedule Heading
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Daily Shifts & Operational Schedule",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "${schedule.size} days verified",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }

        // 6. Day-by-Day Shift Cards
        if (schedule.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No schedule entries loaded for this roster.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        } else {
            items(schedule, key = { it.day }) { entry ->
                val colors = getShiftColors(entry.currentShift)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (entry.isModified) 1.5.dp else 1.dp,
                        color = if (entry.isModified) Color(0xFFF57C00) else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Card Top: Day number, Date, and Shift Badge
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Day ${entry.day}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                entry.date?.let { d ->
                                    Text(
                                        text = d,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }

                            // Shift Badge
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = colors.background,
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = entry.currentShiftCode.ifBlank { entry.extractedShiftCode.ifBlank { "—" } },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = colors.text
                                    )
                                    Text(
                                        text = entry.displayTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.text
                                    )
                                }
                            }
                        }

                        // Shift Details & Comparison
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (entry.isModified) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFFFF3E0),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D))
                                        ) {
                                            Text(
                                                text = "Modified: Orig '${entry.extractedShiftCode}' → Now '${entry.currentShiftCode}'",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFE65100)
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "OCR Extracted: ${entry.extractedShiftCode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }

                                if (entry.isProvisional) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFE1F5FE),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF81D4FA))
                                    ) {
                                        Text(
                                            text = "Provisional · Management Assigned",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF0277BD)
                                        )
                                    }
                                }

                                Text(
                                    text = "Floor: ${entry.floorDisplay}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Attendance: ${entry.attendance.status.displayName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }

                            // Edit Button
                            AppOutlinedButton(
                                text = "✏️ Edit Shift",
                                onClick = { onOpenDailyEditor(entry.day) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBulkFloorDialog && onBulkFloorUpdate != null && activeRoster != null) {
        BulkFloorEditDialog(
            currentFloor = effectiveFloor,
            ocrFloor = activeRoster.floorExtracted,
            periodDisplay = periodDisplay,
            totalDays = schedule.size,
            onDismiss = { showBulkFloorDialog = false },
            onConfirmBulkUpdate = { newFloor, author, reason ->
                onBulkFloorUpdate(newFloor, author, reason)
            }
        )
    }
}

@Composable
fun BulkFloorEditDialog(
    currentFloor: String,
    ocrFloor: String?,
    periodDisplay: String,
    totalDays: Int,
    onDismiss: () -> Unit,
    onConfirmBulkUpdate: suspend (newFloor: String, author: String, reason: String?) -> Result<Unit>
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedFloor by rememberSaveable { mutableStateOf(currentFloor) }
    var isCustomFloorMode by rememberSaveable {
        mutableStateOf(currentFloor.isNotBlank() && !PREDEFINED_FLOORS.contains(currentFloor))
    }
    var customFloor by rememberSaveable {
        mutableStateOf(if (isCustomFloorMode) currentFloor else "")
    }
    var author by rememberSaveable { mutableStateOf("Clinical Supervisor") }
    var reason by rememberSaveable { mutableStateOf("Bulk floor update") }

    var isConfirmingStep by rememberSaveable { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val effectiveFloor = remember(isCustomFloorMode, customFloor, selectedFloor) {
        if (isCustomFloorMode) customFloor.trim() else selectedFloor.trim()
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (isConfirmingStep) "Confirm Bulk Floor Update" else "Bulk Floor Assignment",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = IndigoDark
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (errorMessage != null) {
                    InfoCard(
                        containerColor = ErrorContainer,
                        borderColor = ErrorRed.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = OnErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (!isConfirmingStep) {
                    Text(
                        text = "Assign a single default floor across all $totalDays days in $periodDisplay.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )

                    // Current vs OCR Info
                    Surface(
                        color = LavenderSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, LavenderBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Current Monthly Default: ${currentFloor.ifBlank { "Not set" }}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            if (!ocrFloor.isNullOrBlank()) {
                                Text(
                                    "Original OCR Extraction: $ocrFloor",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    Text(
                        text = "Predefined Locations",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    // Predefined Floor Rows
                    val rows = listOf(
                        listOf("Floor 1", "Floor 2", "Floor 3"),
                        listOf("Floor 4", "Floor 5", "ICU"),
                        listOf("Emergency", "OT", "Clear")
                    )

                    rows.forEach { rowFloors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowFloors.forEach { fl ->
                                val isClear = fl == "Clear"
                                val isSelected = !isCustomFloorMode && selectedFloor == fl
                                OutlinedButton(
                                    onClick = {
                                        if (isClear) {
                                            selectedFloor = ""
                                            customFloor = ""
                                            isCustomFloorMode = false
                                        } else {
                                            selectedFloor = fl
                                            isCustomFloorMode = false
                                        }
                                        errorMessage = null
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = if (isClear) {
                                        ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                                    } else if (isSelected) {
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = IndigoContainer,
                                            contentColor = IndigoPrimary
                                        )
                                    } else {
                                        ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                                    },
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isClear) ErrorRed.copy(alpha = 0.5f) else if (isSelected) IndigoPrimary else LavenderBorder
                                    )
                                ) {
                                    Text(
                                        fl,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    ConsistentTextField(
                        value = if (isCustomFloorMode) customFloor else "",
                        onValueChange = {
                            customFloor = it
                            isCustomFloorMode = true
                            selectedFloor = it
                            errorMessage = null
                        },
                        label = "Custom Floor / Unit (e.g. Ward 4B)",
                        placeholder = "Type custom location name"
                    )

                    ConsistentTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = "Author / Clinical Supervisor"
                    )

                    ConsistentTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = "Reason for bulk change"
                    )
                } else {
                    // Confirmation Step
                    InfoCard(
                        containerColor = LavenderSurface,
                        borderColor = LavenderBorder
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Apply '${effectiveFloor.ifBlank { "No Floor" }}' to all $totalDays days in $periodDisplay?",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "This will update every day in the roster, including days with previous individual overrides. Shift types and attendance logs will not be affected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isConfirmingStep) {
                AppButton(
                    text = "Continue",
                    enabled = !isSaving && (effectiveFloor.isNotBlank() || selectedFloor.isBlank()),
                    onClick = {
                        if (author.trim().isBlank()) {
                            errorMessage = "Author / Clinical Supervisor is required."
                        } else {
                            isConfirmingStep = true
                        }
                    }
                )
            } else {
                AppButton(
                    text = if (isSaving) "Applying..." else "Confirm & Apply",
                    enabled = !isSaving,
                    onClick = {
                        isSaving = true
                        errorMessage = null
                        coroutineScope.launch {
                            val res = onConfirmBulkUpdate(
                                effectiveFloor,
                                author.trim(),
                                reason.trim().ifBlank { null }
                            )
                            isSaving = false
                            if (res.isSuccess) {
                                onDismiss()
                            } else {
                                errorMessage = res.exceptionOrNull()?.message ?: "Failed to apply bulk floor change"
                                isConfirmingStep = false
                            }
                        }
                    }
                )
            }
        },
        dismissButton = {
            if (isConfirmingStep && !isSaving) {
                AppOutlinedButton(
                    text = "Back",
                    onClick = { isConfirmingStep = false }
                )
            } else if (!isSaving) {
                AppOutlinedButton(
                    text = "Cancel",
                    onClick = onDismiss
                )
            }
        }
    )
}
