package com.iaido.app

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryCrashHandlerTest {
    @Test
    fun `crash handler writes redacted bounded metadata before delegating`() {
        val calls = mutableListOf<String>()
        val store = RecordingCrashStore { calls += "write" }
        val prior = Thread.UncaughtExceptionHandler { _, _ -> calls += "delegate" }
        val handler = TelemetryCrashHandler(
            enabled = { true },
            crashStore = store,
            breadcrumbs = {
                listOf(
                    DiagnosticsBreadcrumb(
                        kind = DiagnosticsBreadcrumbKind.RUNTIME_ERROR,
                        count = 1,
                        errorCode = DiagnosticsRuntimeErrorCode.RECOGNITION_FAILED,
                        error = redactThrowable(IllegalStateException("other secret")),
                    ),
                )
            },
            prior = prior,
            clock = { 1_000L },
        )

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("secret editor text"))

        assertEquals(listOf("write", "delegate"), calls)
        assertTrue(store.payload!!.contains("\"type\":\"exception\""))
        assertTrue(store.payload!!.contains("<redacted>"))
        assertFalse(store.payload!!.contains("secret editor text"))
        assertFalse(store.payload!!.contains("other secret"))
        assertTrue(store.payload!!.toByteArray().size <= TelemetryCrashHandler.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `disabled crash handler only delegates`() {
        val store = RecordingCrashStore()
        var delegated = false
        val handler = TelemetryCrashHandler(
            enabled = { false },
            crashStore = store,
            breadcrumbs = { emptyList() },
            prior = Thread.UncaughtExceptionHandler { _, _ -> delegated = true },
        )

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("secret"))

        assertTrue(delegated)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun `crash persistence failure still delegates`() {
        var delegated = false
        val handler = TelemetryCrashHandler(
            enabled = { true },
            crashStore = object : CrashStore {
                override fun write(payload: String) = throw IllegalStateException("disk unavailable")
            },
            breadcrumbs = { emptyList() },
            prior = Thread.UncaughtExceptionHandler { _, _ -> delegated = true },
        )

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("secret"))

        assertTrue(delegated)
    }

    @Test
    fun `file crash store never writes beyond its byte limit`() {
        val directory = Files.createTempDirectory("diagnostics-crash").toFile()
        try {
            val file = directory.resolve("crash.json")
            BoundedFileCrashStore(file, maxBytes = 64).write("x".repeat(1_000))

            assertTrue(file.length() <= 64L)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class RecordingCrashStore(
        private val onWrite: () -> Unit = {},
    ) : CrashStore {
        var payload: String? = null
        var writeCount = 0

        override fun write(payload: String) {
            writeCount += 1
            this.payload = payload
            onWrite()
        }
    }
}
