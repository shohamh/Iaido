package com.iaido.app

import androidx.datastore.preferences.core.emptyPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TelemetryConsentTest {
    @Test
    fun `both planes default to disabled and use different consent keys`() {
        assertEquals(ConsentRecord.disabled(), diagnosticsConsentFromPreferences(emptyPreferences()))
        assertEquals(ConsentRecord.disabled(), researchConsentFromPreferences(emptyPreferences()))
        assertNotEquals(diagnosticsConsentKey.name, researchConsentKey.name)
    }
}
