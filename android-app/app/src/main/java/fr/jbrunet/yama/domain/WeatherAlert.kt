package fr.jbrunet.yama.domain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class WeatherAlertType(val defaultThreshold: Double) {
    RAIN(5.0), GUSTS(60.0), COLD(0.0), HEAT(32.0);
}

enum class WeatherAlertHorizon { NEXT_HOUR, TONIGHT, TOMORROW, NEXT_WEEK }

@Serializable
data class WeatherAlertRule(
    val id: String,
    val type: WeatherAlertType,
    val threshold: Double,
    val horizon: WeatherAlertHorizon,
    /** Null follows the current location. */
    val place: Place? = null,
    val enabled: Boolean = true,
) {
    companion object {
        val LegacyRain = WeatherAlertRule("legacy-rain", WeatherAlertType.RAIN, RAIN_THRESHOLD_MM, WeatherAlertHorizon.NEXT_HOUR)
    }
}

internal data class AlertWindow(val start: Long, val end: Long)

internal fun WeatherAlertHorizon.window(now: Instant, zone: ZoneId): AlertWindow {
    val local = now.atZone(zone)
    val date = local.toLocalDate()
    if (this == WeatherAlertHorizon.NEXT_HOUR) return AlertWindow(now.epochSecond, now.epochSecond + 3600)
    val (start, end) = when (this) {
        WeatherAlertHorizon.TONIGHT -> {
            val evening = if (local.toLocalTime().hour < 8) date.minusDays(1) else date
            evening.atTime(22, 0).atZone(zone) to evening.plusDays(1).atTime(LocalTime.of(8, 0)).atZone(zone)
        }
        WeatherAlertHorizon.TOMORROW -> date.plusDays(1).atStartOfDay(zone) to date.plusDays(2).atStartOfDay(zone)
        WeatherAlertHorizon.NEXT_WEEK -> {
            val monday = date.plusDays(8L - date.dayOfWeek.value)
            monday.atStartOfDay(zone) to monday.plusWeeks(1).atStartOfDay(zone)
        }
        WeatherAlertHorizon.NEXT_HOUR -> error("Handled above")
    }
    return AlertWindow(start.toEpochSecond(), end.toEpochSecond())
}

internal data class AlertSample(
    val time: Long,
    val intervalSeconds: Int,
    val rain: Double?,
    val gust: Double?,
    val temperature: Double?,
)

internal data class WeatherAlertMatch(
    val window: AlertWindow,
    val time: Long,
    val value: Double,
    val episodeStart: Long,
    val episodeEnd: Long,
)

/** Accumulations and gust maxima refer to the interval ending at the sample time. */
internal fun WeatherAlertRule.evaluate(window: AlertWindow, now: Long, samples: List<AlertSample>): WeatherAlertMatch? {
    val start = maxOf(window.start, now)
    var rainCoverage = 0L
    val conditions = samples.mapNotNull { sample ->
        val intervalStart = sample.time - sample.intervalSeconds
        val overlap = minOf(sample.time, window.end) - maxOf(intervalStart, start)
        val value = when (type) {
            WeatherAlertType.RAIN -> sample.rain?.takeIf { overlap > 0 }?.let {
                rainCoverage += overlap
                it * overlap / sample.intervalSeconds
            }
            WeatherAlertType.GUSTS -> sample.gust?.takeIf { overlap > 0 }
            WeatherAlertType.COLD, WeatherAlertType.HEAT -> sample.temperature?.takeIf { sample.time in start until window.end }
        } ?: return@mapNotNull null
        maxOf(start, if (type == WeatherAlertType.RAIN || type == WeatherAlertType.GUSTS) intervalStart else sample.time) to value
    }
    if (type == WeatherAlertType.RAIN && rainCoverage < window.end - start) return null
    val matching = when (type) {
        WeatherAlertType.RAIN -> conditions.filter { it.second > 0 }
        WeatherAlertType.COLD -> conditions.filter { it.second < threshold }
        else -> conditions.filter { it.second > threshold }
    }
    val first = matching.firstOrNull() ?: return null
    val value = when (type) {
        WeatherAlertType.RAIN -> matching.sumOf { it.second }.takeIf { it > threshold } ?: return null
        WeatherAlertType.COLD -> matching.minOf { it.second }
        else -> matching.maxOf { it.second }
    }
    val time = if (type == WeatherAlertType.RAIN) first.first else matching.first { it.second == value }.first
    return WeatherAlertMatch(window, time, value, first.first, matching.last().first)
}

@Serializable
internal data class SentWeatherAlert(val start: Long, val end: Long) {
    fun matches(rule: WeatherAlertRule, hit: WeatherAlertMatch): Boolean =
        if (rule.horizon == WeatherAlertHorizon.NEXT_HOUR) {
            hit.episodeStart <= end + 30 * 60 && hit.episodeEnd >= start - 30 * 60
        } else start == hit.window.start

    fun extend(hit: WeatherAlertMatch) = SentWeatherAlert(minOf(start, hit.episodeStart), maxOf(end, hit.episodeEnd))
}

internal fun weatherAlertPlaceKey(rule: WeatherAlertRule): String {
    val place = rule.place ?: return "current"
    // Use the same coordinate granularity as the forecast requests.
    return "${kotlin.math.round(place.latitude * 100).toInt()},${kotlin.math.round(place.longitude * 100).toInt()}"
}
