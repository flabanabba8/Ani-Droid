package com.geminireader.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.geminireader.ReaderApp

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() { super.onCreate(); session = MediaSession.Builder(this, (application as ReaderApp).playback.player).build() }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
