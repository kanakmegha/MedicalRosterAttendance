package com.example.rosterrebuild

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class RosterProcessor {

    private data class MonthDef(val month: Int, val name: String, val patterns: List<String>)

    private val months = listOf(
        MonthDef(9, "September", listOf("SEPTEMBER", "SEPT", "SEP")),
        MonthDef(1, "January", listOf("JANUARY", "JAN")),
        MonthDef(2, "February", listOf("FEBRUARY", "FEB")),
        MonthDef(3, "March", listOf("MARCH", "MAR")),
        MonthDef(4, "April", listOf("APRIL", "APR")),
        MonthDef(5, "May", listOf("MAY")),
        MonthDef(6, "June", listOf("JUNE", "JUN")),
        MonthDef(7, "July", listOf("JULY", "JUL")),
        MonthDef(8, "August", listOf("AUGUST", "AUG")),
        MonthDef(10, "October", listOf("OCTOBER", "OCT")),
        MonthDef(11, "November", listOf("NOVEMBER", "NOV")),
        MonthDef(12, "December", listOf("DECEMBER", "DEC"))
    )

    private val yearRegex = Regex("""(?:\b|\D)(20\d{2})(?:\b|\D)""")

    suspend fun detectPeriod(original: Bitmap): DetectedPeriod? {
        return detectPeriodAndFloor(original).first
    }

    suspend fun detectPeriodAndFloor(original: Bitmap): Pair<DetectedPeriod?, String?> = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var scaledBitmap: Bitmap? = null
        try {
            val scale = if (original.height < 1400) (1500f / original.height).coerceAtLeast(1.0f) else 1.0f
            val ocrBitmap = if (scale > 1.05f) {
                scaledBitmap = Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).roundToInt(),
                    (original.height * scale).roundToInt(),
                    true
                )
                scaledBitmap
            } else {
                original
            }
            val visionText = Tasks.await(recognizer.process(InputImage.fromBitmap(ocrBitmap, 0)))
            val period = detectPeriodFromVisionText(visionText, ocrBitmap.height)
            val floor = detectFloorFromVisionText(visionText, ocrBitmap.height)
            Pair(period, floor)
        } finally {
            scaledBitmap?.recycle()
            recognizer.close()
        }
    }

    fun detectFloorFromVisionText(visionText: Text, imageHeight: Int): String? {
        val allLines = visionText.textBlocks.flatMap { it.lines }
        val headerCutoff = (imageHeight * 0.40).toInt()
        val headerLines = allLines.filter { (it.boundingBox?.top ?: 0) <= headerCutoff }
        for (line in headerLines) {
            val norm = normalizeFloorText(line.text)
            if (norm != null) return norm
        }
        return null
    }

    private fun detectPeriodFromVisionText(visionText: Text, imageHeight: Int): DetectedPeriod? {
        val allLines = visionText.textBlocks.flatMap { it.lines }
        val headerCutoff = (imageHeight * 0.35).toInt()
        val headerLines = allLines.filter { (it.boundingBox?.top ?: 0) <= headerCutoff }

        // 1. First check if any header line contains both month and year
        for (line in headerLines) {
            val period = extractPeriodFromText(line.text)
            if (period != null) return period
        }

        // 2. Check if across all header lines we can match a month and a year
        var detectedMonth: MonthDef? = null
        var detectedYear: Int? = null
        var rawMatch = ""

        for (line in headerLines) {
            if (detectedMonth == null) {
                val m = findMonthInText(line.text)
                if (m != null) {
                    detectedMonth = m
                    rawMatch += if (rawMatch.isEmpty()) line.text else " | ${line.text}"
                }
            }
            if (detectedYear == null) {
                val y = findYearInText(line.text)
                if (y != null) {
                    detectedYear = y
                    rawMatch += if (rawMatch.isEmpty()) line.text else " | ${line.text}"
                }
            }
            if (detectedMonth != null && detectedYear != null) {
                return DetectedPeriod(
                    month = detectedMonth.month,
                    year = detectedYear,
                    monthName = detectedMonth.name,
                    displayText = "${detectedMonth.name} $detectedYear",
                    rawHeaderMatch = rawMatch
                )
            }
        }

        // 3. Fallback: scan all lines in the image if header detection didn't find both
        for (line in allLines) {
            val period = extractPeriodFromText(line.text)
            if (period != null) return period
        }

        return null
    }

    private fun extractPeriodFromText(text: String): DetectedPeriod? {
        val m = findMonthInText(text) ?: return null
        val y = findYearInText(text) ?: return null
        return DetectedPeriod(
            month = m.month,
            year = y,
            monthName = m.name,
            displayText = "${m.name} $y",
            rawHeaderMatch = text
        )
    }

    private fun findMonthInText(text: String): MonthDef? {
        val upper = text.uppercase()
        for (m in months) {
            for (pattern in m.patterns) {
                val regex = Regex("""(?:\b|[^A-Z])$pattern(?:\b|[^A-Z])""")
                if (regex.containsMatchIn(upper) || upper.contains(pattern)) {
                    return m
                }
            }
        }
        return null
    }

    private fun findYearInText(text: String): Int? {
        val match = yearRegex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    suspend fun extract(
        original: Bitmap,
        requestedEmployeeId: String,
        overrideMonth: Int? = null,
        overrideYear: Int? = null
    ): RosterResult = withContext(Dispatchers.Default) {
        val trimmedId = requestedEmployeeId.trim()
        require(trimmedId.isNotBlank()) { "Employee ID is required" }
        check(OpenCVLoader.initLocal()) { "OpenCV could not be initialized" }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var scaledOcrBitmap: Bitmap? = null

        try {
            // For ML Kit OCR: upscale low-res images so text height is >= 24px for reliable detection.
            // Original image is NEVER modified and is preserved for all crops and colour sampling.
            val ocrScale = if (original.height < 1400) {
                (1500f / original.height).coerceAtLeast(1.0f)
            } else {
                1.0f
            }

            val ocrBitmap = if (ocrScale > 1.05f) {
                scaledOcrBitmap = Bitmap.createScaledBitmap(
                    original,
                    (original.width * ocrScale).roundToInt(),
                    (original.height * ocrScale).roundToInt(),
                    true
                )
                scaledOcrBitmap
            } else {
                original
            }

            val visionText = Tasks.await(recognizer.process(InputImage.fromBitmap(ocrBitmap, 0)))
            val allOcrLines = visionText.textBlocks.flatMap { block -> block.lines.map { it.text } }

            // Period detection
            val detectedPeriod = detectPeriodFromVisionText(visionText, ocrBitmap.height)
            val effectiveMonth = overrideMonth ?: detectedPeriod?.month
            val effectiveYear = overrideYear ?: detectedPeriod?.year

            if (effectiveMonth == null || effectiveYear == null) {
                val partialDiagnostics = ExtractionDiagnostics(
                    sourceDimensions = original.width to original.height,
                    allOcrLines = allOcrLines,
                    employeeIdExactMatch = false,
                    matchedIdToken = null,
                    idCandidates = emptyList(),
                    employeeIdBounds = null,
                    detectedMonth = detectedPeriod?.month,
                    detectedYear = detectedPeriod?.year,
                    detectedPeriodDisplay = detectedPeriod?.displayText,
                    dayColumnsCount = 0,
                    employeeRowBoundaries = null,
                    cellCropsCount = 0,
                    warnings = listOf("Could not detect roster month/year from header. Please confirm month and year.")
                )
                throw ExtractionException(
                    "Could not detect roster month/year from the header image. Please confirm them.",
                    partialDiagnostics
                )
            }

            require(effectiveMonth in 1..12) { "Month must be between 1 and 12" }
            val expectedDays = YearMonth.of(effectiveYear, effectiveMonth).lengthOfMonth()

            // Geometry detection on original bitmap
            val geometry = detectGeometry(original, expectedDays)

            // Employee ID detection (with coordinates mapped back to original scale)
            val idResult = detectEmployeeId(
                original = original,
                ocrBitmap = ocrBitmap,
                ocrScale = ocrScale,
                requestedId = trimmedId,
                visionText = visionText,
                recognizer = recognizer
            )

            if (idResult.confirmedToken == null || idResult.confirmedBounds == null) {
                val partialDiagnostics = ExtractionDiagnostics(
                    sourceDimensions = original.width to original.height,
                    allOcrLines = allOcrLines,
                    employeeIdExactMatch = idResult.isExact,
                    matchedIdToken = null,
                    idCandidates = idResult.candidates,
                    employeeIdBounds = null,
                    detectedMonth = effectiveMonth,
                    detectedYear = effectiveYear,
                    detectedPeriodDisplay = detectedPeriod?.displayText ?: "$effectiveMonth/$effectiveYear",
                    dayColumnsCount = geometry.xLines.size - 1,
                    employeeRowBoundaries = null,
                    cellCropsCount = 0,
                    warnings = idResult.warnings
                )
                throw ExtractionException(
                    idResult.failureReason ?: "Employee ID '$trimmedId' was not found",
                    partialDiagnostics
                )
            }

            // Identify employee row from confirmed bounding box in original coordinate space
            val centerY = idResult.confirmedBounds.centerY()
            val rowIndex = findRowIndexForY(centerY, geometry.yLines)
            if (rowIndex < 0) {
                val partialDiagnostics = ExtractionDiagnostics(
                    sourceDimensions = original.width to original.height,
                    allOcrLines = allOcrLines,
                    employeeIdExactMatch = idResult.isExact,
                    matchedIdToken = idResult.confirmedToken,
                    idCandidates = idResult.candidates,
                    employeeIdBounds = idResult.confirmedBounds,
                    detectedMonth = effectiveMonth,
                    detectedYear = effectiveYear,
                    detectedPeriodDisplay = detectedPeriod?.displayText ?: "$effectiveMonth/$effectiveYear",
                    dayColumnsCount = geometry.xLines.size - 1,
                    employeeRowBoundaries = null,
                    cellCropsCount = 0,
                    warnings = listOf("Employee ID OCR position (y=$centerY) did not fall between detected row boundaries")
                )
                throw ExtractionException(
                    "Employee ID OCR position did not fall between detected row boundaries",
                    partialDiagnostics
                )
            }

            val rowTop = geometry.yLines[rowIndex]
            val rowBottom = geometry.yLines[rowIndex + 1]
            val rowHeight = rowBottom - rowTop

            // Locate dedicated FLOOR column and extract employee's assigned floor
            val floorColumnInfo = locateFloorColumn(
                visionText = visionText,
                ocrScale = ocrScale,
                geometry = geometry,
                imageWidth = original.width,
                imageHeight = original.height
            )

            val floorExtraction = extractEmployeeFloor(
                original = original,
                floorInfo = floorColumnInfo,
                rowTop = rowTop,
                rowBottom = rowBottom,
                recognizer = recognizer,
                visionText = visionText,
                ocrScale = ocrScale
            )

            // Extract daily cell crops from original bitmap
            val cells = (1..expectedDays).map { day ->
                val xLeft = geometry.xLines[day - 1]
                val xRight = geometry.xLines[day]
                val insetX = ((xRight - xLeft) * 0.10).roundToInt()
                val insetY = (rowHeight * 0.15).roundToInt()

                val left = (xLeft + insetX).coerceIn(0, original.width - 1)
                val right = (xRight - insetX).coerceIn(left + 1, original.width)
                val top = (rowTop + insetY).coerceIn(0, original.height - 1)
                val bottom = (rowBottom - insetY).coerceIn(top + 1, original.height)

                if (right - left < 3 || bottom - top < 3) {
                    CellResult(
                        day = day,
                        bounds = null,
                        crop = null,
                        ocrText = null,
                        rawOcrText = null,
                        enhancedCropDimensions = null,
                        colorCode = null,
                        colorDistance = null,
                        secondColorDistance = null,
                        detectedRowHeight = rowHeight,
                        cropPaddingUsed = insetX to insetY,
                        status = CellStatus.MISSING_CROP,
                        reason = "Crop failed minimum width/height validation"
                    )
                } else {
                    val crop = Bitmap.createBitmap(original, left, top, right - left, bottom - top)
                    val bounds = Rect(left, top, right, bottom)

                    // Independent OCR pipeline on enhanced crop
                    var (enhancedCrop, enhancedDims) = enhanceCellCropForOcr(crop, applyClahe = true)
                    var rawOcrText: String? = null
                    try {
                        val visionRes = Tasks.await(recognizer.process(InputImage.fromBitmap(enhancedCrop, 0)))
                        val txt = visionRes.text.trim().uppercase()
                        if (txt.isNotBlank()) rawOcrText = txt
                    } catch (_: Exception) {
                    } finally {
                        enhancedCrop.recycle()
                    }

                    // Fallback to plain upscaled grayscale if CLAHE yielded no text
                    if (rawOcrText.isNullOrBlank()) {
                        val (altCrop, altDims) = enhanceCellCropForOcr(crop, applyClahe = false)
                        try {
                            val visionRes = Tasks.await(recognizer.process(InputImage.fromBitmap(altCrop, 0)))
                            val txt = visionRes.text.trim().uppercase()
                            if (txt.isNotBlank()) {
                                rawOcrText = txt
                                enhancedDims = altDims
                            }
                        } catch (_: Exception) {
                        } finally {
                            altCrop.recycle()
                        }
                    }

                    val ocrCode = normalizeShift(rawOcrText)
                    val color = ColorClassifier.classify(crop)
                    val (status, reason) = fuse(ocrCode, color.code)

                    CellResult(
                        day = day,
                        bounds = bounds,
                        crop = crop,
                        ocrText = ocrCode,
                        rawOcrText = rawOcrText,
                        enhancedCropDimensions = enhancedDims,
                        colorCode = color.code,
                        colorDistance = color.bestDistance,
                        secondColorDistance = color.secondDistance,
                        detectedRowHeight = rowHeight,
                        cropPaddingUsed = insetX to insetY,
                        status = status,
                        reason = "$reason; ${color.reason}"
                    )
                }
            }

            check(cells.size == expectedDays) { "Internal error: expected-day retention failed" }

            val fullDiagnostics = ExtractionDiagnostics(
                sourceDimensions = original.width to original.height,
                allOcrLines = allOcrLines,
                employeeIdExactMatch = idResult.isExact,
                matchedIdToken = idResult.confirmedToken,
                idCandidates = idResult.candidates,
                employeeIdBounds = idResult.confirmedBounds,
                detectedMonth = effectiveMonth,
                detectedYear = effectiveYear,
                detectedPeriodDisplay = detectedPeriod?.displayText ?: "$effectiveMonth/$effectiveYear",
                dayColumnsCount = geometry.xLines.size - 1,
                employeeRowBoundaries = rowTop to rowBottom,
                cellCropsCount = cells.size,
                floorHeaderFound = floorColumnInfo.headerFound,
                floorHeaderRawText = floorColumnInfo.headerRawText,
                floorHeaderBounds = floorColumnInfo.headerBounds,
                day1LeftBoundary = floorColumnInfo.day1LeftBoundary,
                floorColumnBounds = floorColumnInfo.columnBounds,
                floorCropBounds = floorExtraction.cropBounds,
                floorCropDimensions = floorExtraction.cropDimensions,
                rawFloorOcrText = floorExtraction.rawOcrText,
                extractedMonthlyFloor = floorExtraction.normalizedFloor,
                originalFloorCropBitmap = floorExtraction.originalCropBitmap,
                enhancedFloorCropBitmap = floorExtraction.enhancedCropBitmap,
                warnings = idResult.warnings
            )

            val schedule = cells.map { cell ->
                DailyScheduleEntry.fromCellResult(
                    cell = cell,
                    year = effectiveYear,
                    month = effectiveMonth,
                    extractedFloor = floorExtraction.normalizedFloor
                )
            }

            RosterResult(
                employeeId = requestedEmployeeId,
                matchedOcrToken = idResult.confirmedToken,
                employeeRowIndex = rowIndex,
                expectedDays = expectedDays,
                detectedPeriod = detectedPeriod,
                cells = cells,
                schedule = schedule,
                extractedFloor = floorExtraction.normalizedFloor,
                originalFloorCropBitmap = floorExtraction.originalCropBitmap,
                enhancedFloorCropBitmap = floorExtraction.enhancedCropBitmap,
                geometry = GeometryDiagnostics(
                    xCandidates = geometry.xCandidates,
                    yCandidates = geometry.yCandidates,
                    selectedX = geometry.xLines,
                    selectedY = geometry.yLines,
                    horizontalThreshold = geometry.horizontalThreshold,
                    verticalThreshold = geometry.verticalThreshold
                ),
                diagnostics = fullDiagnostics,
                sourceWidth = original.width,
                sourceHeight = original.height,
                warnings = listOf(
                    "Colour references are provisional sample medians, not a validated calibration dataset.",
                    "Inspect the geometry and every crop before trusting a shift.",
                    "This experimental app does not write to a calendar or database."
                ) + idResult.warnings
            )
        } finally {
            scaledOcrBitmap?.recycle()
            recognizer.close()
        }
    }

    private data class CandidateToken(
        val text: String,
        val normalized: String,
        val bounds: Rect,
        val confidence: Float?,
        val source: String
    )

    private data class IdDetectionResult(
        val confirmedToken: String?,
        val confirmedBounds: Rect?,
        val isExact: Boolean,
        val candidates: List<IdCandidate>,
        val failureReason: String?,
        val warnings: List<String>
    )

    private suspend fun detectEmployeeId(
        original: Bitmap,
        ocrBitmap: Bitmap,
        ocrScale: Float,
        requestedId: String,
        visionText: Text,
        recognizer: TextRecognizer
    ): IdDetectionResult {
        val wanted = normalizeId(requestedId)
        val tokens = mutableListOf<CandidateToken>()

        // 1. Collect individual OCR elements
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val box = element.boundingBox ?: continue
                    val originalBox = mapBoxToOriginal(box, ocrScale)
                    val norm = normalizeId(element.text)
                    if (norm.isNotEmpty()) {
                        tokens += CandidateToken(element.text, norm, originalBox, element.confidence, "element")
                    }
                }

                // 2. Combine adjacent elements within the line if split
                val validElements = line.elements.filter { it.boundingBox != null }
                for (i in 0 until validElements.size - 1) {
                    val e1 = validElements[i]
                    val e2 = validElements[i + 1]
                    val b1 = e1.boundingBox!!
                    val b2 = e2.boundingBox!!

                    if (areAdjacentInLine(b1, b2)) {
                        val combinedText = e1.text + e2.text
                        val combinedNorm = normalizeId(combinedText)
                        val combinedBox = mapBoxToOriginal(unionRect(b1, b2), ocrScale)
                        tokens += CandidateToken(combinedText, combinedNorm, combinedBox, null, "merged_pair")

                        // Triples (e.g. AB + HL + 341)
                        if (i + 2 < validElements.size) {
                            val e3 = validElements[i + 2]
                            val b3 = e3.boundingBox!!
                            if (areAdjacentInLine(b2, b3)) {
                                val tripleText = combinedText + e3.text
                                val tripleNorm = normalizeId(tripleText)
                                val tripleBox = mapBoxToOriginal(unionRect(unionRect(b1, b2), b3), ocrScale)
                                tokens += CandidateToken(tripleText, tripleNorm, tripleBox, null, "merged_triple")
                            }
                        }
                    }
                }

                // 3. Whole line check (e.g. if an element contained adjacent serial numbers or names)
                val lineBox = line.boundingBox
                if (lineBox != null) {
                    val originalLineBox = mapBoxToOriginal(lineBox, ocrScale)
                    val lineNorm = normalizeId(line.text)
                    if (lineNorm == wanted || lineNorm.contains(wanted)) {
                        tokens += CandidateToken(line.text, lineNorm, originalLineBox, line.confidence, "line")
                    }
                }
            }
        }

        // Deduplicate tokens by normalized text and bounding box proximity
        val distinctTokens = deduplicateTokens(tokens)

        // Exact match check
        val exactMatches = distinctTokens.filter { it.normalized == wanted }
        if (exactMatches.size == 1) {
            val match = exactMatches.single()
            val candidate = IdCandidate(
                text = match.text,
                normalizedText = match.normalized,
                bounds = match.bounds,
                confidence = match.confidence,
                isConfusionMatch = false,
                reason = "Exact OCR token match (${match.source})"
            )
            return IdDetectionResult(
                confirmedToken = match.text,
                confirmedBounds = match.bounds,
                isExact = true,
                candidates = listOf(candidate),
                failureReason = null,
                warnings = emptyList()
            )
        } else if (exactMatches.size > 1) {
            val candidates = exactMatches.map {
                IdCandidate(it.text, it.normalized, it.bounds, it.confidence, false, "Duplicate exact match")
            }
            return IdDetectionResult(
                confirmedToken = null,
                confirmedBounds = null,
                isExact = true,
                candidates = candidates,
                failureReason = "Employee ID '$requestedId' matched ${exactMatches.size} OCR tokens; refusing ambiguous selection",
                warnings = listOf("Multiple exact matches detected")
            )
        }

        // No exact match: search for OCR confusion candidates
        val confusionMatches = distinctTokens.filter { isConfusionMatch(it.normalized, wanted) }
        val candidatesList = confusionMatches.map {
            IdCandidate(
                text = it.text,
                normalizedText = it.normalized,
                bounds = it.bounds,
                confidence = it.confidence,
                isConfusionMatch = true,
                reason = "Potential OCR character confusion with '$wanted'"
            )
        }

        if (confusionMatches.isEmpty()) {
            return IdDetectionResult(
                confirmedToken = null,
                confirmedBounds = null,
                isExact = false,
                candidates = emptyList(),
                failureReason = "Employee ID '$requestedId' was not found as an exact token or OCR-confusion candidate",
                warnings = listOf("No candidate tokens matched the employee ID")
            )
        }

        // Crop each confusion candidate region from original bitmap, enhance with OpenCV, and rerun ML Kit OCR
        val confirmedCandidates = mutableListOf<CandidateToken>()
        for (cand in confusionMatches) {
            val enhancedText = enhanceAndRerunOcr(original, cand.bounds, recognizer)
            val enhancedNorm = normalizeId(enhancedText)
            if (enhancedNorm == wanted || enhancedNorm.contains(wanted)) {
                confirmedCandidates += cand
            }
        }

        if (confirmedCandidates.size == 1) {
            val confirmed = confirmedCandidates.single()
            val candidate = IdCandidate(
                text = confirmed.text,
                normalizedText = confirmed.normalized,
                bounds = confirmed.bounds,
                confidence = confirmed.confidence,
                isConfusionMatch = true,
                reason = "Confirmed via crop enhancement OCR"
            )
            return IdDetectionResult(
                confirmedToken = confirmed.text,
                confirmedBounds = confirmed.bounds,
                isExact = false,
                candidates = candidatesList,
                failureReason = null,
                warnings = listOf(
                    "Employee ID confirmed via high-contrast crop enhancement (initial OCR was '${confirmed.text}')"
                )
            )
        } else if (confirmedCandidates.isEmpty()) {
            return IdDetectionResult(
                confirmedToken = null,
                confirmedBounds = null,
                isExact = false,
                candidates = candidatesList,
                failureReason = "Employee ID '$requestedId' not found. ${confusionMatches.size} confusion candidate(s) detected (${confusionMatches.map { it.text }}), but none confirmed by crop enhancement",
                warnings = listOf("Unconfirmed confusion candidates exist")
            )
        } else {
            return IdDetectionResult(
                confirmedToken = null,
                confirmedBounds = null,
                isExact = false,
                candidates = candidatesList,
                failureReason = "Employee ID '$requestedId' matched multiple candidates after crop enhancement; refusing ambiguous selection",
                warnings = listOf("Ambiguous crop enhancement confirmation")
            )
        }
    }

    private suspend fun enhanceAndRerunOcr(
        original: Bitmap,
        bounds: Rect,
        recognizer: TextRecognizer
    ): String = withContext(Dispatchers.Default) {
        val padX = (bounds.width() * 0.35).roundToInt().coerceAtLeast(10)
        val padY = (bounds.height() * 0.45).roundToInt().coerceAtLeast(10)

        val cropLeft = (bounds.left - padX).coerceIn(0, original.width - 1)
        val cropTop = (bounds.top - padY).coerceIn(0, original.height - 1)
        val cropRight = (bounds.right + padX).coerceIn(cropLeft + 1, original.width)
        val cropBottom = (bounds.bottom + padY).coerceIn(cropTop + 1, original.height)

        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop
        if (cropW < 5 || cropH < 5) return@withContext ""

        val crop = Bitmap.createBitmap(original, cropLeft, cropTop, cropW, cropH)

        val mat = Mat()
        val gray = Mat()
        val upscaled = Mat()
        val enhanced = Mat()
        var clahe: CLAHE? = null

        try {
            Utils.bitmapToMat(crop, mat)
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

            // Upscale 2.5x with cubic interpolation to resolve character strokes
            val targetSize = Size(cropW * 2.5, cropH * 2.5)
            Imgproc.resize(gray, upscaled, targetSize, 0.0, 0.0, Imgproc.INTER_CUBIC)

            clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
            clahe.apply(upscaled, enhanced)

            val enhancedBitmap = Bitmap.createBitmap(
                enhanced.cols(),
                enhanced.rows(),
                Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(enhanced, enhancedBitmap)

            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(enhancedBitmap, 0)))
            result.text
        } catch (_: Exception) {
            ""
        } finally {
            mat.release()
            gray.release()
            upscaled.release()
            enhanced.release()
            clahe?.collectGarbage()
        }
    }

    private fun mapBoxToOriginal(box: Rect, scale: Float): Rect {
        if (scale <= 1.05f) return box
        return Rect(
            (box.left / scale).roundToInt(),
            (box.top / scale).roundToInt(),
            (box.right / scale).roundToInt(),
            (box.bottom / scale).roundToInt()
        )
    }

    /**
     * Checks whether candidate and wanted ID differ only by known OCR character confusion
     * (I vs 1, O vs 0, B vs 8, S vs 5, Z vs 2).
     */
    private fun isConfusionMatch(candidate: String, wanted: String): Boolean {
        if (candidate.length != wanted.length || candidate == wanted) return false

        var diffs = 0
        for (i in candidate.indices) {
            val c1 = candidate[i]
            val c2 = wanted[i]
            if (c1 != c2) {
                if (!areConfusable(c1, c2)) return false
                diffs++
            }
        }
        return diffs in 1..3
    }

    private fun areConfusable(c1: Char, c2: Char): Boolean {
        val pair = if (c1 < c2) "$c1$c2" else "$c2$c1"
        return when (pair) {
            "1I", "1L", "IL" -> true // 1 vs I vs L
            "0O", "0Q", "0D", "DO", "OQ" -> true // 0 vs O vs D vs Q
            "8B" -> true // 8 vs B
            "5S" -> true // 5 vs S
            "2Z" -> true // 2 vs Z
            else -> false
        }
    }

    private fun areAdjacentInLine(b1: Rect, b2: Rect): Boolean {
        val minH = min(b1.height(), b2.height())
        val vertAligned = abs(b1.centerY() - b2.centerY()) <= max(minH * 0.7, 6.0).toInt()
        val horizontalGap = b2.left - b1.right
        val closeHorizontally = horizontalGap in -2..(max(minH * 1.5, 16.0).toInt())
        return vertAligned && closeHorizontally
    }

    private fun unionRect(r1: Rect, r2: Rect): Rect = Rect(
        min(r1.left, r2.left),
        min(r1.top, r2.top),
        max(r1.right, r2.right),
        max(r1.bottom, r2.bottom)
    )

    private fun deduplicateTokens(tokens: List<CandidateToken>): List<CandidateToken> {
        val result = mutableListOf<CandidateToken>()
        for (t in tokens) {
            val alreadyPresent = result.any { existing ->
                existing.normalized == t.normalized &&
                        abs(existing.bounds.centerX() - t.bounds.centerX()) < 10 &&
                        abs(existing.bounds.centerY() - t.bounds.centerY()) < 10
            }
            if (!alreadyPresent) {
                result += t
            }
        }
        return result
    }

    private fun findRowIndexForY(centerY: Int, yLines: List<Int>): Int {
        // Direct containment in a row interval
        val index = yLines.zipWithNext().indexOfFirst { (top, bottom) ->
            centerY in top..bottom
        }
        if (index >= 0) return index

        // Near-boundary tolerance (within 6px)
        return yLines.zipWithNext().indexOfFirst { (top, bottom) ->
            centerY in (top - 6)..(bottom + 6)
        }
    }

    private data class Geometry(
        val xLines: List<Int>,
        val yLines: List<Int>,
        val xCandidates: List<Int>,
        val yCandidates: List<Int>,
        val horizontalThreshold: Int,
        val verticalThreshold: Int
    )

    private fun detectGeometry(bitmap: Bitmap, days: Int): Geometry {
        val rgba = Mat()
        val gray = Mat()
        val enhanced = Mat()
        val binary = Mat()
        val horizontalMask = Mat()
        val verticalMask = Mat()
        val horizontalKernel = Mat()
        val verticalKernel = Mat()
        var clahe: CLAHE? = null

        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, enhanced)

            Imgproc.adaptiveThreshold(
                enhanced, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                31, 9.0
            )

            val horizontalLength = (bitmap.width / 28).coerceAtLeast(18)
            val verticalLength = (bitmap.height / 24).coerceAtLeast(12)
            horizontalKernel.create(1, horizontalLength, CvType.CV_8UC1)
            verticalKernel.create(verticalLength, 1, CvType.CV_8UC1)
            horizontalKernel.setTo(org.opencv.core.Scalar.all(1.0))
            verticalKernel.setTo(org.opencv.core.Scalar.all(1.0))

            Imgproc.morphologyEx(binary, horizontalMask, Imgproc.MORPH_OPEN, horizontalKernel)
            Imgproc.morphologyEx(binary, verticalMask, Imgproc.MORPH_OPEN, verticalKernel)

            val xThreshold = (bitmap.height * 0.18).roundToInt()
            val yThreshold = (bitmap.width * 0.18).roundToInt()

            val xCandidates = projectionCandidates(verticalMask, profileByX = true, minCount = xThreshold)
            val yCandidates = projectionCandidates(horizontalMask, profileByX = false, minCount = yThreshold)

            val xLines = selectRegularSequence(xCandidates, days + 1, bitmap.width, days)
                ?: throw IllegalStateException(
                    "Could not detect ${days + 1} regular day boundaries. Candidates: $xCandidates"
                )

            if (yCandidates.size < 3) {
                throw IllegalStateException("Too few horizontal row boundaries detected: $yCandidates")
            }

            return Geometry(
                xLines, yCandidates, xCandidates, yCandidates,
                horizontalThreshold = yThreshold,
                verticalThreshold = xThreshold
            )
        } finally {
            rgba.release()
            gray.release()
            enhanced.release()
            binary.release()
            horizontalMask.release()
            verticalMask.release()
            horizontalKernel.release()
            verticalKernel.release()
            clahe?.collectGarbage()
        }
    }

    private fun projectionCandidates(mask: Mat, profileByX: Boolean, minCount: Int): List<Int> {
        val width = mask.cols()
        val height = mask.rows()
        val pixels = ByteArray(width * height)
        mask.get(0, 0, pixels)

        val profile = IntArray(if (profileByX) width else height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((pixels[y * width + x].toInt() and 0xFF) != 0) {
                    if (profileByX) profile[x]++ else profile[y]++
                }
            }
        }

        val active = profile.map { it >= minCount }
        val result = mutableListOf<Int>()
        var start = -1
        for (i in active.indices) {
            if (active[i] && start == -1) start = i
            val ends = start != -1 && (!active[i] || i == active.lastIndex)
            if (ends) {
                val end = if (active[i]) i else i - 1
                result += (start + end) / 2
                start = -1
            }
        }
        return result
    }

    private fun selectRegularSequence(
        candidates: List<Int>,
        requiredCount: Int,
        imageWidth: Int,
        days: Int
    ): List<Int>? {
        if (candidates.size < requiredCount) return null
        val expectedGap = imageWidth * 0.72 / days
        val minGap = (expectedGap * 0.55).roundToInt().coerceAtLeast(8)
        val maxGap = (expectedGap * 1.50).roundToInt().coerceAtLeast(minGap + 1)

        var best: List<Int>? = null
        var bestScore = Double.POSITIVE_INFINITY
        for (start in 0..(candidates.size - requiredCount)) {
            val seq = candidates.subList(start, start + requiredCount)
            val gaps = seq.zipWithNext { a, b -> b - a }
            if (gaps.any { it !in minGap..maxGap }) continue
            val mean = gaps.average()
            val variance = gaps.map { (it - mean) * (it - mean) }.average()
            val regularity = variance / (mean * mean)
            val widthPenalty = abs(mean - expectedGap) / expectedGap
            val score = regularity + widthPenalty * 0.25
            if (score < bestScore) {
                bestScore = score
                best = seq.toList()
            }
        }
        return best
    }

    private fun enhanceCellCropForOcr(crop: Bitmap, applyClahe: Boolean): Pair<Bitmap, Pair<Int, Int>> {
        val src = Mat()
        val padded = Mat()
        val resized = Mat()
        val gray = Mat()
        val enhanced = Mat()
        var clahe: CLAHE? = null

        try {
            Utils.bitmapToMat(crop, src)

            // 1. Add white border padding (8px) for text-line separation
            val pad = 8
            org.opencv.core.Core.copyMakeBorder(
                src, padded,
                pad, pad, pad, pad,
                org.opencv.core.Core.BORDER_CONSTANT,
                org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
            )

            // 2. Upscale by 4x using INTER_CUBIC interpolation
            val targetSize = Size(padded.cols() * 4.0, padded.rows() * 4.0)
            Imgproc.resize(padded, resized, targetSize, 0.0, 0.0, Imgproc.INTER_CUBIC)

            // 3. Grayscale conversion
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)

            val finalMat = if (applyClahe) {
                // 4. CLAHE contrast enhancement
                clahe = Imgproc.createCLAHE(2.0, Size(4.0, 4.0))
                clahe.apply(gray, enhanced)
                enhanced
            } else {
                gray
            }

            val outBmp = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(finalMat, outBmp)
            return outBmp to (outBmp.width to outBmp.height)
        } finally {
            src.release()
            padded.release()
            resized.release()
            gray.release()
            enhanced.release()
            clahe?.collectGarbage()
        }
    }

    fun normalizeId(value: String): String =
        value.uppercase().filter { it.isLetterOrDigit() }

    private fun normalizeShift(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val token = text.trim().uppercase().replace('0', 'O').filter { it.isLetter() }
        return when (token) {
            "E", "N", "M", "A", "OFF" -> token
            else -> null
        }
    }

    private fun fuse(ocr: String?, color: String?): Pair<CellStatus, String> = when {
        ocr != null && color != null && ocr == color ->
            CellStatus.DIRECTLY_OBSERVED to "OCR and colour agree ($ocr)"
        ocr != null && color != null ->
            CellStatus.CONFLICT to "OCR ($ocr) and colour ($color) disagree"
        ocr != null -> CellStatus.OCR_ONLY to "Only OCR recognized shift ($ocr)"
        color != null -> CellStatus.COLOR_ONLY to "Only colour recognized shift ($color)"
        else -> CellStatus.UNKNOWN to "Neither channel recognized a shift"
    }

    private fun locateFloorColumn(
        visionText: Text,
        ocrScale: Float,
        geometry: Geometry,
        imageWidth: Int,
        imageHeight: Int
    ): FloorColumnInfo {
        val day1Left = geometry.xLines.first()
        val headerCutoff = (imageHeight * 0.40).toInt()

        var foundHeaderBounds: Rect? = null
        var foundHeaderRawText: String? = null
        var bestTop = Int.MAX_VALUE

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineBoundsOriginal = mapBoxToOriginal(line.boundingBox ?: continue, ocrScale)
                if (lineBoundsOriginal.top > headerCutoff) continue

                // Check line text
                val lineText = line.text.trim().uppercase()
                if ((lineText == "FLOOR" || lineText == "FLR" || lineText.contains("FLOOR")) &&
                    lineBoundsOriginal.centerX() < day1Left && lineBoundsOriginal.top < bestTop
                ) {
                    foundHeaderBounds = lineBoundsOriginal
                    foundHeaderRawText = line.text.trim()
                    bestTop = lineBoundsOriginal.top
                }

                // Check individual elements
                for (element in line.elements) {
                    val elemText = element.text.trim().uppercase()
                    if (elemText == "FLOOR" || elemText == "FLR" || elemText.contains("FLOOR")) {
                        val elemBoundsOriginal = mapBoxToOriginal(element.boundingBox ?: continue, ocrScale)
                        if (elemBoundsOriginal.top <= headerCutoff && elemBoundsOriginal.centerX() < day1Left && elemBoundsOriginal.top < bestTop) {
                            foundHeaderBounds = elemBoundsOriginal
                            foundHeaderRawText = element.text.trim()
                            bestTop = elemBoundsOriginal.top
                        }
                    }
                }
            }
        }

        val colBounds = calculateFloorColumnBounds(
            headerCenterX = foundHeaderBounds?.centerX(),
            xCandidates = geometry.xCandidates,
            day1Left = day1Left
        )

        return FloorColumnInfo(
            headerFound = (foundHeaderBounds != null),
            headerRawText = foundHeaderRawText,
            headerBounds = foundHeaderBounds,
            day1LeftBoundary = day1Left,
            columnBounds = colBounds
        )
    }

    fun calculateFloorColumnBounds(
        headerCenterX: Int?,
        xCandidates: List<Int>,
        day1Left: Int
    ): Pair<Int, Int>? {
        val sortedCandidates = xCandidates.sorted()
        val candidatesBeforeDay1 = sortedCandidates.filter { it < day1Left }
        if (candidatesBeforeDay1.isEmpty()) return null

        if (headerCenterX != null) {
            // 1. Look for interval in sortedCandidates that encloses headerCenterX
            for (i in 0 until sortedCandidates.size - 1) {
                val c1 = sortedCandidates[i]
                val c2 = sortedCandidates[i + 1]
                if (headerCenterX in c1..c2 && c1 < day1Left) {
                    val right = if (c2 >= day1Left || abs(c2 - day1Left) <= 12) day1Left else c2
                    return c1 to right
                }
            }
            // 2. If header is very close to a candidate or slightly outside
            val closestLeft = candidatesBeforeDay1.filter { it <= headerCenterX }.lastOrNull()
            if (closestLeft != null) {
                return closestLeft to day1Left
            }
        }

        // 3. Fallback when header is not found or no enclosing interval:
        // The FLOOR column is immediately adjacent to Day 1 column.
        val cRight = day1Left
        val cLeft = candidatesBeforeDay1.last()
        return cLeft to cRight
    }

    fun calculateFloorCropBounds(
        colBounds: Pair<Int, Int>,
        rowTop: Int,
        rowBottom: Int,
        imageWidth: Int,
        imageHeight: Int
    ): CropBounds {
        val colLeft = colBounds.first
        val colRight = colBounds.second
        val rowHeight = rowBottom - rowTop
        val colWidth = colRight - colLeft

        // Safe insets: avoid cutting 10-14px tall characters
        val insetX = (colWidth * 0.04).roundToInt().coerceIn(1, 3)
        val insetY = (rowHeight * 0.05).roundToInt().coerceIn(0, 1)

        val left = (colLeft + insetX).coerceIn(0, imageWidth - 1)
        val right = (colRight - insetX).coerceIn(left + 1, imageWidth)
        val top = (rowTop + insetY).coerceIn(0, imageHeight - 1)
        val bottom = (rowBottom - insetY).coerceIn(top + 1, imageHeight)

        return CropBounds(left, top, right, bottom)
    }

    private suspend fun extractEmployeeFloor(
        original: Bitmap,
        floorInfo: FloorColumnInfo,
        rowTop: Int,
        rowBottom: Int,
        recognizer: TextRecognizer,
        visionText: Text,
        ocrScale: Float
    ): FloorExtractionResult {
        val colBounds = floorInfo.columnBounds
            ?: return FloorExtractionResult(
                columnInfo = floorInfo,
                cropBounds = null,
                cropDimensions = null,
                rawOcrText = null,
                normalizedFloor = null,
                originalCropBitmap = null,
                enhancedCropBitmap = null
            )

        val cropBounds = calculateFloorCropBounds(
            colBounds = colBounds,
            rowTop = rowTop,
            rowBottom = rowBottom,
            imageWidth = original.width,
            imageHeight = original.height
        )

        if (cropBounds.width < 4 || cropBounds.height < 4) {
            return FloorExtractionResult(
                columnInfo = floorInfo,
                cropBounds = cropBounds,
                cropDimensions = cropBounds.width to cropBounds.height,
                rawOcrText = null,
                normalizedFloor = null,
                originalCropBitmap = null,
                enhancedCropBitmap = null
            )
        }

        // Original floor crop (preserved for UI diagnostics, not recycled)
        val originalCrop = Bitmap.createBitmap(
            original,
            cropBounds.left,
            cropBounds.top,
            cropBounds.width,
            cropBounds.height
        )

        var confirmedRawText: String? = null
        var confirmedFloor: String? = null

        // Pass 1: Upscaled Color with White Padding
        val variantColor = enhanceCropVariantColor(originalCrop)
        var bestEnhancedBitmap: Bitmap = variantColor
        val text1 = runOcrOnBitmap(variantColor, recognizer)
        val norm1 = normalizeFloorText(text1)
        if (norm1 != null) {
            confirmedRawText = text1
            confirmedFloor = norm1
        }

        // Pass 2: CLAHE Contrast Enhanced
        if (confirmedFloor == null) {
            val variantClahe = enhanceCropVariantClahe(originalCrop)
            val text2 = runOcrOnBitmap(variantClahe, recognizer)
            val norm2 = normalizeFloorText(text2)
            if (norm2 != null) {
                bestEnhancedBitmap = variantClahe
                confirmedRawText = text2
                confirmedFloor = norm2
            } else {
                variantClahe.recycle()
            }
        }

        // Pass 3: Grayscale Upscaled (no CLAHE)
        if (confirmedFloor == null) {
            val variantGray = enhanceCropVariantGray(originalCrop)
            val text3 = runOcrOnBitmap(variantGray, recognizer)
            val norm3 = normalizeFloorText(text3)
            if (norm3 != null) {
                bestEnhancedBitmap = variantGray
                confirmedRawText = text3
                confirmedFloor = norm3
            } else {
                variantGray.recycle()
            }
        }

        // Pass 4: Full-image visionText overlap
        if (confirmedFloor == null) {
            val overlapText = findTextInCellFromVisionText(visionText, ocrScale, colBounds, rowTop, rowBottom)
            val norm4 = normalizeFloorText(overlapText)
            if (norm4 != null) {
                confirmedRawText = overlapText
                confirmedFloor = norm4
            }
        }

        if (confirmedFloor == null && !confirmedRawText.isNullOrBlank()) {
            confirmedFloor = normalizeFloorText(confirmedRawText)
        }

        return FloorExtractionResult(
            columnInfo = floorInfo,
            cropBounds = cropBounds,
            cropDimensions = cropBounds.width to cropBounds.height,
            rawOcrText = confirmedRawText,
            normalizedFloor = confirmedFloor,
            originalCropBitmap = originalCrop,
            enhancedCropBitmap = bestEnhancedBitmap
        )
    }

    private fun enhanceCropVariantColor(crop: Bitmap): Bitmap {
        val src = Mat()
        val padded = Mat()
        val resized = Mat()
        try {
            Utils.bitmapToMat(crop, src)
            val pad = 16
            org.opencv.core.Core.copyMakeBorder(
                src, padded,
                pad, pad, pad, pad,
                org.opencv.core.Core.BORDER_CONSTANT,
                org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
            )
            val targetSize = Size(padded.cols() * 4.0, padded.rows() * 4.0)
            Imgproc.resize(padded, resized, targetSize, 0.0, 0.0, Imgproc.INTER_CUBIC)
            val out = Bitmap.createBitmap(resized.cols(), resized.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resized, out)
            return out
        } finally {
            src.release()
            padded.release()
            resized.release()
        }
    }

    private fun enhanceCropVariantClahe(crop: Bitmap): Bitmap {
        val src = Mat()
        val padded = Mat()
        val resized = Mat()
        val gray = Mat()
        val enhanced = Mat()
        var clahe: CLAHE? = null
        try {
            Utils.bitmapToMat(crop, src)
            val pad = 16
            org.opencv.core.Core.copyMakeBorder(
                src, padded,
                pad, pad, pad, pad,
                org.opencv.core.Core.BORDER_CONSTANT,
                org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
            )
            val targetSize = Size(padded.cols() * 4.0, padded.rows() * 4.0)
            Imgproc.resize(padded, resized, targetSize, 0.0, 0.0, Imgproc.INTER_CUBIC)
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
            clahe = Imgproc.createCLAHE(2.0, Size(4.0, 4.0))
            clahe.apply(gray, enhanced)
            val out = Bitmap.createBitmap(enhanced.cols(), enhanced.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(enhanced, out)
            return out
        } finally {
            src.release()
            padded.release()
            resized.release()
            gray.release()
            enhanced.release()
            clahe?.collectGarbage()
        }
    }

    private fun enhanceCropVariantGray(crop: Bitmap): Bitmap {
        val src = Mat()
        val padded = Mat()
        val resized = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(crop, src)
            val pad = 16
            org.opencv.core.Core.copyMakeBorder(
                src, padded,
                pad, pad, pad, pad,
                org.opencv.core.Core.BORDER_CONSTANT,
                org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
            )
            val targetSize = Size(padded.cols() * 4.0, padded.rows() * 4.0)
            Imgproc.resize(padded, resized, targetSize, 0.0, 0.0, Imgproc.INTER_CUBIC)
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
            val out = Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(gray, out)
            return out
        } finally {
            src.release()
            padded.release()
            resized.release()
            gray.release()
        }
    }

    private fun findTextInCellFromVisionText(
        visionText: Text,
        ocrScale: Float,
        colBounds: Pair<Int, Int>,
        rowTop: Int,
        rowBottom: Int
    ): String? {
        val colLeft = colBounds.first
        val colRight = colBounds.second
        val matchedElements = mutableListOf<Pair<Rect, String>>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val boxOriginal = mapBoxToOriginal(element.boundingBox ?: continue, ocrScale)
                    val xOverlap = max(0, min(boxOriginal.right, colRight) - max(boxOriginal.left, colLeft))
                    val yOverlap = max(0, min(boxOriginal.bottom, rowBottom) - max(boxOriginal.top, rowTop))
                    if (xOverlap > boxOriginal.width() * 0.35 && yOverlap > boxOriginal.height() * 0.35) {
                        matchedElements.add(boxOriginal to element.text.trim())
                    }
                }
            }
        }

        if (matchedElements.isEmpty()) return null
        matchedElements.sortBy { it.first.left }
        return matchedElements.joinToString(" ") { it.second }
    }

    private suspend fun runOcrOnBitmap(bmp: Bitmap, recognizer: TextRecognizer): String? {
        return try {
            val res = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
            val text = res.text.trim()
            if (text.isNotBlank()) text else null
        } catch (_: Exception) {
            null
        }
    }

    fun normalizeFloorText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim()
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(Regex("""[.,;:]"""), "")
            .replace(Regex("""\s+"""), " ")
            .uppercase()

        if (clean.isBlank()) return null

        // 1. Check known specific units/areas (check NICU and PICU before ICU)
        when {
            clean.contains("NICU") -> return "NICU"
            clean.contains("PICU") -> return "PICU"
            clean.contains("ICU") -> return "ICU"
            clean.contains("EMERGENCY") || clean == "ER" -> return "Emergency"
            clean == "OT" || clean.contains("OPERATION") -> return "OT"
            clean.contains("WING") -> return clean
            clean.startsWith("WARD") -> return clean
        }

        // 2. Matches ordinal + FLOOR (e.g. "4TH FLOOR", "4T FLOOR", "4 FLOOR", "4TH", "IST FLOOR", "STH FLOOR", "2N FLOOR")
        var text = clean
        if (text.startsWith("IST ") || text == "IST") text = "1ST " + text.removePrefix("IST").trim()
        if (text.startsWith("STH ") || text == "STH") text = "5TH " + text.removePrefix("STH").trim()
        text = text.replace("FL00R", "FLOOR").replace("FL0R", "FLOOR")

        val floorNumberRegex = Regex("""\b(\d{1,2})\s*(?:ST|ND|RD|TH|T|S|D|R|N)?\s*(?:FLOOR|FLR)?\b""")
        val match = floorNumberRegex.find(text)
        if (match != null && (text.contains("FLOOR") || text.contains("FLR") || text.matches(Regex("""\d{1,2}(?:ST|ND|RD|TH|T|S|D|R|N)?""")))) {
            val num = match.groupValues[1].toIntOrNull()
            if (num != null && num in 1..99) {
                val ordinal = when {
                    num % 100 in 11..13 -> "${num}TH"
                    num % 10 == 1 -> "${num}ST"
                    num % 10 == 2 -> "${num}ND"
                    num % 10 == 3 -> "${num}RD"
                    else -> "${num}TH"
                }
                return "$ordinal FLOOR"
            }
        }

        val reverseRegex = Regex("""\b(?:FLOOR|FLR)\s*(\d{1,2})\b""")
        val reverseMatch = reverseRegex.find(text)
        if (reverseMatch != null) {
            val num = reverseMatch.groupValues[1].toIntOrNull()
            if (num != null && num in 1..99) {
                val ordinal = when {
                    num % 100 in 11..13 -> "${num}TH"
                    num % 10 == 1 -> "${num}ST"
                    num % 10 == 2 -> "${num}ND"
                    num % 10 == 3 -> "${num}RD"
                    else -> "${num}TH"
                }
                return "$ordinal FLOOR"
            }
        }

        val letterCount = clean.count { it.isLetter() }
        val digitCount = clean.count { it.isDigit() }
        if ((letterCount >= 3 || (letterCount >= 1 && digitCount >= 1)) && clean.length >= 2) {
            return clean
        }

        return null
    }
}
