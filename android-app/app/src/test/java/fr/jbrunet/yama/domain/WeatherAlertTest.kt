package fr.jbrunet.yama.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WeatherAlertTest {
    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun tomorrowUsesThePlacesCalendarIncludingDaylightSaving() {
        val window = WeatherAlertHorizon.TOMORROW.window(Instant.parse("2026-10-24T12:00:00Z"), paris)
        assertEquals(Instant.parse("2026-10-24T22:00:00Z").epochSecond, window.start)
        assertEquals(25 * 3600L, window.end - window.start)
    }

    @Test
    fun nightKeepsItsIdentityAfterMidnight() {
        val before = WeatherAlertHorizon.TONIGHT.window(Instant.parse("2026-11-24T22:00:00Z"), paris)
        val after = WeatherAlertHorizon.TONIGHT.window(Instant.parse("2026-11-25T03:00:00Z"), paris)
        assertEquals(before, after)
    }

    @Test
    fun rainSumsOnlyOverlappingIntervalsAndRequiresCoverage() {
        val rule = WeatherAlertRule("r", WeatherAlertType.RAIN, 5.0, WeatherAlertHorizon.NEXT_HOUR)
        val window = AlertWindow(450, 4050)
        val samples = (1L..5L).map { AlertSample(it * 900, 900, 2.0, null, null) }
        assertEquals(8.0, rule.evaluate(window, 450, samples)?.value)
        assertNull(rule.evaluate(window, 450, samples.dropLast(2)))
    }

    @Test
    fun changedForecastDoesNotRepeatAnAlertForTheSameDay() {
        val rule = WeatherAlertRule("t", WeatherAlertType.COLD, 0.0, WeatherAlertHorizon.TOMORROW)
        val window = AlertWindow(86400, 172800)
        val hit = rule.evaluate(window, 0, listOf(AlertSample(90000, 3600, null, null, -2.0)))!!
        assertTrue(SentWeatherAlert(window.start, window.end).matches(rule, hit))
        assertFalse(SentWeatherAlert(0, 86400).matches(rule, hit))
    }

    @Test
    fun aMovingShowerStillMatchesThePreviouslyAnnouncedEpisode() {
        val rule = WeatherAlertRule("r", WeatherAlertType.RAIN, 5.0, WeatherAlertHorizon.NEXT_HOUR)
        val previous = SentWeatherAlert(3600, 4500)
        assertTrue(previous.matches(rule, WeatherAlertMatch(AlertWindow(4800, 8400), 5400, 8.0, 5400, 6300)))
        assertFalse(previous.matches(rule, WeatherAlertMatch(AlertWindow(8000, 11600), 8100, 4.5, 8100, 9000)))
    }
}
