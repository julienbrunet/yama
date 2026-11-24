package fr.jbrunet.yama.ui

import fr.jbrunet.yama.Fixtures
import fr.jbrunet.yama.domain.MapFrame
import fr.jbrunet.yama.ui.map.ForecastHorizon
import fr.jbrunet.yama.ui.map.MapUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTimelineTest {
    private val map = Fixtures.rainMap()
    private val state = MapUiState.Ready(Fixtures.paris, map, map.nowIndex)

    @Test
    fun forecastHorizonsStartAtNowAndEndAtTheirCutoff() {
        val now = map.frames[map.nowIndex].time
        val hour = state.copy(horizon = ForecastHorizon.ONE_HOUR)
        val halfDay = state.copy(horizon = ForecastHorizon.TWELVE_HOURS)

        assertEquals(hour.forecastStart, hour.playbackRange.first)
        assertEquals(now + 3600, map.frames[hour.playbackRange.last].time)
        assertEquals(now + 12 * 3600, map.frames[halfDay.playbackRange.last].time)
        assertEquals(map.frames.lastIndex, state.playbackRange.last)
        assertEquals(24 * 60, state.forecastMinutes)
    }

    @Test
    fun partialForecastUsesItsActualDurationAndKeepsTheRadarAccessible() {
        val partial = map.copy(frames = map.frames.take(map.nowIndex + 19))
        val ready = state.copy(horizon = ForecastHorizon.TWELVE_HOURS).withMap(partial)

        assertEquals(4 * 60 + 45, ready.forecastMinutes)
        assertEquals(ForecastHorizon.AVAILABLE, ready.horizon)
        assertTrue(ready.supports(ForecastHorizon.ONE_HOUR))
        assertFalse(ready.supports(ForecastHorizon.TWELVE_HOURS))
        assertEquals(0 until partial.nowIndex, ready.copy(index = 0).playbackRange)
    }

    @Test
    fun radarOnlyKeepsItsTimelineUntilForecastArrives() {
        val radar = map.copy(frames = map.frames.filterIsInstance<MapFrame.Radar>())
        val ready = MapUiState.Ready(Fixtures.paris, radar, radar.frames.lastIndex)

        assertNull(ready.forecastStart)
        assertFalse(ready.supports(ForecastHorizon.AVAILABLE))
        assertEquals(radar.frames.indices, ready.playbackRange)
        assertEquals(map.nowIndex, ready.withMap(map).index)
    }
}
