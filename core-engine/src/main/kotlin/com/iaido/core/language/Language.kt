package com.iaido.core.language

enum class Language(val isRtl: Boolean, val localeTag: String) {
    ENGLISH(false, "en-US"),
    HEBREW(true, "he-IL"),
}

class LanguageSwitcher(
    private val enabled: List<Language> = listOf(Language.ENGLISH, Language.HEBREW),
    initial: Language = enabled.first(),
) {
    init {
        require(enabled.isNotEmpty())
        require(initial in enabled)
    }

    var current: Language = initial
        private set

    fun select(language: Language) {
        require(language in enabled) { "Language is not enabled: $language" }
        current = language
    }

    fun next(): Language {
        current = enabled[(enabled.indexOf(current) + 1) % enabled.size]
        return current
    }

    fun handleTwoFingerSwipe(deltaX: Float, deltaY: Float, pointerCount: Int): Boolean {
        if (pointerCount < 2 || kotlin.math.abs(deltaX) <= kotlin.math.abs(deltaY)) return false
        next()
        return true
    }
}
