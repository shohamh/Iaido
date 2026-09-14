package com.iaido.app

import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement

class FailureArtifactRule : TestWatcher() {
    private var scenario: ImeScenario? = null

    fun track(scenario: ImeScenario) {
        this.scenario = scenario
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
