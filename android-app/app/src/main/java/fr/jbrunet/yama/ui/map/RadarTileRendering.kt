package fr.jbrunet.yama.ui.map

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

// Opaque rain RGB entries for 15–65 dBZ, followed by the highest band (75+).
// https://www.rainviewer.com/api/color-schemes.html
private val RadarReflectivity = intArrayOf(
    0x88DDEE, 0x6CD1EB, 0x51C5E8, 0x36BAE5, 0x1BAEE2, 0x00A3E0, 0x009AD5, 0x0091CA,
    0x0088BF, 0x007FB4, 0x0077AA, 0x0070A3, 0x00699C, 0x006295, 0x005B8E, 0x005588,
    0x005180, 0x004E78, 0x004A70, 0x004768, 0xFFEE00, 0xFFE000, 0xFFD200, 0xFFC500,
    0xFFB700, 0xFFAA00, 0xFF9F00, 0xFF9500, 0xFF8B00, 0xFF8100, 0xFF4400, 0xF23600,
    0xE62800, 0xD91B00, 0xCD0D00, 0xC10000, 0xA80000, 0x8F0000, 0x760000, 0x5D0000,
    0xFFAAFF, 0xFF9FFF, 0xFF95FF, 0xFF8BFF, 0xFF81FF, 0xFF77FF, 0xFF6CFF, 0xFF62FF,
    0xFF58FF, 0xFF4EFF, 0xFFFFFF, 0x00FF00,
).withIndex().associate { (index, color) -> color to (if (color == 0x00FF00) 75 else index + 15) }

// Marshall–Palmer Z = 200 R^1.6. Radar-derived rain rates are estimates for the map legend.
private val RadarRainRates = FloatArray(86) { i ->
    val z = 10.0.pow((i - 10) / 10.0)
    ((z / 200).pow(1.0 / 1.6) / 4).toFloat()
}

private fun radarRainRate(argb: Int): Float {
    val alpha = Color.alpha(argb)
    val dbz = when {
        alpha < 20 -> return 0f
        alpha <= 100 -> ((alpha - 20) * 15 / 80f).roundToInt() - 10
        alpha <= 190 -> (alpha - 100) / 10 + 5
        alpha == 255 -> RadarReflectivity[argb and 0xFFFFFF] ?: return 0f
        else -> return 0f
    }
    return RadarRainRates[dbz + 10]
}

/** Softens reflectivity contours before applying the same gradient as the forecast. */
internal fun renderRadarTile(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val rates = FloatArray(pixels.size) { radarRainRate(pixels[it]) }
    val horizontal = FloatArray(pixels.size)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val left = row + (x - 1).coerceAtLeast(0)
            val right = row + (x + 1).coerceAtMost(width - 1)
            horizontal[row + x] = (rates[left] + 2 * rates[row + x] + rates[right]) / 4
        }
    }
    for (y in 0 until height) {
        val row = y * width
        val top = (y - 1).coerceAtLeast(0) * width
        val bottom = (y + 1).coerceAtMost(height - 1) * width
        for (x in 0 until width) {
            val rate = (horizontal[top + x] + 2 * horizontal[row + x] + horizontal[bottom + x]) / 4
            pixels[row + x] = smoothRainArgb(rate.toDouble())
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
