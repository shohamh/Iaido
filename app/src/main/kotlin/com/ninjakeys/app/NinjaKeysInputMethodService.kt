package com.ninjakeys.app

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.os.LocaleList
import android.view.inputmethod.ExtractedTextRequest
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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
import com.ninjakeys.core.dictionary.LearningSignal
import com.ninjakeys.core.dictionary.PersonalDictionary
import com.ninjakeys.core.recognition.FlowCorrectionEngine
import com.ninjakeys.core.recognition.FlowWord
import com.ninjakeys.core.recognition.NgramContextScorer
import com.ninjakeys.core.recognition.ScoredCandidate
import com.ninjakeys.core.recognition.SessionCorrectionHistory
import com.ninjakeys.core.recognition.SuggestionChip
import java.util.concurrent.Executors

class NinjaKeysInputMethodService : InputMethodService() {
    private var composeInputView: ComposeView? = null
    private val inputMethodLifecycleOwner = InputMethodLifecycleOwner()
    private var sessionId = 0
    private val languageSwitcher = LanguageSwitcher()
    private var activeLanguage = Language.ENGLISH
    private val commandMode = CommandModeController(::executeCommand)
    private val commandDispatcher = CommandGestureDispatcher(CommandBindingSet(), ::executeCommand)
    private val correctionHistory = SessionCorrectionHistory()
    private val sessionChips = mutableStateListOf<SuggestionChip>()
    private var visibleWordIds: List<Int> = emptyList()
    private var cursorPosition = 0
    private var pendingCandidates: List<String>? = null
    private val correctionExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val splitGraceHandler = Handler(Looper.getMainLooper())
    private val splitController by lazy {
        SplitTypingController(
            dictionary = {
                if (activeLanguage == Language.ENGLISH) dictionaryRepository.words()
                else hebrewDictionaryRepository.words()
            },
            commitText = typingController::commitWord,
        )
    }
    private val contextScorer = NgramContextScorer(
        windowSize = 3,
        bigrams = mapOf(
            ("in" to "the") to 2.0,
            ("on" to "the") to 2.0,
            ("to" to "the") to 2.0,
            ("of" to "the") to 2.0,
        ),
    )
    private val flowCorrectionEngine = FlowCorrectionEngine(contextScorer)
    private val learningDictionary by lazy { PersonalDictionary(dictionaryRepository.words()) }

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
            onRecognized = ::rememberCandidates,
        )
    }

    private val typingController by lazy {
        TypingController(
            commitText = ::commitText,
            deleteSurroundingText = ::deleteSurroundingText,
            textBeforeCursor = { currentInputConnection?.getTextBeforeCursor(100, 0)?.toString().orEmpty() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        inputMethodLifecycleOwner.onCreate()
        window.window?.decorView?.apply {
            setViewTreeLifecycleOwner(inputMethodLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(inputMethodLifecycleOwner)
        }
    }

    override fun onCreateInputView(): View {
        return ComposeView(this).also { view ->
            inputMethodLifecycleOwner.onStartInputView()
            view.setViewTreeLifecycleOwner(inputMethodLifecycleOwner)
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            composeInputView = view
            renderInputView(view)
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        info?.hintLocales = LocaleList.forLanguageTags(activeLanguage.localeTag)
        sessionId += 1
        correctionHistory.clear()
        sessionChips.clear()
        visibleWordIds = emptyList()
        pendingCandidates = null
        splitController.cancel()
        splitGraceHandler.removeCallbacksAndMessages(null)
        cursorPosition = currentInputConnection
            ?.getExtractedText(ExtractedTextRequest(), 0)
            ?.selectionStart ?: 0
        composeInputView?.let(::renderInputView)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        inputMethodLifecycleOwner.onFinishInputView()
        sessionId += 1
        correctionHistory.clear()
        sessionChips.clear()
        visibleWordIds = emptyList()
        pendingCandidates = null
        splitController.cancel()
        splitGraceHandler.removeCallbacksAndMessages(null)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        cursorPosition = newSelStart
        refreshSuggestionChips()
    }

    override fun onDestroy() {
        inputMethodLifecycleOwner.onDestroy()
        correctionExecutor.shutdownNow()
        splitGraceHandler.removeCallbacksAndMessages(null)
        composeInputView = null
        super.onDestroy()
    }

    private fun renderInputView(view: ComposeView) {
        val currentSession = sessionId
        view.setContent {
            NinjaKeysTheme {
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
                    suggestionChips = sessionChips,
                    onSuggestionRelease = ::releaseSuggestion,
                    onSuggestionUndo = ::undoSuggestion,
                    onSplitBegin = splitController::begin,
                    onSplitMove = splitController::move,
                    onSplitEnd = { pointerId, path, layout, atMs ->
                        splitController.finish(pointerId, path, layout, atMs)
                        val expectedSession = sessionId
                        splitGraceHandler.postDelayed({
                            if (sessionId == expectedSession) splitController.poll(System.currentTimeMillis())
                        }, 351L)
                    },
                    onSplitCancel = splitController::cancel,
                )
            }
        }
    }

    private fun rememberCandidates(results: List<ScoredCandidate>) {
        pendingCandidates = results.take(5).map { it.word.word }
    }

    private fun commitText(text: String) {
        val inputConnection = currentInputConnection ?: return
        val start = cursorPosition
        if (!inputConnection.commitText(text, 1)) return
        cursorPosition = start + text.length
        pendingCandidates?.let { candidates ->
            correctionHistory.record(start, cursorPosition, text, candidates)
            pendingCandidates = null
            scheduleFlowCorrection()
        }
        refreshSuggestionChips()
    }

    private fun deleteSurroundingText(count: Int) {
        val inputConnection = currentInputConnection ?: return
        val oldCursor = cursorPosition
        if (!inputConnection.deleteSurroundingText(count, 0)) return
        val start = (oldCursor - count).coerceAtLeast(0)
        correctionHistory.deleteRange(start, oldCursor)
        cursorPosition = start
        refreshSuggestionChips()
    }

    private fun scheduleFlowCorrection() {
        val scheduledSession = sessionId
        val snapshot = correctionHistory.words()
        if (snapshot.size < 2) return
        correctionExecutor.execute {
            val result = flowCorrectionEngine.correct(
                words = snapshot.map { FlowWord(it.current, it.candidates) },
                previousWords = emptyList(),
            )
            if (result.corrections.isEmpty()) return@execute
            mainHandler.post {
                if (sessionId != scheduledSession) return@post
                result.corrections.forEach { correction ->
                    val source = snapshot.getOrNull(correction.index) ?: return@forEach
                    val current = correctionHistory.words().firstOrNull { it.id == source.id } ?: return@forEach
                    if (current.current != correction.before) return@forEach
                    if (replaceSessionWord(source.id, correction.after, preserveCursor = true)) {
                        learningDictionary.record(
                            signal = LearningSignal.FLOW_CORRECTION,
                            original = correction.before,
                            replacement = correction.after,
                        )
                    }
                }
            }
        }
    }

    private fun refreshSuggestionChips() {
        val words = correctionHistory.aroundCursor(cursorPosition)
        visibleWordIds = words.map { it.id }
        sessionChips.clear()
        sessionChips.addAll(words.map { word ->
            SuggestionChip(
                word = word.current,
                alternatives = word.candidates,
                selectedIndex = word.candidates.indexOf(word.current).coerceAtLeast(0),
                corrected = word.corrected,
                id = word.id,
            )
        })
    }

    private fun releaseSuggestion(displayIndex: Int, candidateIndex: Int) {
        val word = wordForDisplayIndex(displayIndex) ?: return
        val replacement = word.candidates.getOrNull(candidateIndex) ?: return
        replaceSessionWord(word.id, replacement)
    }

    private fun undoSuggestion(displayIndex: Int) {
        val word = wordForDisplayIndex(displayIndex) ?: return
        if (!word.corrected) return
        if (replaceSessionWord(word.id, word.original)) {
            learningDictionary.record(
                signal = LearningSignal.FLOW_UNDO,
                original = word.current,
                replacement = word.original,
            )
        }
    }

    private fun wordForDisplayIndex(displayIndex: Int) =
        visibleWordIds
            .getOrNull(if (activeLanguage == Language.HEBREW) visibleWordIds.lastIndex - displayIndex else displayIndex)
            ?.let { id -> correctionHistory.words().firstOrNull { it.id == id } }

    private fun replaceSessionWord(id: Int, replacement: String, preserveCursor: Boolean = false): Boolean {
        val inputConnection = currentInputConnection ?: return false
        val word = correctionHistory.words().firstOrNull { it.id == id } ?: return false
        if (word.current == replacement) return false
        val oldCursor = cursorPosition
        if (!inputConnection.setSelection(word.start, word.end)) return false
        if (!inputConnection.commitText(replacement, 1)) return false
        correctionHistory.replace(id, replacement)
        val delta = replacement.length - word.current.length
        cursorPosition = if (preserveCursor && word.end <= oldCursor) oldCursor + delta
        else word.start + replacement.length
        inputConnection.setSelection(cursorPosition, cursorPosition)
        refreshSuggestionChips()
        return true
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
