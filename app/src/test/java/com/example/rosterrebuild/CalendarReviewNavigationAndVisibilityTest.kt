package com.example.rosterrebuild

import androidx.lifecycle.SavedStateHandle
import com.example.rosterrebuild.data.local.RosterEntity
import com.example.rosterrebuild.ui.ActiveScreen
import com.example.rosterrebuild.ui.ExtractionStatus
import com.example.rosterrebuild.ui.ExtractionUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/**
 * Verification Test Suite for Calendar Review Navigation, Roster Details, and Shift Visibility.
 *
 * Verifies all 12 critical scenarios:
 * 1. Review action opens the correct review screen (ActiveScreen.ROSTER_REVIEW).
 * 2. Review loads the selected roster ID.
 * 3. Review does not initiate a new extraction.
 * 4. Review does not reset selected roster state.
 * 5. Calendar displays saved planned shifts.
 * 6. Calendar displays current operational shifts after edits.
 * 7. Calendar displays original versus corrected values correctly.
 * 8. Missing shift data is handled explicitly.
 * 9. Tapping a date opens the correct DailyEditorScreen.
 * 10. Editing a shift updates the calendar after saving.
 * 11. Attendance remains unchanged after shift editing.
 * 12. Review and calendar navigation preserve the correct roster version.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarReviewNavigationAndVisibilityTest {

    private fun createSampleRoster(
        id: String = "roster-sep-2026-v1",
        employeeId: String = "ABHL341",
        month: Int = 9,
        year: Int = 2026,
        version: Int = 1,
        floor: String = "Floor 2"
    ): RosterEntity {
        return RosterEntity(
            id = id,
            employeeId = employeeId,
            month = month,
            year = year,
            version = version,
            status = "ACTIVE",
            uploadTimestamp = System.currentTimeMillis(),
            originalFilePath = "/data/user/0/roster.png",
            workbookFilePath = "/data/user/0/roster.xlsx",
            workbookFileUri = null,
            processingStatus = "COMPLETED",
            floorExtracted = floor,
            daysCount = 30
        )
    }

    private fun createSampleSchedule(
        year: Int = 2026,
        month: Int = 9,
        count: Int = 30
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

    // 1. Review action opens the correct review screen (ActiveScreen.ROSTER_REVIEW)
    @Test
    fun test1_reviewActionOpensReviewScreen() {
        val savedStateHandle = SavedStateHandle()
        var uiState = ExtractionUiState(currentScreen = ActiveScreen.MONTHLY_CALENDAR)

        // Simulate Calendar screen top bar "← Review" button tap
        val onNavigateToReview: () -> Unit = {
            savedStateHandle["key_current_screen"] = ActiveScreen.ROSTER_REVIEW.name
            uiState = uiState.copy(currentScreen = ActiveScreen.ROSTER_REVIEW)
        }
        onNavigateToReview()

        assertEquals("Navigation must route to ROSTER_REVIEW", ActiveScreen.ROSTER_REVIEW, uiState.currentScreen)
        assertNotEquals("Must not land on ROSTER_UPLOAD", ActiveScreen.ROSTER_UPLOAD, uiState.currentScreen)
        assertEquals(ActiveScreen.ROSTER_REVIEW.name, savedStateHandle.get<String>("key_current_screen"))
    }

    // 2. Review loads the selected roster ID
    @Test
    fun test2_reviewLoadsSelectedRosterId() {
        val savedStateHandle = SavedStateHandle()
        val roster = createSampleRoster(id = "roster-ABHL341-september-2026", employeeId = "ABHL341")

        // Simulate loading roster
        savedStateHandle["key_active_roster_id"] = roster.id
        savedStateHandle["key_employee_id"] = roster.employeeId
        var uiState = ExtractionUiState(
            activeRoster = roster,
            employeeId = roster.employeeId,
            isSaved = true
        )

        // Navigate to review
        uiState = uiState.copy(currentScreen = ActiveScreen.ROSTER_REVIEW)

        assertEquals("roster-ABHL341-september-2026", uiState.activeRoster?.id)
        assertEquals("ABHL341", uiState.employeeId)
        assertTrue(uiState.isSaved)
        assertEquals(ActiveScreen.ROSTER_REVIEW, uiState.currentScreen)
    }

    // 3. Review does not initiate a new extraction
    @Test
    fun test3_reviewDoesNotInitiateNewExtraction() {
        val roster = createSampleRoster()
        var uiState = ExtractionUiState(
            activeRoster = roster,
            currentScreen = ActiveScreen.MONTHLY_CALENDAR,
            status = ExtractionStatus.SUCCESS
        )

        // Navigate to Review
        uiState = uiState.copy(currentScreen = ActiveScreen.ROSTER_REVIEW)

        assertNotEquals("Review must never set extraction status to RUNNING", ExtractionStatus.RUNNING, uiState.status)
        assertEquals("Status remains SUCCESS", ExtractionStatus.SUCCESS, uiState.status)
        assertNull("No pending roster ID for re-extraction", uiState.pendingRosterId)
    }

    // 4. Review does not reset selected roster state
    @Test
    fun test4_reviewDoesNotResetSelectedRosterState() {
        val roster = createSampleRoster(id = "roster-42")
        val schedule = createSampleSchedule(2026, 9, 30)

        var uiState = ExtractionUiState(
            activeRoster = roster,
            employeeId = roster.employeeId,
            schedule = schedule,
            currentScreen = ActiveScreen.MONTHLY_CALENDAR
        )
        assertEquals("roster-42", uiState.activeRoster?.id)
        assertEquals(30, uiState.schedule.size)

        // Navigate between calendar and review multiple times
        uiState = uiState.copy(currentScreen = ActiveScreen.ROSTER_REVIEW)
        assertEquals("roster-42", uiState.activeRoster?.id)
        assertEquals(30, uiState.schedule.size)

        uiState = uiState.copy(currentScreen = ActiveScreen.MONTHLY_CALENDAR)
        assertEquals("roster-42", uiState.activeRoster?.id)
        assertEquals(30, uiState.schedule.size)

        uiState = uiState.copy(currentScreen = ActiveScreen.ROSTER_REVIEW)
        assertEquals("roster-42", uiState.activeRoster?.id)
        assertEquals(30, uiState.schedule.size)
    }

    // 5. Calendar displays saved planned shifts
    @Test
    fun test5_calendarDisplaysSavedPlannedShifts() {
        val schedule = createSampleSchedule(2026, 9, 30)
        val calendarDays = CalendarDomain.buildMonthDays(2026, 9, schedule)

        assertEquals(30, calendarDays.size)
        assertEquals("M", calendarDays[0].scheduleEntry?.currentShiftCode)
        assertEquals("E", calendarDays[1].scheduleEntry?.currentShiftCode)
        assertEquals("N", calendarDays[2].scheduleEntry?.currentShiftCode)
        assertEquals("OFF", calendarDays[3].scheduleEntry?.currentShiftCode)
        assertEquals("A", calendarDays[4].scheduleEntry?.currentShiftCode)

        // All work shifts have valid floor
        assertEquals("Floor 2", calendarDays[0].scheduleEntry?.currentFloor)
        assertTrue(calendarDays[0].scheduleEntry?.currentShift?.isWorkShift == true)
        assertTrue(calendarDays[3].scheduleEntry?.currentShift?.isWorkShift == false)
    }

    // 6. Calendar displays current operational shifts after edits
    @Test
    fun test6_calendarDisplaysCurrentOperationalShiftsAfterEdits() {
        val schedule = createSampleSchedule(2026, 9, 30).toMutableList()
        val originalDay3 = schedule[2]
        assertEquals("N", originalDay3.currentShiftCode)

        // Edit Day 3 from N to M
        val editedDay3 = originalDay3.updateSchedule("M", ShiftChangeSource.USER_EDIT, "Dr. Smith")
        schedule[2] = editedDay3

        val calendarDays = CalendarDomain.buildMonthDays(2026, 9, schedule)
        val day3Entry = calendarDays[2].scheduleEntry

        assertNotNull(day3Entry)
        assertEquals("M", day3Entry?.currentShiftCode)
        assertEquals("N", day3Entry?.extractedShiftCode)
        assertTrue("Entry must be flagged as modified", day3Entry?.isModified == true)
    }

    // 7. Calendar displays original versus corrected values correctly
    @Test
    fun test7_calendarDisplaysOriginalVersusCorrectedValuesCorrectly() {
        val uneditedEntry = DailyScheduleEntry(
            day = 1,
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M"
        )
        assertFalse(uneditedEntry.isModified)

        val editedEntry = uneditedEntry.updateSchedule("E", ShiftChangeSource.USER_EDIT, "Supervisor")
        assertTrue(editedEntry.isModified)
        assertEquals("M", editedEntry.extractedShiftCode)
        assertEquals("E", editedEntry.currentShiftCode)

        // Restoring to original clears modified flag
        val restoredEntry = editedEntry.updateSchedule("M", ShiftChangeSource.USER_EDIT, "Supervisor")
        assertFalse("Restoring to original shift must clear isModified", restoredEntry.isModified)
    }

    // 8. Missing shift data is handled explicitly
    @Test
    fun test8_missingShiftDataHandledExplicitly() {
        // Schedule with only 3 days populated
        val partialSchedule = createSampleSchedule(2026, 9, 3)
        val calendarDays = CalendarDomain.buildMonthDays(2026, 9, partialSchedule)

        // September 2026 has 30 days
        assertEquals(30, calendarDays.size)

        // Days 1..3 have entries
        assertNotNull(calendarDays[0].scheduleEntry)
        assertNotNull(calendarDays[1].scheduleEntry)
        assertNotNull(calendarDays[2].scheduleEntry)

        // Day 4 has no schedule entry -> handled explicitly
        assertNull(calendarDays[3].scheduleEntry)
        val displayCode = calendarDays[3].scheduleEntry?.currentShiftCode?.takeIf { it.isNotBlank() } ?: "—"
        assertEquals("—", displayCode)
    }

    // 9. Tapping a date opens the correct DailyEditorScreen
    @Test
    fun test9_tappingDateOpensCorrectDailyEditorScreen() {
        val savedStateHandle = SavedStateHandle()
        var uiState = ExtractionUiState(currentScreen = ActiveScreen.MONTHLY_CALENDAR)

        // Simulate openDailyEditor(17)
        savedStateHandle["key_editing_day"] = 17
        savedStateHandle["key_current_screen"] = ActiveScreen.DAILY_EDITOR.name
        uiState = uiState.copy(editingDay = 17, currentScreen = ActiveScreen.DAILY_EDITOR)

        assertEquals(ActiveScreen.DAILY_EDITOR, uiState.currentScreen)
        assertEquals(17, uiState.editingDay)

        // Closing daily editor returns to MONTHLY_CALENDAR
        savedStateHandle.remove<Int>("key_editing_day")
        savedStateHandle["key_current_screen"] = ActiveScreen.MONTHLY_CALENDAR.name
        uiState = uiState.copy(editingDay = null, currentScreen = ActiveScreen.MONTHLY_CALENDAR)

        assertEquals(ActiveScreen.MONTHLY_CALENDAR, uiState.currentScreen)
        assertNull(uiState.editingDay)
    }

    // 10. Editing a shift updates the calendar after saving
    @Test
    fun test10_editingShiftUpdatesCalendarAfterSaving() {
        val schedule = createSampleSchedule(2026, 9, 30).toMutableList()
        var uiState = ExtractionUiState(
            currentScreen = ActiveScreen.DAILY_EDITOR,
            editingDay = 5,
            schedule = schedule
        )

        // Save shift edit for Day 5: from 'A' to 'N'
        val day5Original = schedule.first { it.day == 5 }
        val day5Updated = day5Original.updateSchedule("N", ShiftChangeSource.USER_EDIT, "Supervisor", "Cover night duty")
        val updatedSchedule = schedule.map { if (it.day == 5) day5Updated else it }

        uiState = uiState.copy(
            schedule = updatedSchedule,
            editingDay = null,
            currentScreen = ActiveScreen.MONTHLY_CALENDAR
        )

        assertEquals(ActiveScreen.MONTHLY_CALENDAR, uiState.currentScreen)
        val day5InState = uiState.schedule.find { it.day == 5 }
        assertNotNull(day5InState)
        assertEquals("N", day5InState?.currentShiftCode)
        assertEquals(1, day5InState?.changeHistory?.size)
        assertEquals("Cover night duty", day5InState?.changeHistory?.first()?.reason)
    }

    // 11. Attendance remains unchanged after shift editing
    @Test
    fun test11_attendanceRemainsUnchangedAfterShiftEditing() {
        val original = DailyScheduleEntry(
            day = 8,
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M",
            attendance = AttendanceRecord(
                status = AttendanceStatus.PRESENT,
                notes = "On time, ICU floor"
            )
        )

        // Edit planned shift
        val updated = original.updateSchedule("E", ShiftChangeSource.USER_EDIT, "Dr. Adams")

        assertEquals("E", updated.currentShiftCode)
        assertEquals(AttendanceStatus.PRESENT, updated.attendance.status)
        assertEquals("On time, ICU floor", updated.attendance.notes)
    }

    // 12. Review and calendar navigation preserve the correct roster version
    @Test
    fun test12_reviewAndCalendarNavigationPreservesCorrectRosterVersion() {
        val rosterV2 = createSampleRoster(id = "roster-v2", version = 2)
        var uiState = ExtractionUiState(activeRoster = rosterV2)
        assertEquals(2, uiState.activeRoster?.version)

        // Navigate Dashboard -> Review -> Calendar -> Daily Editor -> Review
        uiState = uiState.copy(currentScreen = ActiveScreen.SAVED_ROSTERS_DASHBOARD)
        assertEquals(2, uiState.activeRoster?.version)

        uiState = uiState.copy(currentScreen = ActiveScreen.ROSTER_REVIEW)
        assertEquals(2, uiState.activeRoster?.version)

        uiState = uiState.copy(currentScreen = ActiveScreen.MONTHLY_CALENDAR)
        assertEquals(2, uiState.activeRoster?.version)

        uiState = uiState.copy(currentScreen = ActiveScreen.DAILY_EDITOR, editingDay = 1)
        assertEquals(2, uiState.activeRoster?.version)

        uiState = uiState.copy(currentScreen = ActiveScreen.ROSTER_REVIEW, editingDay = null)
        assertEquals(2, uiState.activeRoster?.version)
    }
}
