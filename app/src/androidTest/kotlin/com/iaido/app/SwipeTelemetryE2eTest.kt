package com.iaido.app

import android.content.Context
import android.os.SystemClock
import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole research-telemetry path on a real device, against a real collector: research consent
 * through the real consent controller (which provisions over HTTPS), a real swipe on the real
 * Iaido keyboard, the IME's own research capture, queue and WorkManager upload, and the
 * collector's operator API, chunked by app version.
 *
 * Needs a collector and its operator token, so every test returns early (the instrumentation
 * runner reports assumption failures as failures here, see [TelemetryUploadE2eTest]) unless the
 * build has `-PiaidoTelemetryBaseUrl=https://<collector>` and the run passes
 * `-Pandroid.testInstrumentationRunnerArguments.iaidoOperatorToken=<token>`. How to run it against
 * the local Docker collector: `telemetry-server/README.md`, "Run the emulator end-to-end test".
 */
@RunWith(AndroidJUnit4::class)
class SwipeTelemetryE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val baseUrl = BuildConfig.IAIDO_TELEMETRY_BASE_URL.trimEnd('/')
    private val operatorToken = InstrumentationRegistry.getArguments().getString(OPERATOR_TOKEN_ARG).orEmpty()
    private val configured = baseUrl.isNotBlank() && operatorToken.isNotBlank()

    @Test
    fun aSwipeWithResearchConsentReachesTheCollectorTaggedWithTheInstalledVersion() {
        if (!configured) return
        val queue = researchQueue()
        val controller = buildTelemetryConsentController(context)
        val windowStartMs = System.currentTimeMillis() - CLOCK_SLACK_MS

        try {
            enableResearch(controller, queue)
            val installationId = requireNotNull(TelemetryInstallationStore(context).installation()) {
                "Enabling research consent must leave server-issued credentials behind"
            }.installationId
            val before = operatorBatches(BuildConfig.VERSION_NAME).map { it.batchId }.toSet()

            swipeHelloOnTheIaidoKeyboard(consentExpected = true)

            val uploaded = awaitSwipeAtCollector(installationId, before, windowStartMs)
            uploaded.forEach { batch ->
                assertEquals("Batch tagged with the wrong app version", BuildConfig.VERSION_NAME, batch.appVersion)
            }
            assertTrue(
                "The collector has the swipe but the local research queue never drained",
                awaitQueueDrained(queue),
            )

            // Version chunking: the same batches are invisible under any other version.
            val otherVersion = otherVersionThan(BuildConfig.VERSION_NAME)
            val uploadedIds = uploaded.map { it.batchId }.toSet()
            val leaked = operatorBatches(otherVersion).filter { it.batchId in uploadedIds }
            assertTrue("Batches $uploadedIds leaked into version $otherVersion: $leaked", leaked.isEmpty())
            val leakedEvents = exportEvents(otherVersion, windowStartMs)
                .filter { it.installationId == installationId }
            assertTrue("Events of this device exported under $otherVersion: $leakedEvents", leakedEvents.isEmpty())
        } finally {
            disableResearch(controller, queue)
        }
    }

    @Test
    fun consentOffSendsNothing() {
        if (!configured) return
        val queue = researchQueue()
        val controller = buildTelemetryConsentController(context)

        try {
            disableResearch(controller, queue)
            val before = operatorBatches(BuildConfig.VERSION_NAME).map { it.batchId }.toSet()

            swipeHelloOnTheIaidoKeyboard(consentExpected = false)

            // Every research append runs on the serial telemetry thread; wait for it to go idle.
            runBlocking { onTelemetryThread { } }
            assertTrue(
                "A swipe with research consent off queued research data",
                queue.pendingBatches().isEmpty(),
            )
            // Give a stray upload (had one been scheduled) the time a real one takes to land.
            TelemetryUploadScheduler.schedule(context, TelemetryPlane.RESEARCH)
            SystemClock.sleep(CONSENT_OFF_OBSERVATION_MS)
            val arrived = operatorBatches(BuildConfig.VERSION_NAME).filter { it.batchId !in before }
            assertTrue("Research batches reached the collector with consent off: $arrived", arrived.isEmpty())
        } finally {
            disableResearch(controller, queue)
        }
    }

    /**
     * B3 repair: credentials lost while consent stays on (a wiped Keystore key, a restored
     * backup) are never replaced by invented ones; the next swipe schedules the worker, the worker
     * provisions fresh server-issued credentials, and later swipes upload under them.
     */
    @Test
    fun lostCredentialsAreReprovisionedAndTheNextSwipeUploads() {
        if (!configured) return
        val queue = researchQueue()
        val controller = buildTelemetryConsentController(context)
        val windowStartMs = System.currentTimeMillis() - CLOCK_SLACK_MS

        try {
            enableResearch(controller, queue)
            val store = TelemetryInstallationStore(context)
            val lostId = requireNotNull(store.installation()).installationId
            // The upload the enable scheduled could otherwise provision on its own after the wipe
            // and hide whether the swipe itself triggers the repair.
            assertTrue("Research upload work never settled", awaitResearchWorkSettled())
            store.clear()
            assertNull("Credentials should be gone before the repair", store.installation())
            val before = operatorBatches(BuildConfig.VERSION_NAME).map { it.batchId }.toSet()

            val scenario = ImeScenario().also(artifacts::track)
            var repairedId: String? = null
            scenario.run {
                awaitResearchCapture(enabled = true)
                // Dropped (no credentials to attribute it to), but it schedules the worker, which
                // must provision on its own: the test deliberately does not schedule here.
                swipeWord("hello")
                assertText("Hello")
                repairedId = awaitValue(PROVISION_TIMEOUT_MS) {
                    TelemetryInstallationStore(context).installation()?.installationId
                }
                assertNotNull("The worker never re-provisioned after the credentials were lost", repairedId)
                assertNotEquals("Re-provisioning must not resurrect the lost installation", lostId, repairedId)

                clearText()
                swipeWord("hello")
                assertText("Hello")
            }

            awaitSwipeAtCollector(requireNotNull(repairedId), before, windowStartMs)
            assertTrue("The research queue never drained after the repair", awaitQueueDrained(queue))
        } finally {
            disableResearch(controller, queue)
        }
    }

    private fun enableResearch(controller: TelemetryConsentController, queue: TelemetryQueue) {
        runBlocking { onTelemetryThread { queue.deleteAll() } }
        val outcome = runBlocking {
            controller.setEnabled(TelemetryPlane.RESEARCH, true, ConsentRecord.disabled())
        }
        assertEquals(
            "Research consent could not be enabled against $baseUrl: ${outcome.message}",
            TelemetryConsentStatus.ENABLED,
            outcome.status,
        )
    }

    private fun disableResearch(controller: TelemetryConsentController, queue: TelemetryQueue) {
        runBlocking {
            runCatching {
                val current = researchConsentFromPreferences(context.settingsStore.data.first())
                controller.setEnabled(TelemetryPlane.RESEARCH, false, current)
            }
            runCatching { onTelemetryThread { queue.deleteAll() } }
        }
    }

    private fun swipeHelloOnTheIaidoKeyboard(consentExpected: Boolean) {
        ImeScenario().also(artifacts::track).run {
            awaitResearchCapture(consentExpected)
            swipeWord("hello")
            assertText("Hello")
        }
    }

    /** The IME's research capture follows stored consent through a Flow; let it catch up. */
    private fun awaitResearchCapture(enabled: Boolean) {
        assertTrue(
            "The keyboard's research capture never became enabled=$enabled",
            waitUntil(10_000L) { ResearchCorrectionRecorderProvider.isEnabled() == enabled },
        )
    }

    /**
     * Polls the operator API, filtered by the installed app's exact version, until a batch from
     * [installationId] that was not there before holds a swipe `gesture_trace`. Never schedules
     * an upload itself: only the IME's own scheduling may deliver it, so a gesture whose upload
     * gets stranded on the device (e.g. a schedule dropped by the KEEP policy) fails this test.
     */
    private fun awaitSwipeAtCollector(
        installationId: String,
        before: Set<String>,
        windowStartMs: Long,
    ): List<OperatorBatch> {
        val deadline = SystemClock.elapsedRealtime() + COLLECTOR_TIMEOUT_MS
        var lastSeen = emptyList<OperatorBatch>()
        while (SystemClock.elapsedRealtime() < deadline) {
            lastSeen = operatorBatches(BuildConfig.VERSION_NAME)
                .filter { it.installationId == installationId && it.batchId !in before }
            if (lastSeen.isNotEmpty()) {
                val ids = lastSeen.map { it.batchId }.toSet()
                val swipes = exportEvents(BuildConfig.VERSION_NAME, windowStartMs).filter {
                    it.batchId in ids && it.eventType == ResearchEventCodec.GESTURE_TRACE &&
                        it.classification == ResearchTraceClassification.SWIPE
                }
                if (swipes.isNotEmpty()) return lastSeen
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(
            "No swipe gesture_trace from $installationId reached $baseUrl under app_version " +
                "${BuildConfig.VERSION_NAME} within ${COLLECTOR_TIMEOUT_MS}ms; new batches seen: $lastSeen; " +
                "still queued locally: ${researchQueue().pendingBatches().map { it.batchId }}",
        )
    }

    private fun awaitQueueDrained(queue: TelemetryQueue): Boolean = waitUntil(QUEUE_DRAIN_TIMEOUT_MS) {
        runBlocking { onTelemetryThread { } }
        queue.pendingBatches().isEmpty()
    }

    private fun awaitResearchWorkSettled(): Boolean = waitUntil(QUEUE_DRAIN_TIMEOUT_MS) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(telemetryWorkName(TelemetryPlane.RESEARCH))
            .get()
            .all { it.state.isFinished }
    }

    private fun researchQueue() = TelemetryQueue(
        directory = File(context.filesDir, "telemetry"),
        plane = TelemetryPlane.RESEARCH,
        clock = System::currentTimeMillis,
        limits = QueueLimits(),
    )

    private data class OperatorBatch(
        val installationId: String,
        val batchId: String,
        val appVersion: String,
        val algorithmVersion: String,
    )

    private data class ExportedEvent(
        val installationId: String,
        val batchId: String,
        val eventType: String,
        val classification: String?,
    )

    private fun operatorBatches(appVersion: String): List<OperatorBatch> {
        val body = operatorGet("/v1/operator/research/batches?app_version=${encode(appVersion)}")
        return Json.parseToJsonElement(body).jsonObject.getValue("batches").jsonArray.map { element ->
            val batch = element.jsonObject
            OperatorBatch(
                installationId = batch.string("installation_id"),
                batchId = batch.string("batch_id"),
                appVersion = batch.string("app_version"),
                algorithmVersion = batch.string("algorithm_version"),
            )
        }
    }

    private fun exportEvents(appVersion: String, startMs: Long): List<ExportedEvent> {
        val endMs = System.currentTimeMillis() + CLOCK_SLACK_MS
        val body = operatorGet(
            "/v1/operator/research/export?start_ms=$startMs&end_ms=$endMs&app_version=${encode(appVersion)}",
        )
        return body.lineSequence().filter { it.isNotBlank() }.map { line ->
            val row = Json.parseToJsonElement(line).jsonObject
            val event = row.getValue("event").jsonObject
            ExportedEvent(
                installationId = row.string("installation_id"),
                batchId = row.string("batch_id"),
                eventType = event.string("event_type"),
                classification = (event["payload"] as? JsonObject)?.get("classification")?.jsonPrimitive?.content,
            )
        }.toList()
    }

    private fun operatorGet(pathAndQuery: String): String {
        val connection = URL(baseUrl + pathAndQuery).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $operatorToken")
            val status = connection.responseCode
            check(status == 200) {
                "Operator GET ${pathAndQuery.substringBefore('?')} answered $status: " +
                    connection.errorStream?.bufferedReader()?.readText().orEmpty()
            }
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** A syntactically valid version that is guaranteed not to be the installed one. */
    private fun otherVersionThan(version: String): String =
        if (version == "0.0.1") "0.0.2" else "0.0.1"

    private fun <T : Any> awaitValue(timeoutMs: Long, read: () -> T?): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            read()?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return read()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(250L)
        }
        return condition()
    }

    private companion object {
        const val OPERATOR_TOKEN_ARG = "iaidoOperatorToken"
        const val COLLECTOR_TIMEOUT_MS = 120_000L
        const val QUEUE_DRAIN_TIMEOUT_MS = 60_000L
        const val PROVISION_TIMEOUT_MS = 90_000L
        const val CONSENT_OFF_OBSERVATION_MS = 10_000L
        const val POLL_INTERVAL_MS = 2_000L
        /** Device and collector clocks can differ a little; widen the export window by this much. */
        const val CLOCK_SLACK_MS = 5 * 60_000L
    }
}
