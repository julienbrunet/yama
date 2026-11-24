package fr.jbrunet.yama.work

import android.content.Context
import fr.jbrunet.yama.data.local.SettingsStore
import fr.jbrunet.yama.data.remote.AlertSeriesDto
import fr.jbrunet.yama.data.remote.Network
import fr.jbrunet.yama.data.remote.OpenMeteoApi
import fr.jbrunet.yama.data.remote.OpenMeteoQuota
import fr.jbrunet.yama.data.repository.roundCoordinate
import fr.jbrunet.yama.domain.AlertSample
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.SentWeatherAlert
import fr.jbrunet.yama.domain.WeatherAlertHorizon
import fr.jbrunet.yama.domain.WeatherAlertMatch
import fr.jbrunet.yama.domain.WeatherAlertRule
import fr.jbrunet.yama.domain.evaluate
import fr.jbrunet.yama.domain.weatherAlertPlaceKey
import fr.jbrunet.yama.domain.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** Downloads one forecast per alert location, and retains the last dispatch for each rule. */
class WeatherAlertChecker(
    private val context: Context,
    private val api: OpenMeteoApi,
    private val settings: SettingsStore,
    private val currentPlace: suspend () -> Place?,
) {
    private val sent = context.getSharedPreferences("sent_weather_alerts", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun check(rules: List<WeatherAlertRule>) {
        val enabled = rules.filter { it.enabled }
        if (enabled.isEmpty() || !Notifications.canPostWeather(context) || OpenMeteoQuota.isBlocked) return
        val here = if (enabled.any { it.place == null }) currentPlace() else null
        val groups = enabled.mapNotNull { rule -> (rule.place ?: here)?.let { it to rule } }
            .groupBy { (place, _) -> roundCoordinate(place.latitude) to roundCoordinate(place.longitude) }
        for ((point, entries) in groups) {
            if (OpenMeteoQuota.isBlocked) break
            val horizons = entries.map { it.second.horizon }.toSet()
            val hasShort = WeatherAlertHorizon.NEXT_HOUR in horizons
            val forecast = try {
                api.alertForecast(
                    point.first, point.second,
                    days = if (WeatherAlertHorizon.NEXT_WEEK in horizons) 15 else 3,
                    steps = if (hasShort) 8 else null,
                    minutely = if (hasShort) OpenMeteoApi.ALERT_VARIABLES else null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            val zone = runCatching { ZoneId.of(forecast.timezone) }.getOrNull() ?: continue
            val now = Instant.now()
            val hourly = forecast.hourly.samples(3600)
            val short = forecast.minutely?.samples(900).orEmpty()
            val active = settings.current().weatherAlerts.toSet()
            for ((place, rule) in entries) {
                if (rule !in active) continue
                val samples = if (rule.horizon == WeatherAlertHorizon.NEXT_HOUR && short.isNotEmpty()) short else hourly
                val hit = rule.evaluate(rule.horizon.window(now, zone), now.epochSecond, samples) ?: continue
                deliver(rule, place, hit, zone)
            }
        }
    }

    private suspend fun deliver(rule: WeatherAlertRule, place: Place, hit: WeatherAlertMatch, zone: ZoneId) = mutex.withLock {
        if (!Notifications.canPostWeather(context)) return@withLock
        val key = "${rule.id}/${weatherAlertPlaceKey(rule)}"
        val prior = sent.getString(key, null)?.let { runCatching { Network.json.decodeFromString<SentWeatherAlert>(it) }.getOrNull() }
        val rolling = rule.horizon == WeatherAlertHorizon.NEXT_HOUR
        val repeated = prior?.matches(rule, hit) == true
        val state = when {
            repeated && rolling -> requireNotNull(prior).extend(hit)
            repeated -> return@withLock
            rolling -> SentWeatherAlert(hit.episodeStart, hit.episodeEnd)
            else -> SentWeatherAlert(hit.window.start, hit.window.end)
        }
        if (state == prior) return@withLock
        // Record the dispatch before calling Android so a worker retry cannot re-send it.
        val saved = sent.edit().putString(key, Network.json.encodeToString(state)).commit()
        if (saved && !repeated) Notifications.weather(context, rule, place, hit, zone)
    }

    private fun AlertSeriesDto.samples(seconds: Int): List<AlertSample> = time.mapIndexed { i, timestamp ->
        val rain = rain.getOrNull(i)
        val showers = showers.getOrNull(i)
        AlertSample(
            timestamp, seconds,
            if (rain != null || showers != null) (rain ?: 0.0) + (showers ?: 0.0) else null,
            gusts.getOrNull(i), temperature.getOrNull(i),
        )
    }
}
