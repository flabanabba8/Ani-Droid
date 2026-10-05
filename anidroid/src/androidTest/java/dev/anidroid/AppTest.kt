package dev.anidroid

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class AppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun navigationSourcesAndAbout() {
        compose.onNodeWithText("Ani-Droid").assertIsDisplayed()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Luffy · Film & TV").performClick()
        compose.onNodeWithText("Search movies, series & anime").assertIsDisplayed()
        compose.onNodeWithText("Favorites").performClick()
        compose.onNodeWithText("Your favorites will appear here.").assertIsDisplayed()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Your history will appear here.").assertIsDisplayed()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("ani-cli · Anime").performClick()
        compose.onNodeWithText("Search anime").assertIsDisplayed()
        compose.onNodeWithText("About").performClick()
        compose.onNodeWithText("Ani-Droid 0.7").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
    }
}
