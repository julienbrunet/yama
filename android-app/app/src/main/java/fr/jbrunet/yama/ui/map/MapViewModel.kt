package fr.jbrunet.yama.ui.map

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.jbrunet.yama.MeteoApp
import fr.jbrunet.yama.data.repository.Bounds
import fr.jbrunet.yama.data.repository.ForecastFrames
import fr.jbrunet.yama.data.repository.ForecastRepository
import fr.jbrunet.yama.data.repository.LoadProgress
import fr.jbrunet.yama.data.repository.RainMapRepository
import fr.jbrunet.yama.data.repository.staleReason
import fr.jbrunet.yama.domain.MapFrame
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.RainMap
import fr.jbrunet.yama.widget.WidgetMapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MapUiState {
    data object Loading : MapUiState
    data class Ready(
        val place: Place,
        val map: RainMap,
        val index: Int,
        val playing: Boolean = false,
        /** Time of the last download (ms). */
        val loadedAt: Long = 0,
        val refreshing: Boolean = false,
        /** Incomplete forecast (Open-Meteo rate limit): retry from the next minute. */
        val partial: Boolean = false,
        /** While playing: transition in progress towards the next frame (null when stopped). */
        val blend: Blend? = null,
        val horizon: ForecastHorizon = ForecastHorizon.AVAILABLE,
    ) : MapUiState {
        val forecastStart = map.nowIndex.takeIf { map.frames.getOrNull(it) is MapFrame.Forecast }
        // Each forecast frame covers a 15-minute step, including the last one.
        val forecastMinutes = forecastStart?.let { first ->
            ((map.frames.last().time - map.frames[first].time) / 60).toInt() + 15
        } ?: 0
        val lastIndex = horizon.minutes?.let { minutes ->
            forecastStart?.let { first ->
                val end = map.frames[first].time + minutes * 60L
                map.frames.indexOfLast { it.time <= end }.coerceAtLeast(first)
            }
        } ?: map.frames.lastIndex

        fun supports(horizon: ForecastHorizon): Boolean =
            forecastStart != null && forecastMinutes >= (horizon.minutes ?: 0)

        val playbackRange: IntRange
            get() {
                val first = forecastStart ?: return 0..lastIndex
                return if (index < first) 0 until first else first..lastIndex
            }

        fun withMap(updated: RainMap): Ready {
            val replacement = copy(map = updated, blend = null)
            val next = if (replacement.supports(horizon)) replacement else replacement.copy(horizon = ForecastHorizon.AVAILABLE)
            if (index == forecastStart || (forecastStart == null && index == map.frames.lastIndex)) {
                return next.copy(index = next.forecastStart ?: next.lastIndex)
            }
            val wasForecast = map.frames[index] is MapFrame.Forecast
            val newForecastStart = next.forecastStart
            val range = when {
                newForecastStart == null -> 0..next.lastIndex
                wasForecast -> newForecastStart..next.lastIndex
                newForecastStart > 0 -> 0 until newForecastStart
                else -> 0..next.lastIndex
            }
            val newIndex = range.firstOrNull { updated.frames[it].time >= map.frames[index].time } ?: range.last
            return next.copy(index = newIndex)
        }
    }
    data class Error(val reason: fr.jbrunet.yama.data.repository.StaleReason) : MapUiState
}

enum class ForecastHorizon(val minutes: Int?) {
    ONE_HOUR(60), TWELVE_HOURS(12 * 60), AVAILABLE(null),
}

/**
 * Intermediate animation frame: [t] ∈ ]0, 1[ between the current frame and [next];
 * [tween] = interpolated forecast (null between two radar frames: cross-fade).
 */
data class Blend(val next: MapFrame, val t: Float, val tween: MapFrame.Forecast?) {
    /** Displayed time, scrolling continuously. */
    fun time(from: MapFrame): Long = from.time + ((next.time - from.time) * t).toLong()
}

/** “Wind” layer: hidden by default, loaded when first shown. */
data class WindUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val frames: List<fr.jbrunet.yama.domain.WindFrame> = emptyList(),
    val loadedAt: Long = 0,
    val failed: Boolean = false,
)

class MapViewModel(
    private val forecasts: ForecastRepository,
    private val rainMaps: RainMapRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val state: StateFlow<MapUiState> = _state
    private var player: Job? = null
    private var viewportJob: Job? = null

    /** Place shown on the home screen (position or saved place), provided by the screen. */
    private var place: Place? = null

    /** Last visible area of the map, to fit the forecast grid to it. */
    private var viewport: Bounds? = null

    /** Screen size (dp), to know the visible area before the map exists. */
    private var screenDp: Pair<Int, Int>? = null

    /**
     * Shows the map centred on this place (null: place resolved from the settings); reloads if it changed.
     * [screenWidthDp] × [screenHeightDp]: map size, to load the forecast over the whole visible area
     * straight away (otherwise a first smaller square, then a second load).
     */
    fun show(target: Place?, screenWidthDp: Int = 0, screenHeightDp: Int = 0) {
        if (screenWidthDp > 0 && screenHeightDp > 0) screenDp = screenWidthDp to screenHeightDp
        val current = place
        if (target != null) place = target
        if (_state.value !is MapUiState.Ready || (target != null && (current == null || !current.sameAs(target)))) {
            viewport = null
            load()
        } else {
            refreshIfStale()
        }
    }

    /**
     * The map moved (pan / zoom): after a short pause, reload the forecast over the visible
     * area if the current grid no longer covers it or no longer has the right resolution.
     */
    fun onViewportChanged(bounds: Bounds) {
        viewport = bounds
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(400)
            val s = _state.value as? MapUiState.Ready ?: return@launch
            if (!RainMapRepository.needsNewGrid(s.map.grid, bounds)) return@launch
            val now = clock() / 1000
            val cached = rainMaps.cachedFor(bounds, now)
            val grid = cached?.first ?: RainMapRepository.gridFor(bounds)
            val forecast = if (cached != null) {
                ForecastFrames(cached.second)
            } else {
                _state.value = s.copy(refreshing = true)
                runCatching { rainMaps.forecastFor(grid, now) }.getOrNull()
            }
            if (forecast != null) ForecastBitmaps.prepare(forecast.frames)
            ensureActive()
            val cur = _state.value as? MapUiState.Ready ?: return@launch
            _state.value = if (forecast == null || forecast.frames.isEmpty()) {
                cur.copy(refreshing = false)
            } else {
                val map = cur.map.withForecast(grid, forecast.frames)
                cur.withMap(map).copy(refreshing = false, partial = forecast.partial)
            }
        }
    }

    /** Current loading step, shown on the waiting screen and the pill. */
    val progress: StateFlow<LoadProgress?> = rainMaps.progress

    fun load() = viewModelScope.launch {
        val previous = _state.value as? MapUiState.Ready
        if (previous == null) _state.value = MapUiState.Loading else _state.value = previous.copy(refreshing = true)
        _state.value = runCatching {
            coroutineScope {
                val place = place ?: forecasts.resolvePlace()
                val grid = (viewport ?: initialViewport(place))?.let(RainMapRepository::gridFor)
                    ?: RainMapRepository.gridAround(place)
                val forecast = async { runCatching { rainMaps.forecastFor(grid, clock() / 1000) } }
                val radar = rainMaps.radar()
                // First display: the map and the radar appear without waiting for the forecast.
                if (previous == null && radar.isNotEmpty() && !forecast.isCompleted) {
                    _state.value = MapUiState.Ready(place, RainMap(grid, radar), radar.lastIndex, loadedAt = clock(), refreshing = true)
                }
                val predicted = forecast.await()
                val frames = radar + predicted.getOrNull()?.frames.orEmpty()
                if (frames.isEmpty()) throw predicted.exceptionOrNull() ?: IllegalStateException("No rain data")
                val map = RainMap(grid, frames)
                rainMaps.progress.value = LoadProgress(fr.jbrunet.yama.data.repository.LoadStage.PREPARING)
                ForecastBitmaps.prepare(map.frames) // images computed off the main thread
                // Stay on “now” if the user was there, otherwise on the same time.
                val shown = _state.value as? MapUiState.Ready ?: previous
                val ready = shown?.withMap(map) ?: MapUiState.Ready(
                    place, map, if (frames[map.nowIndex] is MapFrame.Forecast) map.nowIndex else map.frames.lastIndex,
                )
                ready.copy(
                    place = place, loadedAt = clock(), refreshing = false,
                    partial = predicted.getOrNull()?.partial ?: true,
                )
            }
        }.getOrElse {
            (_state.value as? MapUiState.Ready)?.copy(refreshing = false) ?: MapUiState.Error(it.staleReason())
        }
        rainMaps.progress.value = null
    }

    /**
     * Visible area when opening (map centred on the place, zoom [INITIAL_ZOOM]). Tiles scaled to
     * the screen (isTilesScaledToDpi): a 256 px tile is 256 dp, hence a computation in dp.
     */
    private fun initialViewport(place: Place): Bounds? = screenDp?.let { (w, h) ->
        WidgetMapRenderer.bounds(place.latitude, place.longitude, w, h, INITIAL_ZOOM)
    }

    /** Reloads if the data is older than [REFRESH_MS] (radar updated every 10 min). */
    fun refreshIfStale() {
        val s = _state.value
        // Incomplete forecast (Open-Meteo rate limit): retry from the next minute.
        val maxAge = if (s is MapUiState.Ready && s.partial) 60_000L else REFRESH_MS
        if (s is MapUiState.Ready && !s.refreshing && clock() - s.loadedAt >= maxAge) load()
        if (s is MapUiState.Error) load()
    }

    private val _wind = MutableStateFlow(WindUiState())
    val wind: StateFlow<WindUiState> = _wind

    /** Shows / hides the wind; downloads it (one Open-Meteo call) if missing or older than 30 min. */
    fun toggleWind() {
        val w = _wind.value
        if (w.visible) {
            _wind.value = w.copy(visible = false)
            return
        }
        _wind.value = w.copy(visible = true, failed = false)
        if (w.frames.isNotEmpty() && clock() - w.loadedAt < 30 * 60_000L) return
        val s = _state.value as? MapUiState.Ready ?: return
        viewModelScope.launch {
            _wind.value = _wind.value.copy(loading = true)
            _wind.value = runCatching { rainMaps.wind(s.map.grid) }.fold(
                { _wind.value.copy(loading = false, frames = it, loadedAt = clock()) },
                { _wind.value.copy(loading = false, failed = true) },
            )
        }
    }

    fun select(index: Int) {
        val s = _state.value as? MapUiState.Ready ?: return
        player?.cancel()
        _state.value = s.copy(index = index.coerceIn(0, s.lastIndex), blend = null, playing = false)
    }

    fun selectHorizon(horizon: ForecastHorizon) {
        val s = _state.value as? MapUiState.Ready ?: return
        val first = s.forecastStart ?: return
        if (!s.supports(horizon)) return
        startPlayback(s.copy(horizon = horizon, index = first, blend = null))
    }

    fun togglePlay() {
        val s = _state.value as? MapUiState.Ready ?: return
        if (s.playing) {
            player?.cancel()
            _state.value = s.copy(playing = false, blend = null)
            return
        }
        val range = s.playbackRange
        val index = if (s.index == range.last) range.first else s.index
        startPlayback(s.copy(index = index))
    }

    private fun startPlayback(state: MapUiState.Ready) {
        player?.cancel()
        val playing = state.playbackRange.count() > 1
        _state.value = state.copy(playing = playing)
        if (!playing) return
        player = viewModelScope.launch {
            while (isActive) {
                val start = _state.value as? MapUiState.Ready ?: break
                val range = start.playbackRange
                val count = range.count()
                if (!start.playing || count <= 1) {
                    _state.value = start.copy(playing = false, blend = null)
                    break
                }
                var deadline = SystemClock.uptimeMillis()
                repeat(count) {
                    val cur = _state.value as? MapUiState.Ready ?: return@launch
                    if (cur.map !== start.map || !cur.playing) break
                    val nextIndex = if (cur.index == range.last) range.first else cur.index + 1
                    val begin = deadline
                    if (cur.index == range.last) {
                        deadline += LAST_FRAME_MS
                    } else {
                        val position = cur.index - range.first
                        val duration = PLAYBACK_MS - LAST_FRAME_MS
                        deadline += duration * (position + 1) / (count - 1) - duration * position / (count - 1)
                    }
                    if (!playFrame(cur, nextIndex, begin, deadline)) break
                }
            }
        }
    }

    private suspend fun playFrame(start: MapUiState.Ready, nextIndex: Int, begin: Long, end: Long): Boolean {
        val from = start.map.frames[start.index]
        val to = start.map.frames[nextIndex]
        val steps = ((end - begin) / TRANSITION_FRAME_MS).toInt().coerceIn(1, MAX_TRANSITION_STEPS)
        if (nextIndex > start.index && steps > 1) {
            val tweenable = from is MapFrame.Forecast && to is MapFrame.Forecast && from.grid == to.grid
            val fade = from is MapFrame.Radar && to is MapFrame.Radar
            if (tweenable || fade) {
                val motion = if (tweenable) withContext(Dispatchers.Default) {
                    FrameInterpolation.motion(from as MapFrame.Forecast, to as MapFrame.Forecast)
                } else null
                for (step in 1 until steps) {
                    val at = begin + (end - begin) * step / steps
                    delay((at - SystemClock.uptimeMillis()).coerceAtLeast(0))
                    if (SystemClock.uptimeMillis() >= end) break
                    val t = step.toFloat() / steps
                    val tween = motion?.let {
                        withContext(Dispatchers.Default) {
                            FrameInterpolation.between(from as MapFrame.Forecast, to as MapFrame.Forecast, t, it)
                                .also { ForecastBitmaps.get(it) }
                        }
                    }
                    val cur = currentPlaybackFrame(start) ?: return false
                    if (SystemClock.uptimeMillis() < end) _state.value = cur.copy(blend = Blend(to, t, tween))
                }
            }
        }
        delay((end - SystemClock.uptimeMillis()).coerceAtLeast(0))
        val cur = currentPlaybackFrame(start) ?: return false
        _state.value = cur.copy(index = nextIndex, blend = null)
        return true
    }

    private fun currentPlaybackFrame(start: MapUiState.Ready): MapUiState.Ready? =
        (_state.value as? MapUiState.Ready)?.takeIf { it.playing && it.map === start.map && it.index == start.index }

    companion object {
        const val REFRESH_MS = 10 * 60 * 1000L

        /** Every horizon fits in one loop. Deadlines include the time spent drawing intermediate frames. */
        private const val PLAYBACK_MS = 8_000L
        private const val LAST_FRAME_MS = 300L
        private const val TRANSITION_FRAME_MS = 40L
        private const val MAX_TRANSITION_STEPS = 24

        /** Zoom when the map opens. */
        const val INITIAL_ZOOM = 8

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MeteoApp
                MapViewModel(app.forecastRepository, app.rainMapRepository)
            }
        }
    }
}
