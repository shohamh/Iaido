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

class IaidoInputMethodService : InputMethodService() {
    private var composeInputView: ComposeView? = null
    private val inputMethodLifecycleOwner = InputMethodLifecycleOwner()
    private var sessionId = 0
    private val languageSwitcher = LanguageSwitcher()
    private var activeLanguage = Language.ENGLISH
    internal var spacingModeForTypingCoordinator = SpacingMode.INFER_SPACES
        private set
    private var splitGraceWindowMs = SettingsDefaults.GRACE_WINDOW_MS.toLong()
    private val commandBindings = CommandBindingSet()
    private val commandMode = CommandModeController(::executeCommand)
    private val commandDispatcher = CommandGestureDispatcher(commandBindings, ::executeCommand)
    private val correctionHistory = SessionCorrectionHistory()
    private val sessionChips = mutableStateOf<List<SuggestionChip>>(emptyList())
    private val replacementOptions = mutableStateOf<List<ReplacementOption>>(emptyList())
    // Ids of replacement options currently produced by the live SwipeTypingCoordinator
    // transaction, as opposed to history-derived joins -- used only to decide whether a
    // join-shaped option still belongs in the edge-anchored ReplacementReelSlot (a still-live join
    // must stay there even once a matching chip pair also exists; see SuggestionStrip.kt).
    private val liveReplacementOptionIds = mutableStateOf<Set<String>>(emptySet())
    private val splitPreview = mutableStateOf<String?>(null)
    private val pendingManualEdit = mutableStateOf<ManualEditCandidate?>(null)
    private val editorTextChangeDetector = EditorTextChangeDetector()
    private var textObservationEnabled = false
    private var visibleWordIds: List<Int> = emptyList()
    // Recomputed only when correctionHistory actually changes (inside refreshSuggestionChips()),
    // not on every mergedReplacementOptions() call -- a live reel-drag preview fires
    // onReplacementOptionsChanged on every frame but never touches correctionHistory, so
    // recomputing this (a full activeDictionary() rebuild plus a fresh lowercase index) on every
    // preview frame would be a hot-path regression with no correctness benefit.
    private var cachedHistoryJoinCandidates: List<ReplacementOption> = emptyList()
    private var cursorPosition = 0
    private var pendingCandidates: List<String>? = null
    private var lastDeletedWord: String? = null
    private var backspaceSwipeText = ""
    private var backspaceSwipeCursor = 0
    private var backspaceSwipeDeletedCount = 0
    private val inferenceSelectionGuard = InferenceSelectionGuard()
    private val correctionExecutor = Executors.newSingleThreadExecutor()
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val splitGraceHandler = Handler(Looper.getMainLooper())
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
                replacementOptions.value = mergedReplacementOptions(options)
            },
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
        swipeTypingCoordinator.onNonSwipeInput()
        super.onStartInputView(info, restarting)
        val runtimeRevision = beginRuntimeRevision()
        if (currentInputConnection != null) runtimeReadiness.markInputConnectionBound(runtimeRevision)
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
        inferenceSelectionGuard.clear()
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
                                    swipeTypingCoordinator.onRecognitionFailed()
                                    return@post
                                }
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
                        typingController.punctuationToSpace(punctuation)
                    },
                    language = activeLanguage,
                    onLanguageSwitch = ::switchLanguage,
                    onCommand = { trigger ->
                        swipeTypingCoordinator.onNonSwipeInput()
                        handleCommand(trigger)
                    },
                    suggestionChips = sessionChips.value,
                    replacementOptions = replacementOptions.value,
                    liveReplacementOptionIds = liveReplacementOptionIds.value,
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
                                    typingController.markSwipeCommitted()
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
        val inputConnection = currentInputConnection ?: return false
        val expectedCursor = span.start + replacement.length
        // Arm before making any framework calls: onUpdateSelection callbacks for this edit can be
        // delivered asynchronously, after this function has already returned, so the guard must stay
        // armed until it actually observes the matching callback (see InferenceSelectionGuard's doc).
        inferenceSelectionGuard.arm(expectedCursor, expectedCursor)
        if (!inputConnection.setSelection(span.start, span.end)) {
            inferenceSelectionGuard.clear()
            return false
        }
        if (textObservationEnabled) editorTextChangeDetector.expectOwnEdit(span.start, span.end, replacement)
        if (!inputConnection.commitText(replacement, 1)) {
            inferenceSelectionGuard.clear()
            return false
        }
        cursorPosition = expectedCursor
        inputConnection.setSelection(cursorPosition, cursorPosition)
        return true
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
        cachedHistoryJoinCandidates = historyJoinCandidates(words, activeDictionary())
        val live = swipeTypingCoordinator.replacementOptions()
        liveReplacementOptionIds.value = live.map { it.id }.toSet()
        replacementOptions.value = mergedReplacementOptions(live)
    }

    private fun mergedReplacementOptions(liveOptions: List<ReplacementOption>): List<ReplacementOption> =
        (liveOptions + cachedHistoryJoinCandidates).distinctBy(ReplacementOption::id)

    private fun joinSessionWords(firstId: Int, secondId: Int, replacement: String): Boolean {
        val inputConnection = currentInputConnection ?: return false
        val words = correctionHistory.words()
        val first = words.firstOrNull { it.id == firstId } ?: return false
        val second = words.firstOrNull { it.id == secondId } ?: return false
        if (!inputConnection.setSelection(first.start, second.end)) return false
        if (textObservationEnabled) editorTextChangeDetector.expectOwnEdit(first.start, second.end, replacement)
        if (!inputConnection.commitText(replacement, 1)) return false
        correctionHistory.join(firstId, secondId, replacement)
        cursorPosition = first.start + replacement.length
        inputConnection.setSelection(cursorPosition, cursorPosition)
        refreshSuggestionChips()
        return true
    }

    private fun releaseReplacementOption(option: ReplacementOption): Boolean {
        val historyMatch = correctionHistory.aroundCursor(cursorPosition).zipWithNext()
            .firstOrNull { (first, second) -> listOf(first.current, second.current) == option.sourceWords }
        if (historyMatch != null) {
            val (first, second) = historyMatch
            return joinSessionWords(first.id, second.id, option.replacementWords.joinToString(" "))
        }
        return swipeTypingCoordinator.releaseReplacement(option)
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
        const val INFERENCE_SELECTION_GUARD_TIMEOUT_MS = 1_000L
    }

    private fun switchLanguage() {
        swipeTypingCoordinator.onNonSwipeInput()
        activeLanguage = languageSwitcher.next()
        composeInputView?.let(::renderInputView)
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
