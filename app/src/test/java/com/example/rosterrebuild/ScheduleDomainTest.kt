package com.example.rosterrebuild

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleDomainTest {

    /**
     * Requirement 1: Correct M/E/N meanings
     * M = Morning Shift
     * E = Evening Shift
     * N = Night Shift
     * OFF = Off
     * A = Management Absence
     */
    @Test
    fun test1_correct_MEN_meanings() {
        val m = ShiftType.fromCode("M")
        assertEquals("M", m.code)
        assertEquals("Morning Shift", m.displayName)
        assertEquals(ShiftType.MORNING, m)
        assertTrue(m.isWorkShift)
        assertFalse(m.isAbsence)

        val e = ShiftType.fromCode("E")
        assertEquals("E", e.code)
        assertEquals("Evening Shift", e.displayName)
        assertEquals(ShiftType.EVENING, e)
        assertTrue(e.isWorkShift)
        assertFalse(e.isAbsence)

        val n = ShiftType.fromCode("N")
        assertEquals("N", n.code)
        assertEquals("Night Shift", n.displayName)
        assertEquals(ShiftType.NIGHT, n)
        assertTrue(n.isWorkShift)
        assertFalse(n.isAbsence)

        val off = ShiftType.fromCode("OFF")
        assertEquals("OFF", off.code)
        assertEquals("Off", off.displayName)
        assertEquals(ShiftType.OFF, off)
        assertFalse(off.isWorkShift)

        val a = ShiftType.fromCode("A")
        assertEquals("A", a.code)
        assertEquals("Management Absence", a.displayName)
        assertEquals(ShiftType.MANAGEMENT_ABSENCE, a)
        assertTrue(a.isAbsence)
        assertTrue(a.isManagementAssigned)
        assertTrue(a.isPotentiallyChangeable)
    }

    /**
     * Requirement 2: Calendar date count for 28, 29, 30, and 31-day months.
     */
    @Test
    fun test2_calendarDateCount_28_29_30_31() {
        // 28 days: February non-leap year (e.g. 2025, 2023)
        assertEquals(28, CalendarDomain.getDaysInMonth(2025, 2))
        assertEquals(28, CalendarDomain.getDaysInMonth(2023, 2))

        // 29 days: February leap year (e.g. 2024, 2028)
        assertEquals(29, CalendarDomain.getDaysInMonth(2024, 2))
        assertEquals(29, CalendarDomain.getDaysInMonth(2028, 2))

        // 30 days: April, June, September, November
        assertEquals(30, CalendarDomain.getDaysInMonth(2026, 4))
        assertEquals(30, CalendarDomain.getDaysInMonth(2026, 6))
        assertEquals(30, CalendarDomain.getDaysInMonth(2026, 9))
        assertEquals(30, CalendarDomain.getDaysInMonth(2026, 11))

        // 31 days: January, March, May, July, August, October, December
        assertEquals(31, CalendarDomain.getDaysInMonth(2026, 1))
        assertEquals(31, CalendarDomain.getDaysInMonth(2026, 3))
        assertEquals(31, CalendarDomain.getDaysInMonth(2026, 5))
        assertEquals(31, CalendarDomain.getDaysInMonth(2026, 7))
        assertEquals(31, CalendarDomain.getDaysInMonth(2026, 8))
        assertEquals(31, CalendarDomain.getDaysInMonth(2026, 10))
        assertEquals(31, CalendarDomain.getDaysInMonth(2026, 12))
    }

    /**
     * Requirement 3: Correct weekday alignment.
     * Week starts on Monday: Monday = 0, Tuesday = 1, ..., Sunday = 6.
     */
    @Test
    fun test3_correctWeekdayAlignment() {
        // June 1, 2026 is Monday -> offset 0
        assertEquals(0, CalendarDomain.getFirstDayOfWeekOffset(2026, 6))

        // September 1, 2026 is Tuesday -> offset 1
        assertEquals(1, CalendarDomain.getFirstDayOfWeekOffset(2026, 9))

        // February 1, 2026 is Sunday -> offset 6
        assertEquals(6, CalendarDomain.getFirstDayOfWeekOffset(2026, 2))

        // January 1, 2026 is Thursday -> offset 3
        assertEquals(3, CalendarDomain.getFirstDayOfWeekOffset(2026, 1))

        // March 1, 2026 is Sunday -> offset 6
        assertEquals(6, CalendarDomain.getFirstDayOfWeekOffset(2026, 3))
    }

    /**
     * Requirement 4: Editing M → E.
     */
    @Test
    fun test4_editing_M_to_E() {
        val entry = DailyScheduleEntry(
            day = 1,
            date = "2026-06-01",
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M"
        )
        assertFalse(entry.isModified)

        val updated = entry.updateSchedule(
            newCode = "E",
            source = ShiftChangeSource.USER_EDIT,
            author = "Nurse Manager",
            reason = "Staff shift swap request"
        )

        assertEquals("M", updated.extractedShiftCode)
        assertEquals(ShiftType.MORNING, updated.extractedShift)
        assertEquals("E", updated.currentShiftCode)
        assertEquals(ShiftType.EVENING, updated.currentShift)
        assertTrue(updated.isModified)
        assertEquals("Evening Shift", updated.displayTitle)
    }

    /**
     * Requirement 5: Editing A → M while preserving original A.
     */
    @Test
    fun test5_editing_A_to_M_whilePreservingOriginalA() {
        val entry = DailyScheduleEntry(
            day = 3,
            date = "2026-06-03",
            extractedShift = ShiftType.MANAGEMENT_ABSENCE,
            extractedShiftCode = "A",
            currentShift = ShiftType.MANAGEMENT_ABSENCE,
            currentShiftCode = "A"
        )
        assertTrue(entry.isProvisional)
        assertFalse(entry.isModified)

        val updated = entry.updateSchedule(
            newCode = "M",
            source = ShiftChangeSource.MANAGEMENT_UPDATE,
            author = "Medical Director",
            reason = "Absence lifted, assigned to Morning clinical duty"
        )

        // Original extracted value is permanently preserved
        assertEquals("A", updated.extractedShiftCode)
        assertEquals(ShiftType.MANAGEMENT_ABSENCE, updated.extractedShift)

        // Current shift is updated
        assertEquals("M", updated.currentShiftCode)
        assertEquals(ShiftType.MORNING, updated.currentShift)
        assertTrue(updated.isModified)
        assertFalse("Morning duty is confirmed work shift, not provisional absence", updated.isProvisional)
    }

    /**
     * Requirement 6: Cancelled edits not changing data.
     */
    @Test
    fun test6_cancelledEditsNotChangingData() {
        var scheduleState = listOf(
            DailyScheduleEntry(
                day = 5,
                date = "2026-06-05",
                extractedShift = ShiftType.NIGHT,
                extractedShiftCode = "N",
                currentShift = ShiftType.NIGHT,
                currentShiftCode = "N"
            )
        )

        // Simulate opening dialog and user clicking "Cancel" (no onUpdateShift executed)
        val candidateCode = "OFF"
        val userCancelled = true
        if (!userCancelled) {
            scheduleState = scheduleState.map { it.updateSchedule(candidateCode, ShiftChangeSource.USER_EDIT) }
        }

        assertEquals(1, scheduleState.size)
        assertEquals("N", scheduleState.first().currentShiftCode)
        assertEquals(ShiftType.NIGHT, scheduleState.first().currentShift)
        assertFalse(scheduleState.first().isModified)
        assertEquals(0, scheduleState.first().changeHistory.size)
    }

    /**
     * Requirement 7: Audit history being appended correctly.
     */
    @Test
    fun test7_auditHistoryAppendedCorrectly() {
        val entry = DailyScheduleEntry.fromCellResult(
            CellResult(
                day = 10,
                bounds = null,
                crop = null,
                ocrText = "A",
                colorCode = "A",
                colorDistance = 1.0,
                status = CellStatus.DIRECTLY_OBSERVED,
                reason = "Extracted"
            )
        )
        assertEquals(1, entry.changeHistory.size)
        assertEquals(ShiftChangeSource.INITIAL_EXTRACTION, entry.changeHistory.first().source)

        val firstEdit = entry.updateSchedule(
            newCode = "M",
            source = ShiftChangeSource.MANAGEMENT_UPDATE,
            author = "Admin Dr. Lee",
            reason = "Staffing requirement"
        )
        assertEquals(2, firstEdit.changeHistory.size)
        val event1 = firstEdit.changeHistory.last()
        assertEquals("A", event1.previousShiftCode)
        assertEquals("M", event1.newShiftCode)
        assertEquals("Admin Dr. Lee", event1.author)
        assertEquals("Staffing requirement", event1.reason)
        assertEquals(ShiftChangeSource.MANAGEMENT_UPDATE, event1.source)
        assertTrue(event1.timestamp > 0)

        val secondEdit = firstEdit.updateSchedule(
            newCode = "E",
            source = ShiftChangeSource.SUPERVISOR_OVERRIDE,
            author = "Supervisor Nurse Clark",
            reason = "Moved to evening shift"
        )
        assertEquals(3, secondEdit.changeHistory.size)
        val event2 = secondEdit.changeHistory.last()
        assertEquals("M", event2.previousShiftCode)
        assertEquals("E", event2.newShiftCode)
        assertEquals(ShiftChangeSource.SUPERVISOR_OVERRIDE, event2.source)
        assertEquals("Supervisor Nurse Clark", event2.author)
        assertEquals("Moved to evening shift", event2.reason)
    }

    /**
     * Requirement 8: Export reflecting edited values and preserving original values.
     */
    @Test
    fun test8_exportReflectingEditedValuesAndPreservingOriginal() {
        val schedule = listOf(
            DailyScheduleEntry(
                day = 1,
                date = "2026-06-01",
                extractedShift = ShiftType.MORNING,
                extractedShiftCode = "M",
                currentShift = ShiftType.EVENING, // Edited M -> E
                currentShiftCode = "E",
                changeHistory = listOf(
                    ShiftChangeEvent(
                        previousShiftCode = "M",
                        newShiftCode = "E",
                        source = ShiftChangeSource.USER_EDIT,
                        author = "User",
                        reason = "Swap with Day 2"
                    )
                )
            ),
            DailyScheduleEntry(
                day = 3,
                date = "2026-06-03",
                extractedShift = ShiftType.MANAGEMENT_ABSENCE,
                extractedShiftCode = "A",
                currentShift = ShiftType.MANAGEMENT_ABSENCE,
                currentShiftCode = "A"
            )
        )

        // 1. Verify CSV Export
        val csv = RosterExport.toCsv(schedule, "ABHL341", "June 2026")
        assertTrue("CSV must reflect edited shift Evening Shift", csv.contains(",M,E,Evening Shift"))
        assertTrue("CSV must preserve original extracted code 'M'", csv.contains(",M,E,"))
        assertTrue("CSV must include Management Absence for Day 3", csv.contains(",A,A,Management Absence"))

        // 2. Verify Excel XML Export
        val xml = RosterExport.toExcelXml(schedule, "ABHL341", "June 2026")
        assertTrue("XML must reflect edited current code 'E'", xml.contains("<Data ss:Type=\"String\">E</Data>"))
        assertTrue("XML must reflect original extracted code 'M'", xml.contains("<Data ss:Type=\"String\">M</Data>"))
        assertTrue("XML must include Management Absence style and title", xml.contains("Management Absence"))
    }

    /**
     * Requirement 9: Calendar and review screen showing the same current schedule.
     */
    @Test
    fun test9_calendarAndReviewShowSameCurrentSchedule() {
        val initialSchedule = listOf(
            DailyScheduleEntry(
                day = 15,
                date = "2026-06-15",
                extractedShift = ShiftType.OFF,
                extractedShiftCode = "OFF",
                currentShift = ShiftType.OFF,
                currentShiftCode = "OFF"
            )
        )

        // When updated via onUpdateShift
        val updatedSchedule = initialSchedule.map { entry ->
            if (entry.day == 15) {
                entry.updateSchedule("N", ShiftChangeSource.MANAGEMENT_UPDATE, "Director", "Emergency cover")
            } else entry
        }

        // Calendar builds its month days from this exact schedule list
        val calendarDays = CalendarDomain.buildMonthDays(2026, 6, updatedSchedule)
        val day15InCalendar = calendarDays.first { it.dayOfMonth == 15 }
        assertEquals("N", day15InCalendar.scheduleEntry?.currentShiftCode)
        assertEquals("Night Shift", day15InCalendar.scheduleEntry?.displayTitle)

        // Review screen finds its day from the exact same schedule list
        val day15InReview = updatedSchedule.first { it.day == 15 }
        assertEquals("N", day15InReview.currentShiftCode)
        assertEquals("Night Shift", day15InReview.displayTitle)

        // Both views point to the identical updated schedule entry
        assertEquals(day15InCalendar.scheduleEntry, day15InReview)
    }

    /**
     * Requirement: Prevent invalid shift codes.
     */
    @Test
    fun test10_preventInvalidShiftCodes() {
        assertTrue(ShiftType.isValidShiftCode("M"))
        assertTrue(ShiftType.isValidShiftCode("E"))
        assertTrue(ShiftType.isValidShiftCode("N"))
        assertTrue(ShiftType.isValidShiftCode("OFF"))
        assertTrue(ShiftType.isValidShiftCode("A"))

        assertFalse(ShiftType.isValidShiftCode("XYZ"))
        assertFalse(ShiftType.isValidShiftCode("LEAVE"))
        assertFalse(ShiftType.isValidShiftCode("SICK"))

        val entry = DailyScheduleEntry(
            day = 1,
            date = "2026-06-01",
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M"
        )

        try {
            entry.updateSchedule("INVALID", ShiftChangeSource.USER_EDIT)
            fail("Expected IllegalArgumentException when passing invalid shift code")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid shift code"))
        }
    }

    /**
     * Requirement: Floor assignment preserves original extracted floor.
     */
    @Test
    fun test11_floorAssignmentAndPreservation() {
        val entryWithoutFloor = DailyScheduleEntry(
            day = 2,
            date = "2026-06-02",
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M"
        )
        assertNull(entryWithoutFloor.extractedFloor)
        assertNull(entryWithoutFloor.currentFloor)
        assertEquals("Floor not assigned", entryWithoutFloor.floorDisplay)
        assertFalse(entryWithoutFloor.isFloorModified)

        // Assign predefined floor
        val withFloor2 = entryWithoutFloor.updateFloor("Floor 2")
        assertNull(withFloor2.extractedFloor)
        assertEquals("Floor 2", withFloor2.currentFloor)
        assertEquals("Floor 2", withFloor2.floorDisplay)
        assertTrue(withFloor2.isFloorModified)

        // Assign custom floor
        val withCustom = withFloor2.updateFloor("ICU Isolation Ward")
        assertEquals("ICU Isolation Ward", withCustom.currentFloor)
        assertEquals("ICU Isolation Ward", withCustom.floorDisplay)

        // If roster processor extracted a floor initially
        val entryWithInitialFloor = DailyScheduleEntry(
            day = 3,
            date = "2026-06-03",
            extractedShift = ShiftType.EVENING,
            extractedShiftCode = "E",
            currentShift = ShiftType.EVENING,
            currentShiftCode = "E",
            extractedFloor = "Floor 1",
            currentFloor = "Floor 1"
        )
        assertEquals("Floor 1", entryWithInitialFloor.floorDisplay)
        assertFalse(entryWithInitialFloor.isFloorModified)

        val editedFloor = entryWithInitialFloor.updateFloor("Floor 4")
        assertEquals("Floor 1", editedFloor.extractedFloor) // Preserved
        assertEquals("Floor 4", editedFloor.currentFloor)
        assertTrue(editedFloor.isFloorModified)
    }

    /**
     * Requirement: Attendance defaults (working shifts -> NOT_MARKED, OFF and A -> NOT_APPLICABLE).
     */
    @Test
    fun test12_attendanceDefaults() {
        val morningEntry = DailyScheduleEntry(
            day = 1,
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M"
        )
        assertEquals(AttendanceStatus.NOT_MARKED, morningEntry.attendance.status)

        val nightEntry = DailyScheduleEntry(
            day = 2,
            extractedShift = ShiftType.NIGHT,
            extractedShiftCode = "N",
            currentShift = ShiftType.NIGHT,
            currentShiftCode = "N"
        )
        assertEquals(AttendanceStatus.NOT_MARKED, nightEntry.attendance.status)

        val offEntry = DailyScheduleEntry(
            day = 3,
            extractedShift = ShiftType.OFF,
            extractedShiftCode = "OFF",
            currentShift = ShiftType.OFF,
            currentShiftCode = "OFF"
        )
        assertEquals(AttendanceStatus.NOT_APPLICABLE, offEntry.attendance.status)

        val aEntry = DailyScheduleEntry(
            day = 4,
            extractedShift = ShiftType.MANAGEMENT_ABSENCE,
            extractedShiftCode = "A",
            currentShift = ShiftType.MANAGEMENT_ABSENCE,
            currentShiftCode = "A"
        )
        assertEquals(AttendanceStatus.NOT_APPLICABLE, aEntry.attendance.status)
    }

    /**
     * Requirement: Attendance marking does not modify planned shift, and records audit history.
     */
    @Test
    fun test13_attendanceMarkingAndAuditHistory() {
        val entry = DailyScheduleEntry(
            day = 5,
            date = "2026-06-05",
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M",
            currentFloor = "Floor 2"
        )
        assertEquals(AttendanceStatus.NOT_MARKED, entry.attendance.status)
        assertEquals(0, entry.attendance.changeHistory.size)

        // Mark as PRESENT
        val presentEntry = entry.updateAttendance(
            newStatus = AttendanceStatus.PRESENT,
            notes = "On time, reported to Charge Nurse",
            author = "Nurse Manager"
        )

        // Planned shift and floor must NOT be modified
        assertEquals("M", presentEntry.extractedShiftCode)
        assertEquals("M", presentEntry.currentShiftCode)
        assertEquals(ShiftType.MORNING, presentEntry.currentShift)
        assertEquals("Floor 2", presentEntry.currentFloor)

        // Attendance state updated
        assertEquals(AttendanceStatus.PRESENT, presentEntry.attendance.status)
        assertEquals("On time, reported to Charge Nurse", presentEntry.attendance.notes)
        assertEquals(1, presentEntry.attendance.changeHistory.size)

        val event1 = presentEntry.attendance.changeHistory.first()
        assertEquals(AttendanceStatus.NOT_MARKED, event1.previousStatus)
        assertEquals(AttendanceStatus.PRESENT, event1.newStatus)
        assertEquals("Nurse Manager", event1.author)
        assertEquals("On time, reported to Charge Nurse", event1.notes)
        assertTrue(event1.timestamp > 0)

        // Update to ABSENT with reason
        val absentEntry = presentEntry.updateAttendance(
            newStatus = AttendanceStatus.ABSENT,
            notes = "Called in sick with fever",
            author = "Supervisor"
        )
        assertEquals(AttendanceStatus.ABSENT, absentEntry.attendance.status)
        assertEquals(2, absentEntry.attendance.changeHistory.size)
        val event2 = absentEntry.attendance.changeHistory.last()
        assertEquals(AttendanceStatus.PRESENT, event2.previousStatus)
        assertEquals(AttendanceStatus.ABSENT, event2.newStatus)
        assertEquals("Supervisor", event2.author)
    }

    /**
     * Requirement: If shift or floor is changed after attendance was recorded,
     * preserve attendance and flag date for review.
     */
    @Test
    fun test14_postAttendanceShiftOrFloorChangeFlagsReview() {
        val initial = DailyScheduleEntry(
            day = 7,
            date = "2026-06-07",
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M",
            currentFloor = "Floor 1"
        )
        val withAttendance = initial.updateAttendance(
            newStatus = AttendanceStatus.PRESENT,
            notes = "Checked in at 07:00"
        )
        assertEquals(AttendanceStatus.PRESENT, withAttendance.attendance.status)
        assertFalse(withAttendance.attendance.isFlaggedForReview)

        // 1. Shift change after attendance recorded
        val shiftChanged = withAttendance.updateSchedule("E", ShiftChangeSource.MANAGEMENT_UPDATE, "Director", "Moved to Evening")
        // Attendance must be PRESERVED
        assertEquals(AttendanceStatus.PRESENT, shiftChanged.attendance.status)
        // Must be FLAGGED FOR REVIEW
        assertTrue(shiftChanged.attendance.isFlaggedForReview)
        assertTrue(shiftChanged.attendance.reviewReason!!.contains("Shift changed"))

        // 2. Floor change after attendance recorded
        val floorChanged = withAttendance.updateFloor("Floor 3", "Supervisor")
        assertEquals(AttendanceStatus.PRESENT, floorChanged.attendance.status)
        assertTrue(floorChanged.attendance.isFlaggedForReview)
        assertTrue(floorChanged.attendance.reviewReason!!.contains("Floor reassigned"))

        // 3. User reviews and updates attendance -> clears review flag
        val reconfirmed = floorChanged.updateAttendance(AttendanceStatus.PRESENT, "Confirmed present on Floor 3")
        assertFalse(reconfirmed.attendance.isFlaggedForReview)
        assertNull(reconfirmed.attendance.reviewReason)
    }
}
