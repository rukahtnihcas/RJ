package com.rj.app.music

import android.Manifest
import android.app.SearchManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.rj.app.data.MusicApp
import com.rj.app.data.MusicSource
import com.rj.core.NowPlaying
import com.rj.core.QuizJudge
import com.rj.core.SongPick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Empty notification listener. Being enabled in "Notification access" is what lets us read the
 * active media sessions of Spotify / YouTube Music etc. (now playing, position, play/pause, play-from-search).
 */
class MediaWatcher : NotificationListenerService()

data class Progress(val positionMs: Long, val durationMs: Long, val playing: Boolean, val trackId: String?)

/** One place the show engine talks to for music, whichever source is active. */
class MusicHub(context: Context) {
    val library = LocalMusic(context)
    val streaming = StreamingApps(context)

    var source = MusicSource.AUTO
    var online = true
    /** The source actually in use right now (AUTO resolves to one of the others). */
    var active = MusicSource.STREAMING
        private set
    var lastProblem: String? = null
        private set

    /** Decide the active source for this drive. */
    fun resolve() {
        active = when (source) {
            MusicSource.LIBRARY -> MusicSource.LIBRARY
            MusicSource.STREAMING -> MusicSource.STREAMING
            MusicSource.AUTO -> if (online && streaming.installed()) MusicSource.STREAMING
            else if (library.tracks().isNotEmpty()) MusicSource.LIBRARY else MusicSource.STREAMING
        }
    }

    /** Songs the RJ may pick from: the phone library when that's the active source, else anything. */
    fun catalog(): List<SongPick> = if (active == MusicSource.LIBRARY) library.tracks().map { it.pick } else emptyList()

    suspend fun play(song: SongPick): Boolean {
        lastProblem = null
        if (active == MusicSource.LIBRARY) return library.play(song).also { if (!it) lastProblem = "Couldn't find ${song.title} on the phone." }
        if (streaming.play(song)) return true
        // Streaming app didn't start. In AUTO, fall back to the phone's own music.
        if (source == MusicSource.AUTO && library.tracks().isNotEmpty()) {
            active = MusicSource.LIBRARY
            lastProblem = "${streaming.app.label} didn't start playing, so I've switched to music on your phone."
            return library.play(library.closest(song))
        }
        lastProblem = if (!streaming.hasSessionAccess()) "Music app opened but didn't play. Allow notification access so I can press play for you."
        else "${streaming.app.label} didn't start ${song.title}."
        return false
    }

    /** Play the first seconds of a song (name that tune). Library only. */
    suspend fun playClip(song: SongPick, millis: Long): Boolean {
        if (!library.play(song, fromMs = 20_000)) return false
        delay(millis)
        library.pause()
        return true
    }

    fun progress(): Progress? = if (active == MusicSource.LIBRARY) library.progress() else streaming.progress()
    fun nowPlaying(): NowPlaying? = if (active == MusicSource.LIBRARY) library.nowPlaying() else streaming.nowPlaying()
    fun isPlaying(): Boolean = if (active == MusicSource.LIBRARY) library.isPlaying() else streaming.isPlaying()
    fun pause() = if (active == MusicSource.LIBRARY) library.pause() else streaming.pause()
    fun resume() = if (active == MusicSource.LIBRARY) library.resume() else streaming.resume()
    /** Our own player has to duck itself; streaming apps duck via audio focus. */
    fun duck(on: Boolean) { if (active == MusicSource.LIBRARY) library.setVolume(if (on) 0.25f else 1f) }
    fun stopAll() = library.stop()
}

/** Music stored on the phone, played by our own player: works offline, exact control over every track. */
class LocalMusic(private val context: Context) {
    data class Track(val id: Long, val pick: SongPick, val durationMs: Long)

    private var cache: List<Track>? = null
    private var player: MediaPlayer? = null
    private var current: Track? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { /* the show controls our own volume; ignore */ }.build()

    fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasPermission() = ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    fun tracks(): List<Track> {
        cache?.let { return it }
        if (!hasPermission()) return emptyList()
        val out = mutableListOf<Track>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 60000",
                null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(1)?.trim().orEmpty()
                    val artist = c.getString(2)?.trim()?.takeUnless { it.isEmpty() || it == "<unknown>" } ?: "Unknown artist"
                    if (title.isNotEmpty()) out += Track(c.getLong(0), SongPick(artist, title), c.getLong(3))
                }
            }
        }
        return out.distinctBy { it.pick.toString().lowercase() }.also { cache = it }
    }

    fun closest(song: SongPick): SongPick {
        val all = tracks()
        val want = QuizJudge.normalize("${song.title} ${song.artist}").split(' ').toSet()
        return all.maxByOrNull { t -> QuizJudge.normalize("${t.pick.title} ${t.pick.artist}").split(' ').count { it in want } }?.pick
            ?: all.random().pick
    }

    private fun find(song: SongPick): Track? {
        val title = QuizJudge.normalize(song.title)
        val artist = QuizJudge.normalize(song.artist)
        val all = tracks()
        return all.firstOrNull { QuizJudge.normalize(it.pick.title) == title && QuizJudge.normalize(it.pick.artist) == artist }
            ?: all.firstOrNull { QuizJudge.normalize(it.pick.title) == title }
            ?: all.firstOrNull { QuizJudge.normalize(it.pick.title).contains(title) || title.contains(QuizJudge.normalize(it.pick.title)) }
    }

    suspend fun play(song: SongPick, fromMs: Int = 0): Boolean = withContext(Dispatchers.Main) {
        val track = find(song) ?: return@withContext false
        stop()
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
        runCatching {
            audio.requestAudioFocus(focus)
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(context, uri)
                prepare()
                if (fromMs > 0 && fromMs < duration - 5_000) seekTo(fromMs)
                start()
            }
            current = track
            true
        }.getOrElse { stop(); false }
    }

    fun progress(): Progress? {
        val p = player ?: return null
        val t = current ?: return null
        return runCatching { Progress(p.currentPosition.toLong(), p.duration.toLong(), p.isPlaying, t.pick.toString()) }.getOrNull()
    }

    fun nowPlaying(): NowPlaying? = current?.let { NowPlaying(it.pick.artist, it.pick.title, "phone") }
    fun isPlaying() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    fun pause() { runCatching { player?.takeIf { it.isPlaying }?.pause() } }
    fun resume() { runCatching { player?.start() } }
    fun setVolume(v: Float) { runCatching { player?.setVolume(v, v) } }

    fun stop() {
        runCatching { player?.release() }
        player = null
        current = null
        audio.abandonAudioFocusRequest(focus)
    }
}

/** Drives whichever streaming app the user prefers. */
class StreamingApps(private val context: Context) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val watcher = ComponentName(context, MediaWatcher::class.java)

    var app: MusicApp = MusicApp.SPOTIFY

    fun installed(): Boolean = app.packageName?.let { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    } ?: true

    fun hasSessionAccess(): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true

    fun openSessionAccessSettings() {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun controllers(): List<MediaController> {
        if (!hasSessionAccess()) return emptyList()
        return runCatching { sessions.getActiveSessions(watcher) }.getOrNull().orEmpty()
            .filter { it.packageName != context.packageName }
    }

    private fun controller(): MediaController? {
        val all = controllers()
        return all.firstOrNull { app.packageName != null && it.packageName == app.packageName }
            ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull()
    }

    private fun preferredController(): MediaController? =
        controllers().firstOrNull { app.packageName == null || it.packageName == app.packageName }

    fun nowPlaying(): NowPlaying? = controller()?.let { c ->
        val md = c.metadata ?: return null
        NowPlaying(
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE),
            app = c.packageName,
        )
    }

    /** Null when we can't see the music app (no notification access). */
    fun progress(): Progress? = controller()?.let { c ->
        val st = c.playbackState ?: return null
        val md = c.metadata
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val pos = if (st.state == PlaybackState.STATE_PLAYING) {
            st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        } else st.position
        Progress(
            positionMs = pos,
            durationMs = duration,
            playing = st.state == PlaybackState.STATE_PLAYING || st.state == PlaybackState.STATE_BUFFERING,
            trackId = md?.let { "${it.getString(MediaMetadata.METADATA_KEY_TITLE)}|${it.getString(MediaMetadata.METADATA_KEY_ARTIST)}" },
        )
    }

    private fun extras(song: SongPick) = Bundle().apply {
        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        putString(MediaStore.EXTRA_MEDIA_TITLE, song.title)
        putString(MediaStore.EXTRA_MEDIA_ARTIST, song.artist)
        putString(SearchManager.QUERY, song.query)
    }

    private fun isPlayingSong(song: SongPick): Boolean {
        val c = preferredController() ?: return false
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.let(QuizJudge::normalize) ?: return false
        val want = QuizJudge.normalize(song.title)
        return playing && (title.contains(want) || want.contains(title))
    }

    /**
     * Start a song and make sure it actually plays. Many apps (Spotify especially) open their search
     * results from the play-from-search intent without starting playback, so we follow up through the
     * app's media session: play-from-search again now the app is awake, then press play.
     */
    suspend fun play(song: SongPick): Boolean {
        val q = song.query
        // 1. If the app's session is already alive, ask it directly (works from the background).
        preferredController()?.let { c ->
            c.transportControls.playFromSearch(q, extras(song))
            if (waitFor(4_000) { isPlayingSong(song) }) return true
        }
        // 2. Wake the app with the standard intent.
        launchSearchIntent(song)
        if (!hasSessionAccess()) {
            // We can't see the app, so the best we can do is press play once it has loaded.
            delay(3_500)
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            delay(1_500)
            return audio.isMusicActive
        }
        if (waitFor(4_000) { isPlayingSong(song) }) return true
        // 3. App is awake now: search through its session, then nudge play.
        val c = waitForController(4_000) ?: return audio.isMusicActive
        c.transportControls.playFromSearch(q, extras(song))
        if (waitFor(4_000) { isPlayingSong(song) }) return true
        c.transportControls.play()
        if (waitFor(2_500) { c.playbackState?.state == PlaybackState.STATE_PLAYING }) return true
        return false
    }

    private fun launchSearchIntent(song: SongPick) {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtras(extras(song))
            app.packageName?.let { setPackage(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(intent.setPackage(null)) }
        }
    }

    private suspend fun waitFor(ms: Long, check: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (runCatching(check).getOrDefault(false)) return true
            delay(400)
        }
        return false
    }

    private suspend fun waitForController(ms: Long): MediaController? {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            preferredController()?.let { return it }
            delay(400)
        }
        return null
    }

    fun pause() { controller()?.transportControls?.pause() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE) }
    fun resume() { controller()?.transportControls?.play() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }
    fun isPlaying(): Boolean = progress()?.playing ?: audio.isMusicActive

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
