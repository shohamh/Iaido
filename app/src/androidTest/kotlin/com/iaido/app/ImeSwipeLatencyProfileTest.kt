package com.iaido.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iaido.core.typing.SpacingMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in profiling (`-Pandroid.testInstrumentationRunnerArguments.profile=true`): logs per-swipe
 * release-to-text latency under tag E2E-PERF. Asserts nothing. The figure includes UiAutomator
 * polling, so use it for relative comparisons only.
 */
@RunWith(AndroidJUnit4::class)
class ImeSwipeLatencyProfileTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun profileEnglishSentenceLatency() {
        org.junit.Assume.assumeTrue(
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("profile") == "true",
        )
        scenario().run {
            val sentence = listOf("there", "is", "a", "keyboard", "that", "understands", "what", "because", "important", "hello")
            for (mode in listOf(SpacingMode.AFTER_SWIPE, SpacingMode.INFER_SPACES)) {
                setSpacingModeForBehaviorTest(mode)
                repeat(2) { round ->
                    clearText()
                    val times = sentence.map { word -> swipeCommitTiming(word).textUpdateAfterReleaseMs }
                    Log.i("E2E-PERF", "mode=$mode round=$round latencies_ms=$times median=${times.sorted()[times.size / 2]}")
                }
            }
        }
    }

    private fun scenario() = ImeScenario().also(artifacts::track)
}
