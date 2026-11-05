package com.example.rosterrebuild

import android.graphics.Bitmap
import android.graphics.Rect

enum class CellStatus {
    DIRECTLY_OBSERVED, OCR_ONLY, COLOR_ONLY, CONFLICT, UNKNOWN, MISSING_CROP
}

data class CellResult(
    val day: Int,
    val bounds: Rect?,
    val crop: Bitmap?,
    val ocrText: String?,
    val rawOcrText: String? = null,
    val enhancedCropDimensions: Pair<Int, Int>? = null,
    val colorCode: String?,
    val colorDistance: Double?,
    val secondColorDistance: Double? = null,
    val detectedRowHeight: Int? = null,
    val cropPaddingUsed: Pair<Int, Int>? = null,
    val status: CellStatus,
    val reason: String
)

data class GeometryDiagnostics(
    val xCandidates: List<Int>,
    val yCandidates: List<Int>,
    val selectedX: List<Int>,
    val selectedY: List<Int>,
    val horizontalThreshold: Int,
    val verticalThreshold: Int
)

data class IdCandidate(
    val text: String,
    val normalizedText: String,
    val bounds: Rect,
    val confidence: Float? = null,
    val isConfusionMatch: Boolean = false,
    val reason: String = ""
)

data class CropBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun toRect(): Rect = Rect(left, top, right, bottom)
}

data class DetectedPeriod(
    val month: Int,
    val year: Int,
    val monthName: String,
    val displayText: String,
    val rawHeaderMatch: String
)

data class FloorColumnInfo(
    val headerFound: Boolean,
    val headerRawText: String?,
    val headerBounds: Rect?,
    val day1LeftBoundary: Int?,
    val columnBounds: Pair<Int, Int>?
)

data class FloorExtractionResult(
    val columnInfo: FloorColumnInfo,
    val cropBounds: CropBounds?,
    val cropDimensions: Pair<Int, Int>?,
    val rawOcrText: String?,
    val normalizedFloor: String?,
    val originalCropBitmap: Bitmap?,
    val enhancedCropBitmap: Bitmap?
)

data class ExtractionDiagnostics(
    val sourceDimensions: Pair<Int, Int>,
    val allOcrLines: List<String>,
    val employeeIdExactMatch: Boolean,
    val matchedIdToken: String?,
    val idCandidates: List<IdCandidate>,
    val employeeIdBounds: Rect?,
    val detectedMonth: Int?,
    val detectedYear: Int?,
    val detectedPeriodDisplay: String?,
    val dayColumnsCount: Int,
    val employeeRowBoundaries: Pair<Int, Int>?,
    val cellCropsCount: Int,
    val floorHeaderFound: Boolean = false,
    val floorHeaderRawText: String? = null,
    val floorHeaderBounds: Rect? = null,
    val day1LeftBoundary: Int? = null,
    val floorColumnBounds: Pair<Int, Int>? = null,
    val floorCropBounds: CropBounds? = null,
    val floorCropDimensions: Pair<Int, Int>? = null,
    val rawFloorOcrText: String? = null,
    val extractedMonthlyFloor: String? = null,
    val originalFloorCropBitmap: Bitmap? = null,
    val enhancedFloorCropBitmap: Bitmap? = null,
    val warnings: List<String> = emptyList()
)

data class RosterResult(
    val employeeId: String,
    val matchedOcrToken: String,
    val employeeRowIndex: Int,
    val expectedDays: Int,
    val detectedPeriod: DetectedPeriod?,
    val cells: List<CellResult>,
    val schedule: List<DailyScheduleEntry> = emptyList(),
    val extractedFloor: String? = null,
    val originalFloorCropBitmap: Bitmap? = null,
    val enhancedFloorCropBitmap: Bitmap? = null,
    val geometry: GeometryDiagnostics,
    val diagnostics: ExtractionDiagnostics,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val warnings: List<String>
)

class ExtractionException(
    message: String,
    val diagnostics: ExtractionDiagnostics? = null
) : Exception(message)
