package com.iaido.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.iaido.core.typing.SpacingMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose text fields (Settings -> Live preview) deliver extracted-text updates asynchronously, so
 * right after the keyboard edits the field the monitor can still report the text from before the
 * edit. That echo must not end the inference run, or the next swipe has no space before it.
 */
@RunWith(AndroidJUnit4::class)
class ImeComposeFieldE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun inferredSpacesSurviveAsynchronousEditorUpdatesInComposeField() {
        ImeScenario().also(artifacts::track).run {
            setSpacingModeForBehaviorTest(SpacingMode.INFER_SPACES)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val device = uiDevice()
            device.wait(Until.hasObject(By.textContains("Settings")), 8_000L)
            // The Settings page keeps growing, so start from the top and scroll down until found.
            repeat(8) {
                device.swipe(device.displayWidth / 2, device.displayHeight / 4, device.displayWidth / 2, device.displayHeight * 3 / 4, 25)
            }
            device.waitForIdle()
            var scrolls = 0
            while (!device.hasObject(By.text("Live preview")) && scrolls++ < 40) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 25)
                device.waitForIdle()
            }
            check(device.hasObject(By.text("Live preview"))) { "Live preview field not found" }
            for (gapMs in listOf(1_000L, 200L)) {
                device.findObject(By.text("Live preview")).click()
                device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 3_000L)
                device.findObject(By.clazz("android.widget.EditText")).text = ""
                device.waitForIdle()
                swipeWordsIntoFocusedEditor(listOf("are", "you", "ready", "for"), gapMs)
                assertEquals(
                    "gap ${gapMs}ms",
                    "Are you ready for",
                    device.findObject(By.clazz("android.widget.EditText")).text,
                )
            }
        }
    }
}
