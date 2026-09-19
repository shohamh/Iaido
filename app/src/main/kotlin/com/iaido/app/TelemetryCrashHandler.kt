package com.iaido.app

import java.io.File

/** Storage sink for a single bounded crash envelope. */
interface CrashStore {
    fun write(payload: String)
}

/**
 * Writes at most [maxBytes] of the payload to [file]. Used so a crash-time write can never
 * balloon local storage even if the caller hands it an oversized payload.
 */
class BoundedFileCrashStore(
    private val file: File,
    private val maxBytes: Int = TelemetryCrashHandler.MAX_PAYLOAD_BYTES,
) : CrashStore {
    override fun write(payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val bounded = if (bytes.size <= maxBytes) bytes else bytes.copyOf(maxBytes)
        file.parentFile?.mkdirs()
        file.writeBytes(bounded)
    }
}

/**
 * Uncaught-exception handler that writes a small, fully redacted crash envelope to [crashStore]
 * only when diagnostics consent is enabled, then always delegates to [prior] - even when the
 * consent check, envelope construction, or persistence itself fails. Crash delegation must never
 * be blocked by diagnostics capture.
 *
 * The envelope is exactly the wire crash payload ([DiagnosticsEventCodec] encoding of
 * [DiagnosticsEvent.Crash]), so the next launch can decode it and enqueue it unchanged. The crash
 * timestamp is deliberately not carried: the replayed event's `occurred_at_ms` is the report
 * time, which keeps the envelope identical to what the server validates.
 */
class TelemetryCrashHandler(
    private val enabled: () -> Boolean,
    private val crashStore: CrashStore,
    private val breadcrumbs: () -> List<DiagnosticsBreadcrumb> = { emptyList() },
    private val prior: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    /** The last payload this handler attempted to write. Exposed for testing/inspection. */
    @Volatile
    var lastWrittenPayload: String? = null
        private set

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (enabled()) {
                val payload = buildPayload(throwable)
                lastWrittenPayload = payload
                try {
                    crashStore.write(payload)
                } catch (_: Exception) {
                    // Crash persistence must never prevent delegation to the prior handler.
                }
            }
        } catch (_: Exception) {
            // Diagnostics capture must never prevent crash delegation.
        }
        prior?.uncaughtException(thread, throwable)
    }

    private fun buildPayload(throwable: Throwable): String {
        val error = redactThrowable(throwable)
        var crumbs = try {
            breadcrumbs()
        } catch (_: Exception) {
            emptyList()
        }.takeLast(MAX_BREADCRUMBS)

        var json = DiagnosticsEventCodec.encode(
            DiagnosticsEvent.Crash(error = error, breadcrumbs = crumbs),
        )
        while (json.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES && crumbs.isNotEmpty()) {
            crumbs = crumbs.drop(1)
            json = DiagnosticsEventCodec.encode(
                DiagnosticsEvent.Crash(error = error, breadcrumbs = crumbs),
            )
        }

        val bytes = json.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= MAX_PAYLOAD_BYTES) json else String(bytes.copyOf(MAX_PAYLOAD_BYTES), Charsets.UTF_8)
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 8192

        /**
         * Installs a [TelemetryCrashHandler] as the process default uncaught-exception handler,
         * chaining to [previous] (defaulting to whatever handler is currently installed).
         */
        fun install(
            enabled: () -> Boolean,
            crashStore: CrashStore,
            breadcrumbs: () -> List<DiagnosticsBreadcrumb> = { emptyList() },
            previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
        ): TelemetryCrashHandler {
            val handler = TelemetryCrashHandler(
                enabled = enabled,
                crashStore = crashStore,
                breadcrumbs = breadcrumbs,
                prior = previous,
            )
            Thread.setDefaultUncaughtExceptionHandler(handler)
            return handler
        }
    }
}
