package com.iaido.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryTransportTest {
    @Test
    fun `transport rejects non HTTPS telemetry endpoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryTransport(
                URL("http://collector.invalid"),
                storeWithCredentials(),
                ::unexpectedConnection,
            )
        }
    }

    @Test
    fun `only 401 is an auth failure and only explicit permanent rejections discard`() {
        assertEquals(UploadDisposition.UNAUTHORIZED, dispositionForResponse(401))
        // The collector's permanent client rejections: bad request, conflicting batch content,
        // body too large, schema invalid.
        listOf(400, 409, 413, 422).forEach { code ->
            assertEquals(UploadDisposition.DISCARD, dispositionForResponse(code), "status $code")
        }
        // Everything else may succeed later and must keep the batch: redirects (not followed; a
        // captive portal answers 302), 403 (never sent by the collector), timeouts, HTTPS
        // required, rate limits, server errors and any unknown status.
        listOf(301, 302, 307, 403, 404, 408, 418, 425, 426, 429, 500, 503, 599, 600, 199).forEach { code ->
            assertEquals(UploadDisposition.RETRY, dispositionForResponse(code), "status $code")
        }
    }

    @Test
    fun `a local non IO failure while uploading keeps the batch for retry`() {
        val transport = TelemetryTransport(
            URL("https://collector.invalid"),
            storeWithCredentials(),
        ) { error("unexpected local failure") }

        assertEquals(UploadDisposition.RETRY, transport.upload(TelemetryPlane.RESEARCH, sampleBatch()))
    }

    @Test
    fun `upload without stored credentials is unauthorized and opens no connection`() {
        val transport = TelemetryTransport(
            URL("https://collector.invalid"),
            TelemetryInstallationStore(FakeInstallationCredentialStorage()),
            ::unexpectedConnection,
        )

        assertEquals(
            UploadDisposition.UNAUTHORIZED,
            transport.upload(TelemetryPlane.RESEARCH, sampleBatch()),
        )
    }

    @Test
    fun `auth response remains non retryable when its error body cannot be read`() {
        val connection = RecordingConnection(
            URL("https://collector.invalid/v1/diagnostics/batches"),
            responseCode = 401,
            responseBody = "",
            responseReadFailure = IOException("error stream unavailable"),
        )
        val transport = TelemetryTransport(
            URL("https://collector.invalid"),
            storeWithCredentials(),
            singleConnectionFactory(connection),
        )

        assertEquals(
            UploadDisposition.UNAUTHORIZED,
            transport.upload(TelemetryPlane.DIAGNOSTICS, sampleBatch()),
        )
    }

    @Test
    fun `upload uses existing batch contract write credential checksum and bounded connection`() {
        val connection = RecordingConnection(
            URL("https://collector.invalid/v1/research/batches"),
            responseCode = 202,
            responseBody = """{"batch_id":"$BATCH_ID","accepted":true}""",
        )
        val transport = TelemetryTransport(
            URL("https://collector.invalid"),
            storeWithCredentials(),
            singleConnectionFactory(connection),
        )

        val result = transport.upload(TelemetryPlane.RESEARCH, sampleBatch())

        assertEquals(UploadDisposition.ACKNOWLEDGED, result)
        assertEquals("POST", connection.requestMethod)
        assertEquals("/v1/research/batches", connection.url.path)
        assertEquals("Bearer write-secret", connection.getRequestProperty("Authorization"))
        assertEquals(BATCH_ID, connection.getRequestProperty("X-Iaido-Batch-Id"))
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)

        val requestBytes = connection.requestBody.toByteArray()
        assertEquals(sha256(requestBytes), connection.getRequestProperty("X-Iaido-Batch-SHA256"))
        assertEquals(
            Json.parseToJsonElement(EXPECTED_BATCH_JSON),
            Json.parseToJsonElement(requestBytes.toString(StandardCharsets.UTF_8)),
        )
        assertTrue(connection.disconnected)
    }

    @Test
    fun `duplicate batch acknowledgement is upload success`() {
        val first = RecordingConnection(
            URL("https://collector.invalid/v1/diagnostics/batches"),
            responseCode = 202,
            responseBody = """{"batch_id":"$BATCH_ID","accepted":true}""",
        )
        val duplicate = RecordingConnection(
            URL("https://collector.invalid/v1/diagnostics/batches"),
            responseCode = 202,
            responseBody = """{"batch_id":"$BATCH_ID","accepted":true}""",
        )
        val connections = ArrayDeque(listOf(first, duplicate))
        val transport = TelemetryTransport(
            URL("https://collector.invalid"),
            storeWithCredentials(),
        ) { connections.removeFirst() }

        assertEquals(
            UploadDisposition.ACKNOWLEDGED,
            transport.upload(TelemetryPlane.DIAGNOSTICS, sampleBatch()),
        )
        assertEquals(
            UploadDisposition.ACKNOWLEDGED,
            transport.upload(TelemetryPlane.DIAGNOSTICS, sampleBatch()),
        )
    }

    @Test
    fun `delete uses deletion credential and exact plane route`() {
        val connection = RecordingConnection(
            URL("https://collector.invalid/v1/research"),
            responseCode = 200,
            responseBody = """{"deleted":true}""",
        )
        val transport = TelemetryTransport(
            URL("https://collector.invalid"),
            storeWithCredentials(),
            singleConnectionFactory(connection),
        )

        val result = transport.delete(TelemetryPlane.RESEARCH)

        assertEquals(UploadDisposition.ACKNOWLEDGED, result)
        assertEquals("DELETE", connection.requestMethod)
        assertEquals("/v1/research", connection.url.path)
        assertEquals("Bearer delete-secret", connection.getRequestProperty("Authorization"))
    }

    @Test
    fun `provision persists only credentials returned by installation route`() {
        val storage = FakeInstallationCredentialStorage()
        val store = TelemetryInstallationStore(storage)
        val connection = RecordingConnection(
            URL("https://collector.invalid/v1/installations"),
            responseCode = 201,
            responseBody = """
                {
                  "installation_id":"10000000-0000-4000-8000-000000000001",
                  "write_credential":"server-write",
                  "deletion_credential":"server-delete"
                }
            """.trimIndent(),
        )
        var openedConnections = 0
        val transport = TelemetryTransport(
            URL("https://collector.invalid"),
            store,
        ) {
            openedConnections += 1
            connection
        }

        assertEquals(UploadDisposition.ACKNOWLEDGED, transport.provision())
        assertEquals(UploadDisposition.ACKNOWLEDGED, transport.provision())

        assertEquals(1, openedConnections)
        assertEquals("POST", connection.requestMethod)
        assertEquals("/v1/installations", connection.url.path)
        assertEquals("server-write", store.writeCredential(TelemetryPlane.DIAGNOSTICS))
        assertEquals("server-write", store.writeCredential(TelemetryPlane.RESEARCH))
        assertEquals("server-delete", store.deletionCredential())
        assertEquals(
            "10000000-0000-4000-8000-000000000001",
            store.getOrCreateInstallation { error("must not provision twice") }.installationId,
        )
    }

    @Test
    fun `oversized successful acknowledgement is not accepted`() {
        val connection = RecordingConnection(
            URL("https://collector.invalid/v1/diagnostics/batches"),
            responseCode = 202,
            responseBody = "x".repeat(64 * 1024 + 1),
        )
        val transport = TelemetryTransport(
            URL("https://collector.invalid"),
            storeWithCredentials(),
            singleConnectionFactory(connection),
        )

        assertEquals(
            UploadDisposition.RETRY,
            transport.upload(TelemetryPlane.DIAGNOSTICS, sampleBatch()),
        )
        assertTrue(connection.disconnected)
    }

    private fun storeWithCredentials(): TelemetryInstallationStore = TelemetryInstallationStore(
        FakeInstallationCredentialStorage(
            TelemetryInstallation(
                installationId = "10000000-0000-4000-8000-000000000001",
                writeCredential = "write-secret",
                deletionCredential = "delete-secret",
            ),
        ),
    )

    private fun sampleBatch(): TelemetryBatch = TelemetryBatch(
        batchId = BATCH_ID,
        events = listOf(
            TelemetryEnvelope(
                schemaVersion = 1,
                eventId = "30000000-0000-4000-8000-000000000009",
                batchId = "20000000-0000-4000-8000-000000000008",
                installationId = "10000000-0000-4000-8000-000000000001",
                sessionId = "10000000-0000-4000-8000-000000000002",
                occurredAtMs = 1_750_000_000_000,
                appVersion = "0.1.4",
                buildType = "release",
                androidApi = 36,
                eventType = "text_sample",
                payload = buildJsonObject { put("text", JsonPrimitive("bounded sample")) },
            ),
        ),
    )

    private fun singleConnectionFactory(connection: RecordingConnection): (URL) -> HttpURLConnection =
        { requestedUrl ->
            assertEquals(connection.url, requestedUrl)
            connection
        }

    private fun unexpectedConnection(url: URL): HttpURLConnection =
        error("Connection must not be opened for $url")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BATCH_ID = "batch-7-1750000000000-10000000-0000-4000-8000-000000000007"
        val EXPECTED_BATCH_JSON = """
            {
              "batch_id":"$BATCH_ID",
              "events":[{
                "schema_version":1,
                "event_id":"30000000-0000-4000-8000-000000000009",
                "batch_id":"20000000-0000-4000-8000-000000000008",
                "installation_id":"10000000-0000-4000-8000-000000000001",
                "session_id":"10000000-0000-4000-8000-000000000002",
                "occurred_at_ms":1750000000000,
                "app_version":"0.1.4",
                "build_type":"release",
                "android_api":36,
                "event_type":"text_sample",
                "payload":{"text":"bounded sample"}
              }]
            }
        """.trimIndent()
    }
}

internal class FakeInstallationCredentialStorage(
    private var installation: TelemetryInstallation? = null,
) : InstallationCredentialStorage {
    override fun read(): TelemetryInstallation? = installation

    override fun write(installation: TelemetryInstallation) {
        this.installation = installation
    }

    override fun clear() {
        installation = null
    }
}

private class RecordingConnection(
    url: URL,
    responseCode: Int,
    responseBody: String,
    private val responseReadFailure: IOException? = null,
) : HttpURLConnection(url) {
    val requestBody = ByteArrayOutputStream()
    private val configuredResponseCode = responseCode
    private val responseBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
    var disconnected = false

    override fun getOutputStream(): ByteArrayOutputStream = requestBody

    override fun getResponseCode(): Int = configuredResponseCode

    override fun getInputStream(): InputStream = responseStream()

    override fun getErrorStream(): InputStream = responseStream()

    private fun responseStream(): InputStream = responseReadFailure?.let { throw it }
        ?: ByteArrayInputStream(responseBytes)

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}
