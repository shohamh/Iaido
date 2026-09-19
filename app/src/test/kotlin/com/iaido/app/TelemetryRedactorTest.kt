package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryRedactorTest {
    @Test
    fun `keeps the exception class and file names but never the message or a directory`() {
        val result = redactThrowable(
            IllegalStateException("typed password=secret at C:\\Users\\me\\file.kt"),
        )
        val rendered = result.frames.joinToString("\n")

        assertEquals("IllegalStateException", result.type)
        assertTrue(result.frames.isNotEmpty(), "expected at least one frame")
        assertTrue(rendered.contains("TelemetryRedactorTest.kt:"), "expected frames from this test")
        assertFalse(rendered.contains("secret"))
        assertFalse(rendered.contains("C:\\Users"))
        assertFalse(rendered.contains("file.kt"))
        assertFalse(rendered.contains("password"))
    }

    @Test
    fun `redacts query strings, readable words, and raw coordinates`() {
        val result = redactThrowable(
            IllegalArgumentException(
                "candidate=hello clipboard=world https://host.test/search?q=password at (123,456)",
            ),
        )
        val rendered = result.frames.joinToString("\n")

        assertFalse(rendered.contains("hello"))
        assertFalse(rendered.contains("world"))
        assertFalse(rendered.contains("host.test"))
        assertFalse(rendered.contains("123,456"))
    }

    @Test
    fun `frames cannot carry an absolute path or exceed the wire bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            RedactedThrowable("IllegalStateException", listOf("Foo.bar(C:\\Users\\me\\A.kt:12)"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RedactedThrowable("IllegalStateException", List(MAX_ERROR_FRAMES + 1) { "Foo.bar(Foo.kt:1)" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            RedactedThrowable("", listOf("<redacted>"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RedactedThrowable("Has spaces", listOf("<redacted>"))
        }

        // A frame that no JVM can render usefully still crosses the wire as the placeholder.
        val noFrames = redactThrowable(IllegalStateException("no frames"))
        assertTrue(noFrames.frames.isNotEmpty())
        assertTrue(noFrames.frames.all { it == "<redacted>" || it.matches(ERROR_FRAME_PATTERN) })
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
