package com.iaido.app

import androidx.datastore.preferences.core.Preferences

enum class TelemetryPlane {
    DIAGNOSTICS,
    RESEARCH,
}

data class ConsentRecord(
    val enabled: Boolean,
    val consentVersion: Int,
    val acceptedAtMs: Long?,
    val revokedAtMs: Long?,
    val policyDigest: String?,
) {
    companion object {
        fun disabled() = ConsentRecord(
            enabled = false,
            consentVersion = 0,
            acceptedAtMs = null,
            revokedAtMs = null,
            policyDigest = null,
        )
    }
}

internal fun diagnosticsConsentFromPreferences(preferences: Preferences): ConsentRecord =
    consentFromPreferences(
        preferences = preferences,
        enabledKey = diagnosticsConsentKey,
        versionKey = diagnosticsConsentVersionKey,
        acceptedAtMsKey = diagnosticsAcceptedAtMsKey,
        revokedAtMsKey = diagnosticsRevokedAtMsKey,
        policyDigestKey = diagnosticsPolicyDigestKey,
    )

internal fun researchConsentFromPreferences(preferences: Preferences): ConsentRecord =
    consentFromPreferences(
        preferences = preferences,
        enabledKey = researchConsentKey,
        versionKey = researchConsentVersionKey,
        acceptedAtMsKey = researchAcceptedAtMsKey,
        revokedAtMsKey = researchRevokedAtMsKey,
        policyDigestKey = researchPolicyDigestKey,
    )

private fun consentFromPreferences(
    preferences: Preferences,
    enabledKey: Preferences.Key<Boolean>,
    versionKey: Preferences.Key<Int>,
    acceptedAtMsKey: Preferences.Key<Long>,
    revokedAtMsKey: Preferences.Key<Long>,
    policyDigestKey: Preferences.Key<String>,
) = ConsentRecord(
    enabled = preferences[enabledKey] ?: false,
    consentVersion = preferences[versionKey] ?: 0,
    acceptedAtMs = preferences[acceptedAtMsKey],
    revokedAtMs = preferences[revokedAtMsKey],
    policyDigest = preferences[policyDigestKey],
)
