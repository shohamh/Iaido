package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `recording an event stamps the server issued installation`() {
        val issued = TelemetryInstallation("installation-1", "server-write", "server-delete")
        val store = TelemetryInstallationStore(FakeInstallationCredentialStorage(issued))

        assertEquals(issued, store.envelopeInstallation())
    }
}
