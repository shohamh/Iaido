package com.ninjakeys.app

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.layout.KeyboardLayout
import com.ninjakeys.core.recognition.GestureRecognizer
import com.ninjakeys.core.recognition.ScoredCandidate

class SwipeCommitController(
    private val recognizer: GestureRecognizer,
    private val dictionary: List<WordEntry>,
    private val commitText: (String) -> Unit,
    private val onRecognized: (List<ScoredCandidate>) -> Unit = {},
    private val runtimeRanker: ((List<ScoredCandidate>) -> List<ScoredCandidate>)? = null,
) {
    fun commit(path: GesturePath, layout: KeyboardLayout, dictionaryOverride: List<WordEntry>? = null) {
        if (path.points.size < 2) return

        val recognized = recognizer.recognize(path, layout, dictionaryOverride ?: dictionary)
        val results = runtimeRanker?.invoke(recognized) ?: recognized
        if (results.isEmpty()) return
        onRecognized(results)
        val result = results.first()
        commitText(result.word.word)
    }
}
