package com.iaido.dynamic

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class CoreEngineDynamicEntrypointTest {
    @Test
    fun `dynamic entrypoint exposes a stable primitive-only ranking contract`() {
        val ranked = CoreEngineDynamicEntrypoint.rank(
            words = arrayOf("first", "second"),
            scores = doubleArrayOf(1.0, 2.0),
        )

        assertArrayEquals(arrayOf("second", "first"), ranked)
    }
}
