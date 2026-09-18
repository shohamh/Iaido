package com.iaido.app

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TelemetryEventTest {
    @Test
    fun `diagnostics event cannot carry research text or trace fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode("""{"event_type":"gesture_outcome","text":"secret","points":[[1,2]]}""")
        }
    }
}
