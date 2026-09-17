package com.iaido.app

import com.iaido.core.language.Language
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSuiteSessionStateTest {
    @Test
    fun firstScenarioBootstrapsAndLaterSameFixtureScenariosReuseIt() {
        val state = ImeSuiteSessionState()

        assertTrue(state.needsBootstrap("fixture-a"))
        state.markReady("fixture-a")

        assertFalse(state.needsBootstrap("fixture-a"))
        assertTrue(state.needsBootstrap("fixture-b"))
    }

    @Test
    fun recordsTheLastImeAndLanguageForAQuickScenarioReset() {
        val state = ImeSuiteSessionState()
        state.markReady("fixture-a", "iaido", Language.HEBREW)

        assertFalse(state.needsImeSelection("iaido"))
        assertTrue(state.needsImeSelection("reference"))
        assertEquals(Language.HEBREW, state.languageOrNull())
    }

    @Test
    fun invalidatingStateForcesTheNextScenarioToBootstrap() {
        val state = ImeSuiteSessionState()
        state.markReady(null)

        state.invalidate()

        assertTrue(state.needsBootstrap(null))
    }
}
