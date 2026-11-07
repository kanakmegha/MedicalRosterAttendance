package com.example.rosterrebuild

import com.example.rosterrebuild.data.local.RosterEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pilot 5 — Comprehensive Test Suite for Calendar Experience and Today's Schedule Reliability.
 *
 * Covers all 10 required test scenarios:
 * 1. Current date identification.
 * 2. Month boundaries and year transitions.
 * 3. Leap-year February.
 * 4. Calendar date rendering for 28, 29, 30 and 31-day months.
 * 5. Today's schedule lookup using full dates.
 * 6. Missing roster and missing schedule entry.
 * 7. Multiple saved rosters and correct applicable-roster selection.
 * 8. Shift/floor edits persisting correctly.
 * 9. Attendance remaining independent from planned shift edits.
 * 10. Dashboard refresh after a date change.
 */
class CalendarAndTodayScheduleTest {

    private fun createSampleRoster(
        id: String = "roster-nov-2026",
        employeeId: String = "ABHL341",
        month: Int = 11,
        year: Int = 2026,
        version: Int = 1,
        status: String = "ACTIVE",
        uploadTimestamp: Long = 1000L
    ): RosterEntity {
        return RosterEntity(
            id = id,
            employeeId = employeeId,
            month = month,
            year = year,
            version = version,
            status = status,
            uploadTimestamp = uploadTimestamp,
            originalFilePath = "/data/roster.png",
            workbookFilePath = "/data/roster.xlsx",
            workbookFileUri = null,
            processingStatus = "COMPLETED",
            floorExtracted = "Floor 2",
            daysCount = 30
        )
    }

    private fun createSampleSchedule(
        year: Int,
        month: Int,
        count: Int
    ): List<DailyScheduleEntry> {
        val shiftCodes = listOf("M", "E", "N", "OFF", "A")
        return (1..count).map { day ->
            val code = shiftCodes[(day - 1) % shiftCodes.size]
            val shift = ShiftType.fromCode(code)
            DailyScheduleEntry(
                day = day,
                date = "%04d-%02d-%02d".format(year, month, day),
                extractedShift = shift,
                extractedShiftCode = code,
                currentShift = shift,
                currentShiftCode = code,
                extractedFloor = "Floor 2",
                currentFloor = "Floor 2",
                attendance = AttendanceRecord(
                    status = AttendanceStatus.defaultForShift(shift)
                )
            )
        }
    }

    // =========================================================================
    // 1. Current Date Identification
    // =========================================================================
    @Test
    fun test1_currentDateIdentification_highlightsOnlyExactDateInCurrentMonth() {
        val today = LocalDate.of(2026, 11, 7)
        val schedule = createSampleSchedule(2026, 11, 30)

        // Case A: Viewing the current month (Nov 2026) when today is 2026-11-07
        val currentMonthDays = CalendarDomain.buildMonthDays(2026, 11, schedule, today = today)
        assertEquals(30, currentMonthDays.size)

        val todayDays = currentMonthDays.filter { it.isToday }
        assertEquals("Exactly one day must be identified as today in current month", 1, todayDays.size)
        assertEquals(7, todayDays.first().dayOfMonth)
        assertEquals(today, todayDays.first().date)
        assertTrue(todayDays.first().isToday)

        // Verify other days are NOT today
        assertFalse(currentMonthDays[0].isToday) // Nov 1
        assertFalse(currentMonthDays[5].isToday) // Nov 6
        assertFalse(currentMonthDays[7].isToday) // Nov 8

        // Case B: Viewing a historical month (June 2026) when today is 2026-11-07
        val juneSchedule = createSampleSchedule(2026, 6, 30)
        val juneDays = CalendarDomain.buildMonthDays(2026, 6, juneSchedule, today = today)
        assertEquals(30, juneDays.size)

        val juneTodayDays = juneDays.filter { it.isToday }
        assertTrue(
            "No day in a historical month should be marked as today",
            juneTodayDays.isEmpty()
        )
        assertFalse("June 7th must NOT be marked as today when today is Nov 7th", juneDays[6].isToday)
    }

    // =========================================================================
    // 2. Month Boundaries and Year Transitions
    // =========================================================================
    @Test
    fun test2_monthBoundariesAndYearTransitions() {
        // Transition: Jan 2026 previous month -> Dec 2025
        val curYear = 2026
        val curMonth = 1
        val prevMonth = if (curMonth == 1) 12 else curMonth - 1
        val prevYear = if (curMonth == 1) curYear - 1 else curYear
        assertEquals(12, prevMonth)
        assertEquals(2025, prevYear)

        // Transition: Dec 2026 next month -> Jan 2027
        val decYear = 2026
        val decMonth = 12
        val nextMonth = if (decMonth == 12) 1 else decMonth + 1
        val nextYear = if (decMonth == 12) decYear + 1 else decYear
        assertEquals(1, nextMonth)
        assertEquals(2027, nextYear)

        // Weekday alignment verification across transitions
        // 2026-01-01 was Thursday (offset 3: Mon=0, Tue=1, Wed=2, Thu=3)
        assertEquals(3, CalendarDomain.getFirstDayOfWeekOffset(2026, 1))

        // 2025-12-01 was Monday (offset 0)
        assertEquals(0, CalendarDomain.getFirstDayOfWeekOffset(2025, 12))

        // 2026-12-01 was Tuesday (offset 1)
        assertEquals(1, CalendarDomain.getFirstDayOfWeekOffset(2026, 12))

        // 2027-01-01 was Friday (offset 4)
        assertEquals(4, CalendarDomain.getFirstDayOfWeekOffset(2027, 1))
    }

    // =========================================================================
    // 3. Leap-Year February
    // =========================================================================
    @Test
    fun test3_leapYearFebruaryCalculations() {
        // Standard leap years (divisible by 4, not century)
        assertEquals("Feb 2024 has 29 days", 29, CalendarDomain.getDaysInMonth(2024, 2))
        assertEquals("Feb 2028 has 29 days", 29, CalendarDomain.getDaysInMonth(2028, 2))

        // Century leap year (divisible by 400)
        assertEquals("Feb 2000 has 29 days", 29, CalendarDomain.getDaysInMonth(2000, 2))

        // Century non-leap year (divisible by 100 but not 400)
        assertEquals("Feb 1900 has 28 days", 28, CalendarDomain.getDaysInMonth(1900, 2))
        assertEquals("Feb 2100 has 28 days", 28, CalendarDomain.getDaysInMonth(2100, 2))

        // Non-leap years
        assertEquals("Feb 2023 has 28 days", 28, CalendarDomain.getDaysInMonth(2023, 2))
        assertEquals("Feb 2025 has 28 days", 28, CalendarDomain.getDaysInMonth(2025, 2))
        assertEquals("Feb 2026 has 28 days", 28, CalendarDomain.getDaysInMonth(2026, 2))
    }

    // =========================================================================
    // 4. Calendar Date Rendering for 28, 29, 30 and 31-Day Months
    // =========================================================================
    @Test
    fun test4_calendarDateRenderingForVariousMonthLengths() {
        // 28-day month (Feb 2025)
        val feb2025Days = CalendarDomain.buildMonthDays(2025, 2, emptyList())
        assertEquals(28, feb2025Days.size)
        assertEquals(1, feb2025Days.first().dayOfMonth)
        assertEquals(28, feb2025Days.last().dayOfMonth)
        assertEquals(LocalDate.of(2025, 2, 28), feb2025Days.last().date)

        // 29-day month (Feb 2024)
        val feb2024Days = CalendarDomain.buildMonthDays(2024, 2, emptyList())
        assertEquals(29, feb2024Days.size)
        assertEquals(29, feb2024Days.last().dayOfMonth)
        assertEquals(LocalDate.of(2024, 2, 29), feb2024Days.last().date)

        // 30-day month (April 2026)
        val apr2026Days = CalendarDomain.buildMonthDays(2026, 4, emptyList())
        assertEquals(30, apr2026Days.size)
        assertEquals(30, apr2026Days.last().dayOfMonth)

        // 31-day month (May 2026)
        val may2026Days = CalendarDomain.buildMonthDays(2026, 5, emptyList())
        assertEquals(31, may2026Days.size)
        assertEquals(31, may2026Days.last().dayOfMonth)

        // Verify every day sequentially numbered from 1 to N
        may2026Days.forEachIndexed { index, calendarDay ->
            assertEquals(index + 1, calendarDay.dayOfMonth)
            assertEquals(LocalDate.of(2026, 5, index + 1), calendarDay.date)
        }
    }

    // =========================================================================
    // 5. Today's Schedule Lookup Using Full Dates
    // =========================================================================
    @Test
    fun test5_todayScheduleLookupUsingFullDates() {
        val roster = createSampleRoster(month = 11, year = 2026)
        val schedule = createSampleSchedule(2026, 11, 30)

        val targetDate = LocalDate.of(2026, 11, 7)
        val entry = TodayScheduleResolver.findScheduleEntry(schedule, roster, targetDate)

        assertNotNull("Must find schedule entry for full date 2026-11-07", entry)
        assertEquals("2026-11-07", entry!!.date)
        assertEquals(7, entry.day)

        // Test that a different target date (Nov 15) finds entry for Nov 15
        val entry15 = TodayScheduleResolver.findScheduleEntry(schedule, roster, LocalDate.of(2026, 11, 15))
        assertNotNull(entry15)
        assertEquals("2026-11-15", entry15!!.date)
        assertEquals(15, entry15.day)

        // Schedule entries from October must NOT match when searching for November
        val octSchedule = createSampleSchedule(2026, 10, 31)
        val wrongEntry = TodayScheduleResolver.findScheduleEntry(octSchedule, roster, targetDate)
        assertNull("October schedule entry must not match November target date", wrongEntry)
    }

    // =========================================================================
    // 6. Missing Roster and Missing Schedule Entry
    // =========================================================================
    @Test
    fun test6_missingRosterAndMissingScheduleEntry() {
        // Case A: Missing roster entirely for target month
        val rosters = listOf(
            createSampleRoster(id = "r-sep", month = 9, year = 2026),
            createSampleRoster(id = "r-oct", month = 10, year = 2026)
        )
        val novDate = LocalDate.of(2026, 11, 7)
        val resultNoRoster = TodayScheduleResolver.resolveTodaySchedule(
            rosters = rosters,
            employeeId = "ABHL341",
            targetDate = novDate,
            scheduleProvider = { emptyList() }
        )

        assertTrue(resultNoRoster is TodayScheduleResult.NoRoster)
        val noRoster = resultNoRoster as TodayScheduleResult.NoRoster
        assertEquals(novDate, noRoster.date)
        assertTrue(noRoster.reason.contains("No saved roster for November 2026"))

        // Case B: Roster exists, but schedule entry for that day is missing (e.g. truncated schedule)
        val novRoster = createSampleRoster(id = "r-nov", month = 11, year = 2026)
        val partialSchedule = createSampleSchedule(2026, 11, 5) // Only days 1-5

        val resultNoEntry = TodayScheduleResolver.resolveTodaySchedule(
            rosters = listOf(novRoster),
            employeeId = "ABHL341",
            targetDate = novDate, // Nov 7
            scheduleProvider = { partialSchedule }
        )

        assertTrue(resultNoEntry is TodayScheduleResult.NoEntryInRoster)
        val noEntry = resultNoEntry as TodayScheduleResult.NoEntryInRoster
        assertEquals(novRoster.id, noEntry.roster.id)
        assertTrue(noEntry.reason.contains("No schedule entry found for 7 November 2026"))
    }

    // =========================================================================
    // 7. Multiple Saved Rosters and Correct Applicable-Roster Selection
    // =========================================================================
    @Test
    fun test7_multipleSavedRosters_selectsCorrectApplicableRoster() {
        val employeeId = "ABHL341"
        val targetDate = LocalDate.of(2026, 11, 7)

        val rosters = listOf(
            // Historical month
            createSampleRoster(id = "r-oct", month = 10, year = 2026, version = 1, status = "ARCHIVED", uploadTimestamp = 1000L),
            // Older version of target month
            createSampleRoster(id = "r-nov-v1", month = 11, year = 2026, version = 1, status = "ARCHIVED", uploadTimestamp = 2000L),
            // Correct active version of target month
            createSampleRoster(id = "r-nov-v2", month = 11, year = 2026, version = 2, status = "ACTIVE", uploadTimestamp = 3000L),
            // Future month with most recent upload timestamp!
            createSampleRoster(id = "r-dec-v1", month = 12, year = 2026, version = 1, status = "UPCOMING", uploadTimestamp = 4000L),
            // Different employee with active roster in same month
            createSampleRoster(id = "r-nov-other", employeeId = "OTHER999", month = 11, year = 2026, version = 1, status = "ACTIVE", uploadTimestamp = 5000L)
        )

        val applicable = TodayScheduleResolver.findApplicableRoster(rosters, employeeId, targetDate)
        assertNotNull(applicable)
        assertEquals("Must select the ACTIVE v2 roster for Nov 2026", "r-nov-v2", applicable!!.id)
        assertEquals(2, applicable.version)
        assertEquals("ACTIVE", applicable.status)
        assertEquals(11, applicable.month)
        assertEquals(2026, applicable.year)

        // Must NOT select the December roster even though it has a higher upload timestamp (4000L)
        assertNotEquals("r-dec-v1", applicable.id)
        // Must NOT select other employee's roster
        assertNotEquals("r-nov-other", applicable.id)
    }

    // =========================================================================
    // 8. Shift/Floor Edits Persisting Correctly
    // =========================================================================
    @Test
    fun test8_shiftAndFloorEditsPreserveOriginalOcrAndRecordHistory() {
        val original = DailyScheduleEntry(
            day = 7,
            date = "2026-11-07",
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M",
            extractedFloor = "Floor 2",
            currentFloor = "Floor 2",
            attendance = AttendanceRecord(status = AttendanceStatus.NOT_MARKED)
        )

        // 1. User edits shift from M to N
        val withShiftEdit = original.updateSchedule(
            newCode = "N",
            source = ShiftChangeSource.USER_EDIT,
            author = "Dr. Smith",
            reason = "Night emergency cover"
        )

        assertEquals("Original extracted shift code must be permanently preserved", "M", withShiftEdit.extractedShiftCode)
        assertEquals("Current shift must update to NIGHT", ShiftType.NIGHT, withShiftEdit.currentShift)
        assertEquals("N", withShiftEdit.currentShiftCode)
        assertTrue(withShiftEdit.isModified)
        assertEquals(1, withShiftEdit.changeHistory.size)
        assertEquals("Dr. Smith", withShiftEdit.changeHistory[0].author)
        assertEquals("Night emergency cover", withShiftEdit.changeHistory[0].reason)

        // 2. User edits floor from Floor 2 to ICU
        val withFloorEdit = withShiftEdit.updateFloor(
            newFloor = "ICU",
            author = "Dr. Smith"
        )

        assertEquals("Original extracted floor must be permanently preserved", "Floor 2", withFloorEdit.extractedFloor)
        assertEquals("Current floor must update to ICU", "ICU", withFloorEdit.currentFloor)
        assertTrue(withFloorEdit.isFloorModified)

        // Planned shift remains N
        assertEquals(ShiftType.NIGHT, withFloorEdit.currentShift)
        assertEquals("N", withFloorEdit.currentShiftCode)
    }

    // =========================================================================
    // 9. Attendance Remaining Independent from Planned Shift Edits
    // =========================================================================
    @Test
    fun test9_attendanceRemainsIndependentFromPlannedShiftEdits() {
        val entryWithAttendance = DailyScheduleEntry(
            day = 7,
            date = "2026-11-07",
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M",
            extractedFloor = "Floor 2",
            currentFloor = "Floor 2",
            attendance = AttendanceRecord(
                status = AttendanceStatus.PRESENT,
                notes = "Arrived on time for morning round"
            )
        )

        // 1. Edit planned shift from M to E
        val afterShiftEdit = entryWithAttendance.updateSchedule(
            newCode = "E",
            source = ShiftChangeSource.USER_EDIT,
            author = "Supervisor"
        )

        assertEquals(ShiftType.EVENING, afterShiftEdit.currentShift)
        assertEquals(
            "Attendance status must remain PRESENT after planned shift edit",
            AttendanceStatus.PRESENT,
            afterShiftEdit.attendance.status
        )
        assertEquals(
            "Attendance notes must be preserved",
            "Arrived on time for morning round",
            afterShiftEdit.attendance.notes
        )

        // 2. Edit attendance from PRESENT to ABSENT
        val afterAttendanceEdit = afterShiftEdit.updateAttendance(
            newStatus = AttendanceStatus.ABSENT,
            notes = "Called in sick",
            author = "Supervisor"
        )

        assertEquals(
            "Planned shift must remain EVENING after attendance update",
            ShiftType.EVENING,
            afterAttendanceEdit.currentShift
        )
        assertEquals("E", afterAttendanceEdit.currentShiftCode)
        assertEquals(AttendanceStatus.ABSENT, afterAttendanceEdit.attendance.status)
        assertEquals("Called in sick", afterAttendanceEdit.attendance.notes)
    }

    // =========================================================================
    // 10. Dashboard Refresh After a Date Change
    // =========================================================================
    @Test
    fun test10_dashboardRefreshAfterDateChange() {
        val rosters = listOf(
            createSampleRoster(id = "r-nov", month = 11, year = 2026, version = 1, status = "ACTIVE"),
            createSampleRoster(id = "r-dec", month = 12, year = 2026, version = 1, status = "ACTIVE")
        )

        val novSchedule = createSampleSchedule(2026, 11, 30)
        val decSchedule = createSampleSchedule(2026, 12, 31)

        val scheduleMap = mapOf(
            "r-nov" to novSchedule,
            "r-dec" to decSchedule
        )

        // State on Day 1: Nov 7, 2026
        val clockDay1 = Clock.fixed(Instant.parse("2026-11-07T08:00:00Z"), ZoneOffset.UTC)
        val today1 = LocalDate.now(clockDay1)

        val result1 = TodayScheduleResolver.resolveTodaySchedule(
            rosters = rosters,
            employeeId = "ABHL341",
            targetDate = today1,
            scheduleProvider = { scheduleMap[it] ?: emptyList() }
        )
        assertTrue(result1 is TodayScheduleResult.Success)
        val success1 = result1 as TodayScheduleResult.Success
        assertEquals(7, success1.entry.day)
        assertEquals("2026-11-07", success1.entry.date)
        assertEquals("r-nov", success1.roster.id)

        // State after midnight: Nov 8, 2026
        val clockDay2 = Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"), ZoneOffset.UTC)
        val today2 = LocalDate.now(clockDay2)

        val result2 = TodayScheduleResolver.resolveTodaySchedule(
            rosters = rosters,
            employeeId = "ABHL341",
            targetDate = today2,
            scheduleProvider = { scheduleMap[it] ?: emptyList() }
        )
        assertTrue(result2 is TodayScheduleResult.Success)
        val success2 = result2 as TodayScheduleResult.Success
        assertEquals(8, success2.entry.day)
        assertEquals("2026-11-08", success2.entry.date)
        assertEquals("r-nov", success2.roster.id)

        // State on Month Transition: Dec 1, 2026
        val clockDec1 = Clock.fixed(Instant.parse("2026-12-01T09:00:00Z"), ZoneOffset.UTC)
        val todayDec1 = LocalDate.now(clockDec1)

        val result3 = TodayScheduleResolver.resolveTodaySchedule(
            rosters = rosters,
            employeeId = "ABHL341",
            targetDate = todayDec1,
            scheduleProvider = { scheduleMap[it] ?: emptyList() }
        )
        assertTrue(result3 is TodayScheduleResult.Success)
        val success3 = result3 as TodayScheduleResult.Success
        assertEquals(1, success3.entry.day)
        assertEquals("2026-12-01", success3.entry.date)
        assertEquals("r-dec", success3.roster.id)
        assertEquals(12, success3.roster.month)
    }
}

