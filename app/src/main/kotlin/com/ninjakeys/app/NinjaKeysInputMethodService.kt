package com.ninjakeys.app

import android.inputmethodservice.InputMethodService
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.ninjakeys.core.recognition.GestureRecognizer
import com.ninjakeys.core.recognition.ShapePathScorer
import com.ninjakeys.core.recognition.TrieCandidateGenerator

class NinjaKeysInputMethodService : InputMethodService() {
    private var composeInputView: ComposeView? = null
    private var sessionId = 0

    private val dictionaryRepository by lazy {
        EnglishDictionaryRepository {
            assets.open(englishDictionaryAsset).bufferedReader().use { it.readText() }
        }
    }

    private val controller by lazy {
        SwipeCommitController(
            recognizer = GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer()),
            dictionary = dictionaryRepository.words(),
            commitText = { word -> currentInputConnection?.commitText(word, 1) },
        )
    }

    override fun onCreateInputView(): View {
        return ComposeView(this).also { view ->
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            composeInputView = view
            renderInputView(view)
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sessionId += 1
        composeInputView?.let(::renderInputView)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        sessionId += 1
    }

    override fun onDestroy() {
        composeInputView = null
        super.onDestroy()
    }

    private fun renderInputView(view: ComposeView) {
        val currentSession = sessionId
        view.setContent {
            MaterialTheme {
                KeyboardInputView(
                    sessionId = currentSession,
                    onSwipe = { path, layout -> controller.commit(path, layout) },
                )
            }
        }
    }
}
