package com.ninjakeys.app

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.layout.KeyboardLayout
import com.ninjakeys.core.recognition.GestureRecognizer

class SwipeCommitController(
    private val recognizer: GestureRecognizer,
    private val dictionary: List<WordEntry>,
    private val commitText: (String) -> Unit,
) {
    fun commit(path: GesturePath, layout: KeyboardLayout, dictionaryOverride: List<WordEntry>? = null) {
        if (path.points.size < 2) return

        val result = recognizer.recognize(path, layout, dictionaryOverride ?: dictionary).firstOrNull() ?: return
        commitText(result.word.word)
    }
}
