package com.rj.app.show

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.OutputConfig
import com.rj.app.RjApp
import com.rj.app.connect.CarBluetooth
import com.rj.app.connect.HealthReader
import com.rj.app.connect.Inbox
import com.rj.app.connect.MicrosoftAccount
import com.rj.app.connect.ObdLink
import com.rj.app.context.CalendarSource
import com.rj.app.context.DriveTracker
import com.rj.app.data.AppSettings
import com.rj.app.data.BrainMode
import com.rj.app.data.DriveStats
import com.rj.app.data.MemoryStore
import com.rj.app.data.MusicSource
import com.rj.app.data.SettingsStore
import com.rj.app.music.MusicHub
import com.rj.app.music.sameSong
import com.rj.app.voice.Ears
import com.rj.app.voice.VoiceDesk
import com.rj.core.Cached
import com.rj.core.CalendarEvent
import com.rj.core.ClaudeBrain
import com.rj.core.Command
import com.rj.core.Commands
import com.rj.core.Cue
import com.rj.core.CueType
import com.rj.core.GeoPoint
import com.rj.core.HealthSnapshot
import com.rj.core.InboxItem
import com.rj.core.InboxKind
import com.rj.core.IntentRouter
import com.rj.core.NewsClient
import com.rj.core.NewsItem
import com.rj.core.Offer
import com.rj.core.OfflineBrain
import com.rj.core.Persona
import com.rj.core.QuizJudge
import com.rj.core.RjBrain
import com.rj.core.Segment
import com.rj.core.ShowContext
import com.rj.core.ShowPlanner
import com.rj.core.SongPick
import com.rj.core.Speaker
import com.rj.core.SpokenLine
import com.rj.core.TrafficClient
import com.rj.core.TrafficInfo
import com.rj.core.UserProfile
import com.rj.core.VehicleStatus
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
import kotlinx.coroutines.isActive
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
    /** At-a-glance context the RJ is working with ("6h 10m sleep", "ETA 09:42", "Fuel 18%"...). */
    val senses: List<String> = emptyList(),
) {
    val live get() = status != OnAir.OFF
    val lastRjLine get() = transcript.lastOrNull { it.who == Who.RJ }?.text
}

/**
 * Runs the radio show: decides the next moment, gathers live context (calendar, Outlook, messages,
 * health, car, traffic, weather, news), asks the brain for a segment, and puts it on air like a real
 * radio desk: the next song starts and the host talks over its intro with the music faded down.
 * Handles quizzes, two-voice podcasts, voice replies to messages and running-late notes, and falls
 * back to the offline host whenever the network or Claude isn't available.
 */
class ShowEngine(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsStore = SettingsStore(app)
    private val memory = MemoryStore(app)
    private val stats = DriveStats(app)
    private val calendar = CalendarSource(app)
    val drive = DriveTracker(app)
    val music = MusicHub(app)
    val health = HealthReader(app)
    val obd = ObdLink(app)
    val microsoft = MicrosoftAccount(settingsStore)
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
    private var sensorsJob: Job? = null
    @Volatile private var stopping = false

    private var settings = AppSettings()
    private var cloud: RjBrain? = null
    private var persona = Persona()
    private var profile = UserProfile()
    private val recentSongs = mutableListOf<String>()
    private var quizCorrect = 0
    private var quizAsked = 0
    private var showStart: ZonedDateTime = ZonedDateTime.now()
    private val vipSeen = mutableSetOf<String>()
    private val locale: Locale get() = Locale.getDefault()

    // Live senses, refreshed in the background so building the context never waits on the network.
    @Volatile private var healthNow: HealthSnapshot? = null
    @Volatile private var vehicleNow: VehicleStatus? = null
    @Volatile private var trafficNow: TrafficInfo? = null
    @Volatile private var destWeather: WeatherSnapshot? = null
    @Volatile private var outlookEvents: List<CalendarEvent> = emptyList()
    @Volatile private var outlookMail: List<InboxItem> = emptyList()
    private var destPoint: Pair<String, GeoPoint>? = null

    private val events = Cached<List<CalendarEvent>>(Duration.ofMinutes(10)) { calendar.upcoming() }
    private val weather = Cached<WeatherSnapshot>(Duration.ofMinutes(20)) {
        if (!isOnline()) null else drive.lastLocation?.let { weatherClient.fetch(it.latitude, it.longitude, drive.locality()) }
    }
    private val news = Cached<List<NewsItem>>(Duration.ofMinutes(30)) {
        if (!isOnline()) return@Cached null
        val country = drive.lastAddress?.countryCode ?: locale.country.ifBlank { "US" }
        val city = drive.lastAddress?.locality ?: profile.homeCity.ifBlank { null }
        newsClient.fetch(locale.language.ifBlank { "en" }, country.uppercase(), profile.interests, city).ifEmpty { null }
    }

    fun isOnline(): Boolean = runCatching {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false
    }.getOrDefault(false)

    fun start(destination: String?) {
        if (job?.isActive == true) return
        while (actions.tryReceive().isSuccess) Unit // drop stale taps
        RjApp.isLive = true
        job = scope.launch {
            try {
                runShow(destination)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Show crashed: ${e.message}") }
            } finally {
                sensorsJob?.cancel()
                withContext(NonCancellable + Dispatchers.Main) { drive.stop() }
                withContext(NonCancellable + Dispatchers.IO) { obd.close() }
                if (!stopping) end()
            }
        }
    }

    private fun end() {
        RjApp.isLive = false
        _state.update { it.copy(status = OnAir.OFF, quiz = null) }
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
            val d = drive.state()
            stats.endDrive(d.distanceKm)
            stats.recordTrip(d.destination, d.elapsed.toMinutes())
            withTimeoutOrNull(20_000) {
                val recap = buildList {
                    add("today: ${"%.1f".format(d.distanceKm)} km in ${d.elapsed.toMinutes()} minutes")
                    if (recentSongs.isNotEmpty()) add("${recentSongs.size} songs played")
                    if (quizAsked > 0) add("quiz score $quizCorrect out of $quizAsked")
                    healthNow?.stepsToday?.let { add("$it steps so far today") }
                }
                air(think(Cue(CueType.SIGN_OFF, recap)), allowSong = false)
            }
            withContext(Dispatchers.Main) { music.pause(); music.stopAll() }
            stopping = false
            end()
        }
    }

    fun talk() { actions.trySend(Action.Talk) }
    fun skip() { actions.trySend(Action.Skip) }
    fun feature(type: CueType) { actions.trySend(Action.Feature(type)) }
    fun setDestination(d: String?) { drive.destination = d; destPoint = null }

    private suspend fun runShow(destination: String?) {
        val s = settingsStore.load()
        settings = s
        profile = s.profile
        persona = s.persona
        recentSongs.clear(); quizCorrect = 0; quizAsked = 0
        healthNow = null; vehicleNow = null; trafficNow = null; destWeather = null; destPoint = null
        outlookEvents = emptyList(); outlookMail = emptyList()
        showStart = ZonedDateTime.now().minusMinutes(10) // include messages from just before we set off
        vipSeen.clear()
        planner.reset()
        events.invalidate(); weather.invalidate(); news.invalidate()
        Inbox.enabled = s.announceMessages
        Inbox.vips = s.vipList
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
        voices.configure(s.accent.localeTag, s.persona.style.speechRate, s.persona.style.pitch, s.deepVoice)

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
                dialect = s.accent.dialect.ifBlank { null },
            )
        }
        if (cloud == null && s.brainMode == BrainMode.AUTO) {
            _state.update { it.copy(notice = "No Claude API key: running the offline show. Add a key in Settings for news, chat and fresh content.") }
        }
        cloud?.startShow(s.persona, s.profile, memory.all(), catalog)
        offline.startShow(s.persona, s.profile, memory.all(), catalog)
        stats.startDrive()

        // Learn which Bluetooth device is the car, so next time we can offer to go on air when you get in.
        if (s.carBluetoothName.isBlank()) CarBluetooth.connectedAudioDevice(app)?.let { settingsStore.saveCarBluetooth(it) }

        // Gather the slower senses before the opener, but don't hold the show up for long.
        sensorsJob = scope.launch { senseLoop() }
        withTimeoutOrNull(6_000) { while (healthNow == null && outlookEvents.isEmpty() && trafficNow == null) delay(300) }

        // Opener.
        afterAir(air(think(planner.next(context()))))

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

    /** Background refresh of health, car, traffic and Outlook while the show runs. */
    private suspend fun senseLoop() = withContext(Dispatchers.IO) {
        var tick = 0
        while (isActive) {
            val online = isOnline()
            if (settings.useHealth && tick % 10 == 0) runCatching { healthNow = health.snapshot() }       // every 5 min
            if (settings.obdAddress.isNotBlank() && tick % 2 == 0) {                                       // every minute
                obd.poll(settings.obdAddress)?.let { vehicleNow = it }
            }
            if (online && tick % 10 == 0 && microsoft.signedIn) {
                outlookEvents = microsoft.calendar(ZonedDateTime.now())
                outlookMail = microsoft.unreadMail(showStart.toInstant())
            }
            if (online && tick % 6 == 0) refreshTraffic()                                                // every 3 min
            updateSenses()
            tick++
            delay(30_000)
        }
    }

    private fun refreshTraffic() {
        val dest = drive.destination?.takeIf { it.isNotBlank() } ?: nextEventLocation() ?: run { trafficNow = null; return }
        val here = drive.lastLocation?.let { GeoPoint(it.latitude, it.longitude) } ?: return
        val client = TrafficClient(tomTomKey = settings.tomTomKey.ifBlank { null })
        val point = destPoint?.takeIf { it.first == dest }?.second
            ?: client.geocode(dest, here)?.also { destPoint = dest to it } ?: return
        val d = drive.state()
        trafficNow = client.trafficInfo(here, point, dest, d.averageSpeedKph, d.elapsed.toMinutes())
        if (destWeather == null) destWeather = weatherClient.fetch(point.lat, point.lon, dest)
    }

    /** No destination typed? Use where the next meeting is (if it has a real-looking address). */
    private fun nextEventLocation(): String? = allEvents(ZonedDateTime.now())
        .filter { !it.allDay && it.start.isAfter(ZonedDateTime.now()) && it.start.isBefore(ZonedDateTime.now().plusHours(3)) }
        .minByOrNull { it.start }?.location
        ?.takeIf { loc -> loc.length > 4 && !Regex("teams|zoom|meet|online|call|http", RegexOption.IGNORE_CASE).containsMatchIn(loc) }

    private fun allEvents(now: ZonedDateTime): List<CalendarEvent> {
        val phone = events.get(now).orEmpty()
        // Outlook events often also sync into the phone calendar: keep the richer Outlook copy.
        val outlook = outlookEvents
        val phoneOnly = phone.filter { p -> outlook.none { o -> o.title.equals(p.title, true) && Duration.between(o.start, p.start).abs() < Duration.ofMinutes(2) } }
        return (outlook + phoneOnly).sortedBy { it.start }
    }

    private fun updateSenses() {
        val now = ZonedDateTime.now()
        val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val senses = buildList {
            healthNow?.sleepLastNight?.let { add("😴 ${it.toHours()}h ${it.toMinutesPart()}m sleep") }
            healthNow?.stepsToday?.let { add("👟 $it steps") }
            healthNow?.latestHeartRate?.let { add("❤️ $it bpm") }
            trafficNow?.let { add("🚦 ETA ${it.arrival(now).format(hm)}" + if (it.delayMinutes >= 2) " (+${it.delayMinutes})" else "") }
            vehicleNow?.let { v -> v.fuelPercent?.let { add("⛽ $it%") }; v.batteryPercent?.let { add("🔋 $it%") }; if (v.checkEngine == true) add("⚠️ engine light") }
            val unread = Inbox.snapshot(showStart).size + outlookMail.size
            if (unread > 0) add("💬 $unread new")
            if (outlookEvents.isNotEmpty()) add("📅 Outlook")
        }
        _state.update { it.copy(senses = senses) }
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
        afterAir(air(p.segment))
    }

    /** Follow-ups once a segment has played: an offer to act, or a question to listen for. */
    private suspend fun afterAir(seg: Segment?) {
        seg ?: return
        seg.offer?.let { if (handleOffer(it)) return }
        if (seg.listen) conversation()
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

            // A message from a VIP shouldn't wait for the song to end.
            val urgentIds = Inbox.items(showStart, false).filter { it.vip && it.id !in vipSeen }.map { it.id }
            val urgent = urgentIds.isNotEmpty()
            vipSeen += urgentIds
            val nearEnd = if (remaining != null) remaining < 25_000 else elapsed > fallbackGap.toMillis() - 20_000
            if ((nearEnd || urgent) && prefetch == null) {
                val cue = planner.next(context())
                prefetch = scope.async {
                    val seg = if (cue.type == CueType.QUIZ) think(cue.copy(notes = quizNotes(1, 0, 0)), updateStatus = false)
                    else think(cue, updateStatus = false)
                    voices.prefetch(seg.spokenParts() + listOfNotNull(seg.introTag).map { SpokenLine(Speaker.HOST, it) })
                    Prepared(cue, seg)
                }
                if (urgent && prefetch.await().cue.type == CueType.INBOX) return Moment.Link(prefetch)
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

    /** Listen for one utterance, pausing the music so the mic hears the driver. */
    private suspend fun listen(): String? {
        val wasPlaying = music.isPlaying()
        if (wasPlaying) music.pause()
        _state.update { it.copy(status = OnAir.LISTENING) }
        val heard = ears.listen(locale, preferOffline = !isOnline())?.trim()?.ifEmpty { null }
        if (wasPlaying) music.resume()
        heard?.let { addLine(Who.YOU, it) }
        return heard
    }

    /** The driver wants to talk (or the RJ asked a question). Up to three back-and-forths. */
    private suspend fun conversation() {
        repeat(3) {
            val heard = listen() ?: return
            // Direct commands: read messages, reply, running late.
            Commands.parse(heard)?.let { cmd -> return runCommand(cmd) }
            // "Quiz me", "tell me some trivia", "debate"... jump straight to that feature.
            IntentRouter.route(heard)?.let { type ->
                planner.markRun(type, ZonedDateTime.now())
                runCue(Cue(type, listOf("the driver asked for this"), userSaid = heard))
                return
            }
            val seg = think(Cue(CueType.REPLY, userSaid = heard))
            air(seg)
            seg.offer?.let { if (handleOffer(it)) return }
            if (!seg.listen) return
        }
    }

    private suspend fun runCommand(cmd: Command) {
        when (cmd) {
            Command.ReadMessages -> readMessages()
            is Command.ReplyTo -> {
                val entry = Inbox.findRepliable(cmd.name, showStart)
                if (entry == null) say(if (cmd.name != null) "I can't see a message from ${cmd.name} I can reply to." else "There's nothing I can reply to right now.")
                else replyFlow(entry, suggested = null)
            }
            Command.RunningLate -> lateFlow(trafficNow?.let { t -> context().nextEvent()?.let { e ->
                Duration.between(e.start, t.arrival(ZonedDateTime.now())).toMinutes().toInt() } }?.coerceAtLeast(5) ?: 10)
        }
    }

    /** Read the latest messages aloud (on the phone), then offer to reply to the most recent. */
    private suspend fun readMessages() {
        val entries = Inbox.snapshot(showStart).filter { it.kind != InboxKind.VEHICLE }.sortedByDescending { it.at }.take(4)
        val mail = outlookMail.take(2)
        if (entries.isEmpty() && mail.isEmpty()) return say("All quiet. No new messages.")
        entries.forEach { e -> say("${e.from}, on ${e.app}: ${e.text.take(280)}") }
        mail.forEach { m -> say("Email from ${m.from}: ${m.text?.take(200) ?: "no preview"}") }
        Inbox.markHandled(entries.map { it.key })
        outlookMail = outlookMail.drop(mail.size)
        entries.firstOrNull { it.reply != null }?.let { e ->
            say("Want to reply to ${e.from}?")
            if (Commands.confirm(listen()) == true) replyFlow(e, suggested = null)
        }
    }

    /** Reply to a chat by voice. Nothing is sent without a clear yes. */
    private suspend fun replyFlow(entry: Inbox.Entry, suggested: String?) {
        var text = suggested
        if (text == null) {
            say("What would you like to say to ${entry.from}?")
            text = listen() ?: return say("No worries, maybe later.")
        }
        repeat(2) {
            say("Reply to ${entry.from}: \"$text\". Shall I send it?")
            val answer = listen()
            when (Commands.confirm(answer)) {
                true -> {
                    val ok = Inbox.sendReply(app, entry, text!!)
                    return say(if (ok) "Sent." else "Hmm, ${entry.app} wouldn't let me send that. Sorry.")
                }
                false -> return say("Okay, not sent.")
                null -> if (answer != null) text = answer else return say("I'll leave it for now.")
            }
        }
        say("Let's leave that one for later.")
    }

    /** Tell the next meeting's organiser you're running late (Outlook email, or a WhatsApp reply). */
    private suspend fun lateFlow(minutes: Int) {
        val e = context().nextEvent() ?: return say("I can't see a meeting coming up to warn.")
        val email = e.organizerEmail
        val chat = e.organizer?.let { Inbox.findRepliable(it.substringBefore(' '), showStart) }
        if (email == null && chat == null) return say("I don't have a way to reach the organiser of ${e.title} from here. Sorry.")
        val note = "Hi${e.organizer?.let { " ${it.substringBefore(' ')}" } ?: ""}, running about $minutes minutes late for ${e.title}. " +
            "Sorry, see you shortly. ${profile.name}".trim()
        say("I'll ${if (email != null) "email" else "message"} ${e.organizer ?: "the organiser"}: \"$note\". Send it?")
        if (Commands.confirm(listen()) != true) return say("Okay, I won't send anything.")
        val ok = if (email != null) withContext(Dispatchers.IO) { microsoft.sendMail(email, "Running late: ${e.title}", note) }
        else Inbox.sendReply(app, chat!!, note)
        say(if (ok) "Done. They know you're on your way." else "That didn't go through, sorry.")
    }

    /** Returns true if the offer took over the conversation. */
    private suspend fun handleOffer(offer: Offer): Boolean = when (offer.kind) {
        Offer.Kind.REPLY -> {
            val entry = Inbox.findRepliable(offer.to, showStart)
            if (entry == null) false else {
                say(if (offer.text != null) "Want me to reply \"${offer.text}\"?" else "Want to reply to ${entry.from}?")
                val answer = listen()
                when (Commands.confirm(answer)) {
                    true -> { replyFlow(entry, offer.text); true }
                    false -> { say("No problem."); true }
                    null -> { if (answer != null) replyFlow(entry, answer); true }
                }
            }
        }
        Offer.Kind.LATE_NOTE -> {
            lateFlow(offer.minutes ?: 10)
            true
        }
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
            scope.launch { voices.prefetch(listOfNotNull(q.rightLine, q.wrongLine, q.fact).map { SpokenLine(Speaker.HOST, it) }) }
            say(seg.speech, duck = false)
            if (q.tune != null) {
                if (music.active != MusicSource.LIBRARY) continue
                say("Listen carefully...", duck = false)
                _state.update { it.copy(status = OnAir.MUSIC) }
                if (!music.playClip(q.tune!!, 12_000)) continue
            }
            _state.update { it.copy(status = OnAir.QUIZ) }
            say(QuizJudge.spokenQuestion(q), duck = false)
            _state.update { it.copy(status = OnAir.LISTENING) }
            val heard = ears.listen(locale, preferOffline = !isOnline())?.trim()
            asked++
            if (heard.isNullOrEmpty()) {
                say("Time's up! The answer was ${q.answer}.", duck = false)
            } else {
                addLine(Who.YOU, heard)
                if (QuizJudge.isCorrect(heard, q)) {
                    score++
                    say(q.rightLine ?: listOf("Yes! Correct!", "Nailed it!", "That's right, well done!").random(), duck = false)
                } else {
                    say(q.wrongLine ?: listOf("Ooh, not quite. It was ${q.answer}.", "Close, but no. The answer was ${q.answer}.").random(), duck = false)
                }
            }
            q.fact?.let { say(it, duck = false) }
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
            say(verdict + if (record) " That's a new personal best!" else "", duck = false)
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

    /**
     * Put a segment on air, radio-style: the next song starts first, then the host talks over its
     * intro with the music faded down (never silenced). The song is only introduced once we've seen
     * what actually started playing, so the announcement always matches the music.
     * Returns the segment for chaining, or null if it was empty.
     */
    private suspend fun air(seg: Segment, allowSong: Boolean = true): Segment? {
        if (seg.speech.isBlank() && seg.song == null && seg.lines.isEmpty() && seg.quiz == null) return null
        memory.add(seg.remember)
        val song = seg.song?.takeIf { allowSong }
        // Render the whole segment's audio up front (in parallel with the first line playing).
        val parts = seg.spokenParts()
        if (parts.size > 1) scope.launch { voices.prefetch(parts.drop(1)) }

        var intro: String? = null
        if (song != null) {
            _state.update { s -> s.copy(status = OnAir.THINKING) }
            val ok = music.play(song)
            music.lastProblem?.let { problem -> _state.update { s -> s.copy(notice = problem) } }
            if (ok) {
                val actual = confirmPlaying()
                val actualTitle = actual?.title
                val actualArtist = actual?.artist
                val match = actualTitle == null || sameSong(song, actualTitle, actualArtist)
                val played = if (match || actualTitle == null) song else SongPick(actualArtist ?: "", actualTitle)
                recentSongs += played.toString()
                addLine(Who.SONG, played.toString())
                intro = if (match) seg.introTag ?: "Here's ${song.title} by ${song.artist}."
                else "Here's $actualTitle${actualArtist?.let { " from $it" } ?: ""}."
            }
        }

        // Talk over the music bed: faded, not stopped.
        music.duck(true)
        try {
            say(seg.speech)
            seg.lines.forEach { say(it.text, it.speaker) }
            intro?.let { say(it) }
        } finally {
            music.duck(false)
        }

        seg.quiz?.let { q ->
            // A one-off question (e.g. Claude asked one in a reply).
            say(QuizJudge.spokenQuestion(q))
            val heard = listen()
            say(if (heard != null && QuizJudge.isCorrect(heard, q)) q.rightLine ?: "Correct!" else q.wrongLine ?: "It was ${q.answer}.")
        }
        voices.lastError?.let { err -> _state.update { s -> s.copy(notice = err) } }
        _state.update { s -> s.copy(status = OnAir.MUSIC) }
        return seg
    }

    /** Wait briefly for the music app to report the new track. */
    private suspend fun confirmPlaying(): com.rj.core.NowPlaying? {
        val end = System.currentTimeMillis() + 3_000
        var np = music.nowPlaying()
        while (System.currentTimeMillis() < end && (np?.title == null || music.progress()?.playing != true)) {
            delay(250); np = music.nowPlaying()
        }
        return np?.takeIf { it.title != null }
    }

    /** Speak a line. By default over a faded music bed; [duck]=false pauses other audio (quiz, prompts). */
    private suspend fun say(text: String, speaker: Speaker = Speaker.HOST, duck: Boolean = true) {
        if (text.isBlank()) return
        _state.update { it.copy(status = if (it.status == OnAir.QUIZ) OnAir.QUIZ else OnAir.SPEAKING) }
        addLine(if (speaker == Speaker.COHOST) Who.COHOST else Who.RJ, text)
        voices.say(text, speaker, duck)
    }

    private fun context(): ShowContext {
        val online = isOnline()
        if (online) drive.refreshAddress()
        val now = ZonedDateTime.now()
        val d = drive.state(now)
        val share = settings.shareMessageText
        val inbox = Inbox.items(showStart, share).filter { it.kind != InboxKind.VEHICLE } +
            outlookMail.map { if (share) it else it.copy(text = null) }
        val carAlerts = Inbox.vehicleAlerts(showStart)
        val vehicle = (vehicleNow ?: if (carAlerts.isNotEmpty()) VehicleStatus() else null)?.copy(alerts = carAlerts)
        return ShowContext(
            now = now,
            profile = profile,
            drive = d,
            events = allEvents(now),
            weather = weather.get(now),
            headlines = news.get(now).orEmpty(),
            nowPlaying = music.nowPlaying(),
            recentSongs = recentSongs.toList(),
            online = online,
            history = stats.summary(),
            inbox = inbox,
            health = healthNow,
            vehicle = vehicle,
            traffic = trafficNow,
            destinationWeather = destWeather,
            usualTripMinutes = stats.usualTripMinutes(d.destination),
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
