package com.iaido.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TelemetryRedactorTest {
    @Test
    fun `redacts file paths and text-like exception details`() {
        val result = redactThrowable(
            IllegalStateException("typed password=secret at C:\\Users\\me\\file.kt"),
        )

        assertFalse(result.stackTrace.contains("secret"))
        assertFalse(result.stackTrace.contains("C:\\Users\\me"))
    }

    @Test
    fun `redacts query strings, readable words, and raw coordinates`() {
        val result = redactThrowable(
            IllegalArgumentException(
                "candidate=hello clipboard=world https://host.test/search?q=password at (123,456)",
            ),
        )

        assertFalse(result.stackTrace.contains("hello"))
        assertFalse(result.stackTrace.contains("world"))
        assertFalse(result.stackTrace.contains("host.test"))
        assertFalse(result.stackTrace.contains("123,456"))
    }

    @Test
    fun `diagnostic validation rejects raw touch and readable text fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode("""{"event_type":"raw_touch_path","points":[[1,2]]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode("""{"event_type":"gesture_outcome","outcome":"typed word"}""")
        }
    }

    @Test
    fun `validated diagnostics preserve only the bounded outcome`() {
        validateDiagnostics(DiagnosticsEvent.GestureOutcome(DiagnosticsOutcome.ACCEPTED))
    }
}
