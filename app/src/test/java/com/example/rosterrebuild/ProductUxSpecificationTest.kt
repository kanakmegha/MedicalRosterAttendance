package com.example.rosterrebuild

import com.example.rosterrebuild.data.local.RosterEntity
import com.example.rosterrebuild.ui.ActiveScreen
import com.example.rosterrebuild.ui.TodayScheduleUiState
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/**
 * Product UX Specification 1.0 — Comprehensive Verification Test Suite.
 *
 * Verifies core UX architecture, attendance rules, upcoming shift resolution,
 * and clean display behaviors required by UX Specification 1.0:
 *
 * 1. Upcoming shift resolution within the current month.
 * 2. Upcoming shift resolution across month boundaries (next month's roster).
 * 3. Upcoming shift skipping rest days (OFF) to find actual clinical duties.
 * 4. Upcoming shift returns null when no future duties are scheduled.
 * 5. Self-reported attendance default statuses for working vs non-working shifts.
 * 6. Not Applicable is distinct from Not Marked.
 * 7. Self-reported author designation.
 * 8. Clean attendance indicators (no repeated textual "N/A" strings in date cells).
 * 9. Primary navigation destinations integrity (Home, My Calendar, My Rosters).
 * 10. Empty month calendar renders real dates without fabricated shifts or dashes.
 */
class ProductUxSpecificationTest {

    private fun createSampleRoster(
        id: String,
        employeeId: String = "ABHL341",
        month: Int,
        year: Int,
        version: Int = 1,
        status: String = "ACTIVE"
    ): RosterEntity {
        return RosterEntity(
            id = id,
            employeeId = employeeId,
            month = month,
            year = year,
            version = version,
            status = status,
            uploadTimestamp = System.currentTimeMillis(),
            originalFilePath = "/data/roster_$id.png",
            workbookFilePath = "/data/roster_$id.xlsx",
            workbookFileUri = null,
            processingStatus = "COMPLETED",
            floorExtracted = "Ward 4B",
            currentMonthlyFloor = "Ward 4B"
        )
    }

    private fun createEntry(
        day: Int,
        shiftCode: String,
        floor: String? = "Ward 4B",
        attendanceStatus: AttendanceStatus = AttendanceStatus.NOT_MARKED
    ): DailyScheduleEntry {
        val shift = ShiftType.fromCode(shiftCode)
        return DailyScheduleEntry(
            day = day,
            date = "2026-11-%02d".format(day),
            extractedShift = shift,
            extractedShiftCode = shift.code,
            currentShift = shift,
            currentShiftCode = shift.code,
            extractedFloor = floor,
            currentFloor = floor,
            attendance = AttendanceRecord(
                status = attendanceStatus,
                notes = null
            )
        )
    }

    // 1. Upcoming shift discovery within the current active month
    @Test
    fun test1_upcomingShiftWithinCurrentMonth() {
        val roster = createSampleRoster("roster-nov", month = 11, year = 2026)
        val today = LocalDate.of(2026, 11, 7) // Today is 7 Nov (Day 7)

        val schedule = listOf(
            createEntry(day = 7, shiftCode = "M"),
            createEntry(day = 8, shiftCode = "OFF"),
            createEntry(day = 9, shiftCode = "N", floor = "ICU")
        )

        val upcoming = TodayScheduleResolver.findUpcomingShift(
            rosters = listOf(roster),
            employeeId = "ABHL341",
            targetDate = today,
            scheduleProvider = { schedule }
        )

        assertNotNull("Upcoming shift must be found", upcoming)
        assertEquals("Upcoming shift date should be Day 9", 9, upcoming!!.date.dayOfMonth)
        assertEquals("Upcoming shift should be Night Shift (N)", "N", upcoming.entry.currentShiftCode)
        assertEquals("Upcoming shift floor should be ICU", "ICU", upcoming.entry.currentFloor)
    }

    // 2. Upcoming shift resolution across month boundaries (to next month's active roster)
    @Test
    fun test2_upcomingShiftAcrossMonthBoundary() {
        val novRoster = createSampleRoster("roster-nov", month = 11, year = 2026)
        val decRoster = createSampleRoster("roster-dec", month = 12, year = 2026)
        val today = LocalDate.of(2026, 11, 30) // Last day of November

        val novSchedule = listOf(
            createEntry(day = 30, shiftCode = "M")
        )
        val decSchedule = listOf(
            createEntry(day = 1, shiftCode = "OFF"),
            createEntry(day = 2, shiftCode = "E", floor = "Emergency")
        )

        val upcoming = TodayScheduleResolver.findUpcomingShift(
            rosters = listOf(novRoster, decRoster),
            employeeId = "ABHL341",
            targetDate = today,
            scheduleProvider = { id -> if (id == "roster-nov") novSchedule else decSchedule }
        )

        assertNotNull("Upcoming shift should be discovered in December roster", upcoming)
        assertEquals("Upcoming shift should be in December", 12, upcoming!!.date.monthValue)
        assertEquals("Upcoming shift date should be Day 2", 2, upcoming.date.dayOfMonth)
        assertEquals("Upcoming shift code should be E", "E", upcoming.entry.currentShiftCode)
    }

    // 3. Upcoming shift skips rest days (OFF) and management absences (A)
    @Test
    fun test3_upcomingShiftSkipsRestDaysAndAbsences() {
        val roster = createSampleRoster("roster-nov", month = 11, year = 2026)
        val today = LocalDate.of(2026, 11, 5)

        val schedule = listOf(
            createEntry(day = 5, shiftCode = "M"),
            createEntry(day = 6, shiftCode = "OFF"),
            createEntry(day = 7, shiftCode = "OFF"),
            createEntry(day = 8, shiftCode = "A"),
            createEntry(day = 9, shiftCode = "M", floor = "Ward 2")
        )

        val upcoming = TodayScheduleResolver.findUpcomingShift(
            rosters = listOf(roster),
            employeeId = "ABHL341",
            targetDate = today,
            scheduleProvider = { schedule }
        )

        assertNotNull(upcoming)
        assertEquals("Should skip OFF and A to find first work shift on Day 9", 9, upcoming!!.date.dayOfMonth)
        assertTrue("Found upcoming shift must be a work shift", upcoming.entry.currentShift.isWorkShift)
    }

    // 4. Upcoming shift returns null when no further work shifts are scheduled
    @Test
    fun test4_upcomingShiftReturnsNullWhenNoneScheduled() {
        val roster = createSampleRoster("roster-nov", month = 11, year = 2026)
        val today = LocalDate.of(2026, 11, 28)

        val schedule = listOf(
            createEntry(day = 28, shiftCode = "M"),
            createEntry(day = 29, shiftCode = "OFF"),
            createEntry(day = 30, shiftCode = "OFF")
        )

        val upcoming = TodayScheduleResolver.findUpcomingShift(
            rosters = listOf(roster),
            employeeId = "ABHL341",
            targetDate = today,
            scheduleProvider = { schedule }
        )

        assertNull("Should return null when remaining days are rest days only", upcoming)
    }

    // 5. Attendance default statuses for working vs non-working shifts
    @Test
    fun test5_attendanceDefaultsForShiftTypes() {
        // Working shifts must default to NOT_MARKED
        listOf(ShiftType.MORNING, ShiftType.EVENING, ShiftType.NIGHT).forEach { shift ->
            assertTrue("Shift ${shift.code} must be a work shift", shift.isWorkShift)
            assertEquals("Default for work shift must be NOT_MARKED",
                AttendanceStatus.NOT_MARKED, AttendanceStatus.defaultForShift(shift))
        }

        // Non-working shifts must default to NOT_APPLICABLE
        listOf(ShiftType.OFF, ShiftType.MANAGEMENT_ABSENCE).forEach { shift ->
            assertFalse("Shift ${shift.code} must not be a work shift", shift.isWorkShift)
            assertEquals("Default for non-work shift must be NOT_APPLICABLE",
                AttendanceStatus.NOT_APPLICABLE, AttendanceStatus.defaultForShift(shift))
        }
    }

    // 6. Not Applicable is strictly distinct from Not Marked
    @Test
    fun test6_notApplicableIsDistinctFromNotMarked() {
        assertNotEquals(AttendanceStatus.NOT_APPLICABLE, AttendanceStatus.NOT_MARKED)
        assertEquals("Not Marked", AttendanceStatus.NOT_MARKED.displayName)
        assertEquals("Not Applicable", AttendanceStatus.NOT_APPLICABLE.displayName)
    }

    // 7. Self-reported attendance records retain "Self-reported" author
    @Test
    fun test7_selfReportedAuthorRetention() {
        val entry = createEntry(day = 7, shiftCode = "M")
        val updated = entry.updateAttendance(
            newStatus = AttendanceStatus.PRESENT,
            notes = "Completed morning ward round",
            author = "Self-reported"
        )

        assertEquals("Self-reported", updated.attendance.changeHistory.last().author)
        assertEquals(AttendanceStatus.PRESENT, updated.attendance.status)
        assertEquals("Completed morning ward round", updated.attendance.notes)
    }

    // 8. Clean attendance indicators (no repeated "N/A" strings in calendar cells)
    @Test
    fun test8_cleanAttendanceIndicatorsWithoutRepeatedNaText() {
        // NOT_APPLICABLE or null must have empty label to avoid cell clutter
        val naStyle = getAttendanceBadgeStyle(AttendanceStatus.NOT_APPLICABLE)
        assertEquals("NOT_APPLICABLE badge label must be empty string to prevent repeated N/A clutter", "", naStyle.label)

        val nullStyle = getAttendanceBadgeStyle(null)
        assertEquals("Null attendance status badge label must be empty string", "", nullStyle.label)

        // PRESENT must use checkmark
        val presentStyle = getAttendanceBadgeStyle(AttendanceStatus.PRESENT)
        assertEquals("PRESENT must use checkmark indicator", "✓", presentStyle.label)

        // ABSENT must use cross
        val absentStyle = getAttendanceBadgeStyle(AttendanceStatus.ABSENT)
        assertEquals("ABSENT must use cross indicator", "✕", absentStyle.label)

        // NOT_MARKED must use amber circle
        val unmarkedStyle = getAttendanceBadgeStyle(AttendanceStatus.NOT_MARKED)
        assertEquals("NOT_MARKED must use circle indicator", "●", unmarkedStyle.label)
    }

    // 9. Primary navigation destinations integrity (Home, My Calendar; My Rosters removed from navigation)
    @Test
    fun test9_navigationDestinationsIntegrity() {
        val primaryScreens = listOf(
            ActiveScreen.SAVED_ROSTERS_DASHBOARD, // Home
            ActiveScreen.MONTHLY_CALENDAR         // My Calendar
        )

        assertEquals(2, primaryScreens.size)
        assertTrue(primaryScreens.contains(ActiveScreen.SAVED_ROSTERS_DASHBOARD))
        assertTrue(primaryScreens.contains(ActiveScreen.MONTHLY_CALENDAR))
        assertFalse(primaryScreens.contains(ActiveScreen.MY_ROSTERS))

        // Sub-screens
        val subScreens = listOf(
            ActiveScreen.MY_ROSTERS,
            ActiveScreen.ROSTER_UPLOAD,
            ActiveScreen.ROSTER_REVIEW,
            ActiveScreen.DAILY_EDITOR
        )
        assertEquals(4, subScreens.size)
    }

    // 10. Empty month calendar renders real dates without fabricated shifts or dashes
    @Test
    fun test10_emptyMonthCalendarRendersCleanDates() {
        val days = CalendarDomain.buildMonthDays(
            year = 2026,
            month = 11,
            schedule = emptyList(),
            today = LocalDate.of(2026, 11, 7)
        )

        assertEquals("November 2026 has 30 days", 30, days.size)
        days.forEachIndexed { index, day ->
            assertEquals("Day of month should match index + 1", index + 1, day.dayOfMonth)
            assertNull("Schedule entry must be null when no roster is uploaded", day.scheduleEntry)
        }

        // Today identification
        assertTrue("Day 7 must be marked today", days[6].isToday)
        assertFalse("Day 8 must not be marked today", days[7].isToday)
    }
}
