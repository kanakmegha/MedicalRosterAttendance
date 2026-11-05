package com.example.rosterrebuild.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rosterrebuild.DailyScheduleEntry
import com.example.rosterrebuild.PREDEFINED_FLOORS
import com.example.rosterrebuild.ShiftType
import com.example.rosterrebuild.getShiftColors
import com.example.rosterrebuild.ui.components.*
import com.example.rosterrebuild.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase 2 — Full-Screen Daily Schedule Editor.
 *
 * Dedicated full-screen editing interface exclusively for editing planned shifts and floor assignments.
 *
 * Product Rules:
 * 1. Attendance can ONLY be edited from Monthly Calendar view. Strictly NO attendance controls on this screen.
 * 2. Visual distinction between original extracted, current saved, and newly selected values.
 * 3. Selecting back the original/saved value immediately clears pending dirty state.
 * 4. Save button disabled when there are no pending changes.
 * 5. Warns on back/cancel navigation if there are unsaved pending changes.
 * 6. Audit trail recorded with author and reason.
 */
@Composable
fun DailyEditorScreen(
    entry: DailyScheduleEntry,
    monthlyFloor: String?,
    onBack: () -> Unit,
    onSave: suspend (newShiftCode: String, newFloor: String?, author: String, reason: String?) -> Result<Unit>,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    // Working state preserved across configuration changes (rotation)
    var selectedShift by rememberSaveable(entry.day) { mutableStateOf(entry.currentShiftCode) }
    var selectedFloor by rememberSaveable(entry.day) { mutableStateOf(entry.currentFloor ?: "") }
    var customFloor by rememberSaveable(entry.day) { mutableStateOf("") }
    var isCustomFloorMode by rememberSaveable(entry.day) {
        mutableStateOf(entry.currentFloor != null && !PREDEFINED_FLOORS.contains(entry.currentFloor))
    }
    var author by rememberSaveable(entry.day) { mutableStateOf("Clinical Supervisor") }
    var reason by rememberSaveable(entry.day) { mutableStateOf("") }

    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    // Effective floor based on custom or predefined selection
    val effectiveFloor = remember(isCustomFloorMode, customFloor, selectedFloor) {
        if (isCustomFloorMode) customFloor.trim() else selectedFloor.trim()
    }

    // Determine pending change status
    val currentSavedShift = entry.currentShiftCode
    val currentSavedFloor = entry.currentFloor ?: ""

    val isShiftDirty = selectedShift != currentSavedShift
    val isFloorDirty = effectiveFloor != currentSavedFloor
    val hasPendingChanges = isShiftDirty || isFloorDirty

    // Formatted date string
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

    fun handleBack() {
        if (hasPendingChanges) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    // System back handler
    BackHandler(enabled = true) {
        handleBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(
                color = SurfaceClinical,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, LavenderBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = { handleBack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Calendar",
                                tint = IndigoPrimary
                            )
                        }
                        Column {
                            Text(
                                text = "Edit Daily Record",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = formattedDate,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (hasPendingChanges) {
                        StatusBadge(
                            text = "Unsaved Changes",
                            variant = StatusBadgeVariant.WARNING
                        )
                    } else {
                        StatusBadge(
                            text = "Saved",
                            variant = StatusBadgeVariant.SUCCESS
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = SurfaceClinical,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, LavenderBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppOutlinedButton(
                        text = "Cancel",
                        onClick = { handleBack() },
                        modifier = Modifier.weight(1f),
                        enabled = !isSaving
                    )

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isSaving = true
                                errorMessage = null
                                val saveResult = onSave(
                                    selectedShift,
                                    effectiveFloor.ifBlank { null },
                                    author.trim().ifBlank { "Clinical Supervisor" },
                                    reason.trim().ifBlank { null }
                                )
                                isSaving = false
                                saveResult.onSuccess {
                                    onBack()
                                }.onFailure { err ->
                                    errorMessage = err.message ?: "Failed to save schedule changes."
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1.5f)
                            .defaultMinSize(minHeight = 44.dp),
                        enabled = hasPendingChanges && !isSaving,
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
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Saving...", style = MaterialTheme.typography.labelLarge)
                        } else {
                            Text("Save Changes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Error banner if save failed
            errorMessage?.let { msg ->
                InfoCard(
                    containerColor = ErrorContainer,
                    borderColor = ErrorRed.copy(alpha = 0.5f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⚠️", fontSize = 18.sp)
                        Text(
                            text = msg,
                            color = OnErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Summary Status Card
            InfoCard(
                containerColor = LavenderSurface,
                borderColor = LavenderBorder
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Record Status · Day ${entry.day}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = IndigoDark
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Original Extracted", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(
                                    "${entry.extractedShiftCode} · ${entry.extractedFloor ?: "No floor"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }
                            Column {
                                Text("Current Saved", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                Text(
                                    "${entry.currentShiftCode} · ${entry.currentFloor ?: "No floor"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = IndigoPrimary
                                )
                            }
                        }
                    }

                    if (entry.isModified || entry.isFloorModified) {
                        StatusBadge(
                            text = "Modified from OCR",
                            variant = StatusBadgeVariant.PRIMARY
                        )
                    }
                }
            }

            // ==========================================
            // SECTION 1: PLANNED SHIFT SELECTION
            // ==========================================
            SectionHeading(
                title = "1. Planned Shift",
                subtitle = "Select assigned duty. Codes: M, E, N, OFF, A."
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShiftType.ALLOWED_EDIT_CODES.forEach { code ->
                    val type = ShiftType.fromCode(code)
                    val shiftColors = getShiftColors(type)
                    val isCurrentSelection = (selectedShift == code)
                    val isOriginalValue = (entry.extractedShiftCode == code)
                    val isSavedValue = (entry.currentShiftCode == code)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedShift = code
                                errorMessage = null
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrentSelection) LavenderSurface else SurfaceClinical
                        ),
                        border = BorderStroke(
                            width = if (isCurrentSelection) 2.dp else 1.dp,
                            color = if (isCurrentSelection) IndigoPrimary else LavenderBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                // Prominent Shift Code Badge
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(shiftColors.background, RoundedCornerShape(6.dp))
                                        .border(1.dp, shiftColors.border, RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = code,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = shiftColors.text
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = type.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isCurrentSelection) FontWeight.Bold else FontWeight.Medium,
                                            color = TextPrimary
                                        )

                                        if (type == ShiftType.MANAGEMENT_ABSENCE) {
                                            StatusBadge(
                                                text = "Provisional",
                                                backgroundColor = Color(0xFFE1F5FE),
                                                textColor = Color(0xFF0277BD)
                                            )
                                        }
                                    }

                                    Text(
                                        text = type.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }

                            // Tags indicating original extracted or saved value
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isSavedValue) {
                                    StatusBadge(
                                        text = "Saved Value",
                                        variant = StatusBadgeVariant.PRIMARY
                                    )
                                }
                                if (isOriginalValue && !isSavedValue) {
                                    StatusBadge(
                                        text = "Original OCR",
                                        variant = StatusBadgeVariant.NEUTRAL
                                    )
                                }
                                RadioButton(
                                    selected = isCurrentSelection,
                                    onClick = {
                                        selectedShift = code
                                        errorMessage = null
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = IndigoPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 2: FLOOR ASSIGNMENT
            // ==========================================
            SectionHeading(
                title = "2. Floor Assignment",
                subtitle = "Assign specific floor or area for this date."
            )

            InfoCard(
                containerColor = SurfaceClinical,
                borderColor = LavenderBorder
            ) {
                // Predefined Floors
                Text(
                    text = "Predefined Clinical Locations",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                // Row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Floor 1", "Floor 2", "Floor 3").forEach { fl ->
                        val isSelected = !isCustomFloorMode && selectedFloor == fl
                        OutlinedButton(
                            onClick = {
                                selectedFloor = fl
                                isCustomFloorMode = false
                                errorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = if (isSelected)
                                ButtonDefaults.outlinedButtonColors(containerColor = IndigoContainer, contentColor = IndigoPrimary)
                            else ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) IndigoPrimary else LavenderBorder
                            )
                        ) {
                            Text(fl, style = MaterialTheme.typography.labelMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                // Row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Floor 4", "Floor 5", "ICU").forEach { fl ->
                        val isSelected = !isCustomFloorMode && selectedFloor == fl
                        OutlinedButton(
                            onClick = {
                                selectedFloor = fl
                                isCustomFloorMode = false
                                errorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = if (isSelected)
                                ButtonDefaults.outlinedButtonColors(containerColor = IndigoContainer, contentColor = IndigoPrimary)
                            else ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) IndigoPrimary else LavenderBorder
                            )
                        ) {
                            Text(fl, style = MaterialTheme.typography.labelMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                // Row 3
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Emergency", "OT", "Clear").forEach { fl ->
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
                                ButtonDefaults.outlinedButtonColors(containerColor = IndigoContainer, contentColor = IndigoPrimary)
                            } else {
                                ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                            },
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isClear) ErrorRed.copy(alpha = 0.5f) else if (isSelected) IndigoPrimary else LavenderBorder
                            )
                        ) {
                            Text(fl, style = MaterialTheme.typography.labelMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Custom Floor Input
                ConsistentTextField(
                    value = if (isCustomFloorMode) customFloor else "",
                    onValueChange = {
                        customFloor = it
                        isCustomFloorMode = true
                        selectedFloor = it
                        errorMessage = null
                    },
                    label = "Custom Floor / Unit (e.g. Ward 4B, Day Surgery)",
                    placeholder = "Type custom location name"
                )

                // Preservation note
                Text(
                    text = "Permanent Monthly Floor: ${monthlyFloor ?: entry.extractedFloor ?: "Not assigned"} (monthly baseline is preserved)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            // ==========================================
            // SECTION 3: AUDIT INFORMATION
            // ==========================================
            SectionHeading(
                title = "3. Audit Information",
                subtitle = "Track clinical supervisor authority and change justification."
            )

            InfoCard(
                containerColor = SurfaceClinical,
                borderColor = LavenderBorder
            ) {
                ConsistentTextField(
                    value = author,
                    onValueChange = {
                        author = it
                        errorMessage = null
                    },
                    label = "Author / Clinical Supervisor",
                    placeholder = "Enter name or role"
                )

                ConsistentTextField(
                    value = reason,
                    onValueChange = {
                        reason = it
                        errorMessage = null
                    },
                    label = "Reason for Change (Optional)",
                    placeholder = "e.g. Covering sick leave, ward reassignment, management adjustment",
                    singleLine = false
                )

                Text(
                    text = "All changes are recorded in the permanent audit trail with timestamp and previous values.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Unsaved Changes Confirmation Dialog
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = {
                Text(
                    text = "Unsaved Changes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "You have unsaved changes for Day ${entry.day} (${formattedDate}). What would you like to do?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedDialog = false
                        coroutineScope.launch {
                            isSaving = true
                            errorMessage = null
                            val saveResult = onSave(
                                selectedShift,
                                effectiveFloor.ifBlank { null },
                                author.trim().ifBlank { "Clinical Supervisor" },
                                reason.trim().ifBlank { null }
                            )
                            isSaving = false
                            saveResult.onSuccess {
                                onBack()
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Failed to save schedule changes."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                ) {
                    Text("Save & Exit")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            onBack()
                        }
                    ) {
                        Text("Discard", color = ErrorRed)
                    }

                    TextButton(
                        onClick = { showUnsavedDialog = false }
                    ) {
                        Text("Continue Editing")
                    }
                }
            }
        )
    }
}

