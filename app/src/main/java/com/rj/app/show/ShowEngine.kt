package com.rj.app.show

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.OutputConfig
import com.rj.app.context.CalendarSource
import com.rj.app.context.DriveTracker
import com.rj.app.data.BrainMode
import com.rj.app.data.DriveStats
import com.rj.app.data.MemoryStore
import com.rj.app.data.MusicSource
import com.rj.app.data.SettingsStore
import com.rj.app.music.MusicHub
import com.rj.app.voice.Ears
import com.rj.app.voice.VoiceDesk
import com.rj.core.Cached
import com.rj.core.CalendarEvent
import com.rj.core.ClaudeBrain
import com.rj.core.Cue
import com.rj.core.CueType
import com.rj.core.IntentRouter
import com.rj.core.NewsClient
import com.rj.core.NewsItem
import com.rj.core.OfflineBrain
import com.rj.core.Persona
import com.rj.core.QuizJudge
import com.rj.core.QuizQuestion
import com.rj.core.RjBrain
import com.rj.core.Segment
import com.rj.core.ShowContext
import com.rj.core.ShowPlanner
import com.rj.core.Speaker
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
    LISTENING("Listening"), MUSIC("Music"), QUIZ("Quiz"),
}

enum class Who { RJ, COHOST, YOU, SONG }
data class Line(val who: Who, val text: String)

data class ShowState(
    val status: OnAir = OnAir.OFF,
    val carName: String = "",
    val cohostName: String = "",
    val transcript: List<Line> = emptyList(),
    val nowPlaying: String? = null,
    val notice: String? = null,
    val online: Boolean = true,
    /** e.g. "Quiz 2/3 · score 1" while a quiz round is running. */
    val quiz: String? = null,
) {
    val live get() = status != OnAir.OFF
    val lastRjLine get() = transcript.lastOrNull { it.who == Who.RJ }?.text
}

/**
 * Runs the radio show: decides the next moment, gathers live context, asks the brain for a segment,
 * speaks it (two voices for podcasts), cues the music, runs quizzes, listens when spoken to, and
 * pre-renders the next link while a song plays so there's no dead air. Falls back to the offline
 * host whenever the network or Claude isn't available.
 */
class ShowEngine(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsStore = SettingsStore(app)
    private val memory = MemoryStore(app)
    private val stats = DriveStats(app)
    private val calendar = CalendarSource(app)
    val drive = DriveTracker(app)
    val music = MusicHub(app)
    private val voices = VoiceDesk(app)
    private val ears = Ears(app)
    private val planner = ShowPlanner()
    private val offline by lazy { OfflineBrain() }
    private val weatherClient = WeatherClient()
    private val newsClient = NewsClient()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(ShowState())
    val state: StateFlow<ShowState> = _state

    private sealed interface Action {
        data object Talk : Action
        data object Skip : Action
        data class Feature(val type: CueType) : Action
    }
    private val actions = Channel<Action>(Channel.CONFLATED)
    private var job: Job? = null
    @Volatile private var stopping = false

    private var cloud: RjBrain? = null
    private var persona = Persona()
    private var profile = UserProfile()
    private val recentSongs = mutableListOf<String>()
    private var quizCorrect = 0
    private var quizAsked = 0
    private val locale: Locale get() = Locale.getDefault()

    private val events = Cached<List<CalendarEvent>>(Duration.ofMinutes(10)) { calendar.upcoming() }
    private val weather = Cached<WeatherSnapshot>(Duration.ofMinutes(20)) {
        if (!isOnline()) null else drive.lastLocation?.let { weatherClient.fetch(it.latitude, it.longitude, drive.locality()) }
    }
    private val news = Cached<List<NewsItem>>(Duration.ofMinutes(30)) {
        if (!isOnline()) return@Cached null
        val country = drive.lastAddress?.countryCode ?: locale.country.ifBlank { "US" }
        newsClient.fetch(locale.language.ifBlank { "en" }, country.uppercase(), profile.interests).ifEmpty { null }
    }

    fun isOnline(): Boolean = runCatching {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false
    }.getOrDefault(false)

    fun start(destination: String?) {
        if (job?.isActive == true) return
        while (actions.tryReceive().isSuccess) Unit // drop stale taps
        job = scope.launch {
            try {
                runShow(destination)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Show crashed: ${e.message}") }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { drive.stop() }
                if (!stopping) _state.update { it.copy(status = OnAir.OFF, quiz = null) }
            }
        }
    }

    /** End the drive with a short sign-off and recap. */
    fun stop() {
        val running = job ?: return
        if (stopping) return
        stopping = true
        scope.launch {
            running.cancel()
            voices.stop()
            running.join()
            withContext(Dispatchers.Main) { music.pause() }
            val d = drive.state()
            stats.endDrive(d.distanceKm)
            withTimeoutOrNull(20_000) {
                val recap = buildList {
                    add("today: ${"%.1f".format(d.distanceKm)} km in ${d.elapsed.toMinutes()} minutes")
                    if (recentSongs.isNotEmpty()) add("${recentSongs.size} songs played")
                    if (quizAsked > 0) add("quiz score $quizCorrect out of $quizAsked")
                }
                air(think(Cue(CueType.SIGN_OFF, recap)), allowSong = false)
            }
            withContext(Dispatchers.Main) { music.stopAll() }
            stopping = false
            _state.update { it.copy(status = OnAir.OFF, quiz = null) }
        }
    }

    fun talk() { actions.trySend(Action.Talk) }
    fun skip() { actions.trySend(Action.Skip) }
    fun feature(type: CueType) { actions.trySend(Action.Feature(type)) }
    fun setDestination(d: String?) { drive.destination = d }

    private suspend fun runShow(destination: String?) {
        val s = settingsStore.load()
        profile = s.profile
        persona = s.persona
        recentSongs.clear(); quizCorrect = 0; quizAsked = 0
        planner.reset()
        events.invalidate(); weather.invalidate(); news.invalidate()
        val online = isOnline()
        _state.value = ShowState(status = OnAir.WARMING_UP, carName = s.persona.carName, cohostName = s.persona.cohostName, online = online)

        withContext(Dispatchers.Main) {
            drive.destination = destination
            drive.start()
        }
        voices.engine = s.voiceEngine
        voices.elevenLabsKey = s.elevenLabsKey
        voices.hostVoiceId = s.hostVoiceId
        voices.cohostVoiceId = s.cohostVoiceId
        voices.isOnline = ::isOnline
        voices.configure(locale, s.persona.style.speechRate, s.persona.style.pitch)

        music.source = s.musicSource
        music.streaming.app = s.musicApp
        music.online = online
        withContext(Dispatchers.IO) {
            music.resolve()
            drive.refreshAddress()
        }
        if (music.active == MusicSource.LIBRARY && music.catalog().isEmpty()) {
            _state.update { it.copy(notice = "No music found on the phone. Allow music access, or pick a streaming app in Settings.") }
        }
        val catalog = music.catalog()

        cloud = if (s.brainMode == BrainMode.OFFLINE || s.apiKey.isBlank()) null else {
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
        if (cloud == null && s.brainMode == BrainMode.AUTO) {
            _state.update { it.copy(notice = "No Claude API key: running the offline show. Add a key in Settings for news, chat and fresh content.") }
        }
        cloud?.startShow(s.persona, s.profile, memory.all(), catalog)
        offline.startShow(s.persona, s.profile, memory.all(), catalog)
        stats.startDrive()

        // Opener.
        air(think(planner.next(context())))?.let { if (it.listen) conversation() }

        val fallbackGap = Duration.ofMinutes(s.linkEveryMinutes.coerceIn(2, 15).toLong())
        while (true) {
            when (val m = waitForMoment(fallbackGap)) {
                is Moment.Talk -> conversation()
                is Moment.Feature -> runFeature(m.type)
                is Moment.Link -> {
                    val prepared = m.prefetched?.let { runCatching { it.await() }.getOrNull() }
                    if (prepared != null) play(prepared) else runCue(planner.next(context()))
                }
            }
        }
    }

    private sealed interface Moment {
        data object Talk : Moment
        data class Feature(val type: CueType) : Moment
        data class Link(val prefetched: Deferred<Prepared>?) : Moment
    }

    /** A segment generated ahead of time, with the cue that produced it. */
    private data class Prepared(val cue: Cue, val segment: Segment)

    private suspend fun runCue(cue: Cue) {
        if (cue.type == CueType.QUIZ) return runQuiz()
        play(Prepared(cue, think(cue)))
    }

    private suspend fun play(p: Prepared) {
        if (p.cue.type == CueType.QUIZ) return runQuiz(firstQuestion = p.segment)
        air(p.segment)?.let { if (it.listen) conversation() }
    }

    private suspend fun runFeature(type: CueType) {
        planner.markRun(type, ZonedDateTime.now())
        runCue(Cue(type, listOf("the driver asked for this")))
    }

    /**
     * Wait while music plays. Returns when the driver taps something, or the current song ends.
     * When the song is close to ending, the next segment and its voice audio are prepared in the background.
     */
    private suspend fun waitForMoment(fallbackGap: Duration): Moment {
        _state.update { it.copy(status = OnAir.MUSIC, quiz = null) }
        val started = System.currentTimeMillis()
        var track: String? = null
        var prefetch: Deferred<Prepared>? = null
        while (true) {
            actions.tryReceive().getOrNull()?.let { a ->
                prefetch?.cancel()
                return when (a) {
                    Action.Talk -> Moment.Talk
                    Action.Skip -> Moment.Link(null)
                    is Action.Feature -> Moment.Feature(a.type)
                }
            }
            val p = music.progress()
            _state.update {
                it.copy(
                    online = isOnline(),
                    nowPlaying = music.nowPlaying()?.let { np -> listOfNotNull(np.title, np.artist).joinToString(" – ") },
                )
            }
            val elapsed = System.currentTimeMillis() - started
            val remaining = p?.takeIf { it.durationMs > 0 && it.playing }?.let { it.durationMs - it.positionMs }
            if (track == null && p?.playing == true) track = p.trackId

            val nearEnd = if (remaining != null) remaining < 25_000 else elapsed > fallbackGap.toMillis() - 20_000
            if (nearEnd && prefetch == null) {
                val cue = planner.next(context())
                prefetch = scope.async {
                    val seg = if (cue.type == CueType.QUIZ) think(cue.copy(notes = quizNotes(1, 0, 0)), updateStatus = false)
                    else think(cue, updateStatus = false)
                    voices.prefetch(seg.spokenParts())
                    Prepared(cue, seg)
                }
            }
            val finished = p != null && !p.playing && p.durationMs > 0 && p.positionMs >= p.durationMs - 1_500
            val ended = when {
                finished -> true
                remaining != null && remaining < 1_200 -> true
                track != null && p?.trackId != null && p.trackId != track -> true // the app moved on to another track
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
            val heard = ears.listen(locale, preferOffline = !isOnline())?.trim()
            if (heard.isNullOrEmpty()) {
                if (resumeMusic) music.resume()
                return
            }
            addLine(Who.YOU, heard)
            // "Quiz me", "tell me some trivia", "debate"... jump straight to that feature.
            IntentRouter.route(heard)?.let { type ->
                planner.markRun(type, ZonedDateTime.now())
                runCue(Cue(type, listOf("the driver asked for this"), userSaid = heard))
                return
            }
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

    private fun quizNotes(n: Int, score: Int, asked: Int) = listOf("question $n of $QUIZ_ROUND", "score so far $score out of $asked")

    /** A three-question voice quiz, judged on the phone so the verdict is instant. */
    private suspend fun runQuiz(firstQuestion: Segment? = null) {
        val wasPlaying = music.isPlaying()
        if (wasPlaying) music.pause()
        var score = 0
        var asked = 0
        for (n in 1..QUIZ_ROUND) {
            _state.update { it.copy(quiz = "Quiz $n/$QUIZ_ROUND · score $score") }
            val seg = if (n == 1 && firstQuestion != null) firstQuestion
            else think(Cue(CueType.QUIZ, quizNotes(n, score, asked)))
            val q = seg.quiz
            if (q == null) { air(seg, allowSong = false); break }
            // Pre-render the reactions while the question is being asked.
            scope.launch { voices.prefetch(listOfNotNull(q.rightLine, q.wrongLine, q.fact).map { com.rj.core.SpokenLine(Speaker.HOST, it) }) }
            say(seg.speech)
            if (q.tune != null) {
                if (music.active != MusicSource.LIBRARY) continue
                say("Listen carefully...")
                _state.update { it.copy(status = OnAir.MUSIC) }
                if (!music.playClip(q.tune!!, 12_000)) continue
            }
            _state.update { it.copy(status = OnAir.QUIZ) }
            say(QuizJudge.spokenQuestion(q))
            _state.update { it.copy(status = OnAir.LISTENING) }
            val heard = ears.listen(locale, preferOffline = !isOnline())?.trim()
            asked++
            if (heard.isNullOrEmpty()) {
                say("Time's up! The answer was ${q.answer}.")
            } else {
                addLine(Who.YOU, heard)
                if (QuizJudge.isCorrect(heard, q)) {
                    score++
                    say(q.rightLine ?: listOf("Yes! Correct!", "Nailed it!", "That's right, well done!").random())
                } else {
                    say(q.wrongLine ?: listOf("Ooh, not quite. It was ${q.answer}.", "Close, but no. The answer was ${q.answer}.").random())
                }
            }
            q.fact?.let { say(it) }
            _state.update { it.copy(quiz = "Quiz $n/$QUIZ_ROUND · score $score") }
        }
        quizCorrect += score
        quizAsked += asked
        if (asked > 0) {
            val record = stats.recordQuiz(score, asked)
            val verdict = when {
                score == asked -> "A perfect round! $score out of $asked."
                score == 0 -> "Zero out of $asked. We'll call that a warm-up."
                else -> "You got $score out of $asked."
            }
            say(verdict + if (record) " That's a new personal best!" else "")
        }
        _state.update { it.copy(quiz = null) }
        // Back to the music with a fresh pick.
        air(think(Cue(CueType.LINK, listOf("a quiz round just ended, score $score out of $asked")))) ?: run {
            if (wasPlaying) music.resume()
        }
    }

    private suspend fun think(cue: Cue, updateStatus: Boolean = true): Segment {
        if (updateStatus) _state.update { it.copy(status = OnAir.THINKING) }
        return withContext(Dispatchers.IO) {
            val ctx = context()
            val brain = cloud?.takeIf { ctx.online }
            val result = if (brain == null) null else runCatching { withTimeoutOrNull(45_000) { brain.next(cue, ctx) } }
            result?.exceptionOrNull()?.let { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(notice = describe(e)) }
            }
            result?.getOrNull()?.takeIf { it.speech.isNotBlank() || it.song != null || it.quiz != null || it.lines.isNotEmpty() }
                ?: offline.next(cue, ctx)
        }
    }

    /** Put a segment on air. Returns it for chaining, or null if it was empty. */
    private suspend fun air(seg: Segment, allowSong: Boolean = true): Segment? {
        if (seg.speech.isBlank() && seg.song == null && seg.lines.isEmpty() && seg.quiz == null) return null
        memory.add(seg.remember)
        val song = seg.song?.takeIf { allowSong }
        if ((song != null || seg.lines.isNotEmpty()) && music.isPlaying()) music.pause()
        // Render the whole segment's audio up front (in parallel with the first line playing).
        val parts = seg.spokenParts()
        if (parts.size > 1) scope.launch { voices.prefetch(parts.drop(1)) }
        say(seg.speech)
        seg.lines.forEach { say(it.text, it.speaker) }
        seg.quiz?.let { q ->
            // A one-off question (e.g. Claude asked one in a reply).
            say(QuizJudge.spokenQuestion(q))
            _state.update { it.copy(status = OnAir.LISTENING) }
            val heard = ears.listen(locale, preferOffline = !isOnline())
            if (heard != null) addLine(Who.YOU, heard)
            say(if (heard != null && QuizJudge.isCorrect(heard, q)) q.rightLine ?: "Correct!" else q.wrongLine ?: "It was ${q.answer}.")
        }
        song?.let {
            addLine(Who.SONG, it.toString())
            recentSongs += it.toString()
            _state.update { s -> s.copy(status = OnAir.MUSIC) }
            val ok = music.play(it)
            music.lastProblem?.let { problem -> _state.update { s -> s.copy(notice = problem) } }
            if (ok) seg.introTag?.let { tag ->
                delay(2_500) // let the intro kick in, then talk over it with the music ducked
                music.duck(true)
                say(tag, duck = true)
                music.duck(false)
            }
        }
        voices.lastError?.let { err -> _state.update { s -> s.copy(notice = err) } }
        return seg
    }

    private suspend fun say(text: String, speaker: Speaker = Speaker.HOST, duck: Boolean = false) {
        if (text.isBlank()) return
        _state.update { it.copy(status = if (it.status == OnAir.QUIZ) OnAir.QUIZ else OnAir.SPEAKING) }
        addLine(if (speaker == Speaker.COHOST) Who.COHOST else Who.RJ, text)
        voices.say(text, speaker, duck)
    }

    private fun context(): ShowContext {
        val online = isOnline()
        if (online) drive.refreshAddress()
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
            online = online,
            history = stats.summary(),
        )
    }

    private fun addLine(who: Who, text: String) =
        _state.update { it.copy(transcript = (it.transcript + Line(who, text)).takeLast(80)) }

    private fun describe(e: Throwable): String = when (e) {
        is UnauthorizedException -> "Claude rejected the API key. Check it in Settings. Using the offline show for now."
        is AnthropicServiceException -> "Claude API error (${e.statusCode()}). Using the offline show for now."
        else -> "Can't reach Claude (${e.javaClass.simpleName}). Using the offline show for now."
    }

    private companion object { const val QUIZ_ROUND = 3 }
}
