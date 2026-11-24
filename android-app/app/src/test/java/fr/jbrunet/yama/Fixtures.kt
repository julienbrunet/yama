package fr.jbrunet.yama

import fr.jbrunet.yama.data.remote.ForecastDto
import fr.jbrunet.yama.data.remote.GeocodingDto
import fr.jbrunet.yama.data.remote.GridPointDto
import fr.jbrunet.yama.data.remote.Network
import fr.jbrunet.yama.data.repository.LocalForecast
import fr.jbrunet.yama.data.repository.RainMapRepository
import fr.jbrunet.yama.data.repository.toDomain
import fr.jbrunet.yama.domain.ForecastGrid
import fr.jbrunet.yama.domain.MapFrame
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.RainMap
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Test data: forecast.json and geocoding.json are real Open-Meteo responses
 * (the “hourly” block of forecast.json was added, consistent with the real min/max, as were
 * the sunrise and sunset times in Paris);
 * grid.json follows the documented multi-point format (generated, sandbox quota reached).
 */
object Fixtures {
    private fun read(name: String) = requireNotNull(javaClass.classLoader?.getResource(name)).readText()

    fun forecastDto(): ForecastDto = Network.json.decodeFromString(ForecastDto.serializer(), read("forecast.json"))
    fun geocodingDto(): GeocodingDto = Network.json.decodeFromString(GeocodingDto.serializer(), read("geocoding.json"))
    fun gridDto(): List<GridPointDto> =
        Network.json.decodeFromString(ListSerializer(GridPointDto.serializer()), read("grid.json"))

    val paris = Place("Paris", 48.8566, 2.3522, "Position actuelle")

    fun localForecast(offline: Boolean = false) = LocalForecast(paris, forecastDto().toDomain(), 1_792_178_100_000L, if (offline) fr.jbrunet.yama.data.repository.StaleReason.RATE_LIMITED else null)

    /** Synthetic rain map with a moving front and scattered showers. */
    fun rainMap(grid: ForecastGrid = RainMapRepository.gridAround(paris)): RainMap {
        val now = 1_792_180_800L
        val radar = (6 downTo 1).map { MapFrame.Radar(now - it * 600, "https://example.invalid/{z}/{x}/{y}.png") }
        val forecast = (0 until 96).map { step ->
            MapFrame.Forecast(
                grid = grid,
                time = now + step * 900,
                values = FloatArray(grid.rows * grid.cols) { k ->
                    val y = (k / grid.cols).toDouble() / (grid.rows - 1)
                    val x = (k % grid.cols).toDouble() / (grid.cols - 1) - step * 0.0015
                    val center = 0.26 + (y - 0.3) * 0.46 + 0.05 * sin(21 * y)
                    val front = exp(-((x - center) / 0.14).pow(2) - ((y - 0.43) / 0.34).pow(4))
                    val shower = exp(-((x - 0.76) / 0.23).pow(2) - ((y - 0.53) / 0.16).pow(2))
                    val northern = exp(-((x - 0.67) / 0.22).pow(2) - ((y - 0.75) / 0.13).pow(2))
                    (front * (2.2 + 1.1 * sin(23 * y)) + shower * 0.42 + northern * 0.24).toFloat()
                },
                // Wide cloud front around the shower, plus a clear area to the south-east.
                clouds = fr.jbrunet.yama.domain.CloudLayer(grid, FloatArray(grid.rows * grid.cols) { k ->
                    val r = k / grid.cols
                    val c = k % grid.cols
                    (100 - 9.0 * kotlin.math.abs(r - 9 - (c - 4) * 0.5)).coerceIn(0.0, 100.0).toFloat()
                }),
            )
        }
        return RainMap(grid, radar + forecast)
    }

    /** Synthetic wind: south-westerly, strengthening towards the north of the grid, on a coarse grid. */
    fun wind(grid: fr.jbrunet.yama.domain.ForecastGrid): List<fr.jbrunet.yama.domain.WindFrame> {
        val g = RainMapRepository.coarse(grid)
        val n = g.rows * g.cols
        return listOf(
            fr.jbrunet.yama.domain.WindFrame(
                time = 1_792_180_800L - 3600,
                grid = g,
                speed = FloatArray(n) { k -> 5f + (k / g.cols) * 9f + (k % g.cols) * 2f },
                direction = FloatArray(n) { k -> 200f + (k % g.cols) * 8f },
                gusts = FloatArray(n) { 0f },
            ),
        )
    }
}
