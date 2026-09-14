package com.iaido.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.testing.SwipeFixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeFixtureInstrumentedTest {
    @Test
    fun `shared swipe fixture preserves its word and timing`() {
        val path = SwipeFixtures.pathThrough(KeyboardLayout.qwertyTestLayout(), "there")

        assertEquals(5, path.points.size)
        assertEquals(40L, path.points.last().timestampMs)
    }
}
