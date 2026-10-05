package dev.anidroid

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.ui.PlayerView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleOptionsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun playerView(v: View): PlayerView? {
        if(v is PlayerView)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)playerView(v.getChildAt(i))?.let {return it}
        return null
    }
    @Test fun controlsPersistWithoutReplacingThePlayer() {
        lateinit var model: LibraryModel
        compose.runOnIdle {
            model=ViewModelProvider(compose.activity)[LibraryModel::class.java]
            val title=Title("ani","subtitle-test","Subtitle options test")
            model.playing=Playing(title,Episode("ep",1,"1"),Stream("Test","http://127.0.0.1:9/no-video.m3u8",emptyMap()))
        }
        compose.waitForIdle()
        var originalPlayer: Any?=null
        var oldSize=0
        var oldColor=0
        compose.runOnIdle {originalPlayer=playerView(compose.activity.window.decorView)!!.player;oldSize=model.subtitleSize;oldColor=model.subtitleColor}
        compose.onNodeWithText("Subtitles",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Size: $oldSize sp · Change",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Color: ${model.subtitleColors.first { it.second==oldColor }.first} · Change",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Done",useUnmergedTree=true).performClick()
        compose.runOnIdle {
            assertNotEquals(oldSize,model.subtitleSize);assertNotEquals(oldColor,model.subtitleColor)
            assertSame(originalPlayer,playerView(compose.activity.window.decorView)!!.player)
            val prefs=compose.activity.getSharedPreferences("library",0)
            assertEquals(model.subtitleSize,prefs.getInt("subtitleSize",0))
            assertEquals(model.subtitleColor,prefs.getInt("subtitleColor",0))
            model.back()
        }
    }
}
