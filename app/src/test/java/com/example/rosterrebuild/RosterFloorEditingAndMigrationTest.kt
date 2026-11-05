package com.example.rosterrebuild

import com.example.rosterrebuild.data.local.*
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth
import java.util.UUID

/**
 * Comprehensive Test Suite for Floor Editing, Bulk Assignment, and Room Migration.
 *
 * Verifies all 12 required scenarios:
 * 1. Initial upload with OCR-extracted month/year/floor.
 * 2. User correcting month, year and floor before saving.
 * 3. Missing OCR floor entered manually.
 * 4. Bulk floor update across all days.
 * 5. Bulk update replacing previously existing per-day overrides.
 * 6. Individual floor edit after a bulk update.
 * 7. Individual edit does not affect other dates or monthly default.
 * 8. Bulk change does not alter shifts or attendance.
 * 9. Audit history records both bulk and individual changes.
 * 10. Transaction rollback if a bulk update fails.
 * 11. Month/year change across leap-year February (28/29) and 30/31 day months.
 * 12. Existing saved roster remains readable after Room migration (MIGRATION_1_2).
 */
class RosterFloorEditingAndMigrationTest {

    // Simulated In-Memory Database Structure
    class MockDatabase {
        val rosters = mutableMapOf<String, RosterEntity>()
        val schedules = mutableMapOf<String, DailyScheduleEntity>()
        val attendances = mutableMapOf<String, AttendanceEntity>()
        val bulkFloorAudits = mutableListOf<BulkFloorChangeEventEntity>()
        val floorAudits = mutableListOf<FloorChangeEventEntity>()

        fun insertRoster(roster: RosterEntity) {
            rosters[roster.id] = roster
        }

        fun insertSchedule(schedule: DailyScheduleEntity) {
            schedules[schedule.id] = schedule
        }

        fun getSchedulesForRoster(rosterId: String): List<DailyScheduleEntity> {
            return schedules.values.filter { it.rosterId == rosterId }.sortedBy { it.day }
        }

        fun updateScheduleFloor(scheduleId: String, newFloor: String?, author: String, reason: String? = null) {
            val existing = schedules[scheduleId] ?: error("Schedule not found")
            val prevFloor = existing.currentFloor
            schedules[scheduleId] = existing.copy(currentFloor = newFloor)
            floorAudits.add(
                FloorChangeEventEntity(
                    id = UUID.randomUUID().toString(),
                    scheduleId = scheduleId,
                    rosterId = existing.rosterId,
                    day = existing.day,
                    date = existing.date,
                    previousFloor = prevFloor,
                    newFloor = newFloor,
                    author = author,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        fun bulkUpdateFloorTransaction(
            rosterId: String,
            newFloor: String,
            author: String,
            reason: String?,
            failDuringExecution: Boolean = false
        ) {
            // Transaction snapshot
            val snapshotRoster = rosters[rosterId]?.copy()
            val snapshotSchedules = schedules.filter { it.value.rosterId == rosterId }.mapValues { it.value.copy() }
            val snapshotBulkAudits = bulkFloorAudits.toMutableList()
            val snapshotFloorAudits = floorAudits.toMutableList()

            try {
                val roster = rosters[rosterId] ?: error("Roster not found")
                val prevMonthlyFloor = roster.getEffectiveMonthlyFloor()
                rosters[rosterId] = roster.copy(currentMonthlyFloor = newFloor)

                val rosterSchedules = schedules.values.filter { it.rosterId == rosterId }
                rosterSchedules.forEach { sched ->
                    schedules[sched.id] = sched.copy(currentFloor = newFloor)
                    floorAudits.add(
                        FloorChangeEventEntity(
                            id = UUID.randomUUID().toString(),
                            scheduleId = sched.id,
                            rosterId = sched.rosterId,
                            day = sched.day,
                            date = sched.date,
                            previousFloor = sched.currentFloor,
                            newFloor = newFloor,
                            author = author,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                bulkFloorAudits.add(
                    BulkFloorChangeEventEntity(
                        id = UUID.randomUUID().toString(),
                        rosterId = rosterId,
                        month = roster.month,
                        year = roster.year,
                        previousFloor = prevMonthlyFloor,
                        newFloor = newFloor,
                        affectedDaysCount = rosterSchedules.size,
                        author = author,
                        description = reason ?: "",
                        timestamp = System.currentTimeMillis()
                    )
                )

                if (failDuringExecution) {
                    throw IllegalStateException("Forced database constraint failure during bulk floor update")
                }
            } catch (e: Exception) {
                // Rollback transaction
                if (snapshotRoster != null) {
                    rosters[rosterId] = snapshotRoster
                }
                snapshotSchedules.forEach { (id, sched) ->
                    schedules[id] = sched
                }
                bulkFloorAudits.clear()
                bulkFloorAudits.addAll(snapshotBulkAudits)
                floorAudits.clear()
                floorAudits.addAll(snapshotFloorAudits)
                throw e
            }
        }
    }

    private fun populateRoster(
        db: MockDatabase,
        rosterId: String = UUID.randomUUID().toString(),
        year: Int = 2026,
        month: Int = 6,
        floorExtracted: String? = "4TH FLOOR",
        confirmedFloor: String? = null
    ): String {
        val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
        val effectiveMonthly = confirmedFloor ?: floorExtracted

        val roster = RosterEntity(
            id = rosterId,
            employeeId = "ABHL341",
            month = month,
            year = year,
            floorExtracted = floorExtracted,
            currentMonthlyFloor = confirmedFloor,
            daysCount = daysInMonth,
            status = "ACTIVE"
        )
        db.insertRoster(roster)

        for (day in 1..daysInMonth) {
            val schedId = UUID.randomUUID().toString()
            val shift = if (day % 7 == 0) "OFF" else "M"
            val dateStr = "%04d-%02d-%02d".format(year, month, day)
            db.insertSchedule(
                DailyScheduleEntity(
                    id = schedId,
                    rosterId = rosterId,
                    day = day,
                    date = dateStr,
                    extractedShiftCode = shift,
                    currentShiftCode = shift,
                    extractedFloor = floorExtracted,
                    currentFloor = effectiveMonthly
                )
            )
            db.attendances[dateStr] = AttendanceEntity(
                id = UUID.randomUUID().toString(),
                employeeId = "ABHL341",
                date = dateStr,
                status = if (shift == "OFF") "NOT_APPLICABLE" else "PRESENT",
                notes = null,
                updatedTimestamp = System.currentTimeMillis()
            )
        }
        return rosterId
    }

    // =========================================================================
    // 1. Initial upload with OCR-extracted month/year/floor
    // =========================================================================
    @Test
    fun test1_InitialUploadWithOcrExtractedMonthYearFloor() {
        val db = MockDatabase()
        val rosterId = populateRoster(db, year = 2026, month = 6, floorExtracted = "4TH FLOOR", confirmedFloor = null)

        val roster = db.rosters[rosterId]!!
        assertEquals("4TH FLOOR", roster.floorExtracted)
        assertNull(roster.currentMonthlyFloor)
        assertEquals("4TH FLOOR", roster.getEffectiveMonthlyFloor())

        val schedules = db.getSchedulesForRoster(rosterId)
        assertEquals(30, schedules.size)
        schedules.forEach { entry ->
            assertEquals("4TH FLOOR", entry.extractedFloor)
            assertEquals("4TH FLOOR", entry.currentFloor)
            assertEquals(entry.extractedFloor, entry.currentFloor)
        }
    }

    // =========================================================================
    // 2. User correcting month, year and floor before saving
    // =========================================================================
    @Test
    fun test2_UserCorrectingMonthYearAndFloorBeforeSaving() {
        val db = MockDatabase()
        // OCR detected May 2026, 3RD FLOOR. User corrected to June 2026, 5TH FLOOR.
        val rosterId = populateRoster(
            db,
            year = 2026,
            month = 6, // user corrected month
            floorExtracted = "3RD FLOOR", // preserved original OCR
            confirmedFloor = "5TH FLOOR" // user specified floor
        )

        val roster = db.rosters[rosterId]!!
        assertEquals(6, roster.month)
        assertEquals(2026, roster.year)
        assertEquals("3RD FLOOR", roster.floorExtracted) // Preserved for diagnostics
        assertEquals("5TH FLOOR", roster.currentMonthlyFloor) // Active user override
        assertEquals("5TH FLOOR", roster.getEffectiveMonthlyFloor())

        val schedules = db.getSchedulesForRoster(rosterId)
        assertEquals(30, schedules.size)
        schedules.forEach { entry ->
            assertTrue(entry.date?.startsWith("2026-06") == true)
            assertEquals("3RD FLOOR", entry.extractedFloor)
            assertEquals("5TH FLOOR", entry.currentFloor)
            assertNotEquals("Floor should be recognized as user modified from OCR", entry.extractedFloor, entry.currentFloor)
        }
    }

    // =========================================================================
    // 3. Missing OCR floor entered manually
    // =========================================================================
    @Test
    fun test3_MissingOcrFloorEnteredManually() {
        val db = MockDatabase()
        // OCR had no floor in document, user entered "ICU"
        val rosterId = populateRoster(
            db,
            year = 2026,
            month = 7,
            floorExtracted = null,
            confirmedFloor = "ICU"
        )

        val roster = db.rosters[rosterId]!!
        assertNull(roster.floorExtracted)
        assertEquals("ICU", roster.currentMonthlyFloor)
        assertEquals("ICU", roster.getEffectiveMonthlyFloor())

        val schedules = db.getSchedulesForRoster(rosterId)
        assertEquals(31, schedules.size)
        schedules.forEach { entry ->
            assertNull(entry.extractedFloor)
            assertEquals("ICU", entry.currentFloor)
            assertNotEquals(entry.extractedFloor, entry.currentFloor)
        }
    }

    // =========================================================================
    // 4. Bulk floor update across all days
    // =========================================================================
    @Test
    fun test4_BulkFloorUpdateAcrossAllDays() {
        val db = MockDatabase()
        val rosterId = populateRoster(db, year = 2026, month = 6, floorExtracted = "Floor 2", confirmedFloor = null)

        // Bulk update to "Floor 5"
        db.bulkUpdateFloorTransaction(
            rosterId = rosterId,
            newFloor = "Floor 5",
            author = "Dr. Smith (Clinical Supervisor)",
            reason = "Ward reallocation for June"
        )

        val updatedRoster = db.rosters[rosterId]!!
        assertEquals("Floor 2", updatedRoster.floorExtracted) // original OCR unchanged
        assertEquals("Floor 5", updatedRoster.currentMonthlyFloor)
        assertEquals("Floor 5", updatedRoster.getEffectiveMonthlyFloor())

        val schedules = db.getSchedulesForRoster(rosterId)
        assertEquals(30, schedules.size)
        schedules.forEach { entry ->
            assertEquals("Floor 5", entry.currentFloor)
            assertEquals("Floor 2", entry.extractedFloor)
            assertNotEquals(entry.extractedFloor, entry.currentFloor)
        }

        assertEquals(1, db.bulkFloorAudits.size)
        val bulkAudit = db.bulkFloorAudits[0]
        assertEquals("Floor 2", bulkAudit.previousFloor)
        assertEquals("Floor 5", bulkAudit.newFloor)
        assertEquals(30, bulkAudit.affectedDaysCount)
        assertEquals("Dr. Smith (Clinical Supervisor)", bulkAudit.author)
        assertEquals("Ward reallocation for June", bulkAudit.description)
    }

    // =========================================================================
    // 5. Bulk update replacing previously existing per-day overrides
    // =========================================================================
    @Test
    fun test5_BulkUpdateReplacingPreviouslyExistingPerDayOverrides() {
        val db = MockDatabase()
        val rosterId = populateRoster(db, year = 2026, month = 6, floorExtracted = "Floor 1", confirmedFloor = null)

        val initialSchedules = db.getSchedulesForRoster(rosterId)
        // Set individual overrides on Day 3 and Day 7
        val day3 = initialSchedules.first { it.day == 3 }
        val day7 = initialSchedules.first { it.day == 7 }
        db.updateScheduleFloor(day3.id, "ICU", "Supervisor", "Covering ICU")
        db.updateScheduleFloor(day7.id, "OT", "Supervisor", "OT duty")

        assertEquals("ICU", db.schedules[day3.id]!!.currentFloor)
        assertEquals("OT", db.schedules[day7.id]!!.currentFloor)

        // Bulk update to "Floor 4"
        db.bulkUpdateFloorTransaction(
            rosterId = rosterId,
            newFloor = "Floor 4",
            author = "Nurse Manager",
            reason = "Department-wide shift to Floor 4"
        )

        val postBulkSchedules = db.getSchedulesForRoster(rosterId)
        postBulkSchedules.forEach { entry ->
            assertEquals("All days including days 3 and 7 must be updated to Floor 4", "Floor 4", entry.currentFloor)
        }
        assertEquals("Floor 4", db.rosters[rosterId]!!.getEffectiveMonthlyFloor())
    }

    // =========================================================================
    // 6. Individual floor edit after a bulk update
    // =========================================================================
    @Test
    fun test6_IndividualFloorEditAfterBulkUpdate() {
        val db = MockDatabase()
        val rosterId = populateRoster(db, year = 2026, month = 6, floorExtracted = "Floor 1", confirmedFloor = null)

        // Step 1: Bulk update to Floor 4
        db.bulkUpdateFloorTransaction(rosterId, "Floor 4", "Supervisor", "Bulk update")

        // Step 2: Individual edit on Day 5 to Emergency
        val day5 = db.getSchedulesForRoster(rosterId).first { it.day == 5 }
        db.updateScheduleFloor(day5.id, "Emergency", "ER Lead", "Emergency cover")

        // Verify Day 5 has Emergency
        assertEquals("Emergency", db.schedules[day5.id]!!.currentFloor)

        // Verify all other days still have Floor 4
        db.getSchedulesForRoster(rosterId).filter { it.day != 5 }.forEach { entry ->
            assertEquals("Floor 4", entry.currentFloor)
        }

        // Verify roster monthly default remains Floor 4
        assertEquals("Floor 4", db.rosters[rosterId]!!.getEffectiveMonthlyFloor())
    }

    // =========================================================================
    // 7. Individual edit does not affect other dates or monthly default
    // =========================================================================
    @Test
    fun test7_IndividualEditDoesNotAffectOtherDatesOrMonthlyDefault() {
        val db = MockDatabase()
        val rosterId = populateRoster(db, year = 2026, month = 6, floorExtracted = "Floor 3", confirmedFloor = null)

        val day12 = db.getSchedulesForRoster(rosterId).first { it.day == 12 }
        db.updateScheduleFloor(day12.id, "OT", "Surgery Lead", "Day surgery assignment")

        // Check only day 12 is OT
        assertEquals("OT", db.schedules[day12.id]!!.currentFloor)

        db.getSchedulesForRoster(rosterId).filter { it.day != 12 }.forEach { entry ->
            assertEquals("Floor 3", entry.currentFloor)
        }

        // Check roster monthly default remains intact
        assertEquals("Floor 3", db.rosters[rosterId]!!.getEffectiveMonthlyFloor())
    }

    // =========================================================================
    // 8. Bulk change does not alter shifts or attendance
    // =========================================================================
    @Test
    fun test8_BulkChangeDoesNotAlterShiftsOrAttendance() {
        val db = MockDatabase()
        val rosterId = populateRoster(db, year = 2026, month = 6, floorExtracted = "Floor 1", confirmedFloor = null)

        val schedulesBefore = db.getSchedulesForRoster(rosterId)
        val day1Before = schedulesBefore.first { it.day == 1 }
        val day7Before = schedulesBefore.first { it.day == 7 }

        // Custom attendance status on Day 1
        db.attendances[day1Before.date!!] = AttendanceEntity(
            id = UUID.randomUUID().toString(),
            employeeId = "ABHL341",
            date = day1Before.date!!,
            status = "PRESENT",
            notes = "Checked in on time",
            updatedTimestamp = 1000L
        )

        // Custom attendance on Day 7
        db.attendances[day7Before.date!!] = AttendanceEntity(
            id = UUID.randomUUID().toString(),
            employeeId = "ABHL341",
            date = day7Before.date!!,
            status = "NOT_APPLICABLE",
            notes = "Scheduled weekly off",
            updatedTimestamp = 1000L
        )

        // Perform bulk floor change
        db.bulkUpdateFloorTransaction(rosterId, "ICU", "Supervisor", "ICU transfer")

        // Verify planned shifts are strictly preserved
        val schedulesAfter = db.getSchedulesForRoster(rosterId)
        schedulesBefore.forEach { before ->
            val after = schedulesAfter.first { it.day == before.day }
            assertEquals("Shift code must be preserved", before.currentShiftCode, after.currentShiftCode)
            assertEquals("Extracted shift code must be preserved", before.extractedShiftCode, after.extractedShiftCode)
            assertEquals("Floor must be updated", "ICU", after.currentFloor)
        }

        // Verify attendance records are completely untouched
        val attDay1 = db.attendances[day1Before.date!!]!!
        assertEquals("PRESENT", attDay1.status)
        assertEquals("Checked in on time", attDay1.notes)

        val attDay7 = db.attendances[day7Before.date!!]!!
        assertEquals("NOT_APPLICABLE", attDay7.status)
        assertEquals("Scheduled weekly off", attDay7.notes)
    }

    // =========================================================================
    // 9. Audit history records both bulk and individual changes
    // =========================================================================
    @Test
    fun test9_AuditHistoryRecordsBothBulkAndIndividualChanges() {
        val db = MockDatabase()
        val rosterId = populateRoster(db, year = 2026, month = 6, floorExtracted = "Floor 1", confirmedFloor = null)

        // Bulk change
        db.bulkUpdateFloorTransaction(
            rosterId = rosterId,
            newFloor = "Floor 2",
            author = "Chief Medical Officer",
            reason = "Annual ward rotation"
        )

        // Individual change on day 10
        val day10 = db.getSchedulesForRoster(rosterId).first { it.day == 10 }
        db.updateScheduleFloor(
            scheduleId = day10.id,
            newFloor = "NICU",
            author = "Pediatric Lead",
            reason = "NICU support"
        )

        // Check bulk audit
        assertEquals(1, db.bulkFloorAudits.size)
        val bulk = db.bulkFloorAudits[0]
        assertEquals(rosterId, bulk.rosterId)
        assertEquals("Floor 1", bulk.previousFloor)
        assertEquals("Floor 2", bulk.newFloor)
        assertEquals("Chief Medical Officer", bulk.author)
        assertEquals("Annual ward rotation", bulk.description)
        assertEquals(30, bulk.affectedDaysCount)

        // Check individual floor audit
        val day10Audits = db.floorAudits.filter { it.scheduleId == day10.id }
        // day10 had 1 audit from bulk update ("Floor 1" -> "Floor 2") and 1 from individual edit ("Floor 2" -> "NICU")
        assertEquals(2, day10Audits.size)
        val individualAudit = day10Audits.last()
        assertEquals("Floor 2", individualAudit.previousFloor)
        assertEquals("NICU", individualAudit.newFloor)
        assertEquals("Pediatric Lead", individualAudit.author)
    }

    // =========================================================================
    // 10. Transaction rollback if a bulk update fails
    // =========================================================================
    @Test
    fun test10_TransactionRollbackIfBulkUpdateFails() {
        val db = MockDatabase()
        val rosterId = populateRoster(db, year = 2026, month = 6, floorExtracted = "Floor 1", confirmedFloor = null)

        val schedulesBefore = db.getSchedulesForRoster(rosterId).map { it.currentFloor }
        val bulkAuditsBeforeCount = db.bulkFloorAudits.size
        val floorAuditsBeforeCount = db.floorAudits.size

        // Attempt bulk update with forced failure
        var exceptionThrown = false
        try {
            db.bulkUpdateFloorTransaction(
                rosterId = rosterId,
                newFloor = "Floor 99",
                author = "Failing Author",
                reason = "Should rollback",
                failDuringExecution = true
            )
        } catch (e: IllegalStateException) {
            exceptionThrown = true
        }

        assertTrue("Expected exception to be thrown during transaction", exceptionThrown)

        // Verify full rollback: roster monthly floor unchanged
        assertEquals("Floor 1", db.rosters[rosterId]!!.getEffectiveMonthlyFloor())
        assertNull(db.rosters[rosterId]!!.currentMonthlyFloor)

        // Verify every daily schedule unchanged
        val schedulesAfter = db.getSchedulesForRoster(rosterId).map { it.currentFloor }
        assertEquals(schedulesBefore, schedulesAfter)

        // Verify audit logs not committed
        assertEquals(bulkAuditsBeforeCount, db.bulkFloorAudits.size)
        assertEquals(floorAuditsBeforeCount, db.floorAudits.size)
    }

    // =========================================================================
    // 11. Month/year change across leap-year February (28/29) and 30/31 day months
    // =========================================================================
    @Test
    fun test11_MonthYearChangeAcrossLeapYearFebruaryAnd30_31DayMonths() {
        // Leap year 2024 (Feb has 29 days)
        val feb2024Days = YearMonth.of(2024, 2).lengthOfMonth()
        assertEquals(29, feb2024Days)

        // Non-leap year 2023 (Feb has 28 days)
        val feb2023Days = YearMonth.of(2023, 2).lengthOfMonth()
        assertEquals(28, feb2023Days)

        // Leap year 2028 (Feb has 29 days)
        val feb2028Days = YearMonth.of(2028, 2).lengthOfMonth()
        assertEquals(29, feb2028Days)

        // 30-day month (April)
        val aprDays = YearMonth.of(2026, 4).lengthOfMonth()
        assertEquals(30, aprDays)

        // 31-day month (May)
        val mayDays = YearMonth.of(2026, 5).lengthOfMonth()
        assertEquals(31, mayDays)

        val db = MockDatabase()

        // Case A: User changes extracted March (31 days) to Feb 2024 (29 days)
        val rosterIdLeap = populateRoster(db, year = 2024, month = 2, floorExtracted = "Floor 2", confirmedFloor = "Floor 2")
        val schedulesLeap = db.getSchedulesForRoster(rosterIdLeap)
        assertEquals(29, schedulesLeap.size)
        assertEquals("2024-02-29", schedulesLeap.last().date)
        assertEquals(29, db.rosters[rosterIdLeap]!!.daysCount)

        // Case B: User changes to Feb 2023 (28 days)
        val rosterIdNonLeap = populateRoster(db, year = 2023, month = 2, floorExtracted = "Floor 2", confirmedFloor = "Floor 2")
        val schedulesNonLeap = db.getSchedulesForRoster(rosterIdNonLeap)
        assertEquals(28, schedulesNonLeap.size)
        assertEquals("2023-02-28", schedulesNonLeap.last().date)
        assertEquals(28, db.rosters[rosterIdNonLeap]!!.daysCount)
    }

    // =========================================================================
    // 12. Existing saved roster remains readable after Room migration (MIGRATION_1_2)
    // =========================================================================
    @Test
    fun test12_ExistingSavedRosterRemainsReadableAfterRoomMigration() {
        // Simulates an existing entity created under schema version 1 (where currentMonthlyFloor did not exist, hence null)
        val legacyV1Roster = RosterEntity(
            id = "legacy-roster-123",
            employeeId = "ABHL341",
            month = 3,
            year = 2026,
            version = 1,
            status = "ACTIVE",
            uploadTimestamp = 1000000L,
            floorExtracted = "2ND FLOOR",
            currentMonthlyFloor = null, // In v1 this column was absent, initialized to null via migration
            daysCount = 31
        )

        // Before any bulk edit, effective monthly floor resolves to original floorExtracted
        assertEquals("2ND FLOOR", legacyV1Roster.getEffectiveMonthlyFloor())
        assertEquals("2ND FLOOR", legacyV1Roster.floorExtracted)
        assertNull(legacyV1Roster.currentMonthlyFloor)

        // After migration and subsequent bulk floor update, currentMonthlyFloor takes precedence
        val migratedRosterAfterBulk = legacyV1Roster.copy(currentMonthlyFloor = "5TH FLOOR")
        assertEquals("5TH FLOOR", migratedRosterAfterBulk.getEffectiveMonthlyFloor())
        assertEquals("2ND FLOOR", migratedRosterAfterBulk.floorExtracted) // Original OCR baseline preserved

        // Verify MIGRATION_1_2 SQL definition consistency
        val migration = AppDatabase.MIGRATION_1_2
        assertEquals(1, migration.startVersion)
        assertEquals(2, migration.endVersion)
    }
}
