package com.geminireader.data

import org.junit.Assert.*
import org.junit.Test

class SettingsMigrationTest {
    @Test fun removedEngineUsesOfflineNarrationAndPreservesCredentials() {
        val old = Settings(engine = "removed-local-engine", characterMode = "distinct", groqApiKey = "saved-key", fontSize = 24)
        val migrated = old.supportedEngine()
        assertEquals("kokoro", migrated.engine)
        assertEquals("narrator", migrated.characterMode)
        assertEquals("saved-key", migrated.groqApiKey)
        assertEquals(24, migrated.fontSize)
        assertEquals(migrated, migrated.supportedEngine())
    }
    @Test fun supportedEngineIsPreserved() {
        for (engine in Settings.engines) {
            val current = Settings(engine = engine)
            assertSame(current, current.supportedEngine())
        }
    }
}
