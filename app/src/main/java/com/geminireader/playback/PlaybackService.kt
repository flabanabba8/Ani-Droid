package com.geminireader.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.geminireader.ReaderApp
import com.geminireader.MainActivity
import android.app.PendingIntent
import android.content.Intent

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, (application as ReaderApp).playback.player).setSessionActivity(activity).build().also { addSession(it) }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
