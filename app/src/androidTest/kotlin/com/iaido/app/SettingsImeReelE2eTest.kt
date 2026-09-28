package com.iaido.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.os.SystemClock
import androidx.work.WorkManager
import com.iaido.core.language.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsImeReelE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @After
    fun clearReleaseMonitorTestOverride() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, 0)
            .edit()
            .remove(DebugAutoSpaceFixtures.SKIP_RELEASE_MONITOR_KEY)
            .apply()
    }

    @Test
    fun settingsPreviewUsesTheRealImeSentenceStripAndCapturesTheKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val system = ImeSystemController(instrumentation, device, instrumentation.uiAutomation)
        val sentenceStripDriver = SentenceStripImeDriver(instrumentation)
        instrumentation.targetContext
            .getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, 0)
            .edit()
            .putBoolean(DebugAutoSpaceFixtures.SKIP_RELEASE_MONITOR_KEY, true)
            .commit()
        system.setAutoSpaceFixture(ImeScenarioData.AutoSpaceFixture.TYPED_REEL.preferenceValue)
        system.enableAndSelect(system.iaidoImeId)
        WorkManager.getInstance(instrumentation.targetContext)
            .cancelUniqueWork(RELEASE_MONITOR_WORK_NAME)
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n ${instrumentation.targetContext.packageName}/.SettingsActivity " +
                "--ez ${SettingsActivity.EXTRA_SKIP_AUTOMATIC_UPDATE_CHECKS} true",
        ).close()
        // A cold Compose launch on the emulator can spend several seconds compiling the
        // Settings screen before it becomes visible.  Waiting only five seconds made this
        // screenshot test capture the launcher and report that the preview was missing.
        val settingsTitle = if (BuildConfig.DEBUG) "Iaido Debug Settings" else "Iaido Settings"
        assertTrue(
            "Iaido Settings screen did not become visible",
            device.wait(Until.hasObject(By.text(settingsTitle)), 20_000L),
        )
        val preview = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 20_000L)
        assertTrue("Live preview field was not found", preview != null)
        preview.click()
        device.click(500, 420)
        SystemClock.sleep(1_000L)

        val pointer = PointerInjector(instrumentation.uiAutomation)
        tapLetter(pointer, device, 'h')
        assertTrue(
            "Settings preview did not receive the first typed letter",
            device.wait(Until.hasObject(By.textContains("H")), 5_000L),
        )
        tapLetter(pointer, device, 'i')
        assertTrue(
            "Typing the second letter did not update the Settings preview",
            device.wait(Until.hasObject(By.textContains("Hi")), 5_000L),
        )
        // Preserve a typed-state capture before the detailed strip assertions for later
        // prototype/native screenshot comparison in the consolidated acceptance pass.
        val hiScreenshot = ArtifactWriter.captureScreenshot("settings-ime-typed-hi-sentence-strip", device)
        instrumentation.uiAutomation
            .executeShellCommand(
                "cp ${hiScreenshot.absolutePath} /sdcard/Download/settings-ime-typed-hi-sentence-strip.png",
            )
            .close()
        assertTrue(
            "The real IME sentence strip was not exposed in Settings",
            device.wait(Until.hasObject(By.descStartsWith("Iaido sentence strip")), 5_000L),
        )
        val laneDeadline = SystemClock.elapsedRealtime() + 5_000L
        var visibleWordIndices = sentenceStripDriver.visibleWordIndices()
        while (visibleWordIndices != listOf(0) && SystemClock.elapsedRealtime() < laneDeadline) {
            SystemClock.sleep(50L)
            visibleWordIndices = sentenceStripDriver.visibleWordIndices()
        }
        assertEquals(
            "The typed word did not appear as one inline word lane; descriptions=" +
                device.findObjects(By.descStartsWith("Iaido sentence")).map { it.contentDescription },
            listOf(0),
            visibleWordIndices,
        )
        assertEquals(
            "The first word lane did not retain its alternatives",
            setOf("his", "him"),
            sentenceStripDriver.alternativesForWord(0)
                .map { it.contentDescription.substringAfter("text=") }
                .toSet(),
        )
        val sentenceStrip = device.wait(
            Until.findObject(By.descStartsWith("Iaido sentence strip")),
            5_000L,
        )
        assertTrue("The sentence strip disappeared before its final screenshot", sentenceStrip != null)

        tapLetter(pointer, device, 'm')
        assertTrue(
            "Typing the third letter did not update the Settings preview",
            device.wait(Until.hasObject(By.textContains("Him")), 5_000L),
        )
        assertEquals(
            "Typing another letter should refresh the existing lane instead of duplicating it",
            listOf(0),
            sentenceStripDriver.visibleWordIndices(),
        )
        // The current word uses AnimatedContent and only reports its text layout after Compose
        // draws the incoming frame. Let that transition finish before inspecting the caret pixels.
        device.waitForIdle()
        SystemClock.sleep(220L)
        val finalSentenceStrip = requireNotNull(
            device.wait(Until.findObject(By.descStartsWith("Iaido sentence strip")), 5_000L),
        ) { "The sentence strip disappeared before its final screenshot" }
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val screenshot = captureScreenshotWhenCursorsAreVisible(
            instrumentation = instrumentation,
            device = device,
            previewBounds = preview.visibleBounds,
            sentenceStripBounds = finalSentenceStrip.visibleBounds,
            density = density,
        )
        instrumentation.uiAutomation
            .executeShellCommand(
                "cp ${screenshot.absolutePath} /sdcard/Download/settings-ime-sentence-strip.png",
            )
            .close()
        val bitmap = requireNotNull(BitmapFactory.decodeFile(screenshot.absolutePath)) {
            "The Settings preview screenshot could not be decoded"
        }
        try {
            assertTrue(
                "The preview field did not draw its insertion cursor in the screenshot",
                screenshotHasAccentCursor(
                    bitmap,
                    preview.visibleBounds,
                    topInsetPx = (density * 20f).toInt(),
                    bottomInsetPx = (density * 10f).toInt(),
                ),
            )
            assertTrue(
                "The sentence strip did not draw its insertion cursor in the screenshot",
                screenshotHasAccentCursor(
                    bitmap,
                    finalSentenceStrip.visibleBounds,
                    topInsetPx = (density * 40f).toInt(),
                    bottomInsetPx = (density * 12f).toInt(),
                ),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureScreenshotWhenCursorsAreVisible(
        instrumentation: android.app.Instrumentation,
        device: UiDevice,
        previewBounds: Rect,
        sentenceStripBounds: Rect,
        density: Float,
    ): File {
        val context = instrumentation.targetContext
        val artifactDirectory = File(
            requireNotNull(context.getExternalFilesDir("ime-e2e")) { "Missing external artifact directory" },
            "checkpoints",
        )
        check(artifactDirectory.exists() || artifactDirectory.mkdirs()) {
            "Could not create screenshot directory $artifactDirectory"
        }
        val screenshot = File(artifactDirectory, "settings-ime-sentence-strip-per-height.png")
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        var previewCursorVisible = false
        var stripCursorVisible = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val frame = instrumentation.uiAutomation.takeScreenshot()
            if (frame == null) {
                SystemClock.sleep(75L)
                continue
            }
            try {
                previewCursorVisible = screenshotHasAccentCursor(
                    frame,
                    previewBounds,
                    topInsetPx = (density * 20f).toInt(),
                    bottomInsetPx = (density * 10f).toInt(),
                )
                stripCursorVisible = screenshotHasAccentCursor(
                    frame,
                    sentenceStripBounds,
                    topInsetPx = (density * 40f).toInt(),
                    bottomInsetPx = (density * 12f).toInt(),
                )
                if (previewCursorVisible && stripCursorVisible) {
                    FileOutputStream(screenshot).use { output ->
                        check(frame.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "Could not encode the cursor screenshot"
                        }
                    }
                    return screenshot
                }
            } finally {
                frame.recycle()
            }
            device.waitForIdle()
            SystemClock.sleep(75L)
        }
        error(
            "Timed out waiting for both insertion cursors in one screenshot: " +
                "preview=$previewCursorVisible sentenceStrip=$stripCursorVisible",
        )
    }

    private fun screenshotHasAccentCursor(
        bitmap: Bitmap,
        bounds: Rect,
        topInsetPx: Int,
        bottomInsetPx: Int,
    ): Boolean {
        val left = (bounds.left + 8).coerceIn(0, bitmap.width - 1)
        val right = (bounds.right - 8).coerceIn(left + 1, bitmap.width)
        val top = (bounds.top + topInsetPx).coerceIn(0, bitmap.height - 1)
        val bottom = (bounds.bottom - bottomInsetPx).coerceIn(top + 1, bitmap.height)
        val minimumRun = 48
        for (x in left until right) {
            var run = 0
            for (y in top until bottom) {
                if (isAccent(bitmap.getPixel(x, y))) {
                    run += 1
                    if (run >= minimumRun) return true
                } else {
                    run = 0
                }
            }
        }
        return false
    }

    private fun isAccent(pixel: Int): Boolean =
        android.graphics.Color.alpha(pixel) >= 220 &&
            kotlin.math.abs(android.graphics.Color.red(pixel) - 139) <= 32 &&
            kotlin.math.abs(android.graphics.Color.green(pixel) - 203) <= 32 &&
            kotlin.math.abs(android.graphics.Color.blue(pixel) - 208) <= 32

    private fun tapLetter(pointer: PointerInjector, device: UiDevice, letter: Char) {
        val window = KeyboardWindowLocator.locate(device)
        val center = window.keyCenter(letter.toString().lowercase())
        pointer.injectTap(center.x.toFloat(), center.y.toFloat())
        device.waitForIdle()
    }
}
