package com.iaido.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryInstallationStoreTest {
    @Test
    fun `recording an event with no stored installation never fabricates or persists credentials`() {
        val storage = FakeInstallationCredentialStorage()
        val store = TelemetryInstallationStore(storage)

        assertNull(store.envelopeInstallation())
        assertNull(storage.read())
        assertNull(store.writeCredential(TelemetryPlane.RESEARCH))
    }

    @Test
    fun `invalidating the write credential keeps the installation id and deletion credential`() {
        val storage = FakeInstallationCredentialStorage(
            TelemetryInstallation("installation-1", "server-write", "server-delete"),
        )
        val store = TelemetryInstallationStore(storage)

        store.invalidateWriteCredential()

        assertNull(store.installation())
        assertNull(store.writeCredential(TelemetryPlane.RESEARCH))
        assertEquals(RetiredInstallation("installation-1", "server-delete"), store.retiredInstallation())
        assertTrue(store.needsReprovisioning())

        store.getOrCreateInstallation { TelemetryInstallation("installation-2", "write-2", "delete-2") }

        assertFalse(store.needsReprovisioning())
        assertEquals("write-2", store.writeCredential(TelemetryPlane.RESEARCH))
        assertEquals(RetiredInstallation("installation-1", "server-delete"), store.retiredInstallation())
    }

    @Test
    fun `separate store instances never provision concurrently`() {
        // The worker and the Settings screen each build their own store over the same storage.
        val storage = FakeInstallationCredentialStorage()
        val provisions = AtomicInteger()
        val firstEntered = CountDownLatch(1)
        val first = Thread {
            TelemetryInstallationStore(storage).getOrCreateInstallation {
                provisions.incrementAndGet()
                firstEntered.countDown()
                Thread.sleep(300)
                TelemetryInstallation("installation-1", "write-1", "delete-1")
            }
        }
        first.start()
        firstEntered.await()

        val second = TelemetryInstallationStore(storage).getOrCreateInstallation {
            provisions.incrementAndGet()
            TelemetryInstallation("installation-2", "write-2", "delete-2")
        }
        first.join()

        assertEquals(1, provisions.get())
        assertEquals("installation-1", second.installationId)
    }

    @Test
    fun `recording an event stamps the server issued installation`() {
        val issued = TelemetryInstallation("installation-1", "server-write", "server-delete")
        val store = TelemetryInstallationStore(FakeInstallationCredentialStorage(issued))

        assertEquals(issued, store.envelopeInstallation())
    }
}
