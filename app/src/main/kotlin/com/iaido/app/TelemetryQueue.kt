package com.iaido.app

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class QueueLimits(
    val maxBatchEvents: Int = 100,
    val maxBatchBytes: Long = 256 * 1024,
    val maxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L,
    val maxQueuedBytes: Long = 5 * 1024 * 1024,
) {
    init {
        require(maxBatchEvents > 0)
        require(maxBatchBytes > 0)
        require(maxAgeMs >= 0)
        require(maxQueuedBytes >= 0)
    }
}

data class TelemetryBatch(
    val batchId: String,
    val events: List<TelemetryEnvelope>,
)

class TelemetryQueue(
    private val directory: File,
    private val clock: () -> Long,
    private val limits: QueueLimits,
) {
    private var nextBatchSequence = 0L

    fun append(event: TelemetryEnvelope) {
        runSafely {
            val record = encodeRecord(event)
            if (record.size > limits.maxBatchBytes) return@runSafely
            require(directory.mkdirs() || directory.isDirectory)

            val current = batchFiles().lastOrNull()?.let(::readBatch)
            val target = if (
                current != null &&
                current.events.size < limits.maxBatchEvents &&
                current.file.length() + record.size <= limits.maxBatchBytes
            ) {
                current.file
            } else {
                File(directory, "batch-${clock()}-${nextBatchSequence++}-${UUID.randomUUID()}.batch")
            }
            Files.write(
                target.toPath(),
                record,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            enforceLimitsInternal()
        }
    }

    fun pendingBatches(): List<TelemetryBatch> = try {
        batchFiles().mapNotNull { file ->
            readBatch(file).takeIf { it.events.isNotEmpty() }?.toPublicBatch()
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun acknowledge(batchId: String) {
        runSafely {
            batchFiles().firstOrNull { it.nameWithoutExtension == batchId }?.delete()
        }
    }

    fun deleteAll() {
        runSafely {
            batchFiles().forEach { it.delete() }
        }
    }

    fun enforceLimits() {
        runSafely { enforceLimitsInternal() }
    }

    private fun enforceLimitsInternal() {
        val now = clock()
        batchFiles().forEach { file ->
            val batch = readBatch(file)
            val freshEvents = batch.events.filter { now - it.occurredAtMs <= limits.maxAgeMs }
            when {
                freshEvents.isEmpty() -> file.delete()
                freshEvents.size != batch.events.size -> rewrite(file, freshEvents)
            }
        }

        var queuedBytes = batchFiles().sumOf { it.length() }
        batchFiles().forEach { file ->
            if (queuedBytes <= limits.maxQueuedBytes) return@forEach
            queuedBytes -= file.length()
            file.delete()
        }
    }

    private fun batchFiles(): List<File> = directory.listFiles { file ->
        file.isFile && file.extension == "batch"
    }?.sortedBy { it.name } ?: emptyList()

    private fun rewrite(file: File, events: List<TelemetryEnvelope>) {
        val temporary = File(directory, "${file.name}.tmp")
        Files.write(
            temporary.toPath(),
            events.joinToString("") { encodeRecord(it).toString(StandardCharsets.UTF_8) }
                .toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun readBatch(file: File): StoredBatch {
        val lines = file.readLines(StandardCharsets.UTF_8)
        val events = lines.filter { it.isNotBlank() }.map(::decodeRecord)
        return StoredBatch(file, events)
    }

    private fun encodeRecord(event: TelemetryEnvelope): ByteArray {
        val json = buildJsonObject {
            put("schema_version", event.schemaVersion)
            put("event_id", event.eventId)
            put("batch_id", event.batchId)
            put("installation_id", event.installationId)
            put("session_id", event.sessionId)
            put("occurred_at_ms", event.occurredAtMs)
            put("app_version", event.appVersion)
            put("build_type", event.buildType)
            put("android_api", event.androidApi)
            put("event_type", event.eventType)
            put("payload", event.payload)
        }.toString()
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val checksum = sha256(bytes)
        return "${bytes.size}:$checksum:$json\n".toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeRecord(line: String): TelemetryEnvelope {
        val firstSeparator = line.indexOf(':')
        val secondSeparator = line.indexOf(':', firstSeparator + 1)
        require(firstSeparator > 0 && secondSeparator > firstSeparator)
        val expectedLength = line.substring(0, firstSeparator).toInt()
        val expectedChecksum = line.substring(firstSeparator + 1, secondSeparator)
        val jsonText = line.substring(secondSeparator + 1)
        val jsonBytes = jsonText.toByteArray(StandardCharsets.UTF_8)
        require(expectedLength == jsonBytes.size)
        require(expectedChecksum == sha256(jsonBytes))
        val root = Json.parseToJsonElement(jsonText).jsonObject
        return TelemetryEnvelope(
            schemaVersion = root.requiredInt("schema_version"),
            eventId = root.requiredString("event_id"),
            batchId = root.requiredString("batch_id"),
            installationId = root.requiredString("installation_id"),
            sessionId = root.requiredString("session_id"),
            occurredAtMs = root.requiredLong("occurred_at_ms"),
            appVersion = root.requiredString("app_version"),
            buildType = root.requiredString("build_type"),
            androidApi = root.requiredInt("android_api"),
            eventType = root.requiredString("event_type"),
            payload = root["payload"]?.jsonObject ?: JsonObject(emptyMap()),
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Telemetry is best effort and must never affect keyboard behavior.
        }
    }

    private data class StoredBatch(val file: File, val events: List<TelemetryEnvelope>) {
        fun toPublicBatch() = TelemetryBatch(file.nameWithoutExtension, events)
    }
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing $name")

private fun JsonObject.requiredInt(name: String): Int = requiredString(name).toInt()

private fun JsonObject.requiredLong(name: String): Long = requiredString(name).toLong()
