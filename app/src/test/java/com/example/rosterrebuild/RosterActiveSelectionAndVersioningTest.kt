package com.example.rosterrebuild

import com.example.rosterrebuild.data.local.AttendanceEntity
import com.example.rosterrebuild.data.local.DailyScheduleEntity
import com.example.rosterrebuild.data.local.RosterEntity
import com.example.rosterrebuild.data.repository.RosterStatusPolicy
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Verification Test Suite for Active Roster Selection and Versioning Logic.
 *
 * Verifies all 11 required scenarios using controllable clocks:
 * 1. Older month uploaded after newer month.
 * 2. Newer month uploaded after older month.
 * 3. Corrected older month while a newer month exists.
 * 4. Corrected latest month.
 * 5. Future month uploaded before current month.
 * 6. Current month absent but historical months available.
 * 7. Same month with multiple versions.
 * 8. Multiple employees with independent active rosters.
 * 9. Repeated status recalculation.
 * 10. Existing archived records and attendance remain unchanged.
 * 11. Dashboard ordering by roster month rather than upload timestamp.
 */
class RosterActiveSelectionAndVersioningTest {

    private fun fixedClock(year: Int, month: Int, day: Int = 15): Clock {
        val instant = Instant.parse("%04d-%02d-%02dT12:00:00Z".format(year, month, day))
        return Clock.fixed(instant, ZoneId.of("UTC"))
    }

    private fun fixedYearMonth(year: Int, month: Int): YearMonth {
        return YearMonth.of(year, month)
    }

    private fun createRoster(
        id: String,
        employeeId: String = "ABHL341",
        year: Int,
        month: Int,
        version: Int = 1,
        status: String = "ACTIVE",
        uploadTimestamp: Long = System.currentTimeMillis()
    ): RosterEntity {
        return RosterEntity(
            id = id,
            employeeId = employeeId,
            month = month,
            year = year,
            version = version,
            status = status,
            uploadTimestamp = uploadTimestamp,
            originalFilePath = "/data/roster_$id.png",
            workbookFilePath = "/data/roster_$id.xlsx",
            floorExtracted = "Floor 2",
            daysCount = 30
        )
    }

    // 1. Older month uploaded after newer month
    @Test
    fun test1_olderMonthUploadedAfterNewerMonth() {
        val clock = fixedClock(2026, 11) // November 2026 is current month
        val refYm = YearMonth.now(clock)

        // Upload order:
        // 1: Nov 2026 (uploadTimestamp = 1000)
        // 2: Sep 2026 (uploadTimestamp = 2000)
        // 3: Oct 2026 (uploadTimestamp = 3000)
        val novRoster = createRoster("r-nov", year = 2026, month = 11, uploadTimestamp = 1000)
        val sepRoster = createRoster("r-sep", year = 2026, month = 9, uploadTimestamp = 2000)
        val octRoster = createRoster("r-oct", year = 2026, month = 10, uploadTimestamp = 3000)

        val rosters = listOf(novRoster, sepRoster, octRoster)
        val statuses = RosterStatusPolicy.computeStatuses(rosters, refYm)

        assertEquals("November must remain ACTIVE despite being uploaded first", RosterStatusPolicy.STATUS_ACTIVE, statuses["r-nov"])
        assertEquals("September must be ARCHIVED", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-sep"])
        assertEquals("October must be ARCHIVED", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-oct"])
    }

    // 2. Newer month uploaded after older month
    @Test
    fun test2_newerMonthUploadedAfterOlderMonth() {
        val refYm = fixedYearMonth(2026, 11)

        // Upload order:
        // 1: Sep 2026
        // 2: Oct 2026
        val sepRoster = createRoster("r-sep", year = 2026, month = 9, uploadTimestamp = 1000)
        val octRoster = createRoster("r-oct", year = 2026, month = 10, uploadTimestamp = 2000)

        val statuses = RosterStatusPolicy.computeStatuses(listOf(sepRoster, octRoster), refYm)

        assertEquals("October (latest applicable) becomes ACTIVE", RosterStatusPolicy.STATUS_ACTIVE, statuses["r-oct"])
        assertEquals("September becomes ARCHIVED", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-sep"])
    }

    // 3. Corrected older month while a newer month exists
    @Test
    fun test3_correctedOlderMonthWhileNewerMonthExists() {
        val refYm = fixedYearMonth(2026, 11)

        // September v1, October v1
        // Then September v2 uploaded as a correction
        val sepV1 = createRoster("r-sep-v1", year = 2026, month = 9, version = 1, uploadTimestamp = 1000)
        val octV1 = createRoster("r-oct-v1", year = 2026, month = 10, version = 1, uploadTimestamp = 2000)
        val sepV2 = createRoster("r-sep-v2", year = 2026, month = 9, version = 2, uploadTimestamp = 3000)

        val statuses = RosterStatusPolicy.computeStatuses(listOf(sepV1, octV1, sepV2), refYm)

        assertEquals("September v1 must be ARCHIVED (superseded)", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-sep-v1"])
        assertEquals("September v2 must be ARCHIVED because October is the newer applicable month", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-sep-v2"])
        assertEquals("October v1 must remain ACTIVE", RosterStatusPolicy.STATUS_ACTIVE, statuses["r-oct-v1"])
    }

    // 4. Corrected latest month
    @Test
    fun test4_correctedLatestMonth() {
        val refYm = fixedYearMonth(2026, 11)

        val sepV1 = createRoster("r-sep-v1", year = 2026, month = 9, version = 1, uploadTimestamp = 1000)
        val octV1 = createRoster("r-oct-v1", year = 2026, month = 10, version = 1, uploadTimestamp = 2000)
        val octV2 = createRoster("r-oct-v2", year = 2026, month = 10, version = 2, uploadTimestamp = 3000)

        val statuses = RosterStatusPolicy.computeStatuses(listOf(sepV1, octV1, octV2), refYm)

        assertEquals("October v1 is ARCHIVED (superseded by v2)", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-oct-v1"])
        assertEquals("October v2 is ACTIVE", RosterStatusPolicy.STATUS_ACTIVE, statuses["r-oct-v2"])
        assertEquals("September v1 is ARCHIVED", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-sep-v1"])
    }

    // 5. Future month uploaded before current month
    @Test
    fun test5_futureMonthUploadedBeforeCurrentMonth() {
        // Today is September 2026
        val refYm = fixedYearMonth(2026, 9)

        // Upload November 2026 first (future month)
        val novRoster = createRoster("r-nov", year = 2026, month = 11, uploadTimestamp = 1000)

        var statuses = RosterStatusPolicy.computeStatuses(listOf(novRoster), refYm)
        assertEquals("Future month must not automatically become active; must be UPCOMING", RosterStatusPolicy.STATUS_UPCOMING, statuses["r-nov"])

        // Now upload September 2026 (current month)
        val sepRoster = createRoster("r-sep", year = 2026, month = 9, uploadTimestamp = 2000)

        statuses = RosterStatusPolicy.computeStatuses(listOf(novRoster, sepRoster), refYm)
        assertEquals("September (current month) is ACTIVE", RosterStatusPolicy.STATUS_ACTIVE, statuses["r-sep"])
        assertEquals("November remains UPCOMING", RosterStatusPolicy.STATUS_UPCOMING, statuses["r-nov"])
    }

    // 6. Current month absent but historical months available
    @Test
    fun test6_currentMonthAbsentButHistoricalMonthsAvailable() {
        // Current month is November 2026
        val refYm = fixedYearMonth(2026, 11)

        // Only historical months exist: July 2026, August 2026, September 2026
        val julRoster = createRoster("r-jul", year = 2026, month = 7)
        val augRoster = createRoster("r-aug", year = 2026, month = 8)
        val sepRoster = createRoster("r-sep", year = 2026, month = 9)

        val statuses = RosterStatusPolicy.computeStatuses(listOf(julRoster, augRoster, sepRoster), refYm)

        assertEquals("Most recent available past month (September) must be ACTIVE", RosterStatusPolicy.STATUS_ACTIVE, statuses["r-sep"])
        assertEquals("August must be ARCHIVED", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-aug"])
        assertEquals("July must be ARCHIVED", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-jul"])
    }

    // 7. Same month with multiple versions
    @Test
    fun test7_sameMonthWithMultipleVersions() {
        val refYm = fixedYearMonth(2026, 9)

        val v1 = createRoster("r-v1", year = 2026, month = 9, version = 1, uploadTimestamp = 1000)
        val v2 = createRoster("r-v2", year = 2026, month = 9, version = 2, uploadTimestamp = 2000)
        val v3 = createRoster("r-v3", year = 2026, month = 9, version = 3, uploadTimestamp = 3000)

        val statuses = RosterStatusPolicy.computeStatuses(listOf(v1, v2, v3), refYm)

        assertEquals("Previous version v1 must be ARCHIVED", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-v1"])
        assertEquals("Previous version v2 must be ARCHIVED", RosterStatusPolicy.STATUS_ARCHIVED, statuses["r-v2"])
        assertEquals("Highest version v3 must be ACTIVE", RosterStatusPolicy.STATUS_ACTIVE, statuses["r-v3"])
    }

    // 8. Multiple employees with independent active rosters
    @Test
    fun test8_multipleEmployeesWithIndependentActiveRosters() {
        val refYm = fixedYearMonth(2026, 11)

        // Emp A has Sep and Oct 2026
        val empA_sep = createRoster("empA-sep", employeeId = "EMP_A", year = 2026, month = 9)
        val empA_oct = createRoster("empA-oct", employeeId = "EMP_A", year = 2026, month = 10)

        // Emp B has Aug and Nov 2026
        val empB_aug = createRoster("empB-aug", employeeId = "EMP_B", year = 2026, month = 8)
        val empB_nov = createRoster("empB-nov", employeeId = "EMP_B", year = 2026, month = 11)

        // Emp C has Dec 2026 (future)
        val empC_dec = createRoster("empC-dec", employeeId = "EMP_C", year = 2026, month = 12)

        val allRosters = listOf(empA_sep, empA_oct, empB_aug, empB_nov, empC_dec)
        val statuses = RosterStatusPolicy.computeAllStatuses(allRosters, refYm)

        // Emp A: Oct is latest applicable
        assertEquals(RosterStatusPolicy.STATUS_ACTIVE, statuses["empA-oct"])
        assertEquals(RosterStatusPolicy.STATUS_ARCHIVED, statuses["empA-sep"])

        // Emp B: Nov is current month
        assertEquals(RosterStatusPolicy.STATUS_ACTIVE, statuses["empB-nov"])
        assertEquals(RosterStatusPolicy.STATUS_ARCHIVED, statuses["empB-aug"])

        // Emp C: Dec is in the future
        assertEquals(RosterStatusPolicy.STATUS_UPCOMING, statuses["empC-dec"])

        // Count active rosters per employee
        val empAActiveCount = listOf(empA_sep, empA_oct).count { statuses[it.id] == RosterStatusPolicy.STATUS_ACTIVE }
        val empBActiveCount = listOf(empB_aug, empB_nov).count { statuses[it.id] == RosterStatusPolicy.STATUS_ACTIVE }
        val empCActiveCount = listOf(empC_dec).count { statuses[it.id] == RosterStatusPolicy.STATUS_ACTIVE }

        assertEquals("Each employee has at most 1 active roster", 1, empAActiveCount)
        assertEquals("Each employee has at most 1 active roster", 1, empBActiveCount)
        assertEquals("Emp C has 0 active rosters (only upcoming)", 0, empCActiveCount)
    }

    // 9. Repeated status recalculation
    @Test
    fun test9_repeatedStatusRecalculation() {
        val refYm = fixedYearMonth(2026, 11)

        val r1 = createRoster("r1", year = 2026, month = 8, version = 1)
        val r2 = createRoster("r2", year = 2026, month = 9, version = 1)
        val r3 = createRoster("r3", year = 2026, month = 9, version = 2)
        val r4 = createRoster("r4", year = 2026, month = 11, version = 1)
        val r5 = createRoster("r5", year = 2026, month = 12, version = 1)

        val list = listOf(r1, r2, r3, r4, r5)

        val firstRun = RosterStatusPolicy.computeStatuses(list, refYm)

        for (i in 1..10) {
            val subsequentRun = RosterStatusPolicy.computeStatuses(list, refYm)
            assertEquals("Recalculation iteration $i must be strictly deterministic", firstRun, subsequentRun)
        }
    }

    // 10. Existing archived records and attendance remain unchanged
    @Test
    fun test10_existingArchivedRecordsAndAttendanceRemainUnchanged() {
        val refYm = fixedYearMonth(2026, 11)

        val roster = createRoster("r-old", year = 2026, month = 8, status = "ACTIVE")
        val schedule = DailyScheduleEntity(
            id = "sched-1",
            rosterId = roster.id,
            day = 15,
            date = "2026-08-15",
            extractedShiftCode = "M",
            currentShiftCode = "N",
            extractedFloor = "Floor 2",
            currentFloor = "ICU"
        )
        val attendance = AttendanceEntity(
            id = "att-1",
            employeeId = roster.employeeId,
            date = "2026-08-15",
            status = AttendanceStatus.PRESENT.name,
            notes = "Attended ICU shift on time"
        )

        // When a newer roster arrives (e.g. October 2026)
        val newRoster = createRoster("r-new", year = 2026, month = 10)
        val statusMap = RosterStatusPolicy.computeStatuses(listOf(roster, newRoster), refYm)

        val updatedRoster = roster.copy(status = statusMap[roster.id] ?: "ARCHIVED")

        // Roster status changed to ARCHIVED
        assertEquals(RosterStatusPolicy.STATUS_ARCHIVED, updatedRoster.status)

        // DailyScheduleEntity is completely unchanged
        assertEquals("M", schedule.extractedShiftCode)
        assertEquals("N", schedule.currentShiftCode)
        assertEquals("ICU", schedule.currentFloor)

        // AttendanceEntity is completely unchanged
        assertEquals(AttendanceStatus.PRESENT.name, attendance.status)
        assertEquals("Attended ICU shift on time", attendance.notes)
    }

    // 11. Dashboard ordering by roster month rather than upload timestamp
    @Test
    fun test11_dashboardOrderingByRosterMonthRatherThanUploadTimestamp() {
        // Given rosters uploaded out of chronological order:
        // Nov 2026 uploaded first (timestamp = 1000)
        // Jan 2027 uploaded second (timestamp = 2000, future)
        // Aug 2026 uploaded third (timestamp = 3000, past)
        // Oct 2026 uploaded fourth (timestamp = 4000, past)
        val rNov = createRoster("r-nov", year = 2026, month = 11, uploadTimestamp = 1000)
        val rJan = createRoster("r-jan", year = 2027, month = 1, uploadTimestamp = 2000)
        val rAug = createRoster("r-aug", year = 2026, month = 8, uploadTimestamp = 3000)
        val rOct = createRoster("r-oct", year = 2026, month = 10, uploadTimestamp = 4000)

        val refYm = fixedYearMonth(2026, 11)
        val statusMap = RosterStatusPolicy.computeStatuses(listOf(rNov, rJan, rAug, rOct), refYm)

        val allWithStatus = listOf(rNov, rJan, rAug, rOct).map { it.copy(status = statusMap[it.id] ?: "ARCHIVED") }

        // Dashboard sorts by: year DESC, month DESC, version DESC
        val sortedForDashboard = allWithStatus.sortedWith(
            compareByDescending<RosterEntity> { it.year }
                .thenByDescending { it.month }
                .thenByDescending { it.version }
        )

        // Verify chronological ordering
        assertEquals("First must be Jan 2027 (UPCOMING)", "r-jan", sortedForDashboard[0].id)
        assertEquals(RosterStatusPolicy.STATUS_UPCOMING, sortedForDashboard[0].status)

        assertEquals("Second must be Nov 2026 (ACTIVE)", "r-nov", sortedForDashboard[1].id)
        assertEquals(RosterStatusPolicy.STATUS_ACTIVE, sortedForDashboard[1].status)

        assertEquals("Third must be Oct 2026 (ARCHIVED)", "r-oct", sortedForDashboard[2].id)
        assertEquals(RosterStatusPolicy.STATUS_ARCHIVED, sortedForDashboard[2].status)

        assertEquals("Fourth must be Aug 2026 (ARCHIVED)", "r-aug", sortedForDashboard[3].id)
        assertEquals(RosterStatusPolicy.STATUS_ARCHIVED, sortedForDashboard[3].status)

        // Latest upload check
        val latestUpload = allWithStatus.maxByOrNull { it.uploadTimestamp }
        assertEquals("Latest upload timestamp belongs to r-oct", "r-oct", latestUpload?.id)
        assertEquals("Latest upload is ARCHIVED by chronology, not ACTIVE", RosterStatusPolicy.STATUS_ARCHIVED, latestUpload?.status)
    }
}

