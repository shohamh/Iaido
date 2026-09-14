package com.iaido.dynamic

/**
 * Stable primitive-only boundary for a separately loaded engine.
 *
 * Keeping the boundary free of engine model types allows the APK's classloader
 * to activate a newer implementation without crossing classloader identity
 * boundaries for the app's normal Kotlin objects.
 */
object CoreEngineDynamicEntrypoint {
    @JvmStatic
    fun rank(words: Array<String>, scores: DoubleArray): Array<String> {
        require(words.size == scores.size) { "words and scores must have equal lengths" }
        return words.indices
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .map(words::get)
            .toTypedArray()
    }
}
