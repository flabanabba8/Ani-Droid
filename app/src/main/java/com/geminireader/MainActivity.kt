package com.geminireader

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.geminireader.ui.ReaderUi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        val app = application as ReaderApp
        setContent { ReaderUi(app) }
        if (savedInstanceState == null || app.book == null) app.handle(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); (application as ReaderApp).handle(intent) }
    override fun onStop() { (application as ReaderApp).playback.persist(); super.onStop() }
}
