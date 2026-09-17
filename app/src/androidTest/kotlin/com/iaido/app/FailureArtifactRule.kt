package com.iaido.app

import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import android.os.SystemClock
import android.util.Log
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement

class FailureArtifactRule : TestWatcher() {
    private var scenario: ImeScenario? = null
    private var startedAtMs = 0L

    fun track(scenario: ImeScenario) {
        this.scenario = scenario
    }

    override fun starting(description: Description) {
        startedAtMs = SystemClock.elapsedRealtime()
    }

    override fun finished(description: Description) {
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        Log.i("E2E-PERF", "${description.className}#${description.methodName} ${elapsedMs}ms")
    }

    override fun failed(e: Throwable, description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ArtifactWriter.capture(
            testName = description.className + "_" + description.methodName,
            device = UiDevice.getInstance(instrumentation),
            scenarioState = scenario?.state(),
            failure = e,
        )
    }
}
