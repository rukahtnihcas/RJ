package com.rj.app.music

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import com.rj.app.data.MusicApp
import com.rj.core.NowPlaying
import com.rj.core.SongPick

/**
 * Empty notification listener. Being enabled in "Notification access" is what lets us read the
 * active media sessions of Spotify / YouTube Music etc. (now playing, position, play/pause, play-from-search).
 */
class MediaWatcher : NotificationListenerService()

data class Progress(val positionMs: Long, val durationMs: Long, val playing: Boolean, val trackId: String?)

/** Drives whichever music app the user prefers. */
class MusicController(private val context: Context) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val watcher = ComponentName(context, MediaWatcher::class.java)

    var app: MusicApp = MusicApp.SPOTIFY

    fun hasSessionAccess(): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true

    fun openSessionAccessSettings() {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun controller(): MediaController? {
        if (!hasSessionAccess()) return null
        val all = runCatching { sessions.getActiveSessions(watcher) }.getOrNull().orEmpty()
            .filter { it.packageName != context.packageName }
        return all.firstOrNull { app.packageName != null && it.packageName == app.packageName }
            ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull()
    }

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
        // Extrapolate the position since the app last reported it.
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

    /**
     * Start a song. Uses the music app's media session when we can see it (works from the background);
     * otherwise falls back to the standard "play from search" intent, which opens the music app.
     */
    fun play(song: SongPick): Boolean {
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_TITLE, song.title)
            putString(MediaStore.EXTRA_MEDIA_ARTIST, song.artist)
            putString(SearchManager.QUERY, song.query)
        }
        controller()?.takeIf { app.packageName == null || it.packageName == app.packageName }?.let { c ->
            c.transportControls.playFromSearch(song.query, extras)
            return true
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtras(extras)
            app.packageName?.let { setPackage(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrElse {
            // Preferred app not installed: let any music app take it.
            runCatching { context.startActivity(intent.setPackage(null)); true }.getOrDefault(false)
        }
    }

    fun pause() {
        controller()?.transportControls?.pause() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun resume() {
        controller()?.transportControls?.play() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun skip() {
        controller()?.transportControls?.skipToNext() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun isPlaying(): Boolean = progress()?.playing ?: audio.isMusicActive

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
