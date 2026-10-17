package com.iaido.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class UploadDisposition {
    ACKNOWLEDGED,
    RETRY,
    DISCARD,

    /** The collector refused our credentials (or we hold none): never retry with them. */
    UNAUTHORIZED,
}

/**
 * DISCARD deletes queued data, so it is reserved for the collector's explicit permanent client
 * rejections: 400 (bad request), 409 (batch id already holds different content), 413 (body too
 * large) and 422 (schema invalid). 401 is the collector's only auth failure (it never sends 403).
 * Everything else - redirects (never followed; a captive portal answers 302), 403, 404, 408,
 * 425, 426, 429, 5xx and unknown codes - may succeed later, so the batch is kept for retry.
 */
fun dispositionForResponse(statusCode: Int): UploadDisposition = when (statusCode) {
    in 200..299 -> UploadDisposition.ACKNOWLEDGED
    401 -> UploadDisposition.UNAUTHORIZED
    400, 409, 413, 422 -> UploadDisposition.DISCARD
    else -> UploadDisposition.RETRY
}

class TelemetryTransport(
    private val baseUrl: URL,
    private val installationStore: TelemetryInstallationStore,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    init {
        require(baseUrl.protocol.equals("https", ignoreCase = true)) {
            "Telemetry endpoint must use HTTPS"
        }
        require(baseUrl.host.isNotBlank()) { "Telemetry endpoint must have a host" }
    }

    fun provision(): UploadDisposition = try {
        installationStore.getOrCreateInstallation {
            val response = execute(method = "POST", path = "/v1/installations")
            when (val disposition = dispositionForResponse(response.statusCode)) {
                UploadDisposition.ACKNOWLEDGED -> parseInstallation(response.body)
                    ?: throw ProvisionFailure(UploadDisposition.DISCARD)
                UploadDisposition.RETRY, UploadDisposition.DISCARD -> throw ProvisionFailure(disposition)
                UploadDisposition.UNAUTHORIZED -> throw ProvisionFailure(UploadDisposition.DISCARD)
            }
        }
        UploadDisposition.ACKNOWLEDGED
    } catch (failure: ProvisionFailure) {
        failure.disposition
    } catch (_: IOException) {
        UploadDisposition.RETRY
    } catch (_: Exception) {
        UploadDisposition.DISCARD
    }

    fun upload(plane: TelemetryPlane, batch: TelemetryBatch): UploadDisposition {
        val installation = installationStore.installation() ?: return UploadDisposition.UNAUTHORIZED
        val credential = installation.writeCredential
        if (batch.events.any { it.installationId != installation.installationId }) {
            return UploadDisposition.DISCARD
        }
        val body = runCatching { encodeTelemetryBatch(batch) }
            .getOrElse { return UploadDisposition.RETRY }
        if (body.size > TELEMETRY_MAX_REQUEST_BYTES) return UploadDisposition.DISCARD

        return try {
            val response = execute(
                method = "POST",
                path = "/v1/${plane.pathSegment()}/batches",
                credential = credential,
                body = body,
                batch = batch,
            )
            when (val disposition = dispositionForResponse(response.statusCode)) {
                UploadDisposition.ACKNOWLEDGED -> {
                    if (response.body?.isAcknowledgementFor(batch.batchId) == true) {
                        UploadDisposition.ACKNOWLEDGED
                    } else {
                        UploadDisposition.RETRY
                    }
                }
                UploadDisposition.RETRY, UploadDisposition.DISCARD, UploadDisposition.UNAUTHORIZED -> disposition
            }
        } catch (_: Exception) {
            // IO or any other local failure says nothing about the batch itself: keep it.
            UploadDisposition.RETRY
        }
    }

    /**
     * Deletes [plane]'s uploaded data for the active installation and for the retired one whose
     * write credential was rejected. A 401 for the retired installation means the collector holds
     * nothing under it, so it counts as done.
     */
    fun delete(plane: TelemetryPlane): UploadDisposition {
        val active = installationStore.deletionCredential()
        val retired = installationStore.retiredInstallation()?.deletionCredential
        if (active == null && retired == null) return UploadDisposition.DISCARD
        val outcomes = listOfNotNull(
            active?.let { deleteWith(plane, it) },
            retired?.let { credential ->
                deleteWith(plane, credential).takeUnless { it == UploadDisposition.UNAUTHORIZED }
                    ?: UploadDisposition.ACKNOWLEDGED
            },
        )
        return when {
            outcomes.all { it == UploadDisposition.ACKNOWLEDGED } -> UploadDisposition.ACKNOWLEDGED
            UploadDisposition.RETRY in outcomes -> UploadDisposition.RETRY
            else -> outcomes.first { it != UploadDisposition.ACKNOWLEDGED }
        }
    }

    private fun deleteWith(plane: TelemetryPlane, credential: String): UploadDisposition = try {
        dispositionForResponse(
            execute(
                method = "DELETE",
                path = "/v1/${plane.pathSegment()}",
                credential = credential,
            ).statusCode,
        )
    } catch (_: IOException) {
        UploadDisposition.RETRY
    } catch (_: Exception) {
        UploadDisposition.DISCARD
    }

    private fun execute(
        method: String,
        path: String,
        credential: String? = null,
        body: ByteArray? = null,
        batch: TelemetryBatch? = null,
    ): HttpResponse {
        val connection = connectionFactory(endpoint(path))
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            credential?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                batch?.let {
                    connection.setRequestProperty("X-Iaido-Batch-Id", it.batchId)
                    connection.setRequestProperty("X-Iaido-Batch-SHA256", sha256(body))
                }
                connection.outputStream.use { it.write(body) }
            }

            val statusCode = connection.responseCode
            val responseBody = try {
                val responseStream = if (statusCode >= 400) connection.errorStream else connection.inputStream
                responseStream?.use(::readBounded)
            } catch (_: IOException) {
                null
            }
            return HttpResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun endpoint(path: String): URL = URL(baseUrl.toExternalForm().trimEnd('/') + path)

    private fun readBounded(input: java.io.InputStream): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_RESPONSE_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8)
    }

    private fun parseInstallation(body: String?): TelemetryInstallation? = runCatching {
        val root = Json.parseToJsonElement(body ?: return null).jsonObject
        TelemetryInstallation(
            installationId = root.requiredString("installation_id"),
            writeCredential = root.requiredString("write_credential"),
            deletionCredential = root.requiredString("deletion_credential"),
        )
    }.getOrNull()

    private fun String.isAcknowledgementFor(batchId: String): Boolean = runCatching {
        val root = Json.parseToJsonElement(this).jsonObject
        root.requiredString("batch_id") == batchId &&
            root["accepted"]?.jsonPrimitive?.content == "true"
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class HttpResponse(val statusCode: Int, val body: String?)

    private class ProvisionFailure(val disposition: UploadDisposition) : Exception()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}

/**
 * Uncompressed request-body cap, matching the collector's IAIDO_MAX_REQUEST_BYTES default. The
 * queue rolls batches by this same measure so every stored batch is one the transport will send.
 */
internal const val TELEMETRY_MAX_REQUEST_BYTES = 256 * 1024

/** The exact request body [TelemetryTransport.upload] sends for [batch]. */
internal fun encodeTelemetryBatch(batch: TelemetryBatch): ByteArray = buildJsonObject {
    put("batch_id", batch.batchId)
    put("events", buildJsonArray {
        batch.events.forEach { event -> add(event.toWireJson()) }
    })
}.toString().toByteArray(StandardCharsets.UTF_8)

private fun TelemetryEnvelope.toWireJson(): JsonObject = buildJsonObject {
    put("schema_version", schemaVersion)
    put("event_id", eventId)
    put("batch_id", batchId)
    put("installation_id", installationId)
    put("session_id", sessionId)
    put("occurred_at_ms", occurredAtMs)
    put("app_version", appVersion)
    put("build_type", buildType)
    put("android_api", androidApi)
    put("event_type", eventType)
    put("payload", payload)
}

private fun TelemetryPlane.pathSegment(): String = name.lowercase(Locale.ROOT)

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing $name")
