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
}

fun dispositionForResponse(statusCode: Int): UploadDisposition = when {
    statusCode in 200..299 -> UploadDisposition.ACKNOWLEDGED
    statusCode == 429 || statusCode in 500..599 -> UploadDisposition.RETRY
    else -> UploadDisposition.DISCARD
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
        val credential = installationStore.writeCredential(plane) ?: return UploadDisposition.DISCARD
        val installation = installationStore.getOrCreateInstallation {
            throw IllegalStateException("Telemetry installation is not provisioned")
        }
        if (batch.events.any { it.installationId != installation.installationId }) {
            return UploadDisposition.DISCARD
        }
        val body = runCatching { encodeBatch(batch).toByteArray(StandardCharsets.UTF_8) }
            .getOrElse { return UploadDisposition.DISCARD }
        if (body.size > MAX_REQUEST_BYTES) return UploadDisposition.DISCARD

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
                UploadDisposition.RETRY, UploadDisposition.DISCARD -> disposition
            }
        } catch (_: IOException) {
            UploadDisposition.RETRY
        } catch (_: Exception) {
            UploadDisposition.DISCARD
        }
    }

    fun delete(plane: TelemetryPlane): UploadDisposition {
        val credential = installationStore.deletionCredential() ?: return UploadDisposition.DISCARD
        return try {
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

    private fun encodeBatch(batch: TelemetryBatch): String = buildJsonObject {
        put("batch_id", batch.batchId)
        put("events", buildJsonArray {
            batch.events.forEach { event -> add(event.toWireJson()) }
        })
    }.toString()

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
        const val MAX_REQUEST_BYTES = 256 * 1024
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}

private fun TelemetryPlane.pathSegment(): String = name.lowercase(Locale.ROOT)

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing $name")
