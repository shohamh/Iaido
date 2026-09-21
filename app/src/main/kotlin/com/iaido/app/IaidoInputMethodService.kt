package com.iaido.app

import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import com.iaido.core.recognition.InferenceSegmenter
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
import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.ScoredCandidate
import com.iaido.core.recognition.SegmentationOption
import com.iaido.core.recognition.SentenceCandidateReranker
import com.iaido.core.recognition.SessionCorrectionHistory
import com.iaido.core.recognition.SessionCorrectionHistorySnapshot
import com.iaido.core.recognition.SuggestionChip
import com.iaido.core.state.TypingSessionSnapshot
import com.iaido.core.typing.SpacingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * How many nearby words are considered when deriving cursor-local join candidates. All recorded
 * sentence words remain addressable in the suggestion strip; this bound only limits join lookup.
 */
internal const val VISIBLE_HISTORY_WORDS = 4

private data class CapturedEditorEdit(
    val snapshot: EditorSnapshot,
    val sourceText: String,
)

class IaidoInputMethodService : InputMethodService() {
    private var composeInputView: ComposeView? = null
    private val inputMethodLifecycleOwner = InputMethodLifecycleOwner()
    private var sessionId = 0
    /**
     * Opt-in research capture (Task 9). [researchTraceRecorder] is the service's single in-memory
     * touch-trace buffer, handed to every rendered keyboard view; it resets itself on every
     * `finish`, so one instance covers the whole service lifetime. [researchTraceId] is the most
     * recent typing gesture's trace id, attached to a later correction of the text that gesture
     * produced - never fabricated, and cleared at session boundaries.
     */
    private val researchTraceRecorder = ResearchTraceRecorder(
        enabled = { ResearchCorrectionRecorderProvider.isEnabled() },
    )
    private var researchTraceId: String? = null
    private val languageSwitcher = LanguageSwitcher()
    private val activeLanguageState = mutableStateOf(Language.ENGLISH)
    private var activeLanguage: Language
        get() = activeLanguageState.value
        set(value) {
            activeLanguageState.value = value
        }
    internal var spacingModeForTypingCoordinator = SpacingMode.INFER_SPACES
        private set
    private var splitGraceWindowMs = SettingsDefaults.GRACE_WINDOW_MS.toLong()
    private val commandBindings = CommandBindingSet()
    private val commandMode = CommandModeController(::executeCommand)
    private val commandDispatcher = CommandGestureDispatcher(commandBindings, ::executeCommand)
    private val correctionHistory = SessionCorrectionHistory()
    private val sentenceEditHistory = SentenceEditHistory()
    private val sessionChips = mutableStateOf<List<SuggestionChip>>(emptyList())
    private val replacementOptions = mutableStateOf<List<ReplacementOption>>(emptyList())
    private val sentenceStripState = mutableStateOf(SentenceTextModel.emptyState(Language.ENGLISH))
    private val sentenceStripActions = object : SentenceStripActions {
        override fun setSelection(start: Int, endExclusive: Int): Boolean {
            val connection = currentInputConnection ?: return false
            if (start < 0 || endExclusive < start || !connection.setSelection(start, endExclusive)) return false
            cursorPosition = start
            selectionEndPosition = endExclusive
            sentenceEditHistory.closeGroup()
            latestEditorSnapshot?.let { snapshot ->
                latestEditorSnapshot = snapshot.copy(selectionStart = start, selectionEnd = endExclusive)
            }
            publishSentenceStripState()
            return true
        }

        override fun commitWordReplacement(wordId: String, replacement: String): Boolean {
            val word = sentenceStripState.value.words.firstOrNull { it.id == wordId } ?: return false
            val sessionWordId = wordId.substringAfter("session:", "").toIntOrNull()
            return if (sessionWordId != null) {
                replaceSessionWord(sessionWordId, replacement)
            } else {
                applyEditorReplacement(
                    start = word.start,
                    endExclusive = word.endExclusive,
                    replacement = replacement,
                    kind = SentenceEditKind.CORRECTION,
                    expectedSourceText = word.text,
                )
            }
        }

        override fun commitReplacement(optionId: String): Boolean {
            val replacement = sentenceStripState.value.replacementOptions
                .firstOrNull { it.option.id == optionId }?.option ?: return false
            return releaseReplacementOption(replacement)
        }

        override fun commitDeletion(start: Int, endExclusive: Int): Boolean {
            if (start < 0 || endExclusive <= start) return false
            val changed = applyEditorReplacement(
                start = start,
                endExclusive = endExclusive,
                replacement = "",
                kind = SentenceEditKind.DELETION,
                cursorAfter = start,
            )
            if (changed) {
                correctionHistory.deleteRange(start, endExclusive)
                typedWords.reset()
                refreshSuggestionChips()
            }
            return changed
        }

        override fun undo(): Boolean = undoSentenceEdit()

        override fun redo(): Boolean = redoSentenceEdit()
    }
    private var latestEditorSnapshot: EditorSnapshot? = null
    private val showCandidateScores = mutableStateOf(false)
    // Ids of replacement options currently produced by the live SwipeTypingCoordinator
    // transaction, as opposed to history-derived joins -- used only to decide whether a
    // join-shaped option still belongs in the edge-anchored ReplacementReelSlot (a still-live join
    // must stay there even once a matching chip pair also exists; see SuggestionStrip.kt).
    private val liveReplacementOptionIds = mutableStateOf<Set<String>>(emptySet())
    private val splitPreview = mutableStateOf<String?>(null)
    private val pendingManualEdit = mutableStateOf<ManualEditCandidate?>(null)
    private val editorTextChangeDetector = EditorTextChangeDetector()
    private var textObservationEnabled = false
    /** Tracks the word being typed so it becomes an addressable session word like a swiped one. */
    private val typedWords = TypedWordTracker()
    private var visibleWordIds: List<Int> = emptyList()
    private val focusedWordId = mutableStateOf<Int?>(null)
    // Recomputed only when correctionHistory actually changes (inside refreshSuggestionChips()),
    // not on every mergedReplacementOptions() call -- a live reel-drag preview fires
    // onReplacementOptionsChanged on every frame but never touches correctionHistory, so
    // recomputing this (a full activeDictionary() rebuild plus a fresh lowercase index) on every
    // preview frame would be a hot-path regression with no correctness benefit.
    private var cachedHistoryJoinCandidates: List<ReplacementOption> = emptyList()
    private var cursorPosition = 0
    private var selectionEndPosition = 0
    private var pendingCandidates: List<String>? = null
    private var lastDeletedWord: String? = null
    private var backspaceSwipeText = ""
    private var backspaceSwipeCursor = 0
    private var backspaceSwipeDeletedCount = 0
    private var backspaceSwipeInitialSnapshot: EditorSnapshot? = null
    private val inferenceSelectionGuard = InferenceSelectionGuard()
    private val correctionExecutor = Executors.newSingleThreadExecutor()
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val splitGraceHandler = Handler(Looper.getMainLooper())
    private val splitPollLifecycle = SplitGesturePollLifecycle()
    private val runtimeState = KeyboardRuntimeState()
    private val runtimeReadiness = KeyboardRuntimeReadiness()
    private var activeRuntimeRevision: Long? = null
    private var pendingDebugRestoreId: Long? = null
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
            dictionary = ::activeDictionary,
            commitText = typingController::commitWord,
        )
    }
    private val contextScorer = NgramContextScorer(
        windowSize = 3,
        scoreStore = object : NgramScoreStore {
            override fun bigram(previous: String, next: String): Double =
                debugAutoSpaceFixture()?.bigram(previous, next)
                    ?: ngramStores.store(activeLanguage).bigram(previous, next)

            override fun trigram(first: String, second: String, next: String): Double =
                debugAutoSpaceFixture()?.trigram(first, second, next)
                    ?: ngramStores.store(activeLanguage).trigram(first, second, next)
        },
    )
    private val sentenceCandidateReranker = SentenceCandidateReranker(contextScorer)
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
            segmenter = InferenceSegmenter(contextScorer = contextScorer),
            spacingMode = { spacingModeForTypingCoordinator },
            recognize = { path, layout -> controller.recognize(path, layout, activeDictionary()) },
            dictionary = ::activeDictionary,
            previousWords = { correctionHistory.words().takeLast(3).map { it.current } },
            cursorPosition = { cursorPosition },
            replaceHostSpan = ::replaceInferenceHostSpan,
            commitCompletedText = typingController::commitWord,
            onFinalizedWords = ::recordFinalizedInferenceWords,
            textBeforeCursor = { currentInputConnection?.getTextBeforeCursor(100, 0)?.toString().orEmpty() },
            hasFollowingWhitespace = {
                currentInputConnection?.getTextAfterCursor(1, 0)?.firstOrNull()?.isWhitespace() == true
            },
            pollSplitParts = splitController::pollParts,
            isSplitPending = splitController::isPending,
            onReplacementOptionsChanged = { options ->
                liveReplacementOptionIds.value = options.map { it.id }.toSet()
                val merged = mergedReplacementOptions(options)
                replacementOptions.value = merged
                publishSentenceStripState(merged)
            },
            onInferenceTransactionFinished = sentenceEditHistory::closeGroup,
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
        observeKeyboardSettings()
        if (isDebugBuild()) observeDebugStateRequests()
        correctionExecutor.execute {
            val persisted = learningRepository.entries()
            mainHandler.post { learningDictionary.restore(persisted) }
        }
        correctionExecutor.execute { CoreEngineUpdateClient(applicationContext).checkAndInstall() }
        runCatching { ResearchCorrectionRecorderProvider.initialize(applicationContext) }
        window.window?.decorView?.apply {
            setViewTreeLifecycleOwner(inputMethodLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(inputMethodLifecycleOwner)
        }
    }

    override fun onCreateInputView(): View {
        return ComposeView(this).also { view ->
            if (activeRuntimeRevision == null) beginRuntimeRevision()
            inputMethodLifecycleOwner.onStartInputView()
            view.setViewTreeLifecycleOwner(inputMethodLifecycleOwner)
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            composeInputView = view
            renderInputView(view)
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        DiagnosticsTelemetryProvider.instance?.recordImeSessionStart()
        swipeTypingCoordinator.onNonSwipeInput()
        super.onStartInputView(info, restarting)
        val runtimeRevision = beginRuntimeRevision()
        if (currentInputConnection != null) runtimeReadiness.markInputConnectionBound(runtimeRevision)
        info?.hintLocales = LocaleList.forLanguageTags(activeLanguage.localeTag)
        sessionId += 1
        researchTraceId = null
        typedWords.reset()
        correctionHistory.clear()
        sentenceEditHistory.clear()
        sessionChips.value = emptyList()
        sentenceStripState.value = SentenceTextModel.emptyState(activeLanguage)
        latestEditorSnapshot = null
        splitPreview.value = null
        pendingManualEdit.value = null
        visibleWordIds = emptyList()
        focusedWordId.value = null
        pendingCandidates = null
        lastDeletedWord = null
        splitController.cancel()
        splitGraceHandler.removeCallbacksAndMessages(null)
        inferenceSelectionGuard.clear()
        textObservationEnabled = info?.let(::supportsTextObservation) == true
        if (textObservationEnabled) {
            val extractedText = currentInputConnection
                ?.getExtractedText(extractedTextRequest(), InputConnection.GET_EXTRACTED_TEXT_MONITOR)
            if (extractedText != null) {
                resetEditorSnapshot(extractedText)
                cursorPosition = extractedText.startOffset + extractedText.selectionStart
                selectionEndPosition = extractedText.startOffset + extractedText.selectionEnd
            } else {
                cursorPosition = info?.initialSelStart?.coerceAtLeast(0) ?: 0
                selectionEndPosition = info?.initialSelEnd?.takeIf { it >= 0 } ?: cursorPosition
                editorTextChangeDetector.reset(EditorSnapshot("", cursorPosition, selectionEndPosition))
            }
        } else {
            // Password and non-text fields must not be read for the strip, even for an initial
            // snapshot. Keep only the framework-provided cursor location.
            cursorPosition = info?.initialSelStart?.coerceAtLeast(0) ?: 0
            selectionEndPosition = info?.initialSelEnd?.takeIf { it >= 0 } ?: cursorPosition
            editorTextChangeDetector.reset(EditorSnapshot("", cursorPosition, selectionEndPosition))
        }
        refreshSuggestionChips()
        composeInputView?.let(::renderInputView)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        DiagnosticsTelemetryProvider.instance?.let {
            it.recordImeSessionFinish()
            it.flush()
        }
        swipeTypingCoordinator.onNonSwipeInput()
        super.onFinishInputView(finishingInput)
        inputMethodLifecycleOwner.onFinishInputView()
        sessionId += 1
        researchTraceId = null
        typedWords.reset()
        correctionHistory.clear()
        sentenceEditHistory.clear()
        sessionChips.value = emptyList()
        sentenceStripState.value = SentenceTextModel.emptyState(activeLanguage)
        latestEditorSnapshot = null
        splitPreview.value = null
        pendingManualEdit.value = null
        visibleWordIds = emptyList()
        focusedWordId.value = null
        pendingCandidates = null
        lastDeletedWord = null
        splitController.cancel()
        splitGraceHandler.removeCallbacksAndMessages(null)
        inferenceSelectionGuard.clear()
        textObservationEnabled = false
        invalidateRuntimeReadiness()
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
        val suppressedAsInferenceReplacement = inferenceSelectionGuard.shouldSuppress(
            newSelStart,
            newSelEnd,
            timeoutMs = INFERENCE_SELECTION_GUARD_TIMEOUT_MS,
        )
        if (!suppressedAsInferenceReplacement && (newSelStart != cursorPosition || newSelEnd != newSelStart)) {
            swipeTypingCoordinator.onCursorMoved()
            sentenceEditHistory.closeGroup()
        }
        cursorPosition = newSelStart
        selectionEndPosition = newSelEnd
        latestEditorSnapshot = latestEditorSnapshot?.copy(
            selectionStart = newSelStart,
            selectionEnd = newSelEnd,
        )
        observeCurrentEditorText()
        refreshSuggestionChips()
    }

    override fun onUpdateExtractedText(token: Int, text: ExtractedText) {
        super.onUpdateExtractedText(token, text)
        if (textObservationEnabled) {
            observeExtractedText(text)
            refreshSuggestionChips()
        }
    }

    override fun onDestroy() {
        inputMethodLifecycleOwner.onDestroy()
        settingsScope.cancel()
        correctionExecutor.shutdownNow()
        learningDatabase.value.close()
        splitGraceHandler.removeCallbacksAndMessages(null)
        composeInputView = null
        invalidateRuntimeReadiness()
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
                                    trackGesture(DiagnosticsGestureKind.SWIPE, DiagnosticsOutcome.REJECTED)
                                    swipeTypingCoordinator.onRecognitionFailed()
                                    return@post
                                }
                                trackGesture(DiagnosticsGestureKind.SWIPE, DiagnosticsOutcome.ACCEPTED)
                                rememberCandidatesForCommittedSwipe(results)
                                swipeTypingCoordinator.onRecognizedSingleSwipe(path, results)
                                typingController.markSwipeCommitted()
                            }
                        }
                    },
                        onTap = { value ->
                            swipeTypingCoordinator.onNonSwipeInput()
                            when (value) {
                                "⌫" -> typingController.backspace()
                                SETTINGS_KEY -> openSettings()
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
                        trackGesture(DiagnosticsGestureKind.PUNCTUATION, DiagnosticsOutcome.ACCEPTED)
                        typingController.punctuationToSpace(punctuation)
                    },
                    language = activeLanguage,
                    onLanguageSwitch = ::switchLanguage,
                    onCommand = { trigger ->
                        swipeTypingCoordinator.onNonSwipeInput()
                        trackGesture(DiagnosticsGestureKind.COMMAND, DiagnosticsOutcome.ACCEPTED)
                        handleCommand(trigger)
                    },
                    suggestionChips = sessionChips.value,
                    focusedChipId = focusedWordId.value,
                    replacementOptions = replacementOptions.value,
                    sentenceStripState = sentenceStripState.value,
                    sentenceStripActions = sentenceStripActions,
                    liveReplacementOptionIds = liveReplacementOptionIds.value,
                    showCandidateScores = showCandidateScores.value,
                    onSuggestionRelease = ::releaseSuggestion,
                    onSuggestionUndo = ::undoSuggestion,
                    onReplacementPreview = swipeTypingCoordinator::previewReplacement,
                    onReplacementRelease = ::releaseReplacementOption,
                    onReplacementCancel = swipeTypingCoordinator::cancelReplacement,
                    onBackspaceRepeat = { deleteWord ->
                        swipeTypingCoordinator.onNonSwipeInput()
                        typingController.backspace(singleTap = false, deleteWord = deleteWord)
                    },
                    onBackspacePressStart = {
                        swipeTypingCoordinator.onNonSwipeInput()
                        beginBackspaceSwipe()
                    },
                    onBackspaceSwipeStart = {
                        swipeTypingCoordinator.onNonSwipeInput()
                        typingController.markNonSwipeInput()
                        beginBackspaceSwipe()
                    },
                    onBackspaceSwipeDistance = ::updateBackspaceSwipe,
                    onBackspaceSwipeEnd = ::finishBackspaceSwipe,
                    onBackspaceSwipeCancel = ::cancelBackspaceSwipe,
                    onBackspaceUndo = {
                        typingController.markNonSwipeInput()
                        undoBackspace()
                    },
                    onBackspaceRedo = {
                        typingController.markNonSwipeInput()
                        redoBackspace()
                    },
                    onSplitGestureStart = {
                        splitPollLifecycle.startGesture()
                        splitGraceHandler.removeCallbacksAndMessages(null)
                        splitController.cancel()
                    },
                    onSplitBegin = splitController::begin,
                    onSplitMove = splitController::move,
                    onSplitEnd = splitEnd@{ pointerId, path, layout, atMs ->
                        splitController.finish(pointerId, path, layout, atMs)
                        val expectedSession = sessionId
                        val expectedLanguage = activeLanguage
                        val expectedGeneration = splitPollLifecycle.currentGeneration
                        if (splitPollLifecycle.scheduleCurrentGeneration() == null) return@splitEnd
                        fun pollSplit() {
                            if (
                                sessionId != expectedSession ||
                                activeLanguage != expectedLanguage ||
                                !splitPollLifecycle.isCurrent(expectedGeneration)
                            ) return
                            val parts = when (val result = swipeTypingCoordinator.poll(System.currentTimeMillis())) {
                                is SplitPollOutcome.Resolved -> result.parts
                                SplitPollOutcome.Pending -> {
                                    splitGraceHandler.postDelayed({ pollSplit() }, 50L)
                                    return
                                }
                                SplitPollOutcome.Cancelled -> {
                                    splitPollLifecycle.finishPolling(expectedGeneration)
                                    trackGesture(DiagnosticsGestureKind.SPLIT, DiagnosticsOutcome.CANCELLED)
                                    swipeTypingCoordinator.onRecognitionFailed()
                                    splitPreview.value = null
                                    return
                                }
                            }
                            splitPollLifecycle.finishPolling(expectedGeneration)
                            val dictionary = activeDictionary()
                            correctionExecutor.execute {
                                val candidates = parts.paths.map { part -> controller.recognize(part, layout, dictionary) }
                                mainHandler.post {
                                    if (
                                        sessionId != expectedSession ||
                                        activeLanguage != expectedLanguage ||
                                        !splitPollLifecycle.isCurrent(expectedGeneration)
                                    ) return@post
                                    if (candidates.any { it.isEmpty() }) {
                                        trackGesture(DiagnosticsGestureKind.SPLIT, DiagnosticsOutcome.REJECTED)
                                        swipeTypingCoordinator.onRecognitionFailed()
                                        splitPreview.value = null
                                        return@post
                                    }
                                    trackGesture(DiagnosticsGestureKind.SPLIT, DiagnosticsOutcome.ACCEPTED)
                                    rememberCandidatesForCommittedSwipe(candidates.flatten())
                                    swipeTypingCoordinator.onRecognizedMultiPathResult(
                                        parts = parts.paths,
                                        candidates = candidates,
                                        touchDownAtMs = parts.touchDownAtMs,
                                        graceWindowMs = parts.graceWindowMs,
                                    )
                                    typingController.markSwipeCommitted()
                                    splitPreview.value = null
                                }
                            }
                        }
                        splitGraceHandler.postDelayed({ pollSplit() }, 351L)
                    },
                    onSplitCancel = {
                        splitPollLifecycle.invalidate()
                        splitGraceHandler.removeCallbacksAndMessages(null)
                        splitController.cancel()
                        trackGesture(DiagnosticsGestureKind.SPLIT, DiagnosticsOutcome.CANCELLED)
                        swipeTypingCoordinator.onRecognitionFailed()
                        splitPreview.value = null
                    },
                    splitPreview = splitPreview.value,
                    onSplitPreview = { preview -> splitPreview.value = preview },
                    manualEditCandidate = pendingManualEdit.value,
                    onConfirmManualEdit = ::confirmManualEdit,
                    onDismissManualEdit = { pendingManualEdit.value = null },
                    researchTraceRecorder = researchTraceRecorder,
                    onResearchTraceCaptured = ::onResearchTraceCaptured,
                )
            }
        }
        activeRuntimeRevision?.let { revision ->
            view.post {
                runtimeReadiness.markInputViewRendered(revision)
                publishRuntimeReadyIfComplete(revision)
            }
        }
    }

    private fun beginRuntimeRevision(): Long {
        val revision = runtimeReadiness.beginRestore()
        activeRuntimeRevision = revision
        applicationContext
            .getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, MODE_PRIVATE)
            .edit()
            .remove(DebugAutoSpaceFixtures.RUNTIME_READY_REVISION_KEY)
            .commit()
        return revision
    }

    private fun publishRuntimeReadyIfComplete(revision: Long) {
        if (!runtimeReadiness.isReady(revision)) return
        val preferences = applicationContext
            .getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, MODE_PRIVATE)
        preferences.edit().putLong(DebugAutoSpaceFixtures.RUNTIME_READY_REVISION_KEY, revision).apply()
        pendingDebugRestoreId?.let { requestId ->
            preferences.edit()
                .putLong(DebugAutoSpaceFixtures.STATE_RESPONSE_ID_KEY, requestId)
                .remove(DebugAutoSpaceFixtures.STATE_RESPONSE_ERROR_KEY)
                .commit()
            pendingDebugRestoreId = null
        }
    }

    private fun invalidateRuntimeReadiness() {
        runtimeReadiness.invalidate()
        activeRuntimeRevision = null
        applicationContext
            .getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, MODE_PRIVATE)
            .edit()
            .remove(DebugAutoSpaceFixtures.RUNTIME_READY_REVISION_KEY)
            .commit()
    }

    private fun observeDebugStateRequests() {
        settingsScope.launch {
            val preferences = applicationContext
                .getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, MODE_PRIVATE)
            var consumedRequestId = preferences.getLong(DebugAutoSpaceFixtures.STATE_RESPONSE_ID_KEY, -1L)
            while (isActive) {
                val requestId = preferences.getLong(DebugAutoSpaceFixtures.STATE_REQUEST_ID_KEY, -1L)
                val requestJson = preferences.getString(DebugAutoSpaceFixtures.STATE_REQUEST_JSON_KEY, null)
                if (requestId > consumedRequestId && requestJson != null) {
                    consumedRequestId = requestId
                    val decoded = runCatching { KeyboardStateJsonCodec.decode(requestJson) }
                    mainHandler.post {
                        decoded.fold(
                            onSuccess = { snapshot -> applyDebugStateSnapshot(requestId, snapshot) },
                            onFailure = { error ->
                                preferences.edit()
                                    .putLong(DebugAutoSpaceFixtures.STATE_RESPONSE_ID_KEY, requestId)
                                    .putString(
                                        DebugAutoSpaceFixtures.STATE_RESPONSE_ERROR_KEY,
                                        error.message ?: "Invalid keyboard state snapshot",
                                    )
                                    .commit()
                            },
                        )
                    }
                }
                delay(25L)
            }
        }
    }

    private fun applyDebugStateSnapshot(requestId: Long, snapshot: com.iaido.core.state.KeyboardStateSnapshot) {
        check(isDebugBuild()) { "Debug state restore is unavailable in release builds" }
        val session = snapshot.session ?: emptyTypingSessionSnapshot()
        swipeTypingCoordinator.onNonSwipeInput()
        splitController.cancel()
        splitGraceHandler.removeCallbacksAndMessages(null)
        inferenceSelectionGuard.clear()
        clearBackspaceSwipe()
        pendingManualEdit.value = null
        languageSwitcher.select(session.language)
        activeLanguage = session.language
        spacingModeForTypingCoordinator = snapshot.profile.spacingMode
        flowCorrectionEngine.setMaxCascadeDepth(snapshot.profile.flowCorrectionDepth)
        splitGraceWindowMs = snapshot.profile.splitGraceWindowMs
        splitController.updateGraceWindowMs(splitGraceWindowMs)
        commandBindings.replaceAll(snapshot.profile.commandBindings)
        learningDictionary.restore(snapshot.profile.dictionary)
        correctionHistory.restore(session.correctionHistory)
        cursorPosition = session.cursorPosition
        selectionEndPosition = session.cursorPosition
        pendingCandidates = session.pendingCandidates.takeIf { it.isNotEmpty() }
        runtimeState.restore(session)
        refreshSuggestionChips()
        pendingDebugRestoreId = requestId
        val revision = beginRuntimeRevision()
        if (currentInputConnection != null) runtimeReadiness.markInputConnectionBound(revision)
        composeInputView?.let(::renderInputView)
    }

    private fun emptyTypingSessionSnapshot() = TypingSessionSnapshot(
        language = Language.ENGLISH,
        correctionHistory = SessionCorrectionHistorySnapshot(nextId = 0, entries = emptyList()),
        cursorPosition = 0,
        pendingCandidates = emptyList(),
    )

    private fun isDebugBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun observeKeyboardSettings() {
        settingsScope.launch {
            applicationContext.settingsStore.data
                .map(::keyboardSettingsFromPreferences)
                .collectLatest { resolvedMode ->
                    mainHandler.post {
                        spacingModeForTypingCoordinator = resolvedMode.spacingMode
                        showCandidateScores.value = resolvedMode.showCandidateScores && isDebugBuild()
                        flowCorrectionEngine.setMaxCascadeDepth(resolvedMode.flowCorrectionDepth)
                        splitGraceWindowMs = resolvedMode.splitGraceWindowMs
                        splitController.updateGraceWindowMs(splitGraceWindowMs)
                        commandBindings.replaceAll(resolvedMode.commandBindings)
                        applicationContext
                            .getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, MODE_PRIVATE)
                            .edit()
                            .putString(DebugAutoSpaceFixtures.ACTIVE_SPACING_MODE_KEY, resolvedMode.spacingMode.name)
                            .apply()
                    }
                }
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

    private fun activeDictionary() = if (activeLanguage == Language.ENGLISH) {
        debugAutoSpaceFixture()?.dictionary ?: learningDictionary.entries()
    } else hebrewDictionaryRepository.words()

    private fun debugAutoSpaceFixture(): DebugAutoSpaceFixture? =
        DebugAutoSpaceFixtures.active(applicationContext)

    private fun replaceInferenceHostSpan(span: HostTextSpan, replacement: String): Boolean {
        val expectedCursor = span.start + replacement.length
        return applyEditorReplacement(
            start = span.start,
            endExclusive = span.end,
            replacement = replacement,
            kind = SentenceEditKind.TYPING,
            coalescingKey = "inference:${span.start}",
            cursorAfter = expectedCursor,
            guardInferenceSelection = true,
            refresh = false,
        )
    }

    private fun recordFinalizedInferenceWords(
        span: HostTextSpan,
        words: List<String>,
        alternatives: List<SegmentationOption>,
    ) {
        sentenceEditHistory.closeGroup()
        pendingCandidates = null
        val candidatesByWord = inferenceWordCandidates(words, alternatives)
        val outputIds = correctionHistory.replaceRange(
            start = span.start,
            end = span.end,
            replacementWords = words,
            candidatesByWord = candidatesByWord,
            composite = words.size > 1,
        )
        refreshSuggestionChips(outputIds.firstOrNull())
    }

    private fun commitText(text: String) {
        val inputConnection = currentInputConnection ?: return
        val start = cursorPosition
        val end = selectionEndPosition
        // A commit that carries recognition candidates is a swiped word, which is recorded from
        // those candidates below; everything else is the user typing.
        val swipedWord = pendingCandidates != null
        val coalescingKey = if (!swipedWord && start == end && text.isNotEmpty() && text.all(::isTypingCharacter)) {
            "typing"
        } else null
        if (coalescingKey == null) sentenceEditHistory.closeGroup()
        val captured = if (textObservationEnabled) captureEditorEdit(start, end) else null
        if (textObservationEnabled) editorTextChangeDetector.expectOwnEdit(start, end, text)
        if (!inputConnection.commitText(text, 1)) return
        cursorPosition = start + text.length
        selectionEndPosition = cursorPosition
        captured?.let { before ->
            recordSuccessfulEditorEdit(
                captured = before,
                start = start,
                endExclusive = end,
                replacement = text,
                kind = SentenceEditKind.TYPING,
                coalescingKey = coalescingKey,
                selectionBeforeStart = start,
                selectionBeforeEnd = end,
                selectionAfterStart = cursorPosition,
                selectionAfterEnd = cursorPosition,
            )
        }
        if (coalescingKey == null) sentenceEditHistory.closeGroup()
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
            val id = correctionHistory.record(start, cursorPosition, text, candidates)
            pendingCandidates = null
            refreshSuggestionChips(id)
            scheduleFlowCorrection()
        }
        if (swipedWord) {
            typedWords.reset()
        } else {
            val closed = typedWords.onCommitted(text, cursorBefore = start, cursorAfter = cursorPosition)
            refreshSuggestionChips(updateTypedWord(closed ?: typedWords.openSpan()))
        }
        if (swipedWord) refreshSuggestionChips()
    }

    private fun captureEditorEdit(start: Int, endExclusive: Int): CapturedEditorEdit? {
        return captureEditorEdit(latestEditorSnapshot, start, endExclusive)
    }

    private fun captureEditorEdit(
        snapshot: EditorSnapshot?,
        start: Int,
        endExclusive: Int,
    ): CapturedEditorEdit? {
        snapshot ?: return null
        val localStart = start - snapshot.offset
        val localEnd = endExclusive - snapshot.offset
        if (localStart < 0 || localEnd < localStart || localEnd > snapshot.text.length) return null
        return CapturedEditorEdit(snapshot, snapshot.text.substring(localStart, localEnd))
    }

    /** One editor mutation seam shared by corrections, joins, strip edits, and deletion. */
    private fun applyEditorReplacement(
        start: Int,
        endExclusive: Int,
        replacement: String,
        kind: SentenceEditKind,
        expectedSourceText: String? = null,
        coalescingKey: String? = null,
        cursorAfter: Int = start + replacement.length,
        selectionAfterEnd: Int = cursorAfter,
        requireObservedSnapshot: Boolean = false,
        guardInferenceSelection: Boolean = false,
        recordHistory: Boolean = true,
        refresh: Boolean = true,
    ): Boolean {
        val inputConnection = currentInputConnection ?: return false
        if (start < 0 || endExclusive < start) return false
        val selectionBeforeStart = cursorPosition
        val selectionBeforeEnd = selectionEndPosition
        val captured = if (textObservationEnabled) captureEditorEdit(start, endExclusive) else null
        if (expectedSourceText != null && captured?.sourceText != expectedSourceText) return false
        if (requireObservedSnapshot && captured == null) return false

        if (guardInferenceSelection) inferenceSelectionGuard.arm(cursorAfter, cursorAfter)
        if (!inputConnection.setSelection(start, endExclusive)) {
            if (guardInferenceSelection) inferenceSelectionGuard.clear()
            return false
        }
        if (textObservationEnabled) {
            editorTextChangeDetector.expectOwnEdit(start, endExclusive, replacement)
        }
        if (!inputConnection.commitText(replacement, 1)) {
            captured?.let { editorTextChangeDetector.reset(it.snapshot) }
            inputConnection.setSelection(selectionBeforeStart, selectionBeforeEnd)
            if (guardInferenceSelection) inferenceSelectionGuard.clear()
            return false
        }

        // The text commit is authoritative even if an editor declines the follow-up caret call.
        inputConnection.setSelection(cursorAfter, selectionAfterEnd)
        cursorPosition = cursorAfter
        selectionEndPosition = selectionAfterEnd
        captured?.let { before ->
            if (recordHistory) {
                recordSuccessfulEditorEdit(
                    captured = before,
                    start = start,
                    endExclusive = endExclusive,
                    replacement = replacement,
                    kind = kind,
                    coalescingKey = coalescingKey,
                    selectionBeforeStart = selectionBeforeStart,
                    selectionBeforeEnd = selectionBeforeEnd,
                    selectionAfterStart = cursorAfter,
                    selectionAfterEnd = selectionAfterEnd,
                )
            } else {
                updateLatestEditorSnapshot(before, start, endExclusive, replacement, cursorAfter, selectionAfterEnd)
            }
        }
        if (refresh) refreshSuggestionChips()
        return true
    }

    internal fun undoSentenceEdit(): Boolean = applySentenceHistoryEdit(undo = true)

    internal fun redoSentenceEdit(): Boolean = applySentenceHistoryEdit(undo = false)

    private fun applySentenceHistoryEdit(undo: Boolean): Boolean {
        if (!textObservationEnabled) return false
        val applied = if (undo) {
            sentenceEditHistory.undo { edit -> applyHistoryEdit(edit, undo = true) }
        } else {
            sentenceEditHistory.redo { edit -> applyHistoryEdit(edit, undo = false) }
        }
        refreshSuggestionChips()
        return applied
    }

    private fun applyHistoryEdit(edit: SentenceEdit, undo: Boolean): HistoryApplyResult {
        val expected = if (undo) edit.replacementText else edit.replacedText
        val replacement = if (undo) edit.replacedText else edit.replacementText
        val start = edit.sourceStart
        val end = start + expected.length
        val capture = captureEditorEdit(start, end)
        if (capture == null || capture.sourceText != expected) return HistoryApplyResult.STALE

        val selectionStart = if (undo) edit.selectionBeforeStart else edit.selectionAfterStart
        val selectionEnd = if (undo) edit.selectionBeforeEnd else edit.selectionAfterEnd
        return if (applyEditorReplacement(
                start = start,
                endExclusive = end,
                replacement = replacement,
                kind = edit.kind,
                expectedSourceText = expected,
                cursorAfter = selectionStart,
                selectionAfterEnd = selectionEnd,
                recordHistory = false,
                refresh = false,
            )
        ) HistoryApplyResult.APPLIED else HistoryApplyResult.FAILED
    }

    private fun recordSuccessfulEditorEdit(
        captured: CapturedEditorEdit,
        start: Int,
        endExclusive: Int,
        replacement: String,
        kind: SentenceEditKind,
        coalescingKey: String?,
        selectionBeforeStart: Int,
        selectionBeforeEnd: Int,
        selectionAfterStart: Int,
        selectionAfterEnd: Int,
    ) {
        val snapshot = captured.snapshot
        val localStart = start - snapshot.offset
        val localEnd = endExclusive - snapshot.offset
        if (localStart < 0 || localEnd < localStart || localEnd > snapshot.text.length) return
        val replacedText = snapshot.text.substring(localStart, localEnd)
        sentenceEditHistory.recordAppliedEdit(
            SentenceEdit(
                sourceStart = start,
                sourceEndExclusive = endExclusive,
                replacedText = replacedText,
                replacementText = replacement,
                selectionBeforeStart = selectionBeforeStart,
                selectionBeforeEnd = selectionBeforeEnd,
                selectionAfterStart = selectionAfterStart,
                selectionAfterEnd = selectionAfterEnd,
                kind = kind,
                coalescingKey = coalescingKey,
            ),
        )
        updateLatestEditorSnapshot(captured, start, endExclusive, replacement, selectionAfterStart, selectionAfterEnd)
    }

    private fun updateLatestEditorSnapshot(
        captured: CapturedEditorEdit,
        start: Int,
        endExclusive: Int,
        replacement: String,
        selectionAfterStart: Int,
        selectionAfterEnd: Int = selectionAfterStart,
    ) {
        val snapshot = captured.snapshot
        val localStart = start - snapshot.offset
        val localEnd = endExclusive - snapshot.offset
        if (localStart < 0 || localEnd < localStart || localEnd > snapshot.text.length) return
        val updatedText = snapshot.text.substring(0, localStart) + replacement + snapshot.text.substring(localEnd)
        latestEditorSnapshot = snapshot.copy(
            text = updatedText,
            selectionStart = selectionAfterStart,
            selectionEnd = selectionAfterEnd,
        )
    }

    private fun isTypingCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || when (Character.getType(character)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(), -> true
            else -> false
        }

    /**
     * Mirrors a typed word into the session history so every correction path a swiped word already
     * gets - suggestion chips, the replacement reel, flow correction, learning - can address it.
     *
     * The word is re-recorded as it grows (the entry for the same span is dropped first, so one
     * typed word is always exactly one session word), because the strip has to offer corrections
     * for a word the user is still typing: recording it only when it closed left the reel with
     * nothing to release, which is how a release either did nothing or inserted its candidate at
     * the caret instead of replacing the word ("Hiiiiiiii").
     */
    private fun updateTypedWord(span: TypedWordSpan?): Int? {
        if (span == null || span.word.length < TypedWordTracker.MIN_TYPED_WORD_LENGTH) return null
        return correctionHistory.upsertTyped(
            start = span.start,
            end = span.end,
            word = span.word,
            candidates = typedWordCandidates(span.word, activeDictionary()),
        )
    }

    private fun deleteSurroundingText(count: Int) {
        val oldCursor = cursorPosition
        typedWords.onDeleted(count, cursorBefore = oldCursor)
        val start = (oldCursor - count).coerceAtLeast(0)
        if (!applyEditorReplacement(
                start = start,
                endExclusive = oldCursor,
                replacement = "",
                kind = SentenceEditKind.BACKSPACE,
                coalescingKey = "backspace",
                cursorAfter = start,
                refresh = false,
            )
        ) return
        correctionHistory.words()
            .filter { it.start < oldCursor && it.end > (oldCursor - count).coerceAtLeast(0) }
            .forEach { correctionHistory.breakCompositeGroupFor(it.id) }
        lastDeletedWord = correctionHistory.words().firstOrNull { it.end == oldCursor }?.current
        correctionHistory.deleteRange(start, oldCursor)
        cursorPosition = start
        selectionEndPosition = start
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
        observeCurrentEditorText()
        backspaceSwipeText = inputConnection
            .getTextBeforeCursor(MAX_BACKSPACE_SWIPE_CHARS, 0)
            ?.toString()
            .orEmpty()
        backspaceSwipeCursor = cursorPosition
        backspaceSwipeDeletedCount = 0
        backspaceSwipeInitialSnapshot = latestEditorSnapshot
        sentenceEditHistory.closeGroup()
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
            val capture = if (textObservationEnabled) captureEditorEdit(currentCursor - delta, currentCursor) else null
            if (textObservationEnabled) {
                editorTextChangeDetector.expectOwnEdit(currentCursor - delta, currentCursor, "")
            }
            if (!inputConnection.deleteSurroundingText(delta, 0)) return
            capture?.let { updateLatestEditorSnapshot(it, currentCursor - delta, currentCursor, "", currentCursor - delta) }
        } else {
            val restoreStart = backspaceSwipeText.length - backspaceSwipeDeletedCount
            val restoreEnd = backspaceSwipeText.length - target
            val restored = backspaceSwipeText.substring(restoreStart, restoreEnd)
            val capture = if (textObservationEnabled) captureEditorEdit(currentCursor, currentCursor) else null
            if (textObservationEnabled) {
                editorTextChangeDetector.expectOwnEdit(currentCursor, currentCursor, restored)
            }
            if (!inputConnection.commitText(restored, 1)) return
            capture?.let { updateLatestEditorSnapshot(it, currentCursor, currentCursor, restored, currentCursor + restored.length) }
        }
        backspaceSwipeDeletedCount = target
        cursorPosition = backspaceSwipeCursor - target
        selectionEndPosition = cursorPosition
    }

    private fun finishBackspaceSwipe() {
        if (backspaceSwipeDeletedCount > 0) {
            val start = backspaceSwipeCursor - backspaceSwipeDeletedCount
            val initial = backspaceSwipeInitialSnapshot
            val capture = captureEditorEdit(initial, start, backspaceSwipeCursor)
            if (capture != null) {
                recordSuccessfulEditorEdit(
                    captured = capture,
                    start = start,
                    endExclusive = backspaceSwipeCursor,
                    replacement = "",
                    kind = SentenceEditKind.BACKSPACE,
                    coalescingKey = null,
                    selectionBeforeStart = backspaceSwipeCursor,
                    selectionBeforeEnd = backspaceSwipeCursor,
                    selectionAfterStart = start,
                    selectionAfterEnd = start,
                )
            }
            correctionHistory.deleteRange(
                start,
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
        backspaceSwipeInitialSnapshot = null
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
                        trackResearchCorrection(
                            action = CorrectionAction.FLOW_CORRECTION,
                            sourceText = correction.before,
                            finalText = correction.after,
                        )
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

    private fun refreshSuggestionChips(changedWordId: Int? = null) {
        changedWordId?.let { changedId ->
            val result = sentenceCandidateReranker.rerank(correctionHistory.words(), changedId)
            result.affectedIds.forEach { id ->
                result.words.firstOrNull { it.id == id }?.let { correctionHistory.updateCandidates(id, it.candidates) }
            }
        }
        val selection = suggestionStripSelection(correctionHistory.words(), cursorPosition)
        val words = selection.words
        visibleWordIds = words.map { it.id }
        focusedWordId.value = selection.focusedWordId
        sessionChips.value = words.map { word ->
            SuggestionChip(
                word = word.current,
                alternatives = word.candidates,
                selectedIndex = word.candidates.indexOf(word.current).coerceAtLeast(0),
                corrected = word.corrected,
                id = word.id,
            )
        }
        cachedHistoryJoinCandidates = historyJoinCandidates(
            correctionHistory.aroundCursor(cursorPosition, maxWords = VISIBLE_HISTORY_WORDS),
            activeDictionary(),
        )
        val live = swipeTypingCoordinator.replacementOptions()
        liveReplacementOptionIds.value = live.map { it.id }.toSet()
        val merged = mergedReplacementOptions(live)
        replacementOptions.value = merged
        publishSentenceStripState(merged)
    }

    private fun publishSentenceStripState(mergedReplacementOptions: List<ReplacementOption> = replacementOptions.value) {
        val snapshot = latestEditorSnapshot?.copy(
            selectionStart = cursorPosition,
            selectionEnd = selectionEndPosition,
        ) ?: EditorSnapshot("", cursorPosition, selectionEndPosition)
        sentenceStripState.value = SentenceTextModel.update(
            previous = sentenceStripState.value,
            snapshot = snapshot,
            language = activeLanguage,
            history = correctionHistory.words(),
            replacementOptions = mergedReplacementOptions,
            textObservationAllowed = textObservationEnabled,
        ).copy(
            canUndo = sentenceEditHistory.canUndo,
            canRedo = sentenceEditHistory.canRedo,
        )
    }

    private fun mergedReplacementOptions(liveOptions: List<ReplacementOption>): List<ReplacementOption> =
        (liveOptions + cachedHistoryJoinCandidates).distinctBy(ReplacementOption::id)

    private fun joinSessionWords(firstId: Int, secondId: Int, replacement: String): Boolean {
        val words = correctionHistory.words()
        val first = words.firstOrNull { it.id == firstId } ?: return false
        val second = words.firstOrNull { it.id == secondId } ?: return false
        if (!applyEditorReplacement(
                start = first.start,
                endExclusive = second.end,
                replacement = replacement,
                kind = SentenceEditKind.JOIN,
                cursorAfter = first.start + replacement.length,
                refresh = false,
            )
        ) return false
        correctionHistory.join(firstId, secondId, replacement)
        typedWords.reset()
        refreshSuggestionChips(firstId)
        return true
    }

    private fun releaseReplacementOption(option: ReplacementOption): Boolean {
        val historyMatch = correctionHistory.aroundCursor(cursorPosition).zipWithNext()
            .firstOrNull { (first, second) -> listOf(first.current, second.current) == option.sourceWords }
        val released = if (historyMatch != null) {
            val (first, second) = historyMatch
            joinSessionWords(first.id, second.id, option.replacementWords.joinToString(" "))
        } else {
            swipeTypingCoordinator.releaseReplacement(option)
        }
        trackSuggestion(
            DiagnosticsSuggestionAction.REPLACEMENT,
            if (released) DiagnosticsOutcome.ACCEPTED else DiagnosticsOutcome.REJECTED,
        )
        if (released) {
            trackResearchCorrection(
                action = CorrectionAction.CANDIDATE_SELECTED,
                sourceText = option.sourceWords.joinToString(" "),
                finalText = option.replacementWords.joinToString(" "),
            )
        }
        return released
    }

    private fun releaseSuggestion(displayIndex: Int, candidateIndex: Int) {
        val word = wordForDisplayIndex(displayIndex) ?: return
        val chip = SuggestionChip(
            word = word.current,
            alternatives = word.candidates,
            selectedIndex = word.candidates.indexOf(word.current).coerceAtLeast(0),
            corrected = word.corrected,
            id = word.id,
        )
        val replacement = displayCandidateForIndex(chip, candidateIndex) ?: return
        val changed = replaceSessionWord(word.id, replacement)
        trackSuggestion(
            DiagnosticsSuggestionAction.SUGGESTION_PICK,
            if (changed) DiagnosticsOutcome.ACCEPTED else DiagnosticsOutcome.REJECTED,
        )
        if (changed) {
            trackResearchCorrection(
                action = CorrectionAction.CANDIDATE_SELECTED,
                sourceText = word.current,
                finalText = replacement,
                candidates = chip.alternatives,
            )
        }
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
        val undone = replaceSessionWord(word.id, word.original)
        trackSuggestion(
            DiagnosticsSuggestionAction.UNDO,
            if (undone) DiagnosticsOutcome.ACCEPTED else DiagnosticsOutcome.REJECTED,
        )
        if (undone) {
            trackResearchCorrection(
                action = CorrectionAction.UNDO,
                sourceText = word.current,
                finalText = word.original,
            )
            recordLearning(
                signal = LearningSignal.FLOW_UNDO,
                original = word.current,
                replacement = word.original,
            )
        }
    }

    // Diagnostics are batched in memory (see DiagnosticsTelemetry.record) and only flushed to
    // local storage at natural session boundaries (onFinishInputView) or once the in-memory
    // backlog crosses DiagnosticsTelemetry.DEFAULT_MAX_PENDING_EVENTS - never as a per-event
    // side effect here, since that would mean a disk write + WorkManager call on every gesture.
    private fun trackGesture(kind: DiagnosticsGestureKind, outcome: DiagnosticsOutcome) {
        DiagnosticsTelemetryProvider.instance?.recordGesture(kind, outcome)
    }

    private fun trackSuggestion(action: DiagnosticsSuggestionAction, outcome: DiagnosticsOutcome) {
        DiagnosticsTelemetryProvider.instance?.recordSuggestionAction(action, outcome)
    }

    // Research correction capture (Task 9): each call below fires once, at the natural completion
    // of one discrete correction action (a candidate pick, an undo, a flow correction, a manual
    // edit) - never from a per-touch/per-keystroke loop. ResearchCorrectionRecorder.record is
    // already consent-gated and internally defensive; this wrapper adds a second layer so a
    // failure here can never propagate into the correction/undo/replacement code paths above.
    private fun trackResearchCorrection(
        action: CorrectionAction,
        sourceText: String,
        finalText: String,
        candidates: List<String> = emptyList(),
    ) {
        try {
            ResearchCorrectionRecorderProvider.instance?.record(
                CorrectionInput(
                    action = action,
                    sourceText = sourceText,
                    finalText = finalText,
                    candidates = candidates,
                    algorithmVersion = ResearchCorrectionRecorder.CURRENT_ALGORITHM_VERSION,
                    traceId = researchTraceId,
                ),
            )
        } catch (_: Exception) {
            // Research correction capture is best effort and must never affect keyboard behavior.
        }
    }

    /**
     * Research trace capture (Task 9): called once per completed gesture by `KeyboardInputView`,
     * which owns the raw touch boundary. Enqueues the bounded trace on the research plane and
     * remembers its id when the gesture committed recognized text, so a later correction of that
     * text can be correlated with the gesture that produced it. Diagnostics never see this data.
     */
    private fun onResearchTraceCaptured(trace: ResearchTrace) {
        try {
            ResearchCorrectionRecorderProvider.recordTrace(trace)
            researchTraceId = trace.traceId
                .takeIf { trace.classification in ResearchTraceClassification.TYPING }
        } catch (_: Exception) {
            // Research trace capture is best effort and must never affect keyboard behavior.
        }
    }

    private fun wordForDisplayIndex(displayIndex: Int) =
        visibleWordIds
            .getOrNull(if (activeLanguage == Language.HEBREW) visibleWordIds.lastIndex - displayIndex else displayIndex)
            ?.let { id -> correctionHistory.words().firstOrNull { it.id == id } }

    private fun replaceSessionWord(id: Int, replacement: String, preserveCursor: Boolean = false): Boolean {
        val word = correctionHistory.words().firstOrNull { it.id == id } ?: return false
        if (word.current == replacement) return false
        val oldCursor = cursorPosition
        val delta = replacement.length - word.current.length
        val cursorAfter = if (preserveCursor && word.end <= oldCursor) oldCursor + delta
        else word.start + replacement.length
        if (!applyEditorReplacement(
                start = word.start,
                endExclusive = word.end,
                replacement = replacement,
                kind = SentenceEditKind.CORRECTION,
                expectedSourceText = word.current,
                cursorAfter = cursorAfter,
                refresh = false,
            )
        ) return false
        correctionHistory.breakCompositeGroupFor(id)
        correctionHistory.replace(id, replacement)
        typedWords.reset()
        refreshSuggestionChips(id)
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
            trackResearchCorrection(
                action = CorrectionAction.MANUAL_EDIT,
                sourceText = candidate.original,
                finalText = candidate.replacement,
            )
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
        val snapshot = EditorSnapshot(
            text = text.text?.toString().orEmpty(),
            selectionStart = text.startOffset + text.selectionStart,
            selectionEnd = text.startOffset + text.selectionEnd,
            offset = text.startOffset,
        )
        latestEditorSnapshot = snapshot
        editorTextChangeDetector.reset(snapshot)
    }

    private fun observeEditorSnapshot(snapshot: EditorSnapshot) {
        val previousSnapshot = latestEditorSnapshot
        if (previousSnapshot != null && previousSnapshot.text != snapshot.text) {
            swipeTypingCoordinator.onExternalEdit()
            sentenceEditHistory.markExternalEdit()
        }
        latestEditorSnapshot = snapshot
        editorTextChangeDetector.observe(snapshot)?.let { candidate ->
            if (pendingManualEdit.value == null) pendingManualEdit.value = candidate
        }
        cursorPosition = snapshot.selectionStart
        selectionEndPosition = snapshot.selectionEnd
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
        const val INFERENCE_SELECTION_GUARD_TIMEOUT_MS = 1_000L
    }

    private fun switchLanguage() {
        swipeTypingCoordinator.onNonSwipeInput()
        activeLanguage = languageSwitcher.next()
        refreshSuggestionChips()
    }

    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
