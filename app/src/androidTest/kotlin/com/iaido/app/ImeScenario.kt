package com.iaido.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.iaido.core.language.Language
import com.iaido.core.typing.SpacingMode
import com.iaido.core.testing.SwipeFixtures
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

data class ImeScenarioEvent(
    val index: Int,
    val action: String,
    val expectedText: String,
    val observedText: String,
    val expectedSelection: Int,
    val observedSelection: IntRange,
    val expectedIme: String,
    val observedIme: String,
    val expectedLanguage: Language,
    val observedLanguage: Language,
    val gestureSeed: Long?,
    val pointerEvents: List<InjectedPointerEvent>,
)

data class ImeScenarioState(
    val expectedText: String,
    val expectedSelection: Int,
    val expectedIme: String,
    val expectedLanguage: Language,
    val trace: List<ImeScenarioEvent>,
)

data class PathTransform(
    val jitterSeed: Long = 0L,
    val jitterPx: Float = 0f,
    val stepMs: Long = 48L,
    val pauseAfterPoint: Int? = null,
    val pauseMs: Long = 0L,
    val reverseStart: Int? = null,
    val reverseEndExclusive: Int? = null,
    val cancelAfterPoint: Int? = null,
)

/** Mirrors SuggestionStrip.kt's private REEL_STEP_DP -- the reel's own per-candidate drag step. */
private const val REPLACEMENT_REEL_STEP_DP = 28f
private const val REEL_SETTLE_WAIT_MS = 200L
private const val REEL_COMMIT_SETTLE_WAIT_MS = 50L
private const val REEL_RETRY_COMMIT_TIMEOUT_MS = 3_000L

class ImeScenario(
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation(),
    private val autoSpaceFixture: ImeScenarioData.AutoSpaceFixture? = null,
) {
    private val suiteState = suiteSessionState
    private val device = UiDevice.getInstance(instrumentation)
    private val automation = instrumentation.uiAutomation
    private val system = ImeSystemController(instrumentation, device, automation)
    private val pointer = PointerInjector(automation)
    private val editor = ImeEditorDriver(device, pointer, instrumentation.targetContext.packageName)
    private val density: Float
        get() = instrumentation.targetContext.resources.displayMetrics.density
    private val trace = mutableListOf<ImeScenarioEvent>()
    private var pendingGestureSeed: Long? = null
    private var pendingPointerEvents: List<InjectedPointerEvent> = emptyList()

    private var expectedText = ""
    private var expectedSelection = 0
    private var expectedLanguage = Language.ENGLISH
    private var expectedIme = system.iaidoImeId

    /** Max incremental swipes [scrollToText] will attempt while hunting for a target label. */
    private val scrollToTextMaxSwipes = 8

    fun run(block: ImeScenario.() -> Unit) {
        var succeeded = false
        try {
            setup()
            block()
            suiteState.markReady(
                autoSpaceFixture?.preferenceValue,
                expectedIme,
                expectedLanguage,
            )
            succeeded = true
        } finally {
            if (!succeeded) suiteState.invalidate()
        }
    }

    fun focusEditor() {
        editor.focus()
        checkpoint("focusEditor")
    }

    fun clearText() {
        editor.clear()
        expectedText = ""
        expectedSelection = 0
        checkpoint("clearText")
    }

    fun swipeWord(word: String) {
        swipePath(word)
    }

    fun swipePath(word: String, transform: PathTransform = PathTransform()) {
        val cancelled = injectSwipeWord(word, transform)
        if (cancelled) {
            checkpoint("cancelledSwipe($word)")
            return
        }
        val textBeforeCursor = expectedText.take(expectedSelection)
        val committed = if (textBeforeCursor.isEmpty() || textBeforeCursor.matches(Regex(".*[.!?]\\s*$"))) {
            word.replaceFirstChar { it.uppercase() }
        } else {
            word
        }
        insertExpected(committed)
        checkpoint("swipeWord($word)")
    }

    fun swipeWordExpecting(word: String, expected: String, transform: PathTransform = PathTransform()) {
        check(!injectSwipeWord(word, transform)) { "Expected a completed swipe for '$word'" }
        editor.waitForText(expected)
        expectedText = expected
        expectedSelection = editor.selection().last
        checkpoint("swipeWordExpecting($word)")
    }

    private fun injectSwipeWord(word: String, transform: PathTransform): Boolean {
        require(word.isNotEmpty() && word.all(Char::isLetter)) { "Swipe word must contain letters: '$word'" }
        val window = keyboard()
        check(window.language == expectedLanguage) {
            "Scenario language drifted before '$word': expected=$expectedLanguage observed=${window.language}"
        }
        val keySizePx = window.surfaceBounds.width().toFloat() /
            if (expectedLanguage == Language.HEBREW) 11f else 10f
        val path = if (expectedLanguage == Language.ENGLISH) {
            SwipeFixtures.pathThroughQwerty(word, keySizePx)
        } else {
            SwipeFixtures.pathThroughHebrew(word, keySizePx)
        }
        val transformedPoints = path.points.toMutableList().apply {
            val start = transform.reverseStart
            val end = transform.reverseEndExclusive
            if (start != null || end != null) {
                require(start != null && end != null && start in indices && end in 1..size && start < end) {
                    "Invalid reverse segment ${transform.reverseStart}..${transform.reverseEndExclusive} for '$word'"
                }
                subList(start, end).reverse()
            }
        }
        val cancelled = transform.cancelAfterPoint != null
        val injectedPoints = transform.cancelAfterPoint?.let { index ->
            require(index in transformedPoints.indices) { "Invalid cancel point $index for '$word'" }
            transformedPoints.take(index + 1)
        } ?: transformedPoints
        val injected = pointer.injectSwipe(
            points = injectedPoints,
            surfaceBounds = window.surfaceBounds,
            stepMs = transform.stepMs,
            jitterSeed = transform.jitterSeed,
            jitterPx = transform.jitterPx,
            cancel = cancelled,
            pauseAfterPoint = transform.pauseAfterPoint,
            pauseMs = transform.pauseMs,
        )
        pendingPointerEvents = injected
        pendingGestureSeed = transform.jitterSeed
        return cancelled
    }

    fun tapKey(key: String, checkpointEach: Boolean = true) {
        val logicalKey = key.lowercase()
        if (logicalKey == "globe") {
            val before = expectedLanguage
            pendingPointerEvents = editor.tapMarkedKey(keyDescription(logicalKey))
            expectedLanguage = if (before == Language.ENGLISH) Language.HEBREW else Language.ENGLISH
            checkpoint("switchLanguage", verifyEnvironment = true)
            return
        }
        pendingPointerEvents = editor.tapMarkedKey(keyDescription(logicalKey))
        when (logicalKey) {
            "space" -> {
                insertExpected(" ")
            }
            "backspace" -> {
                deleteExpectedOne()
            }
            else -> {
                val textBeforeCursor = expectedText.take(expectedSelection)
                val value = if (logicalKey.length == 1 && logicalKey.first().isLetter() &&
                    (textBeforeCursor.isEmpty() || textBeforeCursor.last() in ".!?\n")
                ) logicalKey.uppercase() else logicalKey
                insertExpected(value)
            }
        }
        if (checkpointEach) checkpoint("tapKey($key)")
    }

    fun tapSpace(checkpointEach: Boolean = true) = tapKey("space", checkpointEach)

    fun switchLanguage() = tapKey("globe")

    fun twoFingerLanguageSwitch() {
        val window = keyboard()
        val center = window.keyCenter("space")
        val localX = (center.x - window.surfaceBounds.left).toFloat()
        val localY = (center.y - window.surfaceBounds.top).toFloat()
        val paths = listOf(
            listOf(
                com.iaido.core.gesture.GesturePoint(localX - 24f, localY, 0L),
                com.iaido.core.gesture.GesturePoint(localX + 80f, localY, 16L),
            ),
            listOf(
                com.iaido.core.gesture.GesturePoint(localX + 24f, localY, 0L),
                com.iaido.core.gesture.GesturePoint(localX + 128f, localY, 16L),
            ),
        )
        pendingPointerEvents = pointer.injectMultiPointer(paths, window.surfaceBounds)
        expectedLanguage = if (expectedLanguage == Language.ENGLISH) Language.HEBREW else Language.ENGLISH
        checkpoint("twoFingerLanguageSwitch", verifyEnvironment = true)
    }

    fun pressBackspace(count: Int = 1, checkpointEach: Boolean = true) {
        require(count >= 0) { "Backspace count cannot be negative" }
        repeat(count) {
            editor.pressBackspace()
            deleteExpectedOne()
            if (checkpointEach) checkpoint("pressBackspace")
        }
    }

    fun moveCursorLeft(count: Int = 1, checkpointEach: Boolean = true) {
        require(count >= 0) { "Cursor movement count cannot be negative" }
        repeat(count) {
            editor.moveCursorLeft()
            expectedSelection = (expectedSelection - 1).coerceAtLeast(0)
            if (checkpointEach) checkpoint("moveCursorLeft")
        }
    }

    fun waitForCorrection(expected: String) {
        editor.waitForText(expected)
        expectedText = expected
        expectedSelection = editor.selection().last
        checkpoint("waitForCorrection")
    }

    fun tapSuggestion(index: Int, correctedText: String? = null) {
        require(index >= 0) { "Suggestion index cannot be negative" }
        val suggestion = device.findObject(By.desc("Iaido suggestion $index"))
            ?: error("Missing suggestion $index")
        val bounds = suggestion.visibleBounds
        pendingPointerEvents = pointer.injectScreenSwipe(
            listOf(
                PointF((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f),
                PointF((bounds.left + bounds.right) / 2f, bounds.top.toFloat() - 64f),
            ),
        )
        if (correctedText != null) {
            expectedText = correctedText
            expectedSelection = editor.selection().last
        } else {
            editor.waitForText(editor.text())
            expectedText = editor.text()
            expectedSelection = editor.selection().last
        }
        checkpoint("tapSuggestion($index)")
    }

    private data class ReelSwipeTarget(
        val start: PointF,
        val verticalDistancePx: Float,
        val validatePath: (List<PointF>) -> Unit,
    )

    /**
     * Resolves where to start the reel swipe for suggestion [index], retrying the whole
     * find-then-read-bounds lookup on [androidx.test.uiautomator.StaleObjectException]: a node
     * found via [device.findObject] can go stale before its bounds are read if Compose
     * recomposes the suggestion strip in between, mirroring the retry pattern used elsewhere in
     * this file (see [clickTextNode], [isModeChecked]).
     */
    private fun locateReelSwipeTarget(index: Int, verticalDistancePx: Float): ReelSwipeTarget {
        val deadline = SystemClock.elapsedRealtime() + ImeSystemController.DEFAULT_TIMEOUT_MS
        while (true) {
            try {
                return tryLocateReelSwipeTarget(index, verticalDistancePx)
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                if (SystemClock.elapsedRealtime() >= deadline) throw e
                device.waitForIdle()
                SystemClock.sleep(50L)
            }
        }
    }

    private fun tryLocateReelSwipeTarget(index: Int, verticalDistancePx: Float): ReelSwipeTarget {
        var suggestion = device.findObject(By.desc("Iaido suggestion $index"))
        if (suggestion == null) {
            // Not found here can mean two different things: the accessibility tree is merely
            // stale (a node exists on screen but UiAutomator hasn't published it -- the case the
            // fixed-offset fallback below exists for), or the word genuinely hasn't finalized into
            // a SuggestionChip/correctionHistory entry yet, so there is no chip node to find at
            // all -- confirmed via dumpWindowHierarchy immediately after a plain single-word swipe:
            // neither "Iaido suggestion 0" nor any "Iaido replacement:" node is published, and the
            // host editor still shows the bare swiped word. The live swipe-typing transaction can
            // stay open indefinitely until some real external event finalizes it (see
            // SwipeTypingCoordinator's onCursorMoved/onExternalEdit), and nothing else in a plain
            // reel-correction test provides that trigger. Nudge the host editor's cursor with its
            // own "cursor left" control (a real external selection change, the same one
            // moveCursorLeft() elsewhere in this file uses deliberately for this purpose) to force
            // that finalize, then try again before falling back to geometry. This leaves the
            // cursor one position left of where it was, but that's harmless here: every caller of
            // this function re-reads the cursor from the editor *after* the reel drag it's about
            // to perform actually commits a change, rather than trusting a position recorded
            // beforehand.
            editor.moveCursorLeft()
            device.waitForIdle()
            suggestion = device.findObject(By.desc("Iaido suggestion $index"))
        }
        if (suggestion != null) {
            val bounds = suggestion.visibleBounds
            return ReelSwipeTarget(
                start = PointF((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f),
                verticalDistancePx = verticalDistancePx,
                validatePath = {},
            )
        }
        val root = device.findObject(By.desc(KeyboardWindowLocator.ROOT_DESCRIPTION))
            ?: error("Missing keyboard root while locating suggestion $index")
        val surface = device.findObject(By.desc(KeyboardWindowLocator.SURFACE_DESCRIPTION))
            ?: error("Missing keyboard surface while locating suggestion $index")
        val strip = device.findObject(By.desc(SUGGESTION_STRIP_DESCRIPTION))
            ?: error("Missing suggestion strip while locating suggestion $index")
        val stripBounds = strip.visibleBounds
        val rootBounds = root.visibleBounds
        val surfaceBounds = surface.visibleBounds
        check(rootBounds.contains(stripBounds)) {
            "Suggestion strip is outside the keyboard root: strip=$stripBounds root=$rootBounds"
        }
        check(stripBounds.bottom <= surfaceBounds.top) {
            "Suggestion strip overlaps the keyboard surface: strip=$stripBounds surface=$surfaceBounds"
        }
        // The strip auto-scrolls horizontally to keep the newest chip in view (see
        // SuggestionStrip's LaunchedEffect(ordered.map { it.id }, rtl)), which can leave a chip
        // this fallback is trying to reach (via a fixed offset off the strip's own left/leading
        // edge -- the "Iaido suggestion $index" node isn't reliably findable before the first
        // drag, a pre-existing accessibility-tree staleness condition) scrolled out of that fixed
        // offset's reach. Reset the strip's own horizontal scroll to its start first, so the
        // fixed-offset point below reliably lands on the leading chip regardless of how many
        // chips or edge-anchored replacement-reel content the strip has accumulated.
        val resetSwipeY = ((stripBounds.top + stripBounds.bottom) / 2f).toInt()
        device.swipe(stripBounds.left + 4, resetSwipeY, stripBounds.right - 4, resetSwipeY, 12)
        device.waitForIdle()
        // The strip's own outer height is pinned to fit the tallest possible reel
        // (MAX_REEL_VISIBLE_SLOTS), but each chip's own Row is only as tall as its actual
        // alternative count needs (as little as one REEL_STEP_DP slot) and is top-aligned within
        // that pinned strip -- so a chip with few alternatives (as short as one slot) sits in a
        // narrow band near the strip's own top edge, not its vertical center. Targeting near that
        // top edge (rather than the strip's center) both reliably lands within any chip's bounds,
        // regardless of its height, and -- since the keyboard's own root view leaves very little
        // room above the strip -- maximizes the vertical drag room available before hitting that
        // root boundary, which a short chip needs to reach even one step past its own
        // alternatives into an appended join candidate.
        val point = PointF(
            (stripBounds.left + 80f).coerceIn(stripBounds.left + 1f, stripBounds.right - 1f),
            stripBounds.top + (4f + REPLACEMENT_REEL_STEP_DP - 8f) * density,
        )
        val safeDistance = verticalDistancePx.coerceIn(
            rootBounds.top.toFloat() - point.y,
            surfaceBounds.top.toFloat() - point.y - 1f,
        )
        return ReelSwipeTarget(
            start = point,
            verticalDistancePx = safeDistance,
            validatePath = { path ->
                check(path.all { candidate ->
                    rootBounds.contains(candidate.x.toInt(), candidate.y.toInt()) &&
                        candidate.y < surfaceBounds.top
                }) {
                    "Correction reel fallback path leaves the suggestion area: path=$path root=$rootBounds surface=$surfaceBounds"
                }
            },
        )
    }

    fun swipeSuggestion(index: Int, verticalDistancePx: Float): String {
        device.waitForIdle()
        SystemClock.sleep(REEL_SETTLE_WAIT_MS)
        val target = locateReelSwipeTarget(index, verticalDistancePx)
        fun injectReelSwipe() {
            val path = (1..3).map { step ->
                val fraction = step / 3f
                PointF(target.start.x, target.start.y + target.verticalDistancePx * fraction)
            }.let { listOf(target.start) + it }
            target.validatePath(path)
            pendingPointerEvents = pointer.injectScreenSwipe(
                points = path,
                holdBeforeMoveMs = 520L,
            )
        }
        val before = expectedText
        injectReelSwipe()
        var after = runCatching { editor.waitForTextChange(before, timeoutMs = 1_500L) }.getOrNull()
        if (after == null) {
            device.waitForIdle()
            SystemClock.sleep(500L)
            injectReelSwipe()
            after = editor.waitForTextChange(before, timeoutMs = REEL_RETRY_COMMIT_TIMEOUT_MS)
        }
        expectedText = after
        device.waitForIdle()
        SystemClock.sleep(REEL_COMMIT_SETTLE_WAIT_MS)
        expectedSelection = editor.selection().last
        checkpoint("swipeSuggestion($index, $verticalDistancePx)")
        return expectedText
    }

    fun swipeSuggestionImmediately(index: Int, verticalDistancePx: Float): String {
        device.waitForIdle()
        SystemClock.sleep(REEL_SETTLE_WAIT_MS)
        val target = locateReelSwipeTarget(index, verticalDistancePx)
        val path = (1..3).map { step ->
            val fraction = step / 3f
            PointF(target.start.x, target.start.y + target.verticalDistancePx * fraction)
        }.let { listOf(target.start) + it }
        target.validatePath(path)
        val before = expectedText
        pendingPointerEvents = pointer.injectScreenSwipe(points = path, holdBeforeMoveMs = 0L)
        val after = editor.waitForTextChange(before, timeoutMs = 1_500L)
        expectedText = after
        device.waitForIdle()
        SystemClock.sleep(REEL_COMMIT_SETTLE_WAIT_MS)
        expectedSelection = editor.selection().last
        checkpoint("swipeSuggestionImmediately($index, $verticalDistancePx)")
        return expectedText
    }

    /**
     * Like [swipeSuggestion], but returns the elapsed ms between the release (ACTION_UP) and the
     * editor text actually changing. A single synthetic reel swipe unreliably lands on the chip's
     * own drag detector on the very first attempt in this emulator (matching [swipeSuggestion]'s
     * own established first-attempt flakiness) -- so, like [swipeSuggestion], this always performs
     * a throwaway first attempt, then measures only the second, empirically-reliable attempt's
     * release-to-commit latency, re-baselining `before` immediately beforehand so the first
     * attempt's own (possibly unrelated) effect on the text doesn't corrupt the measured baseline.
     */
    fun swipeSuggestionCommitLatencyMs(index: Int, verticalDistancePx: Float): Long {
        device.waitForIdle()
        SystemClock.sleep(REEL_SETTLE_WAIT_MS)
        val target = locateReelSwipeTarget(index, verticalDistancePx)
        val path = (1..3).map { step ->
            val fraction = step / 3f
            PointF(target.start.x, target.start.y + target.verticalDistancePx * fraction)
        }.let { listOf(target.start) + it }
        target.validatePath(path)
        var releasedAtMs = -1L
        fun injectReelSwipe() {
            releasedAtMs = -1L
            pendingPointerEvents = pointer.injectScreenSwipe(
                points = path,
                holdBeforeMoveMs = 520L,
                onEvent = { event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        releasedAtMs = SystemClock.elapsedRealtime()
                    }
                },
            )
        }
        injectReelSwipe()
        runCatching { editor.waitForTextChange(expectedText, timeoutMs = 1_500L) }
        device.waitForIdle()
        SystemClock.sleep(500L)
        val before = editor.text()
        injectReelSwipe()
        val after = editor.waitForTextChange(before, timeoutMs = REEL_RETRY_COMMIT_TIMEOUT_MS)
        check(releasedAtMs >= 0L) { "Reel swipe never reported a release event" }
        val committedAtMs = SystemClock.elapsedRealtime()
        expectedText = after
        device.waitForIdle()
        SystemClock.sleep(REEL_COMMIT_SETTLE_WAIT_MS)
        expectedSelection = editor.selection().last
        checkpoint("swipeSuggestionCommitLatencyMs($index, $verticalDistancePx)")
        return committedAtMs - releasedAtMs
    }

    fun holdBackspace(durationMs: Long = 1_000L): String {
        val before = editor.text()
        val center = keyboard().keyCenter("backspace")
        pendingPointerEvents = pointer.injectLongPress(center.x.toFloat(), center.y.toFloat(), durationMs)
        val after = editor.text()
        check(after.length < before.length) { "Holding backspace did not delete text: before='$before' after='$after'" }
        expectedText = after
        expectedSelection = editor.selection().last
        checkpoint("holdBackspace($durationMs)")
        return after
    }

    fun swipeBackspaceLeftThenRight(leftDistancePx: Float = 168f): List<String> {
        val before = editor.text()
        val center = keyboard().keyCenter("backspace")
        val snapshots = mutableListOf<String>()
        pendingPointerEvents = pointer.injectScreenSwipe(
            points = listOf(
                PointF(center.x.toFloat(), center.y.toFloat()),
                PointF(center.x - leftDistancePx, center.y.toFloat()),
                PointF(center.x - leftDistancePx / 2f, center.y.toFloat()),
                PointF(center.x.toFloat(), center.y.toFloat()),
            ),
            holdBeforeMoveMs = 520L,
            onEvent = { event ->
                if (event.action == android.view.MotionEvent.ACTION_MOVE) snapshots += editor.text()
            },
        )
        editor.waitForText(before)
        val after = editor.text()
        check(after == before) { "Backspace swipe did not restore the original text: before='$before' after='$after' snapshots=$snapshots" }
        check(snapshots.any { it.length < before.length }) { "Backspace swipe never changed the text live: $snapshots" }
        expectedText = after
        expectedSelection = editor.selection().last
        checkpoint("swipeBackspaceLeftThenRight")
        return snapshots
    }

    fun swipeBackspaceVertical(distancePx: Float) {
        val center = keyboard().keyCenter("backspace")
        pendingPointerEvents = pointer.injectScreenSwipe(
            points = listOf(
                PointF(center.x.toFloat(), center.y.toFloat()),
                PointF(center.x.toFloat(), center.y.toFloat() + distancePx),
            ),
        )
        expectedText = editor.text()
        expectedSelection = editor.selection().last
        checkpoint("swipeBackspaceVertical($distancePx)")
    }

    fun injectCancelledSwipe(word: String, transform: PathTransform = PathTransform(cancelAfterPoint = 1)) {
        require(transform.cancelAfterPoint != null) { "Cancelled swipe transform must specify cancelAfterPoint" }
        swipePath(word, transform)
    }

    fun injectSplitWords(words: List<String>, expected: String? = null) {
        require(words.size >= 2) { "Split gesture needs at least two words/parts" }
        val window = keyboard()
        val keySizePx = window.surfaceBounds.width().toFloat() / 10f
        val paths = words.map { word -> SwipeFixtures.pathThroughQwerty(word, keySizePx).points }
        pointer.injectMultiPointer(paths, window.surfaceBounds)
        if (expected != null) {
            editor.waitForText(expected)
            expectedText = expected
            expectedSelection = editor.selection().last
        }
        checkpoint("injectSplitWords(${words.joinToString("+")})")
    }

    fun longPressKey(key: String) {
        val marked = device.findObject(By.desc(keyDescription(key.lowercase())))
            ?: error("Missing key '$key'")
        val bounds = marked.visibleBounds
        pendingPointerEvents = pointer.injectLongPress(
            centerX = (bounds.left + bounds.right) / 2f,
            centerY = (bounds.top + bounds.bottom) / 2f,
        )
        checkpoint("longPressKey($key)")
    }

    fun pressKey(keyCode: Int) {
        editor.pressKey(keyCode)
        checkpoint("pressKey($keyCode)")
    }

    fun assertText(expected: String) {
        editor.waitForText(expected)
        check(editor.text() == expected) { "Expected text '$expected', observed '${editor.text()}'" }
        expectedText = expected
        expectedSelection = editor.selection().last
        checkpoint("assertText")
    }

    fun assertTextAndCursor(expected: String) {
        assertText(expected)
        check(editor.selection().last == expected.length) {
            "Expected cursor at ${expected.length}, observed ${editor.selection()} for '$expected'"
        }
        expectedSelection = expected.length
        checkpoint("assertTextAndCursor")
    }

    fun selectSpacingModeThroughSettings(mode: SpacingMode) {
        val firstSettings = openSettings()
        try {
            val label = spacingModeLabel(mode)
            clickTextNode(label)
            waitUntil("spacing mode '$label' selected") {
                isModeChecked(label) || isSpacingModeStored(mode)
            }
        } finally {
            firstSettings.finish()
        }
        SystemClock.sleep(300L)
        recreateInputView()
        val restartedSettings = openSettings()
        try {
            val label = spacingModeLabel(mode)
            waitUntil("persisted spacing mode '$label'") {
                isModeChecked(label) || isSpacingModeStored(mode)
            }
        } finally {
            restartedSettings.finish()
        }
        recreateInputView()
    }

    /**
     * Selects a mode for a behavior-focused scenario without reopening Settings. The real Settings
     * interaction remains covered by ImeSpacingModesE2eTest; inference tests should not repeat that
     * transition for every fixture because it is both slow and accessibility-tree fragile.
     */
    fun setSpacingModeForBehaviorTest(mode: SpacingMode) {
        runBlocking {
            instrumentation.targetContext.settingsStore.edit { preferences ->
                preferences[spacingModeKey] = spacingModeStoredValue(mode)
            }
        }
        system.waitForSpacingMode(mode)
        editor.focus()
        checkpoint("reloadSpacingModeForBehaviorTest")
    }

    fun previewReplacementThenCancel(sourceWords: Int, replacementWords: Int) {
        dragReplacement(sourceWords, replacementWords, cancel = true)
        editor.waitForText(expectedText)
        expectedSelection = editor.selection().last
        checkpoint("previewReplacementThenCancel($sourceWords,$replacementWords)")
    }

    fun releaseReplacement(sourceWords: Int, replacementWords: Int) {
        val before = expectedText
        dragReplacement(sourceWords, replacementWords, cancel = false)
        expectedText = editor.waitForTextChange(before)
        expectedSelection = editor.selection().last
        checkpoint("releaseReplacement($sourceWords,$replacementWords)")
    }

    fun assertLanguage(expected: Language) {
        check(expectedIme == system.iaidoImeId) { "Language markers are unavailable on the reference IME" }
        check(keyboard().language == expected) {
            "Expected language $expected, observed ${keyboard().language}"
        }
        expectedLanguage = expected
        checkpoint("assertLanguage($expected)")
    }

    fun assertKeyboardGeometry() {
        val window = keyboard()
        val display = android.graphics.Rect(0, 0, device.displayWidth, device.displayHeight)
        val navigationInset = instrumentation.targetContext.resources.getIdentifier(
            "navigation_bar_height",
            "dimen",
            "android",
        ).takeIf { it != 0 }
            ?.let(instrumentation.targetContext.resources::getDimensionPixelSize)
            ?: 0
        val safeBottom = navigationSafeBottom(display, navigationInset)
        check(window.surfaceBounds.bottom <= safeBottom) {
            "Keyboard surface reaches navigation area: surface=${window.surfaceBounds} safeBottom=$safeBottom"
        }
        check(window.rootBounds.bottom <= display.bottom) {
            "Keyboard root is outside display: root=${window.rootBounds} display=$display"
        }
        check(window.keyBounds.values.all { it.bottom <= window.surfaceBounds.bottom }) {
            "A key extends below the marked keyboard surface: keys=${window.keyBounds} surface=${window.surfaceBounds}"
        }
        checkpoint("assertKeyboardGeometry")
    }

    fun hideAndShowKeyboard() {
        system.hideKeyboard()
        editor.focus()
        checkpoint("hideAndShowKeyboard", verifyEnvironment = true)
    }

    fun relaunchHost() {
        system.launchHost(autoSpaceFixture?.preferenceValue)
        editor.focus()
        checkpoint("relaunchHost", verifyEnvironment = true)
    }

    fun backgroundAndForeground() {
        device.pressHome()
        system.launchHost(autoSpaceFixture?.preferenceValue)
        editor.focus()
        checkpoint("backgroundAndForeground", verifyEnvironment = true)
    }

    fun recreateInputView() {
        system.enableAndSelect(system.iaidoImeId)
        expectedIme = system.iaidoImeId
        checkpoint("recreateInputView", verifyEnvironment = true)
    }

    fun captureScreenshot(name: String): java.io.File =
        ArtifactWriter.captureScreenshot(name, device)

    fun state(): ImeScenarioState = ImeScenarioState(
        expectedText = expectedText,
        expectedSelection = expectedSelection,
        expectedIme = expectedIme,
        expectedLanguage = expectedLanguage,
        trace = trace.toList(),
    )

    fun switchKeyboard(imeId: String) {
        system.enableAndSelect(imeId)
        system.waitForImeVisible(imeId)
        editor.focus()
        expectedIme = imeId
        checkpoint("switchKeyboard($imeId)", verifyEnvironment = true)
    }

    fun switchToReferenceKeyboard() = switchKeyboard(system.referenceImeId)

    fun switchBackToIaido() = switchKeyboard(system.iaidoImeId)

    fun tapReferenceCommit() {
        check(expectedIme == system.referenceImeId) { "Reference keyboard is not selected" }
        pendingPointerEvents = editor.tapMarkedKey("Iaido reference commit")
        insertExpected("reference")
        checkpoint("tapReferenceCommit")
    }

    private fun setup() {
        val setupStartedAtMs = SystemClock.elapsedRealtime()
        val fixture = autoSpaceFixture?.preferenceValue
        val bootstrap = suiteState.needsBootstrap()
        val needsImeSelection = suiteState.needsImeSelection(system.iaidoImeId)
        val spacingModeChanged = false
        if (bootstrap) {
            system.launchHost(fixture)
            // Bind the selected IME after the editor exists. Selecting it before the host is
            // focused can leave Android's input-method manager with a selected-but-unbound IME.
            system.enableAndSelect(system.iaidoImeId)
        } else {
            // Keep the existing editor activity alive before changing the selected IME. The
            // selection handoff can temporarily hide the editor from UiAutomator; checking after
            // that handoff makes every fixture look like a missing host and relaunches it.
            system.ensureHostVisible(fixture)
            if (spacingModeChanged || needsImeSelection) system.enableAndSelect(system.iaidoImeId)
        }
        system.setAutoSpaceFixture(fixture)
        editor.focus()
        if (bootstrap || spacingModeChanged || needsImeSelection) {
            system.waitForImeVisible(system.iaidoImeId)
        }
        val stateAdapter = DebugKeyboardStateAdapter(instrumentation.targetContext)
        val baselineId = suiteState.baselineOrNull() ?: stateAdapter.saveBaseline().also(suiteState::setBaseline)
        stateAdapter.restoreBaseline(baselineId)
        val clearStartedAtMs = SystemClock.elapsedRealtime()
        editor.clear()
        Log.i(
            "E2E-PERF",
            "phase=editor_clear durationMs=${SystemClock.elapsedRealtime() - clearStartedAtMs}",
        )
        expectedText = ""
        expectedSelection = 0
        expectedLanguage = Language.ENGLISH
        checkpoint("setup", verifyEnvironment = bootstrap)
        suiteState.markReady(fixture, expectedIme, expectedLanguage)
        Log.i(
            "E2E-PERF",
            "phase=scenario_setup durationMs=${SystemClock.elapsedRealtime() - setupStartedAtMs} " +
                "bootstrap=$bootstrap fixture=${fixture ?: "none"} imeSelection=$needsImeSelection " +
                "spacingReset=$spacingModeChanged",
        )
    }

    private fun keyboard(): KeyboardWindow = KeyboardWindowLocator.locate(device)

    private companion object {
        val suiteSessionState = ImeSuiteSessionState()
    }

    private fun openSettings(): android.app.Activity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun spacingModeLabel(mode: SpacingMode): String = when (mode) {
        SpacingMode.MANUAL -> "Manual spacing"
        SpacingMode.AFTER_SWIPE -> "Space after swipe"
        SpacingMode.INFER_SPACES -> "Infer spaces"
    }

    /**
     * Resolves the checkable node for [label], or `null` if it cannot currently be found (e.g.
     * a transient recomposition right after a click). Returns `null` rather than throwing so
     * callers polling via [waitUntil] get real retries instead of aborting on the first miss;
     * [waitUntil] itself still raises a failure if the node is never found before its timeout,
     * so genuine absence is still reported as a failure.
     */
    private fun modeNode(label: String): androidx.test.uiautomator.UiObject2? {
        var node = findTextNode(label) ?: return null
        repeat(4) {
            val current = node ?: return null
            if (current.isCheckable) return current
            node = current.parent
        }
        return node
    }

    /**
     * `true` if [label]'s mode is currently checked, `false` if it isn't (or can't be resolved
     * right now, including a node that went stale between being found and being queried).
     */
    private fun isModeChecked(label: String): Boolean = try {
        modeNode(label)?.isChecked == true
    } catch (e: androidx.test.uiautomator.StaleObjectException) {
        false
    }

    private fun isSpacingModeStored(mode: SpacingMode): Boolean = runBlocking {
        spacingModeFromStoredValue(
            instrumentation.targetContext.settingsStore.data.first()[spacingModeKey],
        ) == mode
    }

    /**
     * Resolves the text node for [label] (e.g. to click it), scrolling it into view first.
     * Returns `null` rather than throwing so callers can retry via [waitUntil].
     */
    private fun findTextNode(label: String): androidx.test.uiautomator.UiObject2? {
        scrollToText(label)
        return device.findObject(By.text(label))
    }

    /**
     * Finds and clicks [label]'s text node, scrolling it into view first, using the same
     * [waitUntil] timeout/retry pattern used elsewhere in this file. Retries the whole
     * find-then-click on any failure: a single immediate lookup right after opening Settings can
     * race the initial Compose layout pass, and a node found right after a scroll can go stale
     * (`StaleObjectException`) before the click lands if Compose recomposes in between. It still
     * raises a failure (via [waitUntil]) if the click never lands before the timeout.
     */
    private fun clickTextNode(label: String) {
        waitUntil("spacing mode option '$label' clicked") {
            val option = modeNode(label)
            if (option == null) {
                false
            } else {
                try {
                    option.click()
                    true
                } catch (e: androidx.test.uiautomator.StaleObjectException) {
                    false
                }
            }
        }
    }

    /**
     * Scrolls the Settings screen's scrollable container (a Compose `verticalScroll` Column)
     * until [label] is on screen, so callers can rely on `device.findObject(By.text(label))`
     * afterward. Spacing-mode options live below the initial fold on a normal phone viewport
     * (headline + live preview + Setup/App updates/Gestures sections above them), so lookups
     * must scroll first rather than assuming the node is already in the accessibility snapshot.
     * A no-op (best-effort) when the label is already visible or no scrollable container exists.
     */
    private fun scrollToText(label: String) {
        if (device.hasObject(By.text(label))) return
        repeat(scrollToTextMaxSwipes) {
            val scrollable = device.findObject(By.scrollable(true)) ?: return
            val bounds = runCatching { scrollable.visibleBounds }.getOrNull() ?: return
            val x = (bounds.left + bounds.right) / 2
            // Swipe from near the bottom of the scrollable area to near its top, i.e. scroll the
            // content *down* into view, since the spacing-mode options sit below the fold.
            val startY = bounds.top + (bounds.height() * 0.8f).toInt()
            val endY = bounds.top + (bounds.height() * 0.2f).toInt()
            device.swipe(x, startY, x, endY, 20)
            device.waitForIdle()
            SystemClock.sleep(150L)
            if (device.hasObject(By.text(label))) return
        }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + ImeSystemController.DEFAULT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        error("Timed out waiting for $description")
    }

    /**
     * Resolves the edge-anchored replacement reel's bounds, falling back to a fixed offset off
     * the suggestion strip's own leading edge when the reel's own accessibility node isn't found
     * -- this strip's LazyRow items, like suggestion chips (see [tryLocateReelSwipeTarget]), never
     * publish to UiAutomator's accessibility tree before this reel is itself the thing being
     * dragged (confirmed: neither a probe tap nor polling mid-drag via
     * [By.descStartsWith] finds it -- the tree simply never gains this node until well after a
     * completed gesture, if at all, so [By.descStartsWith] can't be used to locate or verify it
     * at any point up to and including the drag itself). The fixed-offset fallback assumes the
     * reel is the strip's leading (and only) item, which is only true while no suggestion chips
     * exist yet -- a live join can coexist with chips (see the round-2 investigation that added
     * the liveReplacementOptionIds filter), and under RTL the reel renders as the strip's
     * *trailing* item, not leading. So this actively checks for a leading chip (not just assumes
     * its absence) and fails loudly, naming what it did and didn't find, rather than silently
     * dragging the wrong element and producing a confusing text-mismatch failure downstream.
     */
    private fun locateReplacementReelBounds(targetDescription: String): android.graphics.Rect {
        val deadline = SystemClock.elapsedRealtime() + ImeSystemController.DEFAULT_TIMEOUT_MS
        while (true) {
            device.findObject(By.descStartsWith("Iaido replacement:"))?.let { return it.visibleBounds }
            val strip = device.findObject(By.desc(SUGGESTION_STRIP_DESCRIPTION))
            val leadingChipExists = device.findObject(By.desc("Iaido suggestion 0")) != null
            if (strip != null && !leadingChipExists) {
                val stripBounds = strip.visibleBounds
                return android.graphics.Rect(
                    (stripBounds.left + 20).coerceIn(stripBounds.left + 1, stripBounds.right - 1),
                    stripBounds.top + 10,
                    (stripBounds.left + 140).coerceIn(stripBounds.left + 1, stripBounds.right - 1),
                    stripBounds.top + 170,
                )
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                error(
                    "Timed out waiting for replacement reel before previewing '$targetDescription' " +
                        "(strip found=${strip != null}, leading chip present=$leadingChipExists)",
                )
            }
            device.waitForIdle()
            SystemClock.sleep(50L)
        }
    }

    private fun dragReplacement(sourceWords: Int, replacementWords: Int, cancel: Boolean) {
        val targetDescription = "Iaido replacement: $sourceWords source " +
            (if (sourceWords == 1) "word" else "words") + " to $replacementWords replacement " +
            (if (replacementWords == 1) "word" else "words")
        val bounds = locateReplacementReelBounds(targetDescription)
        // Both current call sites want the reel to move exactly one step from its resting
        // position (index 0, the top/identity candidate) to index 1 -- the alternative right next
        // to it, whatever that alternative's shape (a fixture can offer more than just an
        // identity/target pair, e.g. SPLIT_REEL's "inthe"/"in the"/"the"/"in", so overshooting
        // even one extra step lands on the wrong one). A fixed pixel offset (as this used before)
        // doesn't reliably clear ReplacementReelGroup's own DRAG_THRESHOLD_DP touch-slop-like
        // consumption while also staying under two full REEL_STEP_DP steps, so this computes the
        // distance from the reel's own step size directly -- comfortably past one step's worth of
        // travel (>0.5 steps) while well short of two (<1.5 steps) once slop is consumed.
        val stepPx = REPLACEMENT_REEL_STEP_DP * density
        val dragDistancePx = stepPx * 1.25f
        val points = listOf(
            PointF((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f),
            PointF((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f - dragDistancePx),
        )
        pendingPointerEvents = pointer.injectScreenSwipe(
            points = points,
            cancel = cancel,
            holdBeforeMoveMs = 520L,
            onEvent = { event ->
                // This reel's own accessibility node -- like a suggestion chip's (see
                // locateReplacementReelBounds/tryLocateReelSwipeTarget) -- never publishes to
                // UiAutomator's tree, even while it's the one actively being dragged (confirmed:
                // polling `By.descStartsWith("Iaido replacement:")` mid-drag here returns null for
                // the whole gesture), so this only gives Compose a moment to process the move
                // before the gesture continues, rather than confirming the preview via the a11y
                // tree. The caller verifies the actual outcome afterward via the committed text.
                if (event.action == android.view.MotionEvent.ACTION_MOVE) {
                    device.waitForIdle()
                    SystemClock.sleep(150L)
                }
            },
        )
    }

    private fun checkpoint(action: String, verifyEnvironment: Boolean = false) {
        val checkpointStartedAtMs = SystemClock.elapsedRealtime()
        val observedText = editor.waitForText(expectedText)
        val observedSelection = editor.selection()
        val observedIme = if (verifyEnvironment) system.selectedInputMethodId() else expectedIme
        val observedLanguage = if (verifyEnvironment && expectedIme == system.iaidoImeId) {
            system.waitForImeVisible(expectedIme)
            keyboard().language
        } else {
            expectedLanguage
        }
        val event = ImeScenarioEvent(
            index = trace.size,
            action = action,
            expectedText = expectedText,
            observedText = observedText,
            expectedSelection = expectedSelection,
            observedSelection = observedSelection,
            expectedIme = expectedIme,
            observedIme = observedIme,
            expectedLanguage = expectedLanguage,
            observedLanguage = observedLanguage,
            gestureSeed = pendingGestureSeed,
            pointerEvents = pendingPointerEvents,
        )
        trace += event
        pendingGestureSeed = null
        pendingPointerEvents = emptyList()
        check(observedText == expectedText && observedSelection.first == expectedSelection &&
            observedSelection.last == expectedSelection && observedIme == expectedIme &&
            observedLanguage == expectedLanguage) {
            "IME scenario mismatch at #${event.index} '$action':\n$event"
        }
        Log.i(
            "E2E-PERF",
            "phase=checkpoint durationMs=${SystemClock.elapsedRealtime() - checkpointStartedAtMs} " +
                "action=${action.replace(' ', '_')} verifyEnvironment=$verifyEnvironment",
        )
    }

    private fun keyDescription(key: String): String =
        KeyboardWindowLocator.KEY_DESCRIPTION_PREFIX + key

    private fun insertExpected(value: String) {
        val cursor = expectedSelection.coerceIn(0, expectedText.length)
        expectedText = expectedText.take(cursor) + value + expectedText.drop(cursor)
        expectedSelection = cursor + value.length
    }

    private fun deleteExpectedOne() {
        if (expectedSelection <= 0) return
        expectedText = expectedText.removeRange(expectedSelection - 1, expectedSelection)
        expectedSelection -= 1
    }

}
