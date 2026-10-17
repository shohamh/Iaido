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
    /** Cap on a batch's compressed (on-disk) size. */
    val maxBatchBytes: Long = 256 * 1024,
    val maxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L,
    val maxQueuedBytes: Long = 5 * 1024 * 1024,
    /** Cap on a batch's uncompressed upload body, as the transport and collector measure it. */
    val maxRequestBytes: Long = TELEMETRY_MAX_REQUEST_BYTES.toLong(),
) {
    init {
        require(maxBatchEvents > 0)
        require(maxBatchBytes > 0)
        require(maxRequestBytes > 0)
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

    /**
     * Appends to the newest batch only while it is still open; once [pendingBatches] has sealed a
     * batch (it may be mid-upload), new events start a fresh batch so acknowledging the sealed one
     * can never delete them.
     */
    fun append(event: TelemetryEnvelope) = locked {
        runSafely {
            try {
                validateForPlane(event)
                require(storageDirectory.mkdirs() || storageDirectory.isDirectory)

                val current = batchFiles().lastOrNull()
                    ?.takeUnless { it.sealed }
                    ?.let { readBatchOrNull(it.file) }
                val appended = current?.events.orEmpty() + event
                // A batch carries one installation: after re-provisioning, new events start a new
                // batch, so the old-installation batch is rejected alone and never takes them along.
                val sameInstallation = current?.events?.all { it.installationId == event.installationId } == true
                if (current != null && sameInstallation && appended.size <= limits.maxBatchEvents &&
                    fitsLimits(current.file, appended)
                ) {
                    writeBatch(current.file, appended)
                } else {
                    // A single event too large to ever be uploaded is dropped rather than queued.
                    val file = newBatchFile()
                    if (fitsLimits(file, listOf(event))) writeBatch(file, listOf(event))
                }
            } finally {
                enforceLimitsInternal()
            }
        }
    }

    /**
     * Seals every batch, then returns them oldest first. A returned batch's contents never change
     * afterwards (later appends go to a new batch), so it is safe to upload, retry with identical
     * bytes, and acknowledge by id.
     */
    fun pendingBatches(): List<TelemetryBatch> = locked {
        try {
            batchFiles().map { batchFile -> if (batchFile.sealed) batchFile.file else seal(batchFile.file) }
                .mapNotNull { file -> readBatchOrNull(file)?.takeIf { it.events.isNotEmpty() }?.toPublicBatch() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun acknowledge(batchId: String) = locked {
        runSafely {
            batchFiles().firstOrNull { it.file.nameWithoutExtension == batchId }?.file?.delete()
        }
    }

    fun deleteAll() = locked {
        runSafely {
            storageDirectory.listFiles()?.filter { it.isFile && isQueueFile(it) }?.forEach { it.delete() }
        }
    }

    fun enforceLimits() = locked {
        runSafely { enforceLimitsInternal() }
    }

    /**
     * Serialises append against read/seal/acknowledge across every [TelemetryQueue] instance for
     * the same directory: the IME and the upload worker each build their own instance, in the
     * same (main) process.
     */
    private fun <T> locked(block: () -> T): T =
        synchronized(directoryLocks.getOrPut(storageDirectory.absolutePath) { Any() }) { block() }

    private fun seal(file: File): File {
        val sealed = File(storageDirectory, "${file.nameWithoutExtension}.$SEALED_EXTENSION")
        try {
            Files.move(file.toPath(), sealed.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(file.toPath(), sealed.toPath())
        }
        return sealed
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
                // A sealed batch may be mid-upload under its id; rewriting it would change the
                // bytes the collector deduplicates on, so it is only ever deleted whole.
                batchFile.sealed -> Unit
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

    /**
     * Whether [events] stored in [file] stay within both the on-disk (compressed) cap and the
     * upload (uncompressed) cap. The latter is measured on the exact body the transport sends -
     * batch id included - because the transport and the collector reject anything larger.
     */
    private fun fitsLimits(file: File, events: List<TelemetryEnvelope>): Boolean =
        encodeTelemetryBatch(TelemetryBatch(file.nameWithoutExtension, events)).size <= limits.maxRequestBytes &&
            compressBatch(events).size <= limits.maxBatchBytes

    private fun validateForPlane(event: TelemetryEnvelope) {
        if (plane != TelemetryPlane.DIAGNOSTICS) return
        // Decoding both validates the payload against the diagnostics contract and rejects any
        // research payload (its event type is unknown here), so the plane guard still holds while
        // every diagnostics event type - gesture outcome, runtime error, crash - is accepted.
        val decoded = DiagnosticsEventCodec.decode(event.payload.toString())
        require(event.eventType == DiagnosticsEventCodec.eventType(decoded)) {
            "Diagnostics envelope event type must match its payload"
        }
        validateDiagnostics(decoded)
    }

    private fun batchFiles(): List<BatchFile> = storageDirectory.listFiles { file ->
        file.isFile && (file.extension == OPEN_EXTENSION || file.extension == SEALED_EXTENSION)
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
                sealed = file.extension == SEALED_EXTENSION,
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun newBatchFile(): File {
        val sequence = nextSequence()
        return File(storageDirectory, "batch-$sequence-${clock()}-${UUID.randomUUID()}.$OPEN_EXTENSION")
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

    private fun isQueueFile(file: File): Boolean =
        file.extension == OPEN_EXTENSION || file.extension == SEALED_EXTENSION || file.extension == "tmp"

    private fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Telemetry is best effort and must never affect keyboard behavior.
        }
    }

    private data class BatchFile(val file: File, val sequence: Long, val createdAt: Long, val sealed: Boolean)

    private companion object {
        /** A batch that may still receive appends. */
        const val OPEN_EXTENSION = "batch"

        /** A batch handed out for upload: immutable until acknowledged or evicted. */
        const val SEALED_EXTENSION = "sealed"

        val directoryLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }

    private data class StoredBatch(val file: File, val events: List<TelemetryEnvelope>) {
        fun toPublicBatch() = TelemetryBatch(file.nameWithoutExtension, events)
    }
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing $name")

private fun JsonObject.requiredInt(name: String): Int = requiredString(name).toInt()

private fun JsonObject.requiredLong(name: String): Long = requiredString(name).toLong()
