package com.example.rosterrebuild

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorExtractionTest {

    private val processor = RosterProcessor()

    @Test
    fun testFloorColumnBoundsCalculation_JuneGeometry() {
        // In June sample: Day 1 starts at 312, candidates before Day 1: 4, 35, 86, 204, 250
        val xCandidates = listOf(4, 35, 86, 204, 250, 312, 339, 370, 396)
        val day1Left = 312
        val headerCenterX = 267 // Header "FLOOR" center X

        val bounds = processor.calculateFloorColumnBounds(
            headerCenterX = headerCenterX,
            xCandidates = xCandidates,
            day1Left = day1Left
        )

        assertNotNull(bounds)
        assertEquals(250, bounds!!.first)
        assertEquals(312, bounds.second)
    }

    @Test
    fun testFloorColumnBoundsCalculation_SeptemberGeometry() {
        // In September sample: Day 1 starts at 309, candidates before Day 1: 1, 32, 83, 201, 247
        val xCandidates = listOf(1, 32, 83, 201, 247, 309, 336, 367, 393)
        val day1Left = 309
        val headerCenterX = 265 // Header "FLOOR" center X

        val bounds = processor.calculateFloorColumnBounds(
            headerCenterX = headerCenterX,
            xCandidates = xCandidates,
            day1Left = day1Left
        )

        assertNotNull(bounds)
        assertEquals(247, bounds!!.first)
        assertEquals(309, bounds.second)
    }

    @Test
    fun testFloorColumnBoundsCalculation_FallbackWhenHeaderOcrMissed() {
        // When OCR misses the header text, it must fallback to the column immediately adjacent to Day 1
        val xCandidates = listOf(1, 32, 83, 201, 247, 309, 336, 367)
        val day1Left = 309

        val bounds = processor.calculateFloorColumnBounds(
            headerCenterX = null,
            xCandidates = xCandidates,
            day1Left = day1Left
        )

        assertNotNull(bounds)
        assertEquals(247, bounds!!.first)
        assertEquals(309, bounds.second)
    }

    @Test
    fun testFloorCropBoundsCalculation_EmployeeRowIntersection() {
        val colBounds = 250 to 312
        val rowTop = 277
        val rowBottom = 291
        val imageWidth = 1138
        val imageHeight = 656

        val cropBounds = processor.calculateFloorCropBounds(
            colBounds = colBounds,
            rowTop = rowTop,
            rowBottom = rowBottom,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )

        assertTrue(cropBounds.left >= colBounds.first)
        assertTrue(cropBounds.right <= colBounds.second)
        assertTrue(cropBounds.top >= rowTop)
        assertTrue(cropBounds.bottom <= rowBottom)
        assertTrue(cropBounds.width > 30)
        assertTrue(cropBounds.height > 8)
    }

    @Test
    fun testNormalizeFloorText_JuneSample() {
        assertEquals("4TH FLOOR", processor.normalizeFloorText("4TH FLOOR"))
        assertEquals("4TH FLOOR", processor.normalizeFloorText("4th floor"))
    }

    @Test
    fun testNormalizeFloorText_SeptemberSample() {
        // "4T FLOOR" extracted from September OCR must normalize to "4TH FLOOR"
        assertEquals("4TH FLOOR", processor.normalizeFloorText("4T FLOOR"))
        assertEquals("4TH FLOOR", processor.normalizeFloorText("4t floor"))
    }

    @Test
    fun testNormalizeFloorText_CommonVariations() {
        assertEquals("4TH FLOOR", processor.normalizeFloorText("4 FLOOR"))
        assertEquals("4TH FLOOR", processor.normalizeFloorText("4TH"))
        assertEquals("1ST FLOOR", processor.normalizeFloorText("1ST FLOOR"))
        assertEquals("1ST FLOOR", processor.normalizeFloorText("1S FLOOR"))
        assertEquals("1ST FLOOR", processor.normalizeFloorText("IST FLOOR"))
        assertEquals("2ND FLOOR", processor.normalizeFloorText("2ND FLOOR"))
        assertEquals("2ND FLOOR", processor.normalizeFloorText("2N FLOOR"))
        assertEquals("3RD FLOOR", processor.normalizeFloorText("3RD FLOOR"))
        assertEquals("3RD FLOOR", processor.normalizeFloorText("3R FLOOR"))
        assertEquals("5TH FLOOR", processor.normalizeFloorText("5TH FLOOR"))
        assertEquals("5TH FLOOR", processor.normalizeFloorText("STH FLOOR"))
        assertEquals("2ND FLOOR", processor.normalizeFloorText("FLOOR 2"))
        assertEquals("3RD FLOOR", processor.normalizeFloorText("FLR 3"))
        assertEquals("4TH FLOOR", processor.normalizeFloorText("4"))
        assertEquals("3RD FLOOR", processor.normalizeFloorText("3"))
        assertEquals("4TH FLOOR", processor.normalizeFloorText("4TH FL00R"))
        assertEquals("9TH A WING", processor.normalizeFloorText("9TH A WING"))
        assertEquals("9TH B WING", processor.normalizeFloorText("9TH B WING"))
    }

    @Test
    fun testNormalizeFloorText_SpecializedUnits() {
        assertEquals("ICU", processor.normalizeFloorText("ICU"))
        assertEquals("NICU", processor.normalizeFloorText("NICU"))
        assertEquals("PICU", processor.normalizeFloorText("PICU"))
        assertEquals("Emergency", processor.normalizeFloorText("EMERGENCY"))
        assertEquals("Emergency", processor.normalizeFloorText("ER"))
        assertEquals("OT", processor.normalizeFloorText("OT"))
        assertEquals("WARD 4B", processor.normalizeFloorText("Ward 4B"))
    }

    @Test
    fun testNormalizeFloorText_NoiseAndBlanks() {
        assertNull(processor.normalizeFloorText(null))
        assertNull(processor.normalizeFloorText(""))
        assertNull(processor.normalizeFloorText("   "))
        assertNull(processor.normalizeFloorText("---"))
        assertNull(processor.normalizeFloorText("."))
        assertNull(processor.normalizeFloorText("?"))
    }

    @Test
    fun testDailyScheduleEntry_FloorInheritanceAndManualOverride() {
        val cell = CellResult(
            day = 1,
            bounds = null,
            crop = null,
            ocrText = "M",
            colorCode = "M",
            colorDistance = 1.0,
            status = CellStatus.DIRECTLY_OBSERVED,
            reason = "Test"
        )

        // 1. Initial creation with extracted floor
        val entry = DailyScheduleEntry.fromCellResult(
            cell = cell,
            year = 2026,
            month = 6,
            extractedFloor = "4TH FLOOR"
        )

        assertEquals("4TH FLOOR", entry.extractedFloor)
        assertEquals("4TH FLOOR", entry.currentFloor)
        assertEquals("4TH FLOOR", entry.floorDisplay)
        assertFalse(entry.isFloorModified)

        // 2. Manual override of a specific date's floor
        val overridden = entry.updateFloor("Floor 2", author = "Supervisor")

        assertEquals("4TH FLOOR", overridden.extractedFloor) // Permanently preserved original!
        assertEquals("Floor 2", overridden.currentFloor)      // Updated current!
        assertEquals("Floor 2", overridden.floorDisplay)
        assertTrue(overridden.isFloorModified)
    }

    @Test
    fun testDailyScheduleEntry_FloorChangeAfterAttendanceFlagsReview() {
        val cell = CellResult(
            day = 1,
            bounds = null,
            crop = null,
            ocrText = "M",
            colorCode = "M",
            colorDistance = 1.0,
            status = CellStatus.DIRECTLY_OBSERVED,
            reason = "Test"
        )

        val entry = DailyScheduleEntry.fromCellResult(
            cell = cell,
            year = 2026,
            month = 6,
            extractedFloor = "4TH FLOOR"
        )

        // Mark attendance as PRESENT
        val attended = entry.updateAttendance(AttendanceStatus.PRESENT)
        assertFalse(attended.attendance.isFlaggedForReview)

        // Now supervisor reassigns floor to Floor 3
        val reassigned = attended.updateFloor("Floor 3")
        assertTrue(reassigned.attendance.isFlaggedForReview)
        assertTrue(reassigned.attendance.reviewReason!!.contains("Floor reassigned"))
    }
}

