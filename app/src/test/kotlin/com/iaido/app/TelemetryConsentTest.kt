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

    @Test
    fun `each plane reads its own consent fields`() {
        val preferences = emptyPreferences().toMutablePreferences().apply {
            this[diagnosticsConsentKey] = true
            this[diagnosticsConsentVersionKey] = 3
            this[diagnosticsAcceptedAtMsKey] = 10L
            this[diagnosticsRevokedAtMsKey] = 20L
            this[diagnosticsPolicyDigestKey] = "diagnostics-policy"
            this[researchConsentKey] = true
            this[researchConsentVersionKey] = 4
            this[researchAcceptedAtMsKey] = 30L
            this[researchRevokedAtMsKey] = 40L
            this[researchPolicyDigestKey] = "research-policy"
        }

        assertEquals(
            ConsentRecord(true, 3, 10L, 20L, "diagnostics-policy"),
            diagnosticsConsentFromPreferences(preferences),
        )
        assertEquals(
            ConsentRecord(true, 4, 30L, 40L, "research-policy"),
            researchConsentFromPreferences(preferences),
        )
        assertEquals(
            10,
            setOf(
                diagnosticsConsentKey.name,
                diagnosticsConsentVersionKey.name,
                diagnosticsAcceptedAtMsKey.name,
                diagnosticsRevokedAtMsKey.name,
                diagnosticsPolicyDigestKey.name,
                researchConsentKey.name,
                researchConsentVersionKey.name,
                researchAcceptedAtMsKey.name,
                researchRevokedAtMsKey.name,
                researchPolicyDigestKey.name,
            ).size,
        )
    }
}
