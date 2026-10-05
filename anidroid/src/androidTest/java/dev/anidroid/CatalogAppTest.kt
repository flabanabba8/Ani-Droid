package dev.anidroid

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

class CatalogAppTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun connectedCatalogSearchFiltersDetailsAndLiveSearch() {
        lateinit var model: LibraryModel
        compose.runOnIdle { model=ViewModelProvider(compose.activity)[LibraryModel::class.java] }
        assumeTrue("Install private catalog connection before the integration test",model.catalogConnection != null)
        compose.waitUntil(90000) { !model.busy }
        compose.runOnIdle { model.catalogProvider="ani";model.browseCatalog() }
        compose.waitUntil(90000) { !model.busy }
        lateinit var title: Title
        compose.runOnIdle {
            assertNull(model.error);assertTrue(model.catalogTotal>0);assertTrue(model.catalogItems.all { it.provider=="ani" })
            title=model.catalogItems.first();model.catalogQuery=title.name;model.browseCatalog()
        }
        compose.waitUntil(90000) { !model.busy }
        compose.runOnIdle { assertNull(model.error);assertTrue(model.catalogItems.any { it.id==title.id });model.open(title) }
        compose.waitUntil(90000) { !model.busy }
        compose.runOnIdle { assertNull(model.error);assertTrue(model.details!!.episodes.isNotEmpty());model.back();model.catalogQuery="";model.catalogProvider="luffy";model.catalogKind="movie";model.browseCatalog() }
        compose.waitUntil(90000) { !model.busy }
        compose.runOnIdle { assertNull(model.error);assertTrue(model.catalogItems.isNotEmpty());assertTrue(model.catalogItems.all { !it.series && it.provider=="luffy" }) }
        lateinit var genre: String
        compose.runOnIdle {
            assertTrue("Genres should have been indexed",model.catalogGenres.isNotEmpty())
            genre=model.catalogGenres.first().first;model.catalogProvider="all";model.catalogKind="all";model.catalogGenre=genre;model.browseCatalog()
        }
        compose.waitUntil(90000) { !model.busy }
        compose.runOnIdle { assertNull(model.error);assertTrue(model.catalogItems.isNotEmpty());assertTrue(model.catalogItems.all { genre in it.genres }) }
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("ani-cli · Anime").assertIsDisplayed()
        compose.onNodeWithText("Catalog",useUnmergedTree=false).performClick()
        compose.onNodeWithText("Search catalog").assertIsDisplayed()
    }
    @Test fun wrongCertificateAndAccessKeyAreRejected() {
        lateinit var model: LibraryModel
        compose.runOnIdle { model=ViewModelProvider(compose.activity)[LibraryModel::class.java] }
        val connection=model.catalogConnection
        assumeTrue(connection != null)
        try {
            CatalogClient(connection!!.copy(fingerprint="0".repeat(64))).catalog("","all","all",1,"name")
            fail("Wrong pin must be rejected")
        } catch(expected: java.io.IOException) { }
        try {
            CatalogClient(connection!!.copy(token="wrong-test-key-1234567890")).catalog("","all","all",1,"name")
            fail("Wrong token must be rejected")
        } catch(expected: IllegalStateException) { assertTrue(expected.message!!.contains("access key")) }
    }
}
