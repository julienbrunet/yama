package fr.jbrunet.yama.ui

import android.graphics.Bitmap
import android.graphics.Color
import fr.jbrunet.yama.ui.map.RainLevel
import fr.jbrunet.yama.ui.map.renderRadarTile
import fr.jbrunet.yama.ui.map.smoothRainArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class RainMapRenderingTest {
    @Test
    fun paletteFadesInAndMatchesTheLegend() {
        val faintRain = Color.alpha(smoothRainArgb(0.04))
        assertEquals(0, Color.alpha(smoothRainArgb(0.0)))
        assertTrue(faintRain in 1 until Color.alpha(RainLevel.VERY_LIGHT.color))
        RainLevel.entries.forEach { level ->
            assertEquals(level.color, smoothRainArgb(level.minMmPerHour / 4))
        }
    }

    @Test
    fun radarUsesTheSharedPaletteAndSoftensPixelEdges() {
        // 30 dBZ in the Universal Blue palette corresponds to about 2.73 mm/h.
        val tile = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888)
        for (y in 2..6) for (x in 2..6) tile.setPixel(x, y, 0xFF005588.toInt())
        val rendered = renderRadarTile(tile)

        val center = rendered.getPixel(4, 4)
        val expected = smoothRainArgb(2.73 / 4)
        assertEquals(Color.alpha(expected), Color.alpha(center))
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(center)) <= 1)
        assertTrue(Color.alpha(rendered.getPixel(1, 1)) in 1 until Color.alpha(rendered.getPixel(1, 3)))
        assertEquals(0, Color.alpha(rendered.getPixel(0, 0)))
    }
}
