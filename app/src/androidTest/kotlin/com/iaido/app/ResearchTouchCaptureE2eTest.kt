package com.iaido.app

import android.os.Build
import android.view.MotionEvent
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.iaido.core.language.Language
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected coverage for the real [TouchFrame]/[ResearchTraceRecorder] pipeline against a real
 * device's touch injection, real measured keyboard-surface bounds, and real event timings -
 * things a JVM test (see [ResearchTraceRecorderTest]) cannot exercise.
 *
 * [KeyboardInputView] accepts an optional [ResearchTraceRecorder] (defaulting to `null`, so
 * every existing caller and every other E2E test is unaffected), but wiring a live recorder
 * instance into the running [IaidoInputMethodService] is Task 9's job, not this one - Task 8
 * only adds the capture point and the pure in-memory recorder itself. So this test drives a real
 * swipe against the real running keyboard (confirming the new instrumentation changes nothing
 * about normal typing behavior - the actual committed text is asserted exactly as the other E2E
 * suites do), and separately - using the *exact* real `MotionEvent`-shaped pointer sequence and
 * the *exact* real, on-device measured keyboard-surface bounds that swipe just used - replays
 * that sequence through a standalone [ResearchTraceRecorder] the same way `KeyboardInputView`'s
 * `pointerInteropFilter` now does internally, and writes the resulting [ResearchTrace] to a local
 * fixture file for manual inspection. This is only possible/only written when research consent
 * is enabled, matching [ResearchTraceRecorder.consume]/[ResearchTraceRecorder.finish]'s consent
 * gating.
 */
@RunWith(AndroidJUnit4::class)
class ResearchTouchCaptureE2eTest {
    @get:org.junit.Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun swipingAWordCapturesANormalizedResearchTraceOnlyWhenConsentIsEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)

        setResearchConsent(context, enabled = true)
        try {
            val scenario = ImeScenario().also(artifacts::track)
            scenario.run {
                swipeWord("hello")
                assertText("Hello")
            }

            val window = KeyboardWindowLocator.locate(device)
            val pointerEvents = scenario.state().trace.last { it.pointerEvents.isNotEmpty() }.pointerEvents
            assertTrue("Expected a captured pointer sequence for the swipe", pointerEvents.isNotEmpty())

            val enabledTrace = replayThroughRecorder(
                pointerEvents = pointerEvents,
                surfaceWidthPx = window.surfaceBounds.width().toFloat(),
                surfaceHeightPx = window.surfaceBounds.height().toFloat(),
                enabled = true,
            )
            assertNotNull("Expected a research trace when consent is enabled", enabledTrace)
            requireNotNull(enabledTrace)
            assertTrue("Expected at least one normalized point", enabledTrace.points.isNotEmpty())
            assertTrue(
                "Normalized points must stay within [0, 1]",
                enabledTrace.points.all { it.x in 0f..1f && it.y in 0f..1f },
            )
            assertEquals(ResearchTraceClassification.SWIPE, enabledTrace.classification)
            assertEquals(Language.ENGLISH, enabledTrace.language)
            val fixture = writeFixture(context, "swiping_a_word_consent_enabled", enabledTrace)
            assertTrue("Expected an inspectable fixture file to be written", fixture.exists() && fixture.length() > 0L)

            val disabledTrace = replayThroughRecorder(
                pointerEvents = pointerEvents,
                surfaceWidthPx = window.surfaceBounds.width().toFloat(),
                surfaceHeightPx = window.surfaceBounds.height().toFloat(),
                enabled = false,
            )
            assertNull("Expected no research trace when consent is disabled", disabledTrace)
        } finally {
            setResearchConsent(context, enabled = false)
        }
    }

    /**
     * Feeds the exact pointer sequence a real device swipe produced (captured via
     * [PointerInjector]/[ImeScenario]) through a standalone [ResearchTraceRecorder], converting
     * each [InjectedPointerEvent] into a [TouchFrame] the same way `KeyboardInputView`'s
     * `MotionEvent.toTouchFrame` extension does internally.
     */
    private fun replayThroughRecorder(
        pointerEvents: List<InjectedPointerEvent>,
        surfaceWidthPx: Float,
        surfaceHeightPx: Float,
        enabled: Boolean,
    ): ResearchTrace? {
        val recorder = ResearchTraceRecorder(enabled = { enabled })
        pointerEvents.forEach { event ->
            recorder.consume(
                TouchFrame(
                    action = maskedAction(event.action),
                    eventTimeMs = event.eventTimeMs,
                    surfaceWidthPx = surfaceWidthPx,
                    surfaceHeightPx = surfaceHeightPx,
                    pointers = event.pointers.map { pointer ->
                        TouchPointer(pointerId = pointer.id, xPx = pointer.x, yPx = pointer.y)
                    },
                ),
            )
        }
        return recorder.finish(ResearchTraceClassification.SWIPE, Language.ENGLISH, "qwerty")
    }

    /** [InjectedPointerEvent.action] is already the unmasked action constant (e.g. ACTION_UP). */
    private fun maskedAction(action: Int): Int = action and MotionEvent.ACTION_MASK

    private fun writeFixture(context: android.content.Context, name: String, trace: ResearchTrace): File {
        val directory = File(context.getExternalFilesDir("research-trace-e2e"), "fixtures").apply { mkdirs() }
        val file = File(directory, "$name.json")
        file.writeText(traceJson(trace))
        return file
    }

    private fun traceJson(trace: ResearchTrace): String {
        val points = trace.points.joinToString(prefix = "[", postfix = "]", separator = ",") { point ->
            "{\"pointerId\":${point.pointerId},\"action\":${point.action}," +
                "\"timeOffsetMs\":${point.timeOffsetMs},\"x\":${point.x},\"y\":${point.y}}"
        }
        return "{\"classification\":\"${trace.classification}\",\"language\":\"${trace.language.name}\"," +
            "\"layoutId\":\"${trace.layoutId}\",\"algorithmVersion\":${trace.algorithmVersion}," +
            "\"androidApi\":${Build.VERSION.SDK_INT},\"points\":$points}"
    }

    private fun setResearchConsent(context: android.content.Context, enabled: Boolean) {
        runBlocking {
            context.settingsStore.edit { preferences -> preferences[researchConsentKey] = enabled }
            check(researchConsentFromPreferences(context.settingsStore.data.first()).enabled == enabled) {
                "Research consent did not settle to $enabled"
            }
        }
    }
}
