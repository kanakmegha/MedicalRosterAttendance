package com.example.rosterrebuild

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Standard roster shift types and absence classifications.
 *
 * Business Rule:
 * Roster code 'A' designates an absence assigned by upper management.
 * It is NOT the same as OFF and is not an employee-requested leave.
 * It is provisional and potentially changeable by management at a later date.
 */
enum class ShiftType(
    val code: String,
    val displayName: String,
    val description: String,
    val isWorkShift: Boolean,
    val isAbsence: Boolean,
    val isManagementAssigned: Boolean = false,
    val isPotentiallyChangeable: Boolean = false
) {
    MORNING(
        code = "M",
        displayName = "Morning Shift",
        description = "Morning clinical duty",
        isWorkShift = true,
        isAbsence = false
    ),
    EVENING(
        code = "E",
        displayName = "Evening Shift",
        description = "Evening clinical duty",
        isWorkShift = true,
        isAbsence = false
    ),
    NIGHT(
        code = "N",
        displayName = "Night Shift",
        description = "Overnight clinical duty",
        isWorkShift = true,
        isAbsence = false
    ),
    OFF(
        code = "OFF",
        displayName = "Off",
        description = "Scheduled rest day / off duty",
        isWorkShift = false,
        isAbsence = false
    ),
    MANAGEMENT_ABSENCE(
        code = "A",
        displayName = "Management Absence",
        description = "Absence assigned by upper management (provisional / subject to management change)",
        isWorkShift = false,
        isAbsence = true,
        isManagementAssigned = true,
        isPotentiallyChangeable = true
    ),
    UNKNOWN(
        code = "UNKNOWN",
        displayName = "Unknown Shift",
        description = "Unclassified or ambiguous shift",
        isWorkShift = false,
        isAbsence = false
    );

    companion object {
        val ALLOWED_EDIT_CODES = listOf("M", "E", "N", "OFF", "A")

        // Backwards compatibility aliases
        val EARLY: ShiftType get() = EVENING
        val MIDDLE: ShiftType get() = MORNING

        fun isValidShiftCode(code: String): Boolean {
            return ALLOWED_EDIT_CODES.contains(code.trim().uppercase())
        }

        fun fromCode(rawCode: String?): ShiftType {
            if (rawCode.isNullOrBlank()) return UNKNOWN
            val normalized = rawCode.trim().uppercase()
            return entries.firstOrNull { it.code == normalized } ?: UNKNOWN
        }
    }
}

/**
 * Attendance status for working dates.
 *
 * Rules:
 * - New working shifts default to NOT_MARKED.
 * - OFF and A default to NOT_APPLICABLE.
 * - The user can mark a working shift as Present or Absent.
 * - Attendance edits must never modify the planned shift.
 */
enum class AttendanceStatus(val displayName: String) {
    PRESENT("Present"),
    ABSENT("Absent"),
    NOT_MARKED("Not Marked"),
    NOT_APPLICABLE("Not Applicable");

    companion object {
        fun defaultForShift(shift: ShiftType): AttendanceStatus {
            return if (shift.isWorkShift) NOT_MARKED else NOT_APPLICABLE
        }
    }
}

/**
 * Predefined floor options for clinical assignments.
 */
val PREDEFINED_FLOORS = listOf(
    "Floor 1", "Floor 2", "Floor 3", "Floor 4", "Floor 5",
    "ICU", "Emergency", "OT"
)

/**
 * Audit record documenting a change to attendance.
 */
data class AttendanceChangeEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val previousStatus: AttendanceStatus?,
    val newStatus: AttendanceStatus,
    val author: String = "User",
    val notes: String? = null
)

/**
 * Attendance tracking state for a specific date.
 */
data class AttendanceRecord(
    val status: AttendanceStatus,
    val notes: String? = null,
    val changeHistory: List<AttendanceChangeEvent> = emptyList(),
    val isFlaggedForReview: Boolean = false,
    val reviewReason: String? = null
)

/**
 * Identifies the origin / actor of a schedule entry change.
 */
enum class ShiftChangeSource(val displayName: String) {
    INITIAL_EXTRACTION("Initial OCR/Roster Extraction"),
    MANAGEMENT_UPDATE("Authorised Management Update"),
    SUPERVISOR_OVERRIDE("Clinical Supervisor Override"),
    USER_EDIT("User Review / Manual Correction")
}

/**
 * Audit record documenting a change to a daily schedule entry.
 * Preserves traceability of who changed the schedule, when, and from what.
 */
data class ShiftChangeEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val previousShiftCode: String?,
    val newShiftCode: String,
    val source: ShiftChangeSource,
    val author: String = "System",
    val reason: String? = null
)

/**
 * Represents the schedule for a single calendar day.
 *
 * Maintains [extractedShift] and [currentShift] separately so that the
 * originally extracted value is permanently preserved even when management or
 * user modifications occur.
 *
 * Also maintains floor assignment ([extractedFloor], [currentFloor]) and
 * independent attendance tracking ([attendance]).
 */
data class DailyScheduleEntry(
    val day: Int,
    val date: String? = null,
    val extractedShift: ShiftType,
    val extractedShiftCode: String,
    val currentShift: ShiftType,
    val currentShiftCode: String,
    val isProvisional: Boolean = (currentShift == ShiftType.MANAGEMENT_ABSENCE),
    val changeHistory: List<ShiftChangeEvent> = emptyList(),
    val rawCellResult: CellResult? = null,

    // Floor assignment
    val extractedFloor: String? = null,
    val currentFloor: String? = null,

    // Attendance tracking
    val attendance: AttendanceRecord = AttendanceRecord(
        status = AttendanceStatus.defaultForShift(currentShift)
    )
) {
    /**
     * Whether the current shift differs from what was originally extracted.
     */
    val isModified: Boolean
        get() = extractedShiftCode != currentShiftCode

    /**
     * Display label for assigned floor.
     */
    val floorDisplay: String
        get() = currentFloor?.takeIf { it.isNotBlank() } ?: "Floor not assigned"

    /**
     * Whether the floor assignment was modified from what was originally extracted.
     */
    val isFloorModified: Boolean
        get() = (extractedFloor ?: "") != (currentFloor ?: "")

    /**
     * Display title for calendar views, day cards, and schedule detail sheets.
     * Rule 3: Display 'A' as "Management Absence".
     */
    val displayTitle: String
        get() = currentShift.displayName

    /**
     * Detailed status and explanation of origin and provisional nature.
     */
    val scheduleDetails: String
        get() = when (currentShift) {
            ShiftType.MANAGEMENT_ABSENCE ->
                "Management Absence (A): Upper management assigned absence. " +
                        "This is not OFF, not an employee-requested leave, and is subject to revision."
            ShiftType.OFF ->
                "Off (OFF): Scheduled rest day / off duty."
            ShiftType.MORNING ->
                "Morning Shift (M): Morning clinical duty."
            ShiftType.EVENING ->
                "Evening Shift (E): Evening clinical duty."
            ShiftType.NIGHT ->
                "Night Shift (N): Overnight clinical duty."
            ShiftType.UNKNOWN ->
                "Unknown shift: Pending manual verification."
        }

    /**
     * Updates the current schedule while permanently preserving the originally extracted shift.
     * Records a timestamped audit event with change source and author.
     *
     * Prevents invalid shift codes (must be M, E, N, OFF, or A).
     * If attendance has already been recorded, preserves attendance and flags the date for review.
     */
    fun updateSchedule(
        newCode: String,
        source: ShiftChangeSource,
        author: String = "User",
        reason: String? = null
    ): DailyScheduleEntry {
        require(ShiftType.isValidShiftCode(newCode)) {
            "Invalid shift code '$newCode'. Allowed shift codes are: ${ShiftType.ALLOWED_EDIT_CODES.joinToString()}"
        }
        val newType = ShiftType.fromCode(newCode)
        val event = ShiftChangeEvent(
            previousShiftCode = this.currentShiftCode,
            newShiftCode = newType.code,
            source = source,
            author = author,
            reason = reason
        )

        val hasRecordedAttendance = attendance.status == AttendanceStatus.PRESENT || attendance.status == AttendanceStatus.ABSENT
        val shiftActuallyChanged = newType != currentShift

        val updatedAttendance = if (hasRecordedAttendance && shiftActuallyChanged) {
            // Preserve attendance and flag for review
            attendance.copy(
                isFlaggedForReview = true,
                reviewReason = "Shift changed from '${currentShiftCode}' to '${newType.code}' after attendance was recorded"
            )
        } else if (!newType.isWorkShift && attendance.status == AttendanceStatus.NOT_MARKED) {
            // OFF and A default to NOT_APPLICABLE
            attendance.copy(status = AttendanceStatus.NOT_APPLICABLE)
        } else if (newType.isWorkShift && attendance.status == AttendanceStatus.NOT_APPLICABLE) {
            // Changed to a work shift
            attendance.copy(status = AttendanceStatus.NOT_MARKED)
        } else {
            attendance
        }

        return copy(
            currentShift = newType,
            currentShiftCode = newType.code,
            isProvisional = (newType == ShiftType.MANAGEMENT_ABSENCE),
            changeHistory = changeHistory + event,
            attendance = updatedAttendance
        )
    }

    /**
     * Updates the assigned floor while preserving the originally extracted floor.
     * If attendance has already been recorded and the floor changed, flags the date for review.
     */
    fun updateFloor(
        newFloor: String?,
        author: String = "User"
    ): DailyScheduleEntry {
        val normalized = newFloor?.trim()?.ifBlank { null }
        val hasRecordedAttendance = attendance.status == AttendanceStatus.PRESENT || attendance.status == AttendanceStatus.ABSENT
        val floorActuallyChanged = normalized != currentFloor

        val updatedAttendance = if (hasRecordedAttendance && floorActuallyChanged) {
            attendance.copy(
                isFlaggedForReview = true,
                reviewReason = "Floor reassigned to '${normalized ?: "Unassigned"}' after attendance was recorded"
            )
        } else {
            attendance
        }

        return copy(
            currentFloor = normalized,
            attendance = updatedAttendance
        )
    }

    /**
     * Updates attendance status and optional notes.
     * Records a timestamped attendance audit event.
     * Does not modify planned shifts or floor assignments.
     */
    fun updateAttendance(
        newStatus: AttendanceStatus,
        notes: String? = null,
        author: String = "User"
    ): DailyScheduleEntry {
        val event = AttendanceChangeEvent(
            previousStatus = attendance.status,
            newStatus = newStatus,
            author = author,
            notes = notes
        )
        return copy(
            attendance = attendance.copy(
                status = newStatus,
                notes = notes,
                changeHistory = attendance.changeHistory + event,
                isFlaggedForReview = false,
                reviewReason = null
            )
        )
    }

    companion object {
        fun fromCellResult(
            cell: CellResult,
            year: Int? = null,
            month: Int? = null,
            extractedFloor: String? = null
        ): DailyScheduleEntry {
            // Priority: OCR confirmed token, then Colour classification
            val rawCode = cell.ocrText ?: cell.colorCode ?: "UNKNOWN"
            val type = ShiftType.fromCode(rawCode)

            val dateStr = if (year != null && month != null) {
                try {
                    val localDate = LocalDate.of(year, month, cell.day)
                    localDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (_: Exception) {
                    null
                }
            } else null

            val initialEvent = ShiftChangeEvent(
                previousShiftCode = null,
                newShiftCode = type.code,
                source = ShiftChangeSource.INITIAL_EXTRACTION,
                author = "RosterProcessor",
                reason = "Extracted from roster image (Status: ${cell.status})"
            )

            val initialAttendance = AttendanceRecord(
                status = AttendanceStatus.defaultForShift(type)
            )

            return DailyScheduleEntry(
                day = cell.day,
                date = dateStr,
                extractedShift = type,
                extractedShiftCode = type.code,
                currentShift = type,
                currentShiftCode = type.code,
                isProvisional = (type == ShiftType.MANAGEMENT_ABSENCE),
                changeHistory = listOf(initialEvent),
                rawCellResult = cell,
                extractedFloor = extractedFloor,
                currentFloor = extractedFloor,
                attendance = initialAttendance
            )
        }
    }
}

