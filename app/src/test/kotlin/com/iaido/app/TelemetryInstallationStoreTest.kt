package com.iaido.app

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
    fun `recording an event stamps the server issued installation`() {
        val issued = TelemetryInstallation("installation-1", "server-write", "server-delete")
        val store = TelemetryInstallationStore(FakeInstallationCredentialStorage(issued))

        assertEquals(issued, store.envelopeInstallation())
    }
}
