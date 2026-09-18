package com.iaido.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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
    directory: File,
    private val plane: TelemetryPlane,
    private val clock: () -> Long,
    private val limits: QueueLimits,
) {
    private val storageDirectory = File(directory, plane.name.lowercase(Locale.ROOT))
    private var nextBatchSequence: Long? = null

    fun append(event: TelemetryEnvelope) {
        runSafely {
            try {
                validateForPlane(event)
                require(storageDirectory.mkdirs() || storageDirectory.isDirectory)

                val current = batchFiles().lastOrNull()?.let { readBatchOrNull(it.file) }
                val currentEvents = current?.events.orEmpty()
                val canAppend = current != null && currentEvents.size < limits.maxBatchEvents
                val candidateEvents = if (canAppend) {
                    currentEvents + event
                } else {
                    listOf(event)
                }
                if (candidateEvents.size <= limits.maxBatchEvents) {
                    val compressed = compressBatch(candidateEvents)
                    val appendToCurrent = canAppend && compressed.size <= limits.maxBatchBytes
                    val eventsToWrite = if (appendToCurrent) candidateEvents else listOf(event)
                    val bytesToWrite = if (appendToCurrent) compressed else compressBatch(eventsToWrite)
                    if (bytesToWrite.size <= limits.maxBatchBytes) {
                        writeBatch(if (appendToCurrent) current!!.file else newBatchFile(), eventsToWrite)
                    }
                }
            } finally {
                enforceLimitsInternal()
            }
        }
    }

    fun pendingBatches(): List<TelemetryBatch> = try {
        batchFiles().mapNotNull { batchFile ->
            readBatchOrNull(batchFile.file)?.takeIf { it.events.isNotEmpty() }?.toPublicBatch()
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun acknowledge(batchId: String) {
        runSafely {
            batchFiles().firstOrNull { it.file.nameWithoutExtension == batchId }?.file?.delete()
        }
    }

    fun deleteAll() {
        runSafely {
            storageDirectory.listFiles()?.filter { it.isFile && isQueueFile(it) }?.forEach { it.delete() }
        }
    }

    fun enforceLimits() {
        runSafely { enforceLimitsInternal() }
    }

    private fun enforceLimitsInternal() {
        cleanupTemporaryFiles()
        val now = clock()
        batchFiles().forEach { batchFile ->
            val batch = readBatchOrNull(batchFile.file)
            if (batch == null) {
                batchFile.file.delete()
                return@forEach
            }

            val freshEvents = batch.events.filter { now - it.occurredAtMs <= limits.maxAgeMs }
            when {
                freshEvents.isEmpty() -> batchFile.file.delete()
                freshEvents.size != batch.events.size -> writeBatch(batchFile.file, freshEvents)
            }
        }

        var queuedBytes = batchFiles().sumOf { it.file.length() }
        batchFiles().forEach { batchFile ->
            if (queuedBytes <= limits.maxQueuedBytes) return@forEach
            queuedBytes -= batchFile.file.length()
            batchFile.file.delete()
        }
    }

    private fun validateForPlane(event: TelemetryEnvelope) {
        if (plane != TelemetryPlane.DIAGNOSTICS) return
        require(event.eventType == "gesture_outcome") {
            "Diagnostics queue only accepts gesture outcome events"
        }
        validateDiagnostics(DiagnosticsEventCodec.decode(event.payload.toString()))
    }

    private fun batchFiles(): List<BatchFile> = storageDirectory.listFiles { file ->
        file.isFile && file.extension == "batch"
    }?.mapNotNull { file ->
        parseBatchFile(file)
    }?.sortedWith(compareBy<BatchFile> { it.sequence }.thenBy { it.createdAt }.thenBy { it.file.name }
    ) ?: emptyList()

    private fun parseBatchFile(file: File): BatchFile? {
        val parts = file.nameWithoutExtension.split('-')
        if (parts.size < 4 || parts[0] != "batch") return null
        return try {
            BatchFile(
                file = file,
                sequence = parts[1].toLong(),
                createdAt = parts[2].toLong(),
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun newBatchFile(): File {
        val sequence = nextSequence()
        return File(storageDirectory, "batch-$sequence-${clock()}-${UUID.randomUUID()}.batch")
    }

    private fun nextSequence(): Long {
        if (nextBatchSequence == null) {
            nextBatchSequence = batchFiles().maxOfOrNull { it.sequence + 1 } ?: 0L
        }
        val sequence = nextBatchSequence!!
        nextBatchSequence = sequence + 1
        return sequence
    }

    private fun writeBatch(file: File, events: List<TelemetryEnvelope>) {
        require(storageDirectory.mkdirs() || storageDirectory.isDirectory)
        val temporary = File(storageDirectory, "${file.name}.tmp")
        Files.write(
            temporary.toPath(),
            compressBatch(events),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun readBatchOrNull(file: File): StoredBatch? = try {
        val uncompressed = GZIPInputStream(file.inputStream()).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val events = uncompressed.lineSequence().filter { it.isNotBlank() }.map(::decodeRecord).toList()
        StoredBatch(file, events)
    } catch (_: Exception) {
        null
    }

    private fun compressBatch(events: List<TelemetryEnvelope>): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            events.forEach { event -> writer.write(encodeRecord(event).toString(StandardCharsets.UTF_8)) }
        }
        return output.toByteArray()
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
        return "${bytes.size}:${sha256(bytes)}:$json\n".toByteArray(StandardCharsets.UTF_8)
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

    private fun cleanupTemporaryFiles() {
        storageDirectory.listFiles()?.filter { it.isFile && it.extension == "tmp" }?.forEach { it.delete() }
    }

    private fun isQueueFile(file: File): Boolean = file.extension == "batch" || file.extension == "tmp"

    private fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Telemetry is best effort and must never affect keyboard behavior.
        }
    }

    private data class BatchFile(val file: File, val sequence: Long, val createdAt: Long)

    private data class StoredBatch(val file: File, val events: List<TelemetryEnvelope>) {
        fun toPublicBatch() = TelemetryBatch(file.nameWithoutExtension, events)
    }
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing $name")

private fun JsonObject.requiredInt(name: String): Int = requiredString(name).toInt()

private fun JsonObject.requiredLong(name: String): Long = requiredString(name).toLong()
