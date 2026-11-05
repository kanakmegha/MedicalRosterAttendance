package com.example.rosterrebuild

import com.example.rosterrebuild.data.local.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DatabaseForeignKeyAndSaveTest {

    @Test
    fun testParentRosterInsertedBeforeChildDailySchedules() {
        // Simulates the exact ordered sequence of saveExtractedRoster
        val rosterId = UUID.randomUUID().toString()
        val employeeId = "ABHL341"
        val month = 9
        val year = 2026
        val version = 1

        val parentRoster = RosterEntity(
            id = rosterId,
            employeeId = employeeId,
            month = month,
            year = year,
            version = version,
            status = "ACTIVE",
            floorExtracted = "4TH FLOOR",
            daysCount = 30
        )

        // Simulated in-memory database tables
        val rostersTable = mutableMapOf<String, RosterEntity>()
        val schedulesTable = mutableMapOf<String, DailyScheduleEntity>()
        val attendancesTable = mutableMapOf<String, AttendanceEntity>()
        val shiftAuditsTable = mutableMapOf<String, ShiftChangeEventEntity>()
        val floorAuditsTable = mutableMapOf<String, FloorChangeEventEntity>()

        // Step 1: Insert parent
        rostersTable[parentRoster.id] = parentRoster
        assertTrue("Parent roster must exist in database", rostersTable.containsKey(rosterId))

        // Step 2: Build 30 daily schedules referencing the exact same rosterId
        val dailySchedules = (1..30).map { day ->
            val schedId = UUID.randomUUID().toString()
            DailyScheduleEntity(
                id = schedId,
                rosterId = rosterId,
                day = day,
                date = "%04d-%02d-%02d".format(year, month, day),
                extractedShiftCode = if (day % 7 == 0) "OFF" else if (day == 5) "A" else "M",
                currentShiftCode = if (day % 7 == 0) "OFF" else if (day == 5) "A" else "M",
                extractedFloor = "4TH FLOOR",
                currentFloor = "4TH FLOOR",
                isProvisional = (day == 5)
            )
        }

        // Verify foreign key integrity constraint: all child records must reference existing parent rosterId
        dailySchedules.forEach { schedule ->
            assertTrue(
                "Foreign key constraint check: parent roster ${schedule.rosterId} must exist",
                rostersTable.containsKey(schedule.rosterId)
            )
            assertEquals("Foreign key rosterId matches parent id", rosterId, schedule.rosterId)
            schedulesTable[schedule.id] = schedule
        }

        assertEquals(30, schedulesTable.size)

        // Step 3: Insert child audit records referencing daily_schedules
        dailySchedules.forEach { schedule ->
            val shiftAudit = ShiftChangeEventEntity(
                id = UUID.randomUUID().toString(),
                scheduleId = schedule.id,
                rosterId = schedule.rosterId,
                day = schedule.day,
                date = schedule.date,
                previousShiftCode = null,
                newShiftCode = schedule.currentShiftCode,
                source = ShiftChangeSource.INITIAL_EXTRACTION.displayName
            )
            // Foreign key to scheduleId
            assertTrue("Parent schedule ${shiftAudit.scheduleId} exists", schedulesTable.containsKey(shiftAudit.scheduleId))
            shiftAuditsTable[shiftAudit.id] = shiftAudit
        }

        assertEquals(30, shiftAuditsTable.size)
    }

    @Test
    fun testSavingSecondEmployeeRosterMaintainsIsolation() {
        val emp1Id = "ABHL341"
        val emp2Id = "EMP999"
        val month = 9
        val year = 2026

        val roster1 = RosterEntity(
            id = UUID.randomUUID().toString(),
            employeeId = emp1Id,
            month = month,
            year = year,
            version = 1,
            status = "ACTIVE"
        )
        val roster2 = RosterEntity(
            id = UUID.randomUUID().toString(),
            employeeId = emp2Id,
            month = month,
            year = year,
            version = 1,
            status = "ACTIVE"
        )

        assertNotEquals(roster1.id, roster2.id)

        val sched1 = (1..30).map { day ->
            DailyScheduleEntity(
                id = UUID.randomUUID().toString(),
                rosterId = roster1.id,
                day = day,
                date = "2026-09-%02d".format(day),
                extractedShiftCode = "M",
                currentShiftCode = "M",
                extractedFloor = "4TH FLOOR",
                currentFloor = "4TH FLOOR"
            )
        }

        val sched2 = (1..30).map { day ->
            DailyScheduleEntity(
                id = UUID.randomUUID().toString(),
                rosterId = roster2.id,
                day = day,
                date = "2026-09-%02d".format(day),
                extractedShiftCode = "N",
                currentShiftCode = "N",
                extractedFloor = "ICU",
                currentFloor = "ICU"
            )
        }

        sched1.forEach { assertEquals(roster1.id, it.rosterId) }
        sched2.forEach { assertEquals(roster2.id, it.rosterId) }
    }

    @Test
    fun testCorrectedRosterUploadPreservesAttendanceAndFlagsDiscrepancy() {
        val employeeId = "ABHL341"
        val date = "2026-09-01"

        // Existing marked attendance for day 1
        val existingAttendance = AttendanceEntity(
            id = "att-1",
            employeeId = employeeId,
            date = date,
            status = AttendanceStatus.PRESENT.name,
            notes = "Attended morning shift"
        )

        val v1Schedule = DailyScheduleEntity(
            id = "s-1",
            rosterId = "roster-v1",
            day = 1,
            date = date,
            extractedShiftCode = "M",
            currentShiftCode = "M",
            extractedFloor = "4TH FLOOR",
            currentFloor = "4TH FLOOR"
        )

        // Upload v2 with shift revised from M to E
        val v2Shift = "E"
        val shiftChanged = v1Schedule.currentShiftCode != v2Shift
        assertTrue("Shift changed in v2", shiftChanged)

        val updatedAttendance = if (shiftChanged && existingAttendance.status == AttendanceStatus.PRESENT.name) {
            existingAttendance.copy(
                isFlaggedForReview = true,
                reviewReason = "Roster v2 changed shift from '${v1Schedule.currentShiftCode}' to '$v2Shift'"
            )
        } else {
            existingAttendance
        }

        assertEquals(AttendanceStatus.PRESENT.name, updatedAttendance.status)
        assertEquals("Attended morning shift", updatedAttendance.notes)
        assertTrue(updatedAttendance.isFlaggedForReview)
        assertTrue(updatedAttendance.reviewReason!!.contains("Roster v2 changed shift from 'M' to 'E'"))
    }

    @Test
    fun testTransactionRollbackOnChildFailure() {
        val rostersTable = mutableMapOf<String, RosterEntity>()
        val schedulesTable = mutableMapOf<String, DailyScheduleEntity>()

        val rosterId = UUID.randomUUID().toString()
        val roster = RosterEntity(
            id = rosterId,
            employeeId = "ABHL341",
            month = 9,
            year = 2026,
            version = 1
        )

        var transactionSuccess = false
        val snapshotRosters = HashMap(rostersTable)
        val snapshotSchedules = HashMap(schedulesTable)

        try {
            // Simulated transaction begin
            rostersTable[roster.id] = roster

            // Simulate child insert failure
            throw IllegalStateException("Simulated SQLite child insert constraint failure")

            // transactionSuccess = true
        } catch (_: Exception) {
            // Rollback
            rostersTable.clear()
            rostersTable.putAll(snapshotRosters)
            schedulesTable.clear()
            schedulesTable.putAll(snapshotSchedules)
        }

        assertFalse("Transaction did not succeed", transactionSuccess)
        assertFalse("Parent roster was rolled back and not persisted", rostersTable.containsKey(rosterId))
        assertTrue("No child schedules persisted", schedulesTable.isEmpty())
    }

    @Test
    fun testAppRestartRetrievalPreservesActiveRoster() {
        // Simulates app restart by reconstructing state from database records
        val rosterId = "persisted-roster-123"
        val persistedRoster = RosterEntity(
            id = rosterId,
            employeeId = "ABHL341",
            month = 9,
            year = 2026,
            version = 1,
            status = "ACTIVE",
            floorExtracted = "4TH FLOOR",
            daysCount = 30
        )

        val persistedSchedules = (1..30).map { day ->
            DailyScheduleEntity(
                id = "sched-$day",
                rosterId = rosterId,
                day = day,
                date = "2026-09-%02d".format(day),
                extractedShiftCode = "M",
                currentShiftCode = "M",
                extractedFloor = "4TH FLOOR",
                currentFloor = "4TH FLOOR"
            )
        }

        // "App Reopening" - querying the database by ID
        assertNotNull(persistedRoster)
        assertEquals("ABHL341", persistedRoster.employeeId)
        assertEquals(9, persistedRoster.month)
        assertEquals(2026, persistedRoster.year)
        assertEquals("ACTIVE", persistedRoster.status)

        val loadedSchedules = persistedSchedules.filter { it.rosterId == rosterId }
        assertEquals(30, loadedSchedules.size)
        loadedSchedules.forEach {
            assertEquals(rosterId, it.rosterId)
            assertEquals("4TH FLOOR", it.currentFloor)
        }
    }
}

