package com.geminireader.data

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geminireader.ReaderApp
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryOnDeviceTest {
    @Test fun reopenRestoresReadingLocationWithoutChangingAudioBookmark() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ReaderApp
        val last = File(app.filesDir, "last-book.txt")
        val previousLast = last.takeIf { it.exists() }?.readBytes()
        val book = app.books.save(Book(title = "Reading bookmark regression", format = "txt", chapters =
            listOf(Chapter("One", listOf("First page.")), Chapter("Two", listOf("Next page.", "Middle page.", "Last page.")))))
        val audio = Position(0, 0, offsetMs = 1234, audioKey = "cached.wav", speed = 1.5f)
        try {
            withContext(Dispatchers.Main) {
                app.playback.stopAndJoin()
                app.books.position(book.id, audio)
                app.books.readingPosition(book.id, Position(1, 2))
                app.open(book.id).join()
                assertEquals(1, app.chapter)
                assertEquals(2, app.paragraph)
                assertEquals(audio, app.books.position(book.id))
                // Exercise launcher restoration independently of opening from the library.
                app.book = null
                app.handle(Intent(Intent.ACTION_MAIN)).join()
                assertEquals(book.id, app.book?.id)
                assertEquals(1, app.chapter)
                assertEquals(2, app.paragraph)
                assertEquals(audio, app.books.position(book.id))
            }
        } finally {
            withContext(Dispatchers.Main) { app.book = null; app.screen = "library" }
            app.books.delete(book.id)
            if (previousLast == null) last.delete() else last.writeBytes(previousLast)
        }
    }
}
