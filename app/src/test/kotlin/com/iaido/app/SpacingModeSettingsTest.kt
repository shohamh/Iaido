package com.iaido.app

import androidx.datastore.preferences.core.emptyPreferences
import com.iaido.core.typing.SpacingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpacingModeSettingsTest {
    @Test
    fun `spacing mode stored values use stable literals and round trip`() {
        val expectedStoredValues = mapOf(
            SpacingMode.MANUAL to "manual",
            SpacingMode.AFTER_SWIPE to "after_swipe",
            SpacingMode.INFER_SPACES to "infer_spaces",
        )

        expectedStoredValues.forEach { (mode, storedValue) ->
            assertEquals(storedValue, spacingModeStoredValue(mode))
            assertEquals(mode, spacingModeFromStoredValue(storedValue))
        }
    }

    @Test
    fun `missing and unknown stored values infer spaces`() {
        assertEquals(SpacingMode.INFER_SPACES, spacingModeFromStoredValue(null))
        assertEquals(SpacingMode.INFER_SPACES, spacingModeFromStoredValue("future-mode"))
    }

    @Test
    fun `spacing mode selector uses the required labels`() {
        assertEquals(
            listOf("Manual spacing", "Space after swipe", "Infer spaces"),
            spacingModeOptions(SpacingMode.INFER_SPACES).map(SpacingModeOption::label),
        )
    }

    @Test
    fun `spacing mode selector has one selected mode`() {
        SpacingMode.entries.forEach { selectedMode ->
            assertEquals(
                listOf(selectedMode),
                spacingModeOptions(selectedMode).filter(SpacingModeOption::selected).map(SpacingModeOption::mode),
            )
        }
    }

    @Test
    fun `number row defaults on and follows the saved setting`() {
        assertTrue(keyboardSettingsFromPreferences(emptyPreferences()).showNumberRow)

        val hidden = emptyPreferences().toMutablePreferences().apply {
            this[showNumberRowKey] = false
        }

        assertFalse(keyboardSettingsFromPreferences(hidden).showNumberRow)
    }
}
