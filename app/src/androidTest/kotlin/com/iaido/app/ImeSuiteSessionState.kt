package com.iaido.app

import com.iaido.core.language.Language

/** Tracks the reusable IME bootstrap shared by scenarios in one instrumentation process. */
internal class ImeSuiteSessionState {
    private var ready = false
    private var activeFixture: String? = null
    private var activeIme: String? = null
    private var activeLanguage: Language? = null

    fun needsBootstrap(requestedFixture: String?): Boolean =
        !ready || activeFixture != requestedFixture

    fun needsImeSelection(imeId: String): Boolean = !ready || activeIme != imeId

    fun languageOrNull(): Language? = activeLanguage

    fun markReady(
        fixture: String?,
        imeId: String? = activeIme,
        language: Language? = activeLanguage,
    ) {
        activeFixture = fixture
        activeIme = imeId
        activeLanguage = language
        ready = true
    }

    fun invalidate() {
        ready = false
    }
}
