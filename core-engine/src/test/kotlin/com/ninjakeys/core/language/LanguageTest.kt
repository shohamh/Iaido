package com.ninjakeys.core.language

import com.ninjakeys.core.layout.KeyboardLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanguageTest {
    @Test
    fun `hebrew layout preserves physical left to right row order`() {
        val letters = KeyboardLayout.hebrewTestLayout().keys
            .filter { it.y == 0f }
            .sortedBy { it.x }
            .joinToString("") { it.letter.toString() }

        assertEquals("קראטוןםפ", letters)
    }

    @Test
    fun `switcher cycles enabled languages`() {
        val switcher = LanguageSwitcher()

        assertEquals(Language.HEBREW, switcher.next())
        assertEquals(Language.ENGLISH, switcher.next())
        assertTrue(Language.HEBREW.isRtl)
        assertFalse(Language.ENGLISH.isRtl)
    }

    @Test
    fun `only a two finger horizontal swipe switches language`() {
        val switcher = LanguageSwitcher()

        assertFalse(switcher.handleTwoFingerSwipe(1f, 2f, 2))
        assertFalse(switcher.handleTwoFingerSwipe(2f, 1f, 1))
        assertTrue(switcher.handleTwoFingerSwipe(2f, 1f, 2))
        assertEquals(Language.HEBREW, switcher.current)
    }
}
