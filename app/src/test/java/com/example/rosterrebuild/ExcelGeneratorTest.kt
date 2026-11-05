package com.example.rosterrebuild

import com.example.rosterrebuild.data.export.ExcelGenerator
import com.example.rosterrebuild.data.local.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ExcelGeneratorTest {

    @Test
    fun testGenerateGenuineOpenXmlWorkbookWithSixSheets() {
        val roster = RosterEntity(
            id = "roster-test-1",
            employeeId = "ABHL341",
            month = 6,
            year = 2026,
            version = 1,
            status = "ACTIVE",
            floorExtracted = "4TH FLOOR",
            daysCount = 2
        )

        val entry1 = DailyScheduleEntry(
            day = 1,
            date = "2026-06-01",
            extractedShift = ShiftType.MORNING,
            extractedShiftCode = "M",
            currentShift = ShiftType.MORNING,
            currentShiftCode = "M",
            extractedFloor = "4TH FLOOR",
            currentFloor = "4TH FLOOR",
            attendance = AttendanceRecord(status = AttendanceStatus.PRESENT, notes = "On time")
        )

        val entry2 = DailyScheduleEntry(
            day = 2,
            date = "2026-06-02",
            extractedShift = ShiftType.MANAGEMENT_ABSENCE,
            extractedShiftCode = "A",
            currentShift = ShiftType.MANAGEMENT_ABSENCE,
            currentShiftCode = "A",
            isProvisional = true,
            extractedFloor = null,
            currentFloor = null,
            attendance = AttendanceRecord(status = AttendanceStatus.NOT_APPLICABLE)
        )

        val shiftEvents = listOf(
            ShiftChangeEventEntity(
                id = "shift-evt-1",
                scheduleId = "sched-1",
                rosterId = "roster-test-1",
                day = 1,
                date = "2026-06-01",
                previousShiftCode = null,
                newShiftCode = "M",
                source = "Initial OCR",
                author = "System"
            )
        )

        val floorEvents = listOf(
            FloorChangeEventEntity(
                id = "floor-evt-1",
                scheduleId = "sched-1",
                rosterId = "roster-test-1",
                day = 1,
                date = "2026-06-01",
                previousFloor = null,
                newFloor = "4TH FLOOR",
                author = "RosterProcessor"
            )
        )

        val attendanceEvents = listOf(
            AttendanceChangeEventEntity(
                id = "att-evt-1",
                attendanceId = "att-1",
                employeeId = "ABHL341",
                date = "2026-06-01",
                previousStatus = "NOT_MARKED",
                newStatus = "PRESENT",
                author = "User",
                notes = "On time"
            )
        )

        val out = ByteArrayOutputStream()
        ExcelGenerator.generateWorkbook(
            roster = roster,
            scheduleEntries = listOf(entry1, entry2),
            shiftChanges = shiftEvents,
            floorChanges = floorEvents,
            attendanceChanges = attendanceEvents,
            outputStream = out
        )

        val bytes = out.toByteArray()
        assertTrue("Workbook bytes must not be empty", bytes.isNotEmpty())

        // Verify ZIP entries (Genuine OpenXML standard)
        val entryNames = mutableListOf<String>()
        val sheetContents = mutableMapOf<String, String>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                val content = zis.bufferedReader().readText()
                if (entry.name.startsWith("xl/worksheets/")) {
                    sheetContents[entry.name] = content
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Must contain ECMA-376 required parts
        assertTrue("[Content_Types].xml must be present", entryNames.contains("[Content_Types].xml"))
        assertTrue("_rels/.rels must be present", entryNames.contains("_rels/.rels"))
        assertTrue("xl/workbook.xml must be present", entryNames.contains("xl/workbook.xml"))
        assertTrue("xl/styles.xml must be present", entryNames.contains("xl/styles.xml"))

        // Must contain exactly 6 sheets
        assertTrue("sheet1.xml (Monthly Attendance) present", entryNames.contains("xl/worksheets/sheet1.xml"))
        assertTrue("sheet2.xml (Shift Changes) present", entryNames.contains("xl/worksheets/sheet2.xml"))
        assertTrue("sheet3.xml (Floor Changes) present", entryNames.contains("xl/worksheets/sheet3.xml"))
        assertTrue("sheet4.xml (Attendance Changes) present", entryNames.contains("xl/worksheets/sheet4.xml"))
        assertTrue("sheet5.xml (Attendance Summary) present", entryNames.contains("xl/worksheets/sheet5.xml"))
        assertTrue("sheet6.xml (Roster Details) present", entryNames.contains("xl/worksheets/sheet6.xml"))

        // Check Sheet 1: Monthly Attendance contents
        val sheet1 = sheetContents["xl/worksheets/sheet1.xml"]!!
        assertTrue("Sheet 1 contains 2026-06-01", sheet1.contains("2026-06-01"))
        assertTrue("Sheet 1 contains Morning Shift", sheet1.contains("Morning Shift"))
        assertTrue("Sheet 1 contains 4TH FLOOR", sheet1.contains("4TH FLOOR"))
        assertTrue("Sheet 1 contains Present", sheet1.contains("Present"))
        assertTrue("Sheet 1 contains Management Absence", sheet1.contains("Management Absence"))
        assertTrue("Sheet 1 contains Provisional tag", sheet1.contains("[Provisional]"))

        // Check Sheet 2: Shift Changes contents
        val sheet2 = sheetContents["xl/worksheets/sheet2.xml"]!!
        assertTrue("Sheet 2 contains shift code M", sheet2.contains(">M<"))
        assertTrue("Sheet 2 contains Initial OCR", sheet2.contains("Initial OCR"))

        // Check Sheet 3: Floor Changes contents
        val sheet3 = sheetContents["xl/worksheets/sheet3.xml"]!!
        assertTrue("Sheet 3 contains 4TH FLOOR", sheet3.contains("4TH FLOOR"))

        // Check Sheet 4: Attendance Changes contents
        val sheet4 = sheetContents["xl/worksheets/sheet4.xml"]!!
        assertTrue("Sheet 4 contains PRESENT", sheet4.contains("PRESENT"))
        assertTrue("Sheet 4 contains On time", sheet4.contains("On time"))

        // Check Sheet 5: Attendance Summary contents
        val sheet5 = sheetContents["xl/worksheets/sheet5.xml"]!!
        assertTrue("Sheet 5 contains Total Days in Roster", sheet5.contains("Total Days in Roster"))
        assertTrue("Sheet 5 contains Days Present", sheet5.contains("Days Present"))
        assertTrue("Sheet 5 contains Management Absence Days (A)", sheet5.contains("Management Absence Days (A)"))

        // Check Sheet 6: Roster Details contents
        val sheet6 = sheetContents["xl/worksheets/sheet6.xml"]!!
        assertTrue("Sheet 6 contains employeeId ABHL341", sheet6.contains("ABHL341"))
        assertTrue("Sheet 6 contains June 2026", sheet6.contains("June 2026"))
        assertTrue("Sheet 6 contains v1", sheet6.contains("v1"))
        assertTrue("Sheet 6 contains ACTIVE", sheet6.contains("ACTIVE"))
    }
}

