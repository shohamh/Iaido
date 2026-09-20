package com.iaido.app

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iaido.core.recognition.SuggestionChip
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SuggestionStripVisibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reelShowsTextAfterScrollingFourOptionsDown() {
        val candidates = (0..5).map { "option-$it" }

        composeRule.setContent {
            var selectedIndex by remember { mutableIntStateOf(0) }
            MaterialTheme {
                SuggestionStrip(
                    chips = listOf(
                        SuggestionChip(
                            word = candidates[selectedIndex],
                            alternatives = candidates,
                            selectedIndex = selectedIndex,
                            id = 1,
                        ),
                    ),
                    rtl = false,
                    onRelease = { _, candidateIndex -> selectedIndex = candidateIndex },
                )
            }
        }

        repeat(4) {
            composeRule.onNodeWithContentDescription("Iaido suggestion 0")
                .performTouchInput {
                    val delta = with(density) { 28.dp.toPx() * 1.25f }
                    val center = this.center
                    swipe(
                        start = Offset(center.x, center.y + delta / 2f),
                        end = Offset(center.x, center.y - delta / 2f),
                        durationMillis = 250,
                    )
                }
            composeRule.waitForIdle()
        }

        val screenshot = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir("ime-e2e/checkpoints"),
            "suggestion-strip-after-four-swipes.png",
        ).apply { parentFile?.mkdirs() }
        screenshot.outputStream().use { output ->
            composeRule.onNodeWithContentDescription("Iaido suggestion 0")
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        composeRule.onNodeWithText("option-4", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun reelsUseTheirOwnCandidateCountForHeight() {
        composeRule.setContent {
            MaterialTheme {
                SuggestionStrip(
                    chips = listOf(
                        SuggestionChip(
                            word = "one",
                            alternatives = listOf("two", "three"),
                            selectedIndex = 0,
                            id = 1,
                        ),
                        SuggestionChip(
                            word = "only",
                            alternatives = listOf("only"),
                            selectedIndex = 0,
                            id = 2,
                        ),
                    ),
                    rtl = false,
                )
            }
        }

        val screenshot = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir("ime-e2e/checkpoints"),
            "suggestion-strip-per-reel-height.png",
        ).apply { parentFile?.mkdirs() }
        screenshot.outputStream().use { output ->
            composeRule.onNodeWithContentDescription(SUGGESTION_STRIP_DESCRIPTION)
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp ${screenshot.absolutePath} /sdcard/Download/suggestion-strip-per-reel-height-node.png")
            .close()

        composeRule.onNodeWithContentDescription("Iaido suggestion 0")
            .assertHeightIsEqualTo(84.dp)
        composeRule.onNodeWithContentDescription("Iaido suggestion 1")
            .assertHeightIsEqualTo(28.dp)
    }
}
