package com.iaido.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IaidoApplicationTest {
    @Test
    fun `release monitor runs only in the default application process`() {
        assertTrue(shouldScheduleReleaseMonitor("com.iaido.app", "com.iaido.app"))
        assertFalse(shouldScheduleReleaseMonitor("com.iaido.app:ime_test_host", "com.iaido.app"))
    }
}
