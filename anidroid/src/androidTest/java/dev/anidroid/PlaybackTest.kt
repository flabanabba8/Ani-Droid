package dev.anidroid

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun playerView(view: View): PlayerView? {
        if(view is PlayerView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) playerView(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun liveProvidersRenderVideoAndSaveResumePosition() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        lateinit var model: LibraryModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[LibraryModel::class.java] }
        for(provider in InstrumentationRegistry.getArguments().getString("sources","ani,luffy").split(",")) {
            compose.runOnIdle { model.switchProvider(provider); model.query = "cyberpunk edgerunners"; model.search() }
            compose.waitUntil(120000) { !model.busy }
            compose.runOnIdle {
                assertNull("$provider search: ${model.error}", model.error)
                assertTrue(model.results.isNotEmpty())
                model.open(model.results.first { it.name.contains("Edgerunners", true) })
            }
            compose.waitUntil(120000) { !model.busy }
            compose.runOnIdle {
                assertNull("$provider details: ${model.error}",model.error)
                model.resolve(model.details!!.episodes.first())
            }
            compose.waitUntil(180000) { !model.busy }
            lateinit var playing: Playing
            compose.runOnIdle {
                assertNull("$provider streams: ${model.error}",model.error)
                model.play(model.streams.last())
                playing = model.playing!!
            }
            var playbackError: String? = null
            compose.waitUntil(120000) {
                var rendered = false
                compose.activity.runOnUiThread {
                    val player = playerView(compose.activity.window.decorView)?.player as? ExoPlayer
                    playbackError = player?.playerError?.errorCodeName
                    rendered = (player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 5 && (player?.currentPosition ?: 0) > 2000
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                rendered || playbackError != null
            }
            assertNull("$provider playback error", playbackError)
            compose.runOnIdle { model.back() }
            compose.waitForIdle()
            assertTrue("$provider saved progress", model.position(playing) > 0)
            Thread.sleep(10000)
        }
    }
}
