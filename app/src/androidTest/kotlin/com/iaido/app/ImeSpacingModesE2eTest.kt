package com.iaido.app

import com.iaido.core.typing.SpacingMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeSpacingModesE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun manualSpacingPersistsAcrossKeyboardRestartAndDoesNotInsertSpaces() {
        scenario(ImeScenarioData.AutoSpaceFixture.SEPARATE).run {
            selectSpacingModeThroughSettings(SpacingMode.MANUAL)
            typePrefix()
            swipeWordExpecting("in", "X in")
            swipeWordExpecting("the", "X inthe")
            assertText("X inthe")
        }
    }

    @Test
    fun spaceAfterSwipePersistsAcrossKeyboardRestartAndAddsOneSpacePerUnit() {
        scenario(ImeScenarioData.AutoSpaceFixture.SEPARATE).run {
            selectSpacingModeThroughSettings(SpacingMode.AFTER_SWIPE)
            typePrefix()
            swipeWordExpecting("in", "X in ")
            swipeWordExpecting("the", "X in the ")
            assertText("X in the ")
        }
    }

    @Test
    fun spaceAfterSwipeAddsExactlyOneSpaceForACompletedTwoFingerUnit() {
        scenario(ImeScenarioData.AutoSpaceFixture.TWO_FINGER_MERGE).run {
            selectSpacingModeThroughSettings(SpacingMode.AFTER_SWIPE)
            typePrefix()
            injectSplitWords(listOf("some", "thing"), expected = "X something ")
            assertText("X something ")
        }
    }

    private fun scenario(fixture: ImeScenarioData.AutoSpaceFixture) =
        ImeScenario(autoSpaceFixture = fixture).also(artifacts::track)

    private fun ImeScenario.typePrefix() {
        tapKey("x")
        tapSpace()
        assertText("X ")
    }
}
