package com.rj.app.show

import android.content.Context
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.OutputConfig
import com.rj.app.context.CalendarSource
import com.rj.app.context.DriveTracker
import com.rj.app.data.MemoryStore
import com.rj.app.data.SettingsStore
import com.rj.app.music.MusicController
import com.rj.app.voice.Ears
import com.rj.app.voice.Voice
import com.rj.core.Cached
import com.rj.core.CalendarEvent
import com.rj.core.ClaudeBrain
import com.rj.core.Cue
import com.rj.core.CueType
import com.rj.core.FallbackBrain
import com.rj.core.NewsClient
import com.rj.core.NewsItem
import com.rj.core.RjBrain
import com.rj.core.Segment
import com.rj.core.ShowContext
import com.rj.core.ShowPlanner
import com.rj.core.UserProfile
import com.rj.core.WeatherClient
import com.rj.core.WeatherSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

enum class OnAir(val label: String) {
    OFF("Off air"), WARMING_UP("Warming up"), THINKING("Thinking"), SPEAKING("On air"),
    LISTENING("Listening"), MUSIC("Music"),
}

enum class Who { RJ, YOU, SONG }
data class Line(val who: Who, val text: String)

data class ShowState(
    val status: OnAir = OnAir.OFF,
    val carName: String = "",
    val transcript: List<Line> = emptyList(),
    val nowPlaying: String? = null,
    val notice: String? = null,
) {
    val live get() = status != OnAir.OFF
    val lastRjLine get() = transcript.lastOrNull { it.who == Who.RJ }?.text
}

/**
 * Runs the radio show: decides the next moment, gathers live context, asks the brain for a segment,
 * speaks it, cues the music, listens when spoken to, and pre-fetches the next link while a song plays
 * so there is no dead air.
 */
class ShowEngine(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsStore = SettingsStore(app)
    private val memory = MemoryStore(app)
    private val calendar = CalendarSource(app)
    val drive = DriveTracker(app)
    val music = MusicController(app)
    private val voice = Voice(app)
    private val ears = Ears(app)
    private val planner = ShowPlanner()
    private val fallback = FallbackBrain()
    private val weatherClient = WeatherClient()
    private val newsClient = NewsClient()

    private val _state = MutableStateFlow(ShowState())
    val state: StateFlow<ShowState> = _state

    private enum class Action { TALK, SKIP }
    private val actions = Channel<Action>(Channel.CONFLATED)
    private var job: Job? = null
    @Volatile private var stopping = false

    private lateinit var brain: RjBrain
    private var profile = UserProfile()
    private val recentSongs = mutableListOf<String>()
    private val locale: Locale get() = Locale.getDefault()

    private val events = Cached<List<CalendarEvent>>(Duration.ofMinutes(10)) { calendar.upcoming() }
    private val weather = Cached<WeatherSnapshot>(Duration.ofMinutes(20)) {
        drive.lastLocation?.let { weatherClient.fetch(it.latitude, it.longitude, drive.locality()) }
    }
    private val news = Cached<List<NewsItem>>(Duration.ofMinutes(30)) {
        val country = drive.lastAddress?.countryCode ?: locale.country.ifBlank { "US" }
        newsClient.fetch(locale.language.ifBlank { "en" }, country.uppercase(), profile.interests).ifEmpty { null }
    }

    fun start(destination: String?) {
        if (job?.isActive == true) return
        actions.tryReceive() // drop stale taps
        job = scope.launch {
            try {
                runShow(destination)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Show crashed: ${e.message}") }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { drive.stop() }
                if (!stopping) _state.update { it.copy(status = OnAir.OFF) }
            }
        }
    }

    /** End the drive with a short sign-off. */
    fun stop() {
        val running = job ?: return
        if (stopping) return
        stopping = true
        scope.launch {
            running.cancel()
            voice.stop()
            running.join()
            if (::brain.isInitialized) {
                withTimeoutOrNull(15_000) {
                    _state.update { it.copy(status = OnAir.THINKING) }
                    val seg = think(Cue(CueType.SIGN_OFF))
                    _state.update { it.copy(status = OnAir.SPEAKING) }
                    say(seg.speech)
                }
            }
            stopping = false
            _state.update { it.copy(status = OnAir.OFF) }
        }
    }

    fun talk() { actions.trySend(Action.TALK) }
    fun skip() { actions.trySend(Action.SKIP) }
    fun setDestination(d: String?) { drive.destination = d }

    private suspend fun runShow(destination: String?) {
        val s = settingsStore.load()
        profile = s.profile
        music.app = s.musicApp
        recentSongs.clear()
        planner.reset()
        events.invalidate(); weather.invalidate(); news.invalidate()
        _state.value = ShowState(status = OnAir.WARMING_UP, carName = s.persona.carName)

        withContext(Dispatchers.Main) {
            drive.destination = destination
            drive.start()
        }
        voice.configure(locale, s.persona.style.speechRate, s.persona.style.pitch)

        withContext(Dispatchers.IO) { drive.refreshAddress() }
        brain = if (s.apiKey.isBlank()) {
            _state.update { it.copy(notice = "No Claude API key set: running in offline mode. Add one in Settings.") }
            fallback
        } else {
            val a = drive.lastAddress
            ClaudeBrain(
                apiKey = s.apiKey,
                model = s.model,
                effort = OutputConfig.Effort.LOW, // fast, conversational turns
                location = ClaudeBrain.Location(
                    city = a?.locality ?: s.profile.homeCity.ifBlank { null },
                    region = a?.adminArea,
                    country = a?.countryCode,
                    timezone = ZoneId.systemDefault().id,
                ),
            )
        }
        brain.startShow(s.persona, s.profile, memory.all())
        fallback.startShow(s.persona, s.profile, emptyList())

        // Opener.
        air(think(planner.next(context())))?.let { if (it.listen) conversation() }

        val fallbackGap = Duration.ofMinutes(s.linkEveryMinutes.coerceIn(2, 15).toLong())
        while (true) {
            when (val m = waitForMoment(fallbackGap)) {
                is Moment.Talk -> conversation()
                is Moment.Link -> {
                    val seg = m.prefetched?.let { runCatching { it.await() }.getOrNull() }
                        ?: think(planner.next(context()))
                    air(seg)?.let { if (it.listen) conversation() }
                }
            }
        }
    }

    private sealed interface Moment {
        data object Talk : Moment
        data class Link(val prefetched: Deferred<Segment>?) : Moment
    }

    /**
     * Wait while music plays. Returns when the driver taps Talk/Skip, or the current song ends.
     * When the song is close to ending, the next segment is generated in the background.
     */
    private suspend fun waitForMoment(fallbackGap: Duration): Moment {
        _state.update { it.copy(status = OnAir.MUSIC) }
        val started = System.currentTimeMillis()
        var track: String? = null
        var prefetch: Deferred<Segment>? = null
        while (true) {
            actions.tryReceive().getOrNull()?.let { a ->
                prefetch?.cancel()
                return if (a == Action.TALK) Moment.Talk else Moment.Link(null)
            }
            val p = music.progress()
            _state.update { it.copy(nowPlaying = music.nowPlaying()?.let { np -> listOfNotNull(np.title, np.artist).joinToString(" – ") }) }
            val elapsed = System.currentTimeMillis() - started
            val remaining = p?.takeIf { it.durationMs > 0 && it.playing }?.let { it.durationMs - it.positionMs }
            if (track == null && p?.playing == true) track = p.trackId

            val nearEnd = if (remaining != null) remaining < 25_000 else elapsed > fallbackGap.toMillis() - 20_000
            if (nearEnd && prefetch == null) {
                val cue = planner.next(context())
                prefetch = scope.async { think(cue, updateStatus = false) }
            }
            val ended = when {
                remaining != null && remaining < 1_200 -> true
                track != null && p?.trackId != null && p.trackId != track -> true // app moved on to another track
                p == null && elapsed > fallbackGap.toMillis() -> true
                elapsed > Duration.ofMinutes(12).toMillis() -> true // never go too long without a word
                else -> false
            }
            if (ended) return Moment.Link(prefetch)
            delay(500)
        }
    }

    /** The driver wants to talk (or the RJ asked a question). Up to three back-and-forths. */
    private suspend fun conversation() {
        val wasPlaying = music.isPlaying()
        if (wasPlaying) music.pause()
        var resumeMusic = wasPlaying
        repeat(3) {
            _state.update { it.copy(status = OnAir.LISTENING) }
            val heard = ears.listen(locale)?.trim()
            if (heard.isNullOrEmpty()) {
                if (resumeMusic) music.resume()
                return
            }
            addLine(Who.YOU, heard)
            val seg = think(Cue(CueType.REPLY, userSaid = heard))
            air(seg)
            if (seg.song != null) resumeMusic = false
            if (!seg.listen) {
                if (resumeMusic) music.resume()
                return
            }
        }
        if (resumeMusic) music.resume()
    }

    private suspend fun think(cue: Cue, updateStatus: Boolean = true): Segment {
        if (updateStatus) _state.update { it.copy(status = OnAir.THINKING) }
        return withContext(Dispatchers.IO) {
            val ctx = context()
            val result = runCatching { withTimeoutOrNull(45_000) { brain.next(cue, ctx) } }
            result.exceptionOrNull()?.let { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(notice = describe(e)) }
            }
            result.getOrNull()?.takeIf { it.speech.isNotBlank() || it.song != null } ?: fallback.next(cue, ctx)
        }
    }

    /** Put a segment on air. Returns it for chaining. */
    private suspend fun air(seg: Segment): Segment? {
        if (seg.speech.isBlank() && seg.song == null) return null
        memory.add(seg.remember)
        if (seg.song != null && music.isPlaying()) music.pause()
        say(seg.speech)
        seg.song?.let { song ->
            addLine(Who.SONG, song.toString())
            recentSongs += song.toString()
            music.play(song)
            seg.introTag?.let { tag ->
                delay(2_500) // let the intro kick in, then talk over it with the music ducked
                _state.update { it.copy(status = OnAir.SPEAKING) }
                addLine(Who.RJ, tag)
                voice.say(tag, duck = true)
            }
        }
        return seg
    }

    private suspend fun say(text: String) {
        if (text.isBlank()) return
        _state.update { it.copy(status = OnAir.SPEAKING) }
        addLine(Who.RJ, text)
        voice.say(text, duck = false)
    }

    private fun context(): ShowContext {
        drive.refreshAddress()
        val now = ZonedDateTime.now()
        return ShowContext(
            now = now,
            profile = profile,
            drive = drive.state(now),
            events = events.get(now).orEmpty(),
            weather = weather.get(now),
            headlines = news.get(now).orEmpty(),
            nowPlaying = music.nowPlaying(),
            recentSongs = recentSongs.toList(),
        )
    }

    private fun addLine(who: Who, text: String) =
        _state.update { it.copy(transcript = (it.transcript + Line(who, text)).takeLast(80)) }

    private fun describe(e: Throwable): String = when (e) {
        is UnauthorizedException -> "Claude rejected the API key. Check it in Settings."
        is AnthropicServiceException -> "Claude API error (${e.statusCode()}). Using backup patter for now."
        else -> "Can't reach Claude (${e.javaClass.simpleName}). Using backup patter for now."
    }
}
