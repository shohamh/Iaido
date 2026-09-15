package com.iaido.app

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.os.Handler
import android.os.Looper
import android.view.View
import android.os.LocaleList
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest
import androidx.room.Room
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.iaido.core.recognition.GestureRecognizer
import com.iaido.core.recognition.ShapePathScorer
import com.iaido.core.recognition.TrieCandidateGenerator
import com.iaido.core.language.Language
import com.iaido.core.language.LanguageSwitcher
import com.iaido.core.commands.CommandBindingSet
import com.iaido.core.commands.CommandGestureDispatcher
import com.iaido.core.commands.CommandModeController
import com.iaido.core.commands.GestureAction
import com.iaido.core.commands.GestureTrigger
import com.iaido.core.dictionary.LearningSignal
import com.iaido.core.dictionary.PersonalDictionary
import com.iaido.core.recognition.FlowCorrectionEngine
import com.iaido.core.recognition.FlowWord
import com.iaido.core.recognition.NgramContextScorer
import com.iaido.core.recognition.NgramScoreStore
import com.iaido.core.recognition.ScoredCandidate
import com.iaido.core.recognition.SegmentationOption
import com.iaido.core.recognition.SessionCorrectionHistory
import com.iaido.core.recognition.SuggestionChip
import com.iaido.core.typing.SpacingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

class IaidoInputMethodService : InputMethodService() {
    private var composeInputView: ComposeView? = null
    private val inputMethodLifecycleOwner = InputMethodLifecycleOwner()
    private var sessionId = 0
    private val languageSwitcher = LanguageSwitcher()
    private var activeLanguage = Language.ENGLISH
    internal var spacingModeForTypingCoordinator = SpacingMode.INFER_SPACES
        private set
    private val commandMode = CommandModeController(::executeCommand)
    private val commandDispatcher = CommandGestureDispatcher(CommandBindingSet(), ::executeCommand)
    private val correctionHistory = SessionCorrectionHistory()
    private val sessionChips = mutableStateOf<List<SuggestionChip>>(emptyList())
    private val splitPreview = mutableStateOf<String?>(null)
    private val pendingManualEdit = mutableStateOf<ManualEditCandidate?>(null)
    private val editorTextChangeDetector = EditorTextChangeDetector()
    private var textObservationEnabled = false
    private var visibleWordIds: List<Int> = emptyList()
    private var cursorPosition = 0
    private var pendingCandidates: List<String>? = null
    private var lastDeletedWord: String? = null
    private var backspaceSwipeText = ""
    private var backspaceSwipeCursor = 0
    private var backspaceSwipeDeletedCount = 0
    private var inferenceReplacementInProgress = false
    private val correctionExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val splitGraceHandler = Handler(Looper.getMainLooper())
    private val runtimeCandidateRanker by lazy {
        RuntimeCandidateRanker(
            CoreEngineDexLoader(
                applicationContext,
                CoreEngineUpdateStore(java.io.File(applicationContext.filesDir, "core-engine-updates")),
            ),
        )
    }
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
        scoreStore = object : NgramScoreStore {
            override fun bigram(previous: String, next: String): Double =
                ngramStores.store(activeLanguage).bigram(previous, next)

            override fun trigram(first: String, second: String, next: String): Double =
                ngramStores.store(activeLanguage).trigram(first, second, next)
        },
    )
    private val flowCorrectionEngine = FlowCorrectionEngine(contextScorer)
    private val learningDictionary by lazy { PersonalDictionary(dictionaryRepository.words()) }
    private val learningDatabase = lazy {
        Room.databaseBuilder(applicationContext, PersonalDictionaryDatabase::class.java, "personal_dictionary.db")
            .addMigrations(PERSONAL_DICTIONARY_MIGRATION_1_2)
            .build()
    }
    private val learningRepository by lazy {
        RoomPersonalDictionaryRepository(learningDatabase.value.overrides(), dictionaryRepository.words())
    }

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

    private val ngramStores by lazy {
        AssetNgramScoreStoreRepository(
            loadAsset = { name -> assets.open(name).use { it.readBytes() } },
            dictionary = { language ->
                if (language == Language.ENGLISH) dictionaryRepository.words()
                else hebrewDictionaryRepository.words()
            },
        )
    }

    private val controller by lazy {
        SwipeCommitController(
            recognizer = GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer(), contextScorer),
            dictionary = dictionaryRepository.words(),
            commitText = typingController::commitWord,
            onRecognized = ::rememberCandidates,
            runtimeRanker = runtimeCandidateRanker::rank,
        )
    }

    private val swipeTypingCoordinator by lazy {
        SwipeTypingCoordinator(
            spacingMode = { spacingModeForTypingCoordinator },
            recognize = { path, layout -> controller.recognize(path, layout, activeDictionary()) },
            dictionary = ::activeDictionary,
            previousWords = { correctionHistory.words().takeLast(3).map { it.current } },
            cursorPosition = { cursorPosition },
            replaceHostSpan = ::replaceInferenceHostSpan,
            commitCompletedText = typingController::commitWord,
            onFinalizedWords = ::recordFinalizedInferenceWords,
            hasFollowingWhitespace = {
                currentInputConnection?.getTextAfterCursor(1, 0)?.firstOrNull()?.isWhitespace() == true
            },
            pollSplitParts = splitController::pollParts,
            isSplitPending = splitController::isPending,
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
        loadSpacingMode()
        correctionExecutor.execute {
            val persisted = learningRepository.entries()
            mainHandler.post { learningDictionary.restore(persisted) }
        }
        correctionExecutor.execute { CoreEngineUpdateClient(applicationContext).checkAndInstall() }
        window.window?.decorView?.apply {
            setViewTreeLifecycleOwner(inputMethodLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(inputMethodLifecycleOwner)
        }
    }

    override fun onCreateInputView(): View {
        loadSpacingMode()
        return ComposeView(this).also { view ->
            inputMethodLifecycleOwner.onStartInputView()
            view.setViewTreeLifecycleOwner(inputMethodLifecycleOwner)
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            composeInputView = view
            renderInputView(view)
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        swipeTypingCoordinator.onNonSwipeInput()
        super.onStartInputView(info, restarting)
        loadSpacingMode()
        info?.hintLocales = LocaleList.forLanguageTags(activeLanguage.localeTag)
        sessionId += 1
        correctionHistory.clear()
        sessionChips.value = emptyList()
        splitPreview.value = null
        pendingManualEdit.value = null
        visibleWordIds = emptyList()
        pendingCandidates = null
        lastDeletedWord = null
        splitController.cancel()
        splitGraceHandler.removeCallbacksAndMessages(null)
        textObservationEnabled = info?.let(::supportsTextObservation) == true
        cursorPosition = currentInputConnection
            ?.getExtractedText(extractedTextRequest(), InputConnection.GET_EXTRACTED_TEXT_MONITOR)
            ?.also(::resetEditorSnapshot)
            ?.let { it.startOffset + it.selectionStart } ?: 0
        if (!textObservationEnabled) editorTextChangeDetector.reset(EditorSnapshot("", 0, 0))
        composeInputView?.let(::renderInputView)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        swipeTypingCoordinator.onNonSwipeInput()
        super.onFinishInputView(finishingInput)
        inputMethodLifecycleOwner.onFinishInputView()
        sessionId += 1
        correctionHistory.clear()
        sessionChips.value = emptyList()
        splitPreview.value = null
        pendingManualEdit.value = null
        visibleWordIds = emptyList()
        pendingCandidates = null
        lastDeletedWord = null
        splitController.cancel()
        splitGraceHandler.removeCallbacksAndMessages(null)
        textObservationEnabled = false
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
        if (!inferenceReplacementInProgress && (newSelStart != cursorPosition || newSelEnd != newSelStart)) {
            swipeTypingCoordinator.onCursorMoved()
        }
        cursorPosition = newSelStart
        observeCurrentEditorText()
        refreshSuggestionChips()
    }

    override fun onUpdateExtractedText(token: Int, text: ExtractedText) {
        super.onUpdateExtractedText(token, text)
        if (textObservationEnabled) observeExtractedText(text)
    }

    override fun onDestroy() {
        inputMethodLifecycleOwner.onDestroy()
        correctionExecutor.shutdownNow()
        learningDatabase.value.close()
        splitGraceHandler.removeCallbacksAndMessages(null)
        composeInputView = null
        super.onDestroy()
    }

    private fun renderInputView(view: ComposeView) {
        val currentSession = sessionId
        view.setContent {
            IaidoTheme {
                KeyboardInputView(
                    sessionId = currentSession,
                    onSwipe = { path, layout ->
                        val expectedSession = sessionId
                        val language = activeLanguage
                        val dictionary = activeDictionary()
                        correctionExecutor.execute {
                            val results = controller.recognize(path, layout, dictionary)
                            mainHandler.post {
                                if (sessionId != expectedSession || activeLanguage != language) return@post
                                if (results.isEmpty()) {
                                    swipeTypingCoordinator.onRecognitionFailed()
                                    return@post
                                }
                                rememberCandidatesForCommittedSwipe(results)
                                swipeTypingCoordinator.onRecognizedSingleSwipe(path, results)
                            }
                        }
                    },
                    onTap = { value ->
                        swipeTypingCoordinator.onNonSwipeInput()
                        when (value) {
                            "⌫" -> typingController.backspace()
                            "🌐" -> switchLanguage()
                            else -> typingController.tap(value)
                        }
                    },
                    onFlick = { letter, direction ->
                        swipeTypingCoordinator.onNonSwipeInput()
                        typingController.flick(letter, direction)
                    },
                    onPunctuationToSpace = { punctuation ->
                        swipeTypingCoordinator.onNonSwipeInput()
                        typingController.punctuationToSpace(punctuation)
                    },
                    language = activeLanguage,
                    onLanguageSwitch = ::switchLanguage,
                    onCommand = { trigger ->
                        swipeTypingCoordinator.onNonSwipeInput()
                        handleCommand(trigger)
                    },
                    suggestionChips = sessionChips.value,
                    onSuggestionRelease = ::releaseSuggestion,
                    onSuggestionUndo = ::undoSuggestion,
                    onBackspaceRepeat = {
                        swipeTypingCoordinator.onNonSwipeInput()
                        typingController.backspace()
                    },
                    onBackspacePressStart = {
                        swipeTypingCoordinator.onNonSwipeInput()
                        beginBackspaceSwipe()
                    },
                    onBackspaceSwipeStart = {
                        swipeTypingCoordinator.onNonSwipeInput()
                        beginBackspaceSwipe()
                    },
                    onBackspaceSwipeDistance = ::updateBackspaceSwipe,
                    onBackspaceSwipeEnd = ::finishBackspaceSwipe,
                    onBackspaceSwipeCancel = ::cancelBackspaceSwipe,
                    onBackspaceUndo = ::undoBackspace,
                    onBackspaceRedo = ::redoBackspace,
                    onSplitBegin = splitController::begin,
                    onSplitMove = splitController::move,
                    onSplitEnd = { pointerId, path, layout, atMs ->
                        splitController.finish(pointerId, path, layout, atMs)
                        val expectedSession = sessionId
                        val expectedLanguage = activeLanguage
                        splitGraceHandler.postDelayed({
                            if (sessionId != expectedSession || activeLanguage != expectedLanguage) return@postDelayed
                            val parts = when (val result = swipeTypingCoordinator.poll(System.currentTimeMillis())) {
                                is SplitPollOutcome.Resolved -> result.parts
                                SplitPollOutcome.Pending -> return@postDelayed
                                SplitPollOutcome.Cancelled -> {
                                    swipeTypingCoordinator.onRecognitionFailed()
                                    splitPreview.value = null
                                    return@postDelayed
                                }
                            }
                            val dictionary = activeDictionary()
                            correctionExecutor.execute {
                                val candidates = parts.paths.map { part -> controller.recognize(part, layout, dictionary) }
                                mainHandler.post {
                                    if (sessionId != expectedSession || activeLanguage != expectedLanguage) return@post
                                    if (candidates.any { it.isEmpty() }) {
                                        swipeTypingCoordinator.onRecognitionFailed()
                                        splitPreview.value = null
                                        return@post
                                    }
                                    rememberCandidatesForCommittedSwipe(candidates.flatten())
                                    if (parts.paths.size == 1) {
                                        swipeTypingCoordinator.onRecognizedSingleSwipe(parts.paths.single(), candidates.single())
                                    } else {
                                        swipeTypingCoordinator.onRecognizedTwoFingerResult(parts.paths, candidates)
                                    }
                                    splitPreview.value = null
                                }
                            }
                        }, 351L)
                    },
                    onSplitCancel = {
                        splitController.cancel()
                        swipeTypingCoordinator.onRecognitionFailed()
                        splitPreview.value = null
                    },
                    splitPreview = splitPreview.value,
                    onSplitPreview = { preview -> splitPreview.value = preview },
                    manualEditCandidate = pendingManualEdit.value,
                    onConfirmManualEdit = ::confirmManualEdit,
                    onDismissManualEdit = { pendingManualEdit.value = null },
                )
            }
        }
    }

    private fun loadSpacingMode() {
        correctionExecutor.execute {
            val storedValue = runBlocking { applicationContext.settingsStore.data.first()[spacingModeKey] }
            val resolvedMode = spacingModeFromStoredValue(storedValue)
            mainHandler.post { spacingModeForTypingCoordinator = resolvedMode }
        }
    }

    private fun rememberCandidates(results: List<ScoredCandidate>) {
        pendingCandidates = results.take(5).map { it.word.word }
    }

    private fun rememberCandidatesForCommittedSwipe(results: List<ScoredCandidate>) {
        if (spacingModeForTypingCoordinator == SpacingMode.INFER_SPACES) {
            pendingCandidates = null
        } else {
            rememberCandidates(results)
        }
    }

    private fun activeDictionary() = if (activeLanguage == Language.ENGLISH) learningDictionary.entries()
    else hebrewDictionaryRepository.words()

    private fun replaceInferenceHostSpan(span: HostTextSpan, replacement: String): Boolean {
        val inputConnection = currentInputConnection ?: return false
        inferenceReplacementInProgress = true
        try {
            if (!inputConnection.setSelection(span.start, span.end)) return false
            if (textObservationEnabled) editorTextChangeDetector.expectOwnEdit(span.start, span.end, replacement)
            if (!inputConnection.commitText(replacement, 1)) return false
            cursorPosition = span.start + replacement.length
            inputConnection.setSelection(cursorPosition, cursorPosition)
            return true
        } finally {
            inferenceReplacementInProgress = false
        }
    }

    private fun recordFinalizedInferenceWords(
        span: HostTextSpan,
        words: List<String>,
        alternatives: List<SegmentationOption>,
    ) {
        pendingCandidates = null
        correctionHistory.deleteRange(span.start, span.end)
        val candidatesByWord = inferenceWordCandidates(words, alternatives)
        var start = span.start
        words.forEachIndexed { index, word ->
            correctionHistory.record(start, start + word.length, word, candidatesByWord[index])
            start += word.length + 1
        }
        refreshSuggestionChips()
    }

    private fun commitText(text: String) {
        val inputConnection = currentInputConnection ?: return
        val start = cursorPosition
        if (textObservationEnabled) editorTextChangeDetector.expectOwnEdit(start, start, text)
        if (!inputConnection.commitText(text, 1)) return
        cursorPosition = start + text.length
        val deletedWord = lastDeletedWord
        lastDeletedWord = null
        if (deletedWord != null && activeLanguage == Language.ENGLISH && text.isNotBlank()) {
            recordLearning(
                signal = LearningSignal.DELETE_RETYPE,
                original = deletedWord,
                replacement = text,
            )
        }
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
        if (textObservationEnabled) {
            editorTextChangeDetector.expectOwnEdit(
                start = (oldCursor - count).coerceAtLeast(0),
                end = oldCursor,
                replacement = "",
            )
        }
        if (!inputConnection.deleteSurroundingText(count, 0)) return
        lastDeletedWord = correctionHistory.words().firstOrNull { it.end == oldCursor }?.current
        val start = (oldCursor - count).coerceAtLeast(0)
        correctionHistory.deleteRange(start, oldCursor)
        cursorPosition = start
        refreshSuggestionChips()
    }

    private fun beginBackspaceSwipe() {
        if (backspaceSwipeText.isNotEmpty() || backspaceSwipeCursor > 0) {
            val currentBeforeCursor = currentInputConnection
                ?.getTextBeforeCursor(MAX_BACKSPACE_SWIPE_CHARS, 0)
                ?.toString()
                .orEmpty()
            backspaceSwipeDeletedCount = (backspaceSwipeText.length - currentBeforeCursor.length)
                .coerceIn(0, backspaceSwipeText.length)
            return
        }
        val inputConnection = currentInputConnection ?: return
        backspaceSwipeText = inputConnection
            .getTextBeforeCursor(MAX_BACKSPACE_SWIPE_CHARS, 0)
            ?.toString()
            .orEmpty()
        backspaceSwipeCursor = cursorPosition
        backspaceSwipeDeletedCount = 0
    }

    private fun updateBackspaceSwipe(requestedCharacters: Int) {
        val inputConnection = currentInputConnection ?: return
        val target = deletionCountForSwipe(
            requestedCount = requestedCharacters,
            textBeforeCursor = backspaceSwipeText,
            maxCharacters = MAX_BACKSPACE_SWIPE_CHARS,
        )
        val delta = target - backspaceSwipeDeletedCount
        if (delta == 0) return

        val currentCursor = backspaceSwipeCursor - backspaceSwipeDeletedCount
        if (!inputConnection.setSelection(currentCursor, currentCursor)) return
        if (delta > 0) {
            if (textObservationEnabled) {
                editorTextChangeDetector.expectOwnEdit(currentCursor - delta, currentCursor, "")
            }
            if (!inputConnection.deleteSurroundingText(delta, 0)) return
        } else {
            val restoreStart = backspaceSwipeText.length - backspaceSwipeDeletedCount
            val restoreEnd = backspaceSwipeText.length - target
            val restored = backspaceSwipeText.substring(restoreStart, restoreEnd)
            if (textObservationEnabled) {
                editorTextChangeDetector.expectOwnEdit(currentCursor, currentCursor, restored)
            }
            if (!inputConnection.commitText(restored, 1)) return
        }
        backspaceSwipeDeletedCount = target
        cursorPosition = backspaceSwipeCursor - target
    }

    private fun finishBackspaceSwipe() {
        if (backspaceSwipeDeletedCount > 0) {
            correctionHistory.deleteRange(
                backspaceSwipeCursor - backspaceSwipeDeletedCount,
                backspaceSwipeCursor,
            )
            refreshSuggestionChips()
        }
        clearBackspaceSwipe()
    }

    private fun cancelBackspaceSwipe() {
        updateBackspaceSwipe(0)
        clearBackspaceSwipe()
        refreshSuggestionChips()
    }

    private fun clearBackspaceSwipe() {
        backspaceSwipeText = ""
        backspaceSwipeCursor = 0
        backspaceSwipeDeletedCount = 0
    }

    private fun undoBackspace() {
        currentInputConnection?.performContextMenuAction(android.R.id.undo)
    }

    private fun redoBackspace() {
        currentInputConnection?.performContextMenuAction(android.R.id.redo)
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
                        recordLearning(
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
        sessionChips.value = words.map { word ->
            SuggestionChip(
                word = word.current,
                alternatives = word.candidates,
                selectedIndex = word.candidates.indexOf(word.current).coerceAtLeast(0),
                corrected = word.corrected,
                id = word.id,
            )
        }
    }

    private fun releaseSuggestion(displayIndex: Int, candidateIndex: Int) {
        val word = wordForDisplayIndex(displayIndex) ?: return
        val replacement = word.candidates.getOrNull(candidateIndex) ?: return
        val changed = replaceSessionWord(word.id, replacement)
        if (changed && candidateIndex > 0 && activeLanguage == Language.ENGLISH) {
            recordLearning(
                signal = LearningSignal.SUGGESTION_PICK,
                original = word.current,
                replacement = replacement,
            )
        }
    }

    private fun undoSuggestion(displayIndex: Int) {
        val word = wordForDisplayIndex(displayIndex) ?: return
        if (!word.corrected) return
        if (replaceSessionWord(word.id, word.original)) {
            recordLearning(
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
        if (textObservationEnabled) editorTextChangeDetector.expectOwnEdit(word.start, word.end, replacement)
        if (!inputConnection.commitText(replacement, 1)) return false
        correctionHistory.replace(id, replacement)
        val delta = replacement.length - word.current.length
        cursorPosition = if (preserveCursor && word.end <= oldCursor) oldCursor + delta
        else word.start + replacement.length
        inputConnection.setSelection(cursorPosition, cursorPosition)
        refreshSuggestionChips()
        return true
    }

    private fun recordLearning(
        signal: LearningSignal,
        original: String? = null,
        replacement: String,
    ) {
        learningDictionary.record(signal, original, replacement)
        correctionExecutor.execute {
            learningRepository.record(signal, original, replacement)
        }
    }

    private fun confirmManualEdit() {
        val candidate = pendingManualEdit.value ?: return
        if (candidate.original != candidate.replacement) {
            recordLearning(
                signal = LearningSignal.MANUAL_EDIT,
                original = candidate.original,
                replacement = candidate.replacement,
            )
        }
        pendingManualEdit.value = null
    }

    private fun observeCurrentEditorText() {
        if (!textObservationEnabled) return
        currentInputConnection
            ?.getExtractedText(extractedTextRequest(), InputConnection.GET_EXTRACTED_TEXT_MONITOR)
            ?.let(::observeExtractedText)
    }

    private fun observeExtractedText(text: ExtractedText) {
        observeEditorSnapshot(
            EditorSnapshot(
                text = text.text?.toString().orEmpty(),
                selectionStart = text.startOffset + text.selectionStart,
                selectionEnd = text.startOffset + text.selectionEnd,
                offset = text.startOffset,
            ),
        )
    }

    private fun resetEditorSnapshot(text: ExtractedText) {
        editorTextChangeDetector.reset(
            EditorSnapshot(
                text = text.text?.toString().orEmpty(),
                selectionStart = text.startOffset + text.selectionStart,
                selectionEnd = text.startOffset + text.selectionEnd,
                offset = text.startOffset,
            ),
        )
    }

    private fun observeEditorSnapshot(snapshot: EditorSnapshot) {
        editorTextChangeDetector.observe(snapshot)?.let { candidate ->
            swipeTypingCoordinator.onExternalEdit()
            if (pendingManualEdit.value == null) pendingManualEdit.value = candidate
        }
        cursorPosition = snapshot.selectionStart
    }

    private fun extractedTextRequest() = ExtractedTextRequest().apply {
        token = TEXT_MONITOR_TOKEN
        flags = 0
        hintMaxChars = 10_000
    }

    private fun supportsTextObservation(info: android.view.inputmethod.EditorInfo): Boolean {
        val inputType = info.inputType
        if (inputType == InputType.TYPE_NULL) return false
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return inputType and InputType.TYPE_MASK_VARIATION !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
    }

    private companion object {
        const val TEXT_MONITOR_TOKEN = 0x4E4B
        const val MAX_BACKSPACE_SWIPE_CHARS = 60
    }

    private fun switchLanguage() {
        swipeTypingCoordinator.onNonSwipeInput()
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
