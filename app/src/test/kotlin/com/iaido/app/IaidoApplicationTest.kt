package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IaidoApplicationTest {
    @Test
    fun `application services run only in the default application process`() {
        assertTrue(isDefaultApplicationProcess("com.iaido.app", "com.iaido.app"))
        assertFalse(isDefaultApplicationProcess("com.iaido.app:ime_test_host", "com.iaido.app"))
        assertEquals(
            shouldScheduleReleaseMonitor("com.iaido.app", "com.iaido.app"),
            shouldInitializeDiagnostics("com.iaido.app", "com.iaido.app"),
        )
        assertEquals(
            shouldScheduleReleaseMonitor("com.iaido.app:ime_test_host", "com.iaido.app"),
            shouldInitializeDiagnostics("com.iaido.app:ime_test_host", "com.iaido.app"),
        )
    }
}
