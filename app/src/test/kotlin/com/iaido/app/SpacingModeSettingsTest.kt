package com.iaido.app

import com.iaido.core.typing.SpacingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpacingModeSettingsTest {
    @Test
    fun `spacing mode stored values round trip`() {
        SpacingMode.entries.forEach { mode ->
            assertEquals(mode, spacingModeFromStoredValue(spacingModeStoredValue(mode)))
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
}
