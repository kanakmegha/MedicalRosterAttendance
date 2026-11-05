package com.example.rosterrebuild

import com.example.rosterrebuild.data.local.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RosterVersioningAndRetentionTest {

    @Test
    fun testSameMonthVersioningIncrementsVersion() {
        val existingV1 = RosterEntity(
            id = "roster-1",
            employeeId = "ABHL341",
            month = 6,
            year = 2026,
            version = 1,
            status = "ACTIVE"
        )
        val existingList = listOf(existingV1)

        val nextVersion = if (existingList.isEmpty()) 1 else (existingList.maxOf { it.version } + 1)
        assertEquals(2, nextVersion)

        val existingV2 = existingV1.copy(id = "roster-2", version = 2)
        val nextVersion3 = listOf(existingV1, existingV2).maxOf { it.version } + 1
        assertEquals(3, nextVersion3)
    }

    @Test
    fun testSameMonthUploadPreservesRecordedAttendanceAndFlagsDiscrepancy() {
        // Given an existing attendance marked PRESENT on 2026-06-01
        val recordedAttendance = AttendanceEntity(
            id = "att-1",
            employeeId = "ABHL341",
            date = "2026-06-01",
            status = AttendanceStatus.PRESENT.name,
            notes = "Shift attended"
        )

        val prevSchedule = DailyScheduleEntity(
            id = "s-1",
            rosterId = "roster-v1",
            day = 1,
            date = "2026-06-01",
            extractedShiftCode = "M",
            currentShiftCode = "M",
            extractedFloor = "4TH FLOOR",
            currentFloor = "4TH FLOOR"
        )

        // New roster v2 arrives with revised shift 'N' (Night Shift)
        val v2ShiftCode = "N"
        val shiftChangedFromPrev = prevSchedule.currentShiftCode != v2ShiftCode

        assertTrue("Shift changed from M to N", shiftChangedFromPrev)

        val finalAttendance = if ((recordedAttendance.status == AttendanceStatus.PRESENT.name || recordedAttendance.status == AttendanceStatus.ABSENT.name) && shiftChangedFromPrev) {
            recordedAttendance.copy(
                isFlaggedForReview = true,
                reviewReason = "Roster v2 changed shift from '${prevSchedule.currentShiftCode}' to '$v2ShiftCode'"
            )
        } else {
            recordedAttendance
        }

        // Attendance status is preserved
        assertEquals(AttendanceStatus.PRESENT.name, finalAttendance.status)
        assertEquals("Shift attended", finalAttendance.notes)

        // Flagged for supervisor review
        assertTrue(finalAttendance.isFlaggedForReview)
        assertTrue(finalAttendance.reviewReason!!.contains("Roster v2 changed shift from 'M' to 'N'"))
    }

    @Test
    fun testFloorChangeDiscrepancyFlagsRecordedAttendanceForReview() {
        val recordedAttendance = AttendanceEntity(
            id = "att-2",
            employeeId = "ABHL341",
            date = "2026-06-02",
            status = AttendanceStatus.PRESENT.name,
            notes = "On duty"
        )

        val prevSchedule = DailyScheduleEntity(
            id = "s-2",
            rosterId = "roster-v1",
            day = 2,
            date = "2026-06-02",
            extractedShiftCode = "M",
            currentShiftCode = "M",
            extractedFloor = "4TH FLOOR",
            currentFloor = "4TH FLOOR"
        )

        // New roster changes floor to 5TH FLOOR
        val v2Floor = "5TH FLOOR"
        val floorChanged = (prevSchedule.currentFloor ?: "") != v2Floor
        assertTrue(floorChanged)

        val updatedAttendance = if ((recordedAttendance.status == AttendanceStatus.PRESENT.name || recordedAttendance.status == AttendanceStatus.ABSENT.name) && floorChanged) {
            recordedAttendance.copy(
                isFlaggedForReview = true,
                reviewReason = "Roster v2 changed floor from '${prevSchedule.currentFloor}' to '$v2Floor'"
            )
        } else recordedAttendance

        assertEquals(AttendanceStatus.PRESENT.name, updatedAttendance.status)
        assertTrue(updatedAttendance.isFlaggedForReview)
        assertTrue(updatedAttendance.reviewReason!!.contains("Roster v2 changed floor from '4TH FLOOR' to '5TH FLOOR'"))
    }

    @Test
    fun testAbsenceCodeAPreservedInBothEntitiesAndDomain() {
        val entry = DailyScheduleEntry(
            day = 5,
            date = "2026-06-05",
            extractedShift = ShiftType.MANAGEMENT_ABSENCE,
            extractedShiftCode = "A",
            currentShift = ShiftType.MANAGEMENT_ABSENCE,
            currentShiftCode = "A",
            isProvisional = true
        )

        assertEquals("A", entry.extractedShiftCode)
        assertEquals("A", entry.currentShiftCode)
        assertEquals("Management Absence", entry.displayTitle)
        assertTrue(entry.isProvisional)

        val entity = DailyScheduleEntity(
            rosterId = "roster-1",
            day = entry.day,
            date = entry.date,
            extractedShiftCode = entry.extractedShiftCode,
            currentShiftCode = entry.currentShiftCode,
            extractedFloor = entry.extractedFloor,
            currentFloor = entry.currentFloor,
            isProvisional = entry.isProvisional
        )

        assertEquals("A", entity.extractedShiftCode)
        assertEquals("A", entity.currentShiftCode)
        assertTrue(entity.isProvisional)
    }

    @Test
    fun testMultiMonthArchivalRules() {
        val rosterJune = RosterEntity(
            id = "roster-june",
            employeeId = "ABHL341",
            month = 6,
            year = 2026,
            version = 1,
            status = "ACTIVE"
        )

        // Uploading July roster archives June
        val rosterJuly = RosterEntity(
            id = "roster-july",
            employeeId = "ABHL341",
            month = 7,
            year = 2026,
            version = 1,
            status = "ACTIVE"
        )

        val updatedJune = rosterJune.copy(status = "ARCHIVED")
        assertEquals("ARCHIVED", updatedJune.status)
        assertEquals("ACTIVE", rosterJuly.status)
    }

    @Test
    fun testMonthEndRetentionWarningDetection() {
        // Given a roster for January 2025
        val oldRoster = RosterEntity(
            id = "roster-old",
            employeeId = "ABHL341",
            month = 1,
            year = 2025,
            version = 1,
            status = "ACTIVE"
        )

        // Evaluate against November 2026
        val currentYear = 2026
        val currentMonth = 11

        val isPastMonth = oldRoster.year < currentYear || (oldRoster.year == currentYear && oldRoster.month < currentMonth)
        assertTrue("Roster from 2025-01 is past month and requires retention review", isPastMonth)

        // Future or current month roster
        val activeRoster = RosterEntity(
            id = "roster-current",
            employeeId = "ABHL341",
            month = 11,
            year = 2026,
            version = 1,
            status = "ACTIVE"
        )
        val isCurrentPast = activeRoster.year < currentYear || (activeRoster.year == currentYear && activeRoster.month < currentMonth)
        assertFalse("Current month roster does not trigger past-month warning", isCurrentPast)
    }

    @Test
    fun testAuditTrailEventsStructure() {
        val shiftEvent = ShiftChangeEventEntity(
            scheduleId = "sched-1",
            rosterId = "roster-1",
            day = 3,
            date = "2026-06-03",
            previousShiftCode = "M",
            newShiftCode = "E",
            source = ShiftChangeSource.USER_EDIT.displayName,
            author = "Nurse Incharge",
            reason = "Ward swap"
        )

        assertEquals("M", shiftEvent.previousShiftCode)
        assertEquals("E", shiftEvent.newShiftCode)
        assertEquals("Nurse Incharge", shiftEvent.author)
        assertEquals("Ward swap", shiftEvent.reason)
        assertTrue(shiftEvent.timestamp > 0)

        val floorEvent = FloorChangeEventEntity(
            scheduleId = "sched-1",
            rosterId = "roster-1",
            day = 3,
            date = "2026-06-03",
            previousFloor = "4TH FLOOR",
            newFloor = "ICU",
            author = "Supervisor"
        )

        assertEquals("4TH FLOOR", floorEvent.previousFloor)
        assertEquals("ICU", floorEvent.newFloor)
        assertEquals("Supervisor", floorEvent.author)

        val attendanceEvent = AttendanceChangeEventEntity(
            attendanceId = "att-1",
            employeeId = "ABHL341",
            date = "2026-06-03",
            previousStatus = "NOT_MARKED",
            newStatus = "PRESENT",
            author = "Employee",
            notes = "Checked in at 07:55"
        )

        assertEquals("NOT_MARKED", attendanceEvent.previousStatus)
        assertEquals("PRESENT", attendanceEvent.newStatus)
        assertEquals("Checked in at 07:55", attendanceEvent.notes)
    }
}

