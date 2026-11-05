package com.example.rosterrebuild

/**
 * Excel and CSV export helpers implementing domain business rules for roster export.
 *
 * Rule 10: Include 'A' in Excel export as "Management Absence",
 * preserving the original extracted code in a separate column.
 */
object RosterExport {

    /**
     * Generates a standard CSV export string with quoted fields.
     */
    fun toCsv(
        schedule: List<DailyScheduleEntry>,
        employeeId: String,
        periodDisplay: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# Medical Roster Schedule Export")
        sb.appendLine("# Employee ID: $employeeId")
        sb.appendLine("# Roster Period: ${periodDisplay ?: "Unknown"}")
        sb.appendLine("# Generated: ${java.time.ZonedDateTime.now()}")
        sb.appendLine()

        val headers = listOf(
            "Day",
            "Date",
            "Original Extracted Code",
            "Current Shift Code",
            "Shift Description",
            "Provisional Status",
            "Management Assigned",
            "Is Modified",
            "Audit Events Count",
            "Last Audit Source",
            "Details / Audit Reason"
        )
        sb.appendLine(headers.joinToString(",") { escapeCsv(it) })

        for (entry in schedule) {
            val lastEvent = entry.changeHistory.lastOrNull()
            val provisionalText = if (entry.isProvisional) {
                "Provisional (Subject to Management Revision)"
            } else {
                "Confirmed"
            }
            val mgmtAssignedText = if (entry.currentShift.isManagementAssigned) {
                "Yes (Upper Management Assigned - Not Employee Requested)"
            } else {
                "No"
            }

            val row = listOf(
                entry.day.toString(),
                entry.date ?: "Day ${entry.day}",
                entry.extractedShiftCode,
                entry.currentShiftCode,
                entry.displayTitle, // "Management Absence" for 'A'
                provisionalText,
                mgmtAssignedText,
                if (entry.isModified) "Yes" else "No",
                entry.changeHistory.size.toString(),
                lastEvent?.source?.displayName ?: "Initial Extraction",
                lastEvent?.reason ?: entry.scheduleDetails
            )
            sb.appendLine(row.joinToString(",") { escapeCsv(it) })
        }

        return sb.toString()
    }

    /**
     * Generates an Excel-compatible XML Spreadsheet document (SpreadsheetML 2003).
     * Note: This is an XML-based spreadsheet format supported natively by Excel, Google Sheets,
     * and LibreOffice Calc. It is not a native ZIP-compressed binary .xlsx file.
     */
    fun toExcelXml(
        schedule: List<DailyScheduleEntry>,
        employeeId: String,
        periodDisplay: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<?mso-application progid="Excel.Sheet"?>""")
        sb.appendLine("""<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"""")
        sb.appendLine(""" xmlns:o="urn:schemas-microsoft-com:office:office"""")
        sb.appendLine(""" xmlns:x="urn:schemas-microsoft-com:office:excel"""")
        sb.appendLine(""" xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet"""")
        sb.appendLine(""" xmlns:html="http://www.w3.org/TR/REC-html40">""")

        // Styles
        sb.appendLine(""" <Styles>""")
        sb.appendLine("""  <Style ss:ID="Default" ss:Name="Normal"><Font ss:FontName="Calibri" ss:Size="11"/></Style>""")
        sb.appendLine("""  <Style ss:ID="Header"><Font ss:FontName="Calibri" ss:Size="11" ss:Bold="1"/><Interior ss:Color="#E0E0E0" ss:Pattern="Solid"/></Style>""")
        sb.appendLine("""  <Style ss:ID="Title"><Font ss:FontName="Calibri" ss:Size="14" ss:Bold="1"/></Style>""")
        sb.appendLine("""  <Style ss:ID="Morning"><Font ss:FontName="Calibri" ss:Size="11" ss:Color="#AD1457" ss:Bold="1"/><Interior ss:Color="#FCE4EC" ss:Pattern="Solid"/></Style>""")
        sb.appendLine("""  <Style ss:ID="Evening"><Font ss:FontName="Calibri" ss:Size="11" ss:Color="#F57F17" ss:Bold="1"/><Interior ss:Color="#FFF9C4" ss:Pattern="Solid"/></Style>""")
        sb.appendLine("""  <Style ss:ID="Night"><Font ss:FontName="Calibri" ss:Size="11" ss:Color="#E65100" ss:Bold="1"/><Interior ss:Color="#FFE0B2" ss:Pattern="Solid"/></Style>""")
        sb.appendLine("""  <Style ss:ID="OffDuty"><Font ss:FontName="Calibri" ss:Size="11" ss:Color="#B71C1C" ss:Bold="1"/><Interior ss:Color="#FFCDD2" ss:Pattern="Solid"/></Style>""")
        sb.appendLine("""  <Style ss:ID="MgmtAbsence"><Font ss:FontName="Calibri" ss:Size="11" ss:Color="#005B96" ss:Bold="1"/><Interior ss:Color="#E1F5FE" ss:Pattern="Solid"/></Style>""")
        sb.appendLine(""" </Styles>""")

        sb.appendLine(""" <Worksheet ss:Name="Roster Schedule">""")
        sb.appendLine("""  <Table>""")
        sb.appendLine("""   <Column ss:Width="40"/>""")
        sb.appendLine("""   <Column ss:Width="80"/>""")
        sb.appendLine("""   <Column ss:Width="140"/>""")
        sb.appendLine("""   <Column ss:Width="120"/>""")
        sb.appendLine("""   <Column ss:Width="170"/>""")
        sb.appendLine("""   <Column ss:Width="220"/>""")
        sb.appendLine("""   <Column ss:Width="260"/>""")
        sb.appendLine("""   <Column ss:Width="70"/>""")
        sb.appendLine("""   <Column ss:Width="240"/>""")

        // Header metadata
        sb.appendLine("""   <Row><Cell ss:StyleID="Title"><Data ss:Type="String">Medical Roster Schedule — $employeeId (${periodDisplay ?: ""})</Data></Cell></Row>""")
        sb.appendLine("""   <Row><Cell><Data ss:Type="String">Format: Excel-compatible XML SpreadsheetML (not native .xlsx). Code 'A' indicates Management Absence.</Data></Cell></Row>""")
        sb.appendLine("""   <Row/>""")

        // Column Titles
        val titles = listOf(
            "Day",
            "Date",
            "Original Extracted Code",
            "Current Shift Code",
            "Shift Description",
            "Provisional Status",
            "Management Assigned",
            "Modified",
            "Audit Notes"
        )
        sb.appendLine("""   <Row ss:StyleID="Header">""")
        titles.forEach { t ->
            sb.appendLine("""    <Cell><Data ss:Type="String">$t</Data></Cell>""")
        }
        sb.appendLine("""   </Row>""")

        // Data Rows
        for (entry in schedule) {
            val styleAttr = when (entry.currentShift) {
                ShiftType.MANAGEMENT_ABSENCE -> """ ss:StyleID="MgmtAbsence""""
                ShiftType.OFF -> """ ss:StyleID="OffDuty""""
                ShiftType.MORNING -> """ ss:StyleID="Morning""""
                ShiftType.EVENING -> """ ss:StyleID="Evening""""
                ShiftType.NIGHT -> """ ss:StyleID="Night""""
                else -> ""
            }

            val lastEvent = entry.changeHistory.lastOrNull()
            val provisionalText = if (entry.isProvisional) "Provisional (Subject to Revision)" else "Confirmed"
            val mgmtText = if (entry.currentShift.isManagementAssigned) "Yes (Upper Management - Not Employee Requested)" else "No"

            sb.appendLine("""   <Row$styleAttr>""")
            sb.appendLine("""    <Cell><Data ss:Type="Number">${entry.day}</Data></Cell>""")
            sb.appendLine("""    <Cell><Data ss:Type="String">${escapeXml(entry.date ?: "Day ${entry.day}")}</Data></Cell>""")
            sb.appendLine("""    <Cell><Data ss:Type="String">${escapeXml(entry.extractedShiftCode)}</Data></Cell>""")
            sb.appendLine("""    <Cell><Data ss:Type="String">${escapeXml(entry.currentShiftCode)}</Data></Cell>""")
            sb.appendLine("""    <Cell><Data ss:Type="String">${escapeXml(entry.displayTitle)}</Data></Cell>""")
            sb.appendLine("""    <Cell><Data ss:Type="String">${escapeXml(provisionalText)}</Data></Cell>""")
            sb.appendLine("""    <Cell><Data ss:Type="String">${escapeXml(mgmtText)}</Data></Cell>""")
            sb.appendLine("""    <Cell><Data ss:Type="String">${if (entry.isModified) "Yes" else "No"}</Data></Cell>""")
            sb.appendLine("""    <Cell><Data ss:Type="String">${escapeXml(lastEvent?.reason ?: entry.scheduleDetails)}</Data></Cell>""")
            sb.appendLine("""   </Row>""")
        }

        sb.appendLine("""  </Table>""")
        sb.appendLine(""" </Worksheet>""")
        sb.appendLine("""</Workbook>""")
        return sb.toString()
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

