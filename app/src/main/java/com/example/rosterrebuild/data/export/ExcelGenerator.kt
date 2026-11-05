package com.example.rosterrebuild.data.export

import com.example.rosterrebuild.DailyScheduleEntry
import com.example.rosterrebuild.data.local.*
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExcelGenerator {

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun generateWorkbook(
        roster: RosterEntity,
        scheduleEntries: List<DailyScheduleEntry>,
        shiftChanges: List<ShiftChangeEventEntity>,
        floorChanges: List<FloorChangeEventEntity>,
        attendanceChanges: List<AttendanceChangeEventEntity>,
        outputStream: OutputStream
    ) {
        val zip = ZipOutputStream(outputStream)

        // 1. [Content_Types].xml
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        zip.write(buildContentTypesXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 2. _rels/.rels
        zip.putNextEntry(ZipEntry("_rels/.rels"))
        zip.write(buildRootRelsXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 3. xl/_rels/workbook.xml.rels
        zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
        zip.write(buildWorkbookRelsXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 4. xl/workbook.xml
        zip.putNextEntry(ZipEntry("xl/workbook.xml"))
        zip.write(buildWorkbookXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 5. xl/styles.xml
        zip.putNextEntry(ZipEntry("xl/styles.xml"))
        zip.write(buildStylesXml().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 6. xl/worksheets/sheet1.xml - Monthly Attendance
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        zip.write(buildMonthlyAttendanceSheet(scheduleEntries).toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 7. xl/worksheets/sheet2.xml - Shift Changes
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet2.xml"))
        zip.write(buildShiftChangesSheet(shiftChanges).toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 8. xl/worksheets/sheet3.xml - Floor Changes
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet3.xml"))
        zip.write(buildFloorChangesSheet(floorChanges).toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 9. xl/worksheets/sheet4.xml - Attendance Changes
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet4.xml"))
        zip.write(buildAttendanceChangesSheet(attendanceChanges).toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 10. xl/worksheets/sheet5.xml - Attendance Summary
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet5.xml"))
        zip.write(buildAttendanceSummarySheet(scheduleEntries).toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 11. xl/worksheets/sheet6.xml - Roster Details
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet6.xml"))
        zip.write(buildRosterDetailsSheet(roster, scheduleEntries.size).toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        zip.finish()
        zip.flush()
    }

    private fun escapeXml(str: String?): String {
        if (str == null) return ""
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun buildContentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet4.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet5.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet6.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

    private fun buildRootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun buildWorkbookRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
  <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet4.xml"/>
  <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet5.xml"/>
  <Relationship Id="rId6" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet6.xml"/>
  <Relationship Id="rId7" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun buildWorkbookXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Monthly Attendance" sheetId="1" r:id="rId1"/>
    <sheet name="Shift Changes" sheetId="2" r:id="rId2"/>
    <sheet name="Floor Changes" sheetId="3" r:id="rId3"/>
    <sheet name="Attendance Changes" sheetId="4" r:id="rId4"/>
    <sheet name="Attendance Summary" sheetId="5" r:id="rId5"/>
    <sheet name="Roster Details" sheetId="6" r:id="rId6"/>
  </sheets>
</workbook>"""

    private fun buildStylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font>
      <sz val="11"/>
      <name val="Calibri"/>
    </font>
    <font>
      <b/>
      <sz val="11"/>
      <color rgb="FFFFFFFF"/>
      <name val="Calibri"/>
    </font>
  </fonts>
  <fills count="3">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill>
      <patternFill patternType="solid">
        <fgColor rgb="FF1F4E79"/>
      </patternFill>
    </fill>
  </fills>
  <borders count="1">
    <border>
      <left/><right/><top/><bottom/><diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
  </cellXfs>
</styleSheet>"""

    private fun getDayOfWeek(dateStr: String?): String {
        if (dateStr == null) return ""
        return try {
            val date = LocalDate.parse(dateStr)
            date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        } catch (_: Exception) {
            ""
        }
    }

    // Sheet 1: Monthly Attendance
    private fun buildMonthlyAttendanceSheet(entries: List<DailyScheduleEntry>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <cols>
    <col min="1" max="1" width="14" customWidth="1"/>
    <col min="2" max="2" width="10" customWidth="1"/>
    <col min="3" max="3" width="8" customWidth="1"/>
    <col min="4" max="4" width="16" customWidth="1"/>
    <col min="5" max="5" width="22" customWidth="1"/>
    <col min="6" max="6" width="18" customWidth="1"/>
    <col min="7" max="7" width="16" customWidth="1"/>
    <col min="8" max="8" width="30" customWidth="1"/>
    <col min="9" max="9" width="18" customWidth="1"/>
  </cols>
  <sheetData>
""")

        // Header Row (style 1: bold white text on dark blue fill)
        val headers = listOf(
            "Date", "Day Name", "Day", "Extracted Shift", "Current Shift",
            "Floor Assignment", "Attendance Status", "Notes", "Flagged Review"
        )
        sb.append("    <row r=\"1\">\n")
        headers.forEachIndexed { colIdx, header ->
            val colRef = getColRef(colIdx) + "1"
            sb.append("      <c r=\"$colRef\" t=\"inlineStr\" s=\"1\"><is><t>${escapeXml(header)}</t></is></c>\n")
        }
        sb.append("    </row>\n")

        entries.forEachIndexed { index, entry ->
            val rowNum = index + 2
            val dayName = getDayOfWeek(entry.date)
            val values = listOf(
                entry.date ?: "",
                dayName,
                entry.day.toString(),
                "${entry.extractedShiftCode} (${entry.extractedShift.displayName})",
                "${entry.currentShiftCode} (${entry.currentShift.displayName})" + if (entry.isProvisional) " [Provisional]" else "",
                entry.floorDisplay,
                entry.attendance.status.displayName,
                entry.attendance.notes ?: "",
                if (entry.attendance.isFlaggedForReview) "FLAGGED: ${entry.attendance.reviewReason ?: ""}" else "OK"
            )

            sb.append("    <row r=\"$rowNum\">\n")
            values.forEachIndexed { colIdx, value ->
                val colRef = getColRef(colIdx) + rowNum
                sb.append("      <c r=\"$colRef\" t=\"inlineStr\" s=\"0\"><is><t>${escapeXml(value)}</t></is></c>\n")
            }
            sb.append("    </row>\n")
        }

        sb.append("  </sheetData>\n</worksheet>")
        return sb.toString()
    }

    // Sheet 2: Shift Changes
    private fun buildShiftChangesSheet(events: List<ShiftChangeEventEntity>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <cols>
    <col min="1" max="1" width="22" customWidth="1"/>
    <col min="2" max="2" width="14" customWidth="1"/>
    <col min="3" max="3" width="8" customWidth="1"/>
    <col min="4" max="4" width="16" customWidth="1"/>
    <col min="5" max="5" width="16" customWidth="1"/>
    <col min="6" max="6" width="25" customWidth="1"/>
    <col min="7" max="7" width="20" customWidth="1"/>
    <col min="8" max="8" width="35" customWidth="1"/>
  </cols>
  <sheetData>
""")

        val headers = listOf("Timestamp", "Date", "Day", "Previous Shift", "New Shift", "Change Source", "Author", "Reason")
        sb.append("    <row r=\"1\">\n")
        headers.forEachIndexed { colIdx, header ->
            val colRef = getColRef(colIdx) + "1"
            sb.append("      <c r=\"$colRef\" t=\"inlineStr\" s=\"1\"><is><t>${escapeXml(header)}</t></is></c>\n")
        }
        sb.append("    </row>\n")

        events.forEachIndexed { index, event ->
            val rowNum = index + 2
            val formattedTime = DATE_FORMAT.format(Date(event.timestamp))
            val values = listOf(
                formattedTime,
                event.date ?: "",
                event.day.toString(),
                event.previousShiftCode ?: "None (Initial)",
                event.newShiftCode,
                event.source,
                event.author,
                event.reason ?: ""
            )

            sb.append("    <row r=\"$rowNum\">\n")
            values.forEachIndexed { colIdx, value ->
                val colRef = getColRef(colIdx) + rowNum
                sb.append("      <c r=\"$colRef\" t=\"inlineStr\" s=\"0\"><is><t>${escapeXml(value)}</t></is></c>\n")
            }
            sb.append("    </row>\n")
        }

        sb.append("  </sheetData>\n</worksheet>")
        return sb.toString()
    }

    // Sheet 3: Floor Changes
    private fun buildFloorChangesSheet(events: List<FloorChangeEventEntity>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <cols>
    <col min="1" max="1" width="22" customWidth="1"/>
    <col min="2" max="2" width="14" customWidth="1"/>
    <col min="3" max="3" width="8" customWidth="1"/>
    <col min="4" max="4" width="20" customWidth="1"/>
    <col min="5" max="5" width="20" customWidth="1"/>
    <col min="6" max="6" width="20" customWidth="1"/>
  </cols>
  <sheetData>
""")

        val headers = listOf("Timestamp", "Date", "Day", "Previous Floor", "New Floor", "Author")
        sb.append("    <row r=\"1\">\n")
        headers.forEachIndexed { colIdx, header ->
            val colRef = getColRef(colIdx) + "1"
            sb.append("      <c r=\"$colRef\" t=\"inlineStr\" s=\"1\"><is><t>${escapeXml(header)}</t></is></c>\n")
        }
        sb.append("    </row>\n")

        events.forEachIndexed { index, event ->
            val rowNum = index + 2
            val formattedTime = DATE_FORMAT.format(Date(event.timestamp))
            val values = listOf(
                formattedTime,
                event.date ?: "",
                event.day.toString(),
                event.previousFloor ?: "Unassigned",
                event.newFloor ?: "Unassigned",
                event.author
            )

            sb.append("    <row r=\"$rowNum\">\n")
            values.forEachIndexed { colIdx, value ->
                val colRef = getColRef(colIdx) + rowNum
                sb.append("      <c r=\"$colRef\" t=\"inlineStr\" s=\"0\"><is><t>${escapeXml(value)}</t></is></c>\n")
            }
            sb.append("    </row>\n")
        }

        sb.append("  </sheetData>\n</worksheet>")
        return sb.toString()
    }

    // Sheet 4: Attendance Changes
    private fun buildAttendanceChangesSheet(events: List<AttendanceChangeEventEntity>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <cols>
    <col min="1" max="1" width="22" customWidth="1"/>
    <col min="2" max="2" width="14" customWidth="1"/>
    <col min="3" max="3" width="18" customWidth="1"/>
    <col min="4" max="4" width="18" customWidth="1"/>
    <col min="5" max="5" width="20" customWidth="1"/>
    <col min="6" max="6" width="35" customWidth="1"/>
  </cols>
  <sheetData>
""")

        val headers = listOf("Timestamp", "Date", "Previous Status", "New Status", "Author", "Notes")
        sb.append("    <row r=\"1\">\n")
        headers.forEachIndexed { colIdx, header ->
            val colRef = getColRef(colIdx) + "1"
            sb.append("      <c r=\"$colRef\" t=\"inlineStr\" s=\"1\"><is><t>${escapeXml(header)}</t></is></c>\n")
        }
        sb.append("    </row>\n")

        events.forEachIndexed { index, event ->
            val rowNum = index + 2
            val formattedTime = DATE_FORMAT.format(Date(event.timestamp))
            val values = listOf(
                formattedTime,
                event.date,
                event.previousStatus ?: "None",
                event.newStatus,
                event.author,
                event.notes ?: ""
            )

            sb.append("    <row r=\"$rowNum\">\n")
            values.forEachIndexed { colIdx, value ->
                val colRef = getColRef(colIdx) + rowNum
                sb.append("      <c r=\"$colRef\" t=\"inlineStr\" s=\"0\"><is><t>${escapeXml(value)}</t></is></c>\n")
            }
            sb.append("    </row>\n")
        }

        sb.append("  </sheetData>\n</worksheet>")
        return sb.toString()
    }

    // Sheet 5: Attendance Summary
    private fun buildAttendanceSummarySheet(entries: List<DailyScheduleEntry>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <cols>
    <col min="1" max="1" width="32" customWidth="1"/>
    <col min="2" max="2" width="20" customWidth="1"/>
  </cols>
  <sheetData>
""")

        val totalDays = entries.size
        val workingShifts = entries.count { it.currentShift.isWorkShift }
        val presentDays = entries.count { it.attendance.status.name == "PRESENT" }
        val absentDays = entries.count { it.attendance.status.name == "ABSENT" }
        val notMarkedDays = entries.count { it.attendance.status.name == "NOT_MARKED" }
        val notApplicableDays = entries.count { it.attendance.status.name == "NOT_APPLICABLE" }
        val offDays = entries.count { it.currentShiftCode == "OFF" }
        val managementAbsenceDays = entries.count { it.currentShiftCode == "A" }
        val assignedFloorDays = entries.count { !it.currentFloor.isNullOrBlank() }
        val unassignedFloorDays = entries.count { it.currentFloor.isNullOrBlank() }
        val flaggedReviewDays = entries.count { it.attendance.isFlaggedForReview }

        val summaryMetrics = listOf(
            "Total Days in Roster" to totalDays.toString(),
            "Total Working Shifts" to workingShifts.toString(),
            "Days Present" to presentDays.toString(),
            "Days Absent" to absentDays.toString(),
            "Attendance Not Marked" to notMarkedDays.toString(),
            "Attendance Not Applicable" to notApplicableDays.toString(),
            "Scheduled Off Days (OFF)" to offDays.toString(),
            "Management Absence Days (A)" to managementAbsenceDays.toString(),
            "Days with Assigned Floor" to assignedFloorDays.toString(),
            "Days Floor Unassigned" to unassignedFloorDays.toString(),
            "Dates Flagged for Review" to flaggedReviewDays.toString()
        )

        sb.append("    <row r=\"1\">\n")
        sb.append("      <c r=\"A1\" t=\"inlineStr\" s=\"1\"><is><t>Summary Metric</t></is></c>\n")
        sb.append("      <c r=\"B1\" t=\"inlineStr\" s=\"1\"><is><t>Count / Value</t></is></c>\n")
        sb.append("    </row>\n")

        summaryMetrics.forEachIndexed { index, (metric, value) ->
            val rowNum = index + 2
            sb.append("    <row r=\"$rowNum\">\n")
            sb.append("      <c r=\"A$rowNum\" t=\"inlineStr\" s=\"0\"><is><t>${escapeXml(metric)}</t></is></c>\n")
            sb.append("      <c r=\"B$rowNum\" t=\"inlineStr\" s=\"0\"><is><t>${escapeXml(value)}</t></is></c>\n")
            sb.append("    </row>\n")
        }

        sb.append("  </sheetData>\n</worksheet>")
        return sb.toString()
    }

    // Sheet 6: Roster Details
    private fun buildRosterDetailsSheet(roster: RosterEntity, totalDays: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <cols>
    <col min="1" max="1" width="28" customWidth="1"/>
    <col min="2" max="2" width="36" customWidth="1"/>
  </cols>
  <sheetData>
""")

        val monthName = try {
            java.time.Month.of(roster.month).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        } catch (_: Exception) {
            "Month ${roster.month}"
        }

        val details = listOf(
            "Employee ID" to roster.employeeId,
            "Roster Period" to "$monthName ${roster.year}",
            "Roster Version" to "v${roster.version}",
            "Status" to roster.status,
            "Extracted Monthly Floor" to (roster.floorExtracted ?: "Not detected"),
            "Total Calendar Days" to totalDays.toString(),
            "Roster Uploaded At" to DATE_FORMAT.format(Date(roster.uploadTimestamp)),
            "Workbook Exported At" to DATE_FORMAT.format(Date()),
            "Export Engine" to "MedicalRosterRebuild Offline XLSX Engine"
        )

        sb.append("    <row r=\"1\">\n")
        sb.append("      <c r=\"A1\" t=\"inlineStr\" s=\"1\"><is><t>Property</t></is></c>\n")
        sb.append("      <c r=\"B1\" t=\"inlineStr\" s=\"1\"><is><t>Detail</t></is></c>\n")
        sb.append("    </row>\n")

        details.forEachIndexed { index, (prop, detail) ->
            val rowNum = index + 2
            sb.append("    <row r=\"$rowNum\">\n")
            sb.append("      <c r=\"A$rowNum\" t=\"inlineStr\" s=\"0\"><is><t>${escapeXml(prop)}</t></is></c>\n")
            sb.append("      <c r=\"B$rowNum\" t=\"inlineStr\" s=\"0\"><is><t>${escapeXml(detail)}</t></is></c>\n")
            sb.append("    </row>\n")
        }

        sb.append("  </sheetData>\n</worksheet>")
        return sb.toString()
    }

    private fun getColRef(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + (n % 26)))
            n = (n / 26) - 1
        }
        return sb.toString()
    }
}

