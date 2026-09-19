package com.iaido.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetrySettingsSectionTest {
    @Test
    fun `research disclosure names readable text and raw touch traces`() {
        val copy = telemetryDisclosure(TelemetryPlane.RESEARCH)
        assertTrue(copy.contains("readable typing content"))
        assertTrue(copy.contains("raw touch traces"))
    }

    @Test
    fun `diagnostics disclosure explicitly excludes words and raw paths`() {
        val copy = telemetryDisclosure(TelemetryPlane.DIAGNOSTICS)
        assertTrue(copy.contains("does not include words"))
        assertTrue(copy.contains("raw touch paths"))
    }

    @Test
    fun `enabling a plane persists consent then provisions and schedules only that plane`() = runBlocking {
        val persisted = mutableListOf<Pair<TelemetryPlane, ConsentRecord>>()
        val scheduled = mutableListOf<TelemetryPlane>()
        val controller = TelemetryConsentController(
            now = { 1_000L },
            persistConsent = { plane, record -> persisted += plane to record },
            deletePendingQueue = { error("must not delete pending data when enabling") },
            deleteUploadedData = { error("must not delete uploaded data when enabling") },
            provisionCredentials = { UploadDisposition.ACKNOWLEDGED },
            scheduleUpload = { plane -> scheduled += plane },
        )

        val outcome = controller.setEnabled(TelemetryPlane.DIAGNOSTICS, true, ConsentRecord.disabled())

        assertEquals(TelemetryConsentStatus.ENABLED, outcome.status)
        assertEquals(listOf(TelemetryPlane.DIAGNOSTICS), scheduled)
        assertEquals(1, persisted.size)
        val (plane, record) = persisted.single()
        assertEquals(TelemetryPlane.DIAGNOSTICS, plane)
        assertTrue(record.enabled)
        assertEquals(1_000L, record.acceptedAtMs)
        assertEquals(TELEMETRY_CONSENT_POLICY_VERSION, record.consentVersion)
        assertEquals(telemetryPolicyDigest(TelemetryPlane.DIAGNOSTICS), record.policyDigest)
    }

    @Test
    fun `failed provisioning leaves the plane disabled with actionable status and never schedules`() = runBlocking {
        val persisted = mutableListOf<ConsentRecord>()
        var scheduled = false
        val controller = TelemetryConsentController(
            persistConsent = { _, record -> persisted += record },
            deletePendingQueue = {},
            deleteUploadedData = { UploadDisposition.DISCARD },
            provisionCredentials = { UploadDisposition.RETRY },
            scheduleUpload = { scheduled = true },
        )

        val outcome = controller.setEnabled(TelemetryPlane.RESEARCH, true, ConsentRecord.disabled())

        assertEquals(TelemetryConsentStatus.PROVISIONING_FAILED, outcome.status)
        assertFalse(scheduled)
        assertTrue(outcome.message.isNotBlank())
        // The optimistic enable write is rolled back to disabled once provisioning fails, so the
        // stored consent state never claims the plane is enabled when it could not be wired up.
        assertEquals(2, persisted.size)
        assertTrue(persisted.first().enabled)
        assertFalse(persisted.last().enabled)
        assertEquals(ConsentRecord.disabled(), persisted.last())
    }

    @Test
    fun `disabling a plane deletes its pending queue before persisting the disabled state`() = runBlocking {
        val events = mutableListOf<String>()
        val controller = TelemetryConsentController(
            now = { 2_000L },
            persistConsent = { _, record -> events += "persist:${record.enabled}:${record.revokedAtMs}" },
            deletePendingQueue = { events += "delete" },
            deleteUploadedData = { UploadDisposition.DISCARD },
            provisionCredentials = { error("must not provision when disabling") },
            scheduleUpload = { error("must not schedule when disabling") },
        )
        val enabledRecord = ConsentRecord(true, 1, 500L, null, "digest")

        val outcome = controller.setEnabled(TelemetryPlane.DIAGNOSTICS, false, enabledRecord)

        assertEquals(TelemetryConsentStatus.DISABLED, outcome.status)
        assertEquals(listOf("delete", "persist:false:2000"), events)
    }

    @Test
    fun `disabling preserves consent version and accepted timestamp while marking revoked`() = runBlocking {
        var persisted: ConsentRecord? = null
        val controller = TelemetryConsentController(
            now = { 2_000L },
            persistConsent = { _, record -> persisted = record },
            deletePendingQueue = {},
            deleteUploadedData = { UploadDisposition.DISCARD },
            provisionCredentials = { error("must not provision when disabling") },
            scheduleUpload = { error("must not schedule when disabling") },
        )
        val enabledRecord = ConsentRecord(true, 3, 500L, null, "digest-v3")

        controller.setEnabled(TelemetryPlane.DIAGNOSTICS, false, enabledRecord)

        assertEquals(ConsentRecord(false, 3, 500L, 2_000L, "digest-v3"), persisted)
    }

    @Test
    fun `enabling diagnostics never touches research state and vice versa`() = runBlocking {
        val persistedPlanes = mutableListOf<TelemetryPlane>()
        val scheduledPlanes = mutableListOf<TelemetryPlane>()
        val controller = TelemetryConsentController(
            persistConsent = { plane, _ -> persistedPlanes += plane },
            deletePendingQueue = { error("must not delete pending data when enabling") },
            deleteUploadedData = { error("must not delete uploaded data when enabling") },
            provisionCredentials = { UploadDisposition.ACKNOWLEDGED },
            scheduleUpload = { plane -> scheduledPlanes += plane },
        )

        controller.setEnabled(TelemetryPlane.DIAGNOSTICS, true, ConsentRecord.disabled())

        assertEquals(listOf(TelemetryPlane.DIAGNOSTICS), persistedPlanes)
        assertEquals(listOf(TelemetryPlane.DIAGNOSTICS), scheduledPlanes)
    }

    @Test
    fun `deletePending only touches the requested plane`() = runBlocking {
        val deleted = mutableListOf<TelemetryPlane>()
        val controller = TelemetryConsentController(
            persistConsent = { _, _ -> error("must not persist consent") },
            deletePendingQueue = { plane -> deleted += plane },
            deleteUploadedData = { error("must not delete uploaded data") },
            provisionCredentials = { error("must not provision") },
            scheduleUpload = { error("must not schedule") },
        )

        controller.deletePending(TelemetryPlane.RESEARCH)

        assertEquals(listOf(TelemetryPlane.RESEARCH), deleted)
    }

    @Test
    fun `deleteUploaded only invokes the requested plane's server deletion route`() = runBlocking {
        val requested = mutableListOf<TelemetryPlane>()
        val controller = TelemetryConsentController(
            persistConsent = { _, _ -> error("must not persist consent") },
            deletePendingQueue = { error("must not delete pending data") },
            deleteUploadedData = { plane -> requested += plane; UploadDisposition.ACKNOWLEDGED },
            provisionCredentials = { error("must not provision") },
            scheduleUpload = { error("must not schedule") },
        )

        val disposition = controller.deleteUploaded(TelemetryPlane.DIAGNOSTICS)

        assertEquals(UploadDisposition.ACKNOWLEDGED, disposition)
        assertEquals(listOf(TelemetryPlane.DIAGNOSTICS), requested)
    }

    @Test
    fun `plane labels used in status text stay distinct`() {
        assertEquals("Diagnostics", TelemetryPlane.DIAGNOSTICS.settingsLabel())
        assertEquals("Research", TelemetryPlane.RESEARCH.settingsLabel())
    }

    @Test
    fun `policy digests differ between planes and are stable for the same plane`() {
        assertEquals(telemetryPolicyDigest(TelemetryPlane.DIAGNOSTICS), telemetryPolicyDigest(TelemetryPlane.DIAGNOSTICS))
        assertTrue(telemetryPolicyDigest(TelemetryPlane.DIAGNOSTICS) != telemetryPolicyDigest(TelemetryPlane.RESEARCH))
    }
}
