package com.ninjakeys.app

import android.inputmethodservice.InputMethodService
import android.view.View
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.ninjakeys.core.recognition.GestureRecognizer
import com.ninjakeys.core.recognition.ShapePathScorer
import com.ninjakeys.core.recognition.TrieCandidateGenerator
import com.ninjakeys.core.language.Language
import com.ninjakeys.core.language.LanguageSwitcher
import com.ninjakeys.core.commands.CommandBindingSet
import com.ninjakeys.core.commands.CommandGestureDispatcher
import com.ninjakeys.core.commands.CommandModeController
import com.ninjakeys.core.commands.GestureAction
import com.ninjakeys.core.commands.GestureTrigger

class NinjaKeysInputMethodService : InputMethodService() {
    private var composeInputView: ComposeView? = null
    private var sessionId = 0
    private val languageSwitcher = LanguageSwitcher()
    private var activeLanguage = Language.ENGLISH
    private val commandMode = CommandModeController(::executeCommand)
    private val commandDispatcher = CommandGestureDispatcher(CommandBindingSet(), ::executeCommand)

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
        info?.hintLocales = LocaleList.forLanguageTags(activeLanguage.localeTag)
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
                    onCommand = ::handleCommand,
                )
            }
        }
    }

    private fun switchLanguage() {
        activeLanguage = languageSwitcher.next()
        composeInputView?.let(::renderInputView)
    }

    private fun handleCommand(trigger: GestureTrigger) {
        if (commandMode.isActive) {
            val commandTrigger = when (trigger) {
                GestureTrigger.LEFT -> GestureTrigger.COMMAND_COPY
                GestureTrigger.RIGHT -> GestureTrigger.COMMAND_PASTE
                GestureTrigger.UP -> GestureTrigger.COMMAND_CUT
                GestureTrigger.DOWN -> GestureTrigger.COMMAND_SELECT_ALL
                else -> GestureTrigger.NONE
            }
            if (commandTrigger != GestureTrigger.NONE) commandMode.handle(commandTrigger)
            return
        }
        if (trigger == GestureTrigger.LONG_PRESS_SPACE) {
            commandMode.handle(trigger)
            return
        }
        val binding = mapOf(
            GestureTrigger.HORIZONTAL to "two-finger-horizontal",
            GestureTrigger.DOWN to "two-finger-down",
            GestureTrigger.LEFT to "two-finger-left",
            GestureTrigger.RIGHT to "two-finger-right",
        )[trigger] ?: return
        commandDispatcher.dispatch(binding)
    }

    private fun executeCommand(action: GestureAction) {
        when (action) {
            GestureAction.SWITCH_LANGUAGE -> switchLanguage()
            GestureAction.DISMISS -> requestHideSelf(0)
            GestureAction.UNDO -> currentInputConnection?.performContextMenuAction(android.R.id.undo)
            GestureAction.REDO -> currentInputConnection?.performContextMenuAction(android.R.id.redo)
            GestureAction.CUT -> currentInputConnection?.performContextMenuAction(android.R.id.cut)
            GestureAction.COPY -> currentInputConnection?.performContextMenuAction(android.R.id.copy)
            GestureAction.PASTE -> currentInputConnection?.performContextMenuAction(android.R.id.paste)
            GestureAction.SELECT_ALL -> currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            GestureAction.ENTER_COMMAND_MODE -> Unit
        }
    }
}
