package com.ninjakeys.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeGeometryE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun keyboardAndKeysStayAboveNavigationArea() {
        val scenario = ImeScenario()
        artifacts.track(scenario)
        scenario.run {
            assertKeyboardGeometry()
            swipePath("there", PathTransform(stepMs = 8L))
            val screenshot = captureScreenshot("active-trail-fast")
            check(screenshot.isFile) { "Active-trail screenshot was not written: $screenshot" }
            assertText("There")
            clearText()
            swipePath("there", PathTransform(stepMs = 32L, jitterSeed = 3L, jitterPx = 1f))
            assertText("There")
        }
    }
}
