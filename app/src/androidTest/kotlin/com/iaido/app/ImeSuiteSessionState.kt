package com.iaido.app

import com.iaido.core.language.Language

/** Tracks the reusable IME bootstrap shared by scenarios in one instrumentation process. */
internal class ImeSuiteSessionState {
    private var ready = false
    private var activeFixture: String? = null
    private var activeIme: String? = null
    private var activeLanguage: Language? = null
    private var activeHostGeneration: Long? = null
    private var baselineId: String? = null

    fun needsBootstrap(requestedFixture: String? = activeFixture): Boolean =
        !ready || activeFixture != requestedFixture

    fun needsImeSelection(imeId: String): Boolean = !ready || activeIme != imeId

    fun languageOrNull(): Language? = activeLanguage

    fun hostGenerationOrNull(): Long? = activeHostGeneration

    fun canReuseHost(fixture: String?, hostGeneration: Long): Boolean =
        ready && activeFixture == fixture && activeHostGeneration == hostGeneration

    fun baselineOrNull(): String? = baselineId

    fun setBaseline(id: String) {
        baselineId = id
    }

    fun markReady(
        fixture: String?,
        imeId: String? = activeIme,
        language: Language? = activeLanguage,
        hostGeneration: Long? = activeHostGeneration,
    ) {
        activeFixture = fixture
        activeIme = imeId
        activeLanguage = language
        activeHostGeneration = hostGeneration
        ready = true
    }

    fun invalidateHostLease() {
        ready = false
        activeHostGeneration = null
        baselineId = null
    }

    fun invalidate() {
        ready = false
        activeHostGeneration = null
        baselineId = null
    }
}
