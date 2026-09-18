package com.iaido.app

import com.iaido.core.distribution.SemanticVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CoreEngineUpdateClientTest {
    @Test
    fun `nightly app versions use their stable numeric version for core updates`() {
        assertEquals(
            SemanticVersion.parse("0.1.17"),
            parseInstalledAppVersion("0.1.17-nightly.29"),
        )
    }

    @Test
    fun `missing or malformed app versions disable core updates instead of throwing`() {
        assertNull(parseInstalledAppVersion(null))
        assertNull(parseInstalledAppVersion("not-a-version"))
    }
}
