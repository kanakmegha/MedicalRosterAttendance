package com.example.rosterrebuild

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Provisional classifier based on approximate RGB medians observed in the
 * September sample. Replace these references with measured labelled crop
 * distributions before relying on predictions.
 */
object ColorClassifier {
    private val references = mapOf(
        "E" to Triple(252.0, 252.0, 14.0),
        "N" to Triple(250.5, 190.0, 9.5),
        "M" to Triple(251.0, 196.0, 201.0),
        "A" to Triple(7.5, 172.5, 232.0),
        "OFF" to Triple(233.0, 34.5, 34.0)
    )

    data class Prediction(
        val code: String?,
        val bestDistance: Double?,
        val secondDistance: Double?,
        val margin: Double?,
        val reason: String
    )

    fun classify(originalColorCrop: Bitmap): Prediction {
        val w = originalColorCrop.width
        val h = originalColorCrop.height
        if (w < 3 || h < 3) return Prediction(null, null, null, null, "Crop dimensions too small")

        // 4 interior corner patches away from central text glyphs:
        // Top-Left, Top-Right, Bottom-Left, Bottom-Right
        val cornerPatches = listOf(
            0.05f to 0.25f to (0.10f to 0.45f), // TL
            0.75f to 0.95f to (0.10f to 0.45f), // TR
            0.05f to 0.25f to (0.55f to 0.90f), // BL
            0.75f to 0.95f to (0.55f to 0.90f)  // BR
        )

        val rs = mutableListOf<Double>()
        val gs = mutableListOf<Double>()
        val bs = mutableListOf<Double>()

        for ((xRange, yRange) in cornerPatches) {
            val px0 = (w * xRange.first).toInt().coerceIn(0, w - 1)
            val px1 = (w * xRange.second).toInt().coerceIn(px0 + 1, w)
            val py0 = (h * yRange.first).toInt().coerceIn(0, h - 1)
            val py1 = (h * yRange.second).toInt().coerceIn(py0 + 1, h)

            for (y in py0 until py1) for (x in px0 until px1) {
                val p = originalColorCrop.getPixel(x, y)
                val hsv = FloatArray(3)
                Color.RGBToHSV(Color.red(p), Color.green(p), Color.blue(p), hsv)
                if (hsv[1] >= 0.20f && hsv[2] >= 0.25f) {
                    rs += Color.red(p).toDouble()
                    gs += Color.green(p).toDouble()
                    bs += Color.blue(p).toDouble()
                }
            }
        }

        // Fall back to central region if corners have too few saturated pixels
        if (rs.size < 4) {
            val x0 = (w * 0.20).toInt().coerceIn(0, w - 1)
            val x1 = (w * 0.80).toInt().coerceIn(x0 + 1, w)
            val y0 = (h * 0.20).toInt().coerceIn(0, h - 1)
            val y1 = (h * 0.80).toInt().coerceIn(y0 + 1, h)

            for (y in y0 until y1) for (x in x0 until x1) {
                val p = originalColorCrop.getPixel(x, y)
                val hsv = FloatArray(3)
                Color.RGBToHSV(Color.red(p), Color.green(p), Color.blue(p), hsv)
                if (hsv[1] >= 0.20f && hsv[2] >= 0.25f) {
                    rs += Color.red(p).toDouble()
                    gs += Color.green(p).toDouble()
                    bs += Color.blue(p).toDouble()
                }
            }
        }

        if (rs.size < 4) return Prediction(null, null, null, null, "Too few saturated pixels")

        val sample = lab(Triple(median(rs), median(gs), median(bs)))
        val ranked = references.map { (code, rgb) -> code to distance(sample, lab(rgb)) }
            .sortedBy { it.second }
        val best = ranked[0]
        val second = ranked[1]
        val margin = second.second - best.second

        if (best.second > 35.0)
            return Prediction(null, best.second, second.second, margin, "Out of provisional colour range")
        if (margin < 5.0)
            return Prediction(null, best.second, second.second, margin, "Ambiguous colour classes")
        return Prediction(best.first, best.second, second.second, margin, "Nearest provisional Lab reference")
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2.0 else s[m]
    }

    private data class Lab(val l: Double, val a: Double, val b: Double)

    private fun lab(rgb: Triple<Double, Double, Double>): Lab {
        fun linear(v0: Double): Double {
            val v = v0 / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(rgb.first); val g = linear(rgb.second); val b = linear(rgb.third)
        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = r * 0.2126 + g * 0.7152 + b * 0.0722
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
        val fx = f(x); val fy = f(y); val fz = f(z)
        return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun distance(a: Lab, b: Lab): Double =
        sqrt((a.l-b.l).pow(2) + (a.a-b.a).pow(2) + (a.b-b.b).pow(2))
}
