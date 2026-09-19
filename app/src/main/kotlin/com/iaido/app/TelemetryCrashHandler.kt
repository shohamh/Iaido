package com.iaido.app

import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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
 */
class TelemetryCrashHandler(
    private val enabled: () -> Boolean,
    private val crashStore: CrashStore,
    private val breadcrumbs: () -> List<DiagnosticsBreadcrumb> = { emptyList() },
    private val prior: Thread.UncaughtExceptionHandler?,
    private val clock: () -> Long = System::currentTimeMillis,
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
        }

        var json = renderPayload(error, crumbs)
        while (json.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES && crumbs.isNotEmpty()) {
            crumbs = crumbs.drop(1)
            json = renderPayload(error, crumbs)
        }

        val bytes = json.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= MAX_PAYLOAD_BYTES) json else String(bytes.copyOf(MAX_PAYLOAD_BYTES), Charsets.UTF_8)
    }

    private fun renderPayload(error: RedactedThrowable, crumbs: List<DiagnosticsBreadcrumb>): String =
        buildJsonObject {
            put("occurred_at_ms", JsonPrimitive(clock()))
            put("error", renderError(error))
            put("breadcrumbs", buildJsonArray { crumbs.forEach { add(renderBreadcrumb(it)) } })
        }.toString()

    private fun renderError(error: RedactedThrowable) = buildJsonObject {
        put("type", JsonPrimitive(error.type))
        put("message", error.message?.let { JsonPrimitive(it) } ?: JsonNull)
        put("stackTrace", JsonPrimitive(error.stackTrace))
    }

    private fun renderBreadcrumb(crumb: DiagnosticsBreadcrumb) = buildJsonObject {
        put("kind", JsonPrimitive(crumb.kind.name))
        put("count", JsonPrimitive(crumb.count))
        crumb.gestureKind?.let { put("gestureKind", JsonPrimitive(it.name)) }
        crumb.outcome?.let { put("outcome", JsonPrimitive(it.name)) }
        crumb.latencyBucket?.let { put("latencyBucket", JsonPrimitive(it.name)) }
        crumb.suggestionAction?.let { put("suggestionAction", JsonPrimitive(it.name)) }
        crumb.errorCode?.let { put("errorCode", JsonPrimitive(it.name)) }
        crumb.error?.let { put("error", renderError(it)) }
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
