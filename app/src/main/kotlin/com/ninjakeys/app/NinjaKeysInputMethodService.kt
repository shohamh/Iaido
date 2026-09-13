package com.ninjakeys.app

import android.inputmethodservice.InputMethodService
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.ninjakeys.core.recognition.GestureRecognizer
import com.ninjakeys.core.recognition.ShapePathScorer
import com.ninjakeys.core.recognition.TrieCandidateGenerator
import com.ninjakeys.core.language.Language
import com.ninjakeys.core.language.LanguageSwitcher

class NinjaKeysInputMethodService : InputMethodService() {
    private var composeInputView: ComposeView? = null
    private var sessionId = 0
    private val languageSwitcher = LanguageSwitcher()
    private var activeLanguage = Language.ENGLISH

    private val dictionaryRepository by lazy {
        EnglishDictionaryRepository {
            assets.open(englishDictionaryAsset).bufferedReader().use { it.readText() }
        }
    }

    private val hebrewDictionaryRepository by lazy {
        EnglishDictionaryRepository {
            assets.open("dictionary/he.csv").bufferedReader().use { it.readText() }
        }
    }

    private val controller by lazy {
        SwipeCommitController(
            recognizer = GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer()),
            dictionary = dictionaryRepository.words(),
            commitText = typingController::commitWord,
        )
    }

    private val typingController by lazy {
        TypingController(
            commitText = { text -> currentInputConnection?.commitText(text, 1) },
            deleteSurroundingText = { count -> currentInputConnection?.deleteSurroundingText(count, 0) },
            textBeforeCursor = { currentInputConnection?.getTextBeforeCursor(100, 0)?.toString().orEmpty() },
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
                    onSwipe = { path, layout ->
                        val dictionary = if (activeLanguage == Language.ENGLISH) dictionaryRepository.words()
                        else hebrewDictionaryRepository.words()
                        controller.commit(path, layout, dictionary)
                    },
                    onTap = { value ->
                        when (value) {
                            "⌫" -> typingController.backspace()
                            "🌐" -> switchLanguage()
                            else -> typingController.tap(value)
                        }
                    },
                    onFlick = { letter, direction -> typingController.flick(letter, direction) },
                    onPunctuationToSpace = typingController::punctuationToSpace,
                    language = activeLanguage,
                    onLanguageSwitch = ::switchLanguage,
                )
            }
        }
    }

    private fun switchLanguage() {
        activeLanguage = languageSwitcher.next()
        composeInputView?.let(::renderInputView)
    }
}
