package com.iaido.app

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoreEngineUpdateStoreTest {
    @Test
    fun `install retains the previous known good artifact and rollback restores it`() {
        val directory = Files.createTempDirectory("ninja-update-store").toFile()
        try {
            val store = CoreEngineUpdateStore(directory)
            store.installVerifiedArtifact("0.1.1", "first".toByteArray())
            store.installVerifiedArtifact("0.1.2", "second".toByteArray())

            assertArrayEquals("second".toByteArray(), store.currentArtifact()!!.readBytes())
            assertArrayEquals("first".toByteArray(), store.previousArtifact()!!.readBytes())
            assertEquals("0.1.2", store.currentVersion())
            assertEquals("0.1.1", store.previousVersion())

            store.rollback()

            assertArrayEquals("first".toByteArray(), store.currentArtifact()!!.readBytes())
            assertEquals("0.1.1", store.currentVersion())
            assertEquals(null, store.previousArtifact())
            assertEquals(null, store.previousVersion())
        } finally {
            directory.deleteRecursively()
        }
    }
}
