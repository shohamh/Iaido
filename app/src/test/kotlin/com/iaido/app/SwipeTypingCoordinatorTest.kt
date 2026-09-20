package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.recognition.InferenceSegmenter
import com.iaido.core.recognition.NgramContextScorer
import com.iaido.core.recognition.ScoredCandidate
import com.iaido.core.recognition.SplitWordParts
import com.iaido.core.typing.SpacingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwipeTypingCoordinatorTest {
    private val layout = KeyboardLayout.qwertyTestLayout()

    @Test
    fun `manual spacing commits recognized swipes without an automatic separator`() {
        val editor = FakeEditor()
        val coordinator = coordinator(editor, SpacingMode.MANUAL, dictionary("in", "the"))

        coordinator.onSingleSwipe(path(1), layout)
        coordinator.onSingleSwipe(path(2), layout)

        assertEquals("inthe", editor.text)
    }

    @Test
    fun `space after swipe adds one trailing space for each complete gesture unit`() {
        val editor = FakeEditor()
        val coordinator = coordinator(editor, SpacingMode.AFTER_SWIPE, dictionary("in", "there"))

        coordinator.onSingleSwipe(path(1), layout)
        coordinator.onTwoFingerResult(listOf(path(3), path(4)), layout)

        assertEquals("in there ", editor.text)
    }

    @Test
    fun `space after swipe does not duplicate whitespace already after the cursor`() {
        val editor = FakeEditor()
        val coordinator = coordinator(
            editor,
            SpacingMode.AFTER_SWIPE,
            dictionary("in"),
            hasFollowingWhitespace = { true },
        )

        coordinator.onSingleSwipe(path(1), layout)

        assertEquals("in", editor.text)
    }

    @Test
    fun `inference can rewrite one recognized unit into two words`() {
        val editor = FakeEditor()
        val coordinator = coordinator(editor, SpacingMode.INFER_SPACES, dictionary("in", "the"))

        coordinator.onSingleSwipe(path(5), layout)

        assertEquals("in the", editor.text)
        assertEquals(6, editor.cursor())
    }

    @Test
    fun `replacement reel previews restores and commits a full inference group`() {
        val editor = FakeEditor()
        val finalized = mutableListOf<List<String>>()
        val coordinator = coordinator(
            editor,
            SpacingMode.INFER_SPACES,
            dictionary("in", "the", "inthe"),
            finalized::add,
        )

        coordinator.onSingleSwipe(path(5), layout)
        val split = coordinator.replacementOptions().single { it.replacementWords == listOf("in", "the") }

        assertEquals(listOf("inthe"), split.sourceWords)
        assertTrue(coordinator.previewReplacement(split))
        assertEquals("in the", editor.text)
        assertEquals(6, editor.cursor())
        assertEquals(
            listOf("in", "the"),
            coordinator.replacementOptions().single { it.replacementWords == listOf("in", "the") }.sourceWords,
        )
        val previewedSplit = coordinator.replacementOptions().single { it.replacementWords == listOf("in", "the") }
        assertEquals(split.id, previewedSplit.id)

        coordinator.cancelReplacement()

        assertEquals("inthe", editor.text)
        assertTrue(coordinator.replacementOptions().any { it.replacementWords == listOf("in", "the") })
        assertTrue(coordinator.previewReplacement(split))
        assertTrue(coordinator.releaseReplacement(split))
        assertEquals("in the", editor.text)
        assertEquals(6, editor.cursor())
        assertEquals(listOf(listOf("in", "the")), finalized)
        assertFalse(coordinator.replacementOptions().isNotEmpty())
    }

    @Test
    fun `inference can rewrite two units into one word without offset drift`() {
        val editor = FakeEditor()
        val coordinator = coordinator(editor, SpacingMode.INFER_SPACES, dictionary("some", "thing", "something"))

        coordinator.onSingleSwipe(path(6), layout)
        coordinator.onSingleSwipe(path(7), layout)

        assertEquals("something", editor.text)
        assertEquals(9, editor.cursor())
        assertEquals(
            listOf(
                HostTextSpan(0, 0) to "some",
                HostTextSpan(0, 4) to "something",
            ),
            editor.replacements,
        )
    }

    @Test
    fun `context aware segmenter revises an earlier boundary once later context arrives`() {
        // Regression coverage for the production wiring gap: IaidoInputMethodService must
        // supply its real, fixture/dictionary-aware contextScorer to the InferenceSegmenter
        // it hands to SwipeTypingCoordinator. This test proves the coordinator's own logic
        // does the right thing whenever it *is* given a context-aware segmenter (mirroring
        // InferenceSegmenterTest's "context can change a sequential boundary", but driven
        // through the coordinator across multiple onSingleSwipe calls, since that's the
        // layer where the real wiring gap lived).
        val editor = FakeEditor()
        val dictionary = listOf(
            WordEntry("in", 100.0),
            WordEntry("to", 100.0),
            WordEntry("into", 1.0),
            WordEntry("the", 100.0),
        )
        val segmenter = InferenceSegmenter(
            contextScorer = NgramContextScorer(
                bigrams = mapOf(
                    ("in" to "to") to 10.0,
                    ("into" to "the") to 30.0,
                ),
            ),
        )
        val coordinator = coordinator(
            editor,
            SpacingMode.INFER_SPACES,
            dictionary,
            segmenter = segmenter,
            recognize = { path, _ -> contextRevisionCandidatesFor(path) },
        )

        coordinator.onSingleSwipe(path(21), layout)
        coordinator.onSingleSwipe(path(22), layout)

        assertEquals("in to", editor.text)

        coordinator.onSingleSwipe(path(23), layout)

        assertEquals("into the", editor.text)
    }

    @Test
    fun `seventh inference unit slides the window and finalizes only the oldest unit`() {
        val editor = FakeEditor()
        val finalized = mutableListOf<List<String>>()
        val coordinator = coordinator(
            editor = editor,
            mode = SpacingMode.INFER_SPACES,
            dictionary = dictionary("a"),
            onFinalized = finalized::add,
        )

        repeat(6) { coordinator.onSingleSwipe(path(8), layout) }
        coordinator.onSingleSwipe(path(8), layout)

        assertEquals(listOf(listOf("a")), finalized)
        assertEquals("a a a a a a a", editor.text)

        coordinator.onNonSwipeInput()

        assertEquals(
            listOf(
                listOf("a"),
                listOf("a", "a", "a", "a", "a", "a"),
            ),
            finalized,
        )
    }

    @Test
    fun `infer spaces capitalizes the first swiped word of a fresh sentence`() {
        val editor = FakeEditor()
        val coordinator = coordinator(
            editor,
            SpacingMode.INFER_SPACES,
            dictionary("hello"),
            textBeforeCursor = { "" },
        )

        coordinator.onSingleSwipe(path(9), layout)

        assertEquals("Hello", editor.text)
    }

    @Test
    fun `infer spaces capitalizes the first swiped word after terminal punctuation`() {
        val editor = FakeEditor()
        val coordinator = coordinator(
            editor,
            SpacingMode.INFER_SPACES,
            dictionary("hello"),
            textBeforeCursor = { "Done. " },
        )

        coordinator.onSingleSwipe(path(9), layout)

        assertEquals("Hello", editor.text)
    }

    @Test
    fun `infer spaces does not capitalize a swipe in the middle of a sentence`() {
        val editor = FakeEditor()
        val coordinator = coordinator(
            editor,
            SpacingMode.INFER_SPACES,
            dictionary("hello"),
            textBeforeCursor = { "hi " },
        )

        coordinator.onSingleSwipe(path(9), layout)

        assertEquals("hello", editor.text)
    }

    @Test
    fun `infer spaces capitalizes a two finger gesture unit at the start of a sentence`() {
        val editor = FakeEditor()
        val coordinator = coordinator(
            editor,
            SpacingMode.INFER_SPACES,
            dictionary("there"),
            textBeforeCursor = { "" },
        )

        coordinator.onTwoFingerResult(listOf(path(3), path(4)), layout)

        assertEquals("There", editor.text)
    }

    @Test
    fun `re ranking the same inference transaction keeps the leading word capitalized`() {
        val editor = FakeEditor()
        val coordinator = coordinator(
            editor,
            SpacingMode.INFER_SPACES,
            dictionary("some", "thing", "something"),
            textBeforeCursor = { "" },
        )

        coordinator.onSingleSwipe(path(6), layout)
        assertEquals("Some", editor.text)

        coordinator.onSingleSwipe(path(7), layout)

        assertEquals("Something", editor.text)
    }

    @Test
    fun `sliding the inference window capitalizes only the original finalized leading word`() {
        val editor = FakeEditor()
        val finalized = mutableListOf<List<String>>()
        val coordinator = coordinator(
            editor = editor,
            mode = SpacingMode.INFER_SPACES,
            dictionary = dictionary("a"),
            onFinalized = finalized::add,
            textBeforeCursor = { "" },
        )

        repeat(6) { coordinator.onSingleSwipe(path(8), layout) }
        coordinator.onSingleSwipe(path(8), layout)

        assertEquals(listOf(listOf("A")), finalized)
        assertEquals("A a a a a a a", editor.text)

        coordinator.onNonSwipeInput()

        assertEquals(
            listOf(
                listOf("A"),
                listOf("a", "a", "a", "a", "a", "a"),
            ),
            finalized,
        )
    }

    @Test
    fun `non swipe cursor and external edits finalize and invalidate inference`() {
        val editor = FakeEditor()
        val finalized = mutableListOf<List<String>>()
        val coordinator = coordinator(editor, SpacingMode.INFER_SPACES, dictionary("in"), finalized::add)

        coordinator.onSingleSwipe(path(1), layout)
        coordinator.onNonSwipeInput()
        coordinator.onCursorMoved()
        coordinator.onExternalEdit()

        assertEquals(listOf(listOf("in")), finalized)
        assertEquals("in", editor.text)
    }

    @Test
    fun `failed cancelled and incomplete gestures do not mutate host text or add spaces`() {
        val editor = FakeEditor()
        val coordinator = coordinator(editor, SpacingMode.AFTER_SWIPE, dictionary("in", "there"))

        coordinator.onSingleSwipe(path(0), layout)
        coordinator.onTwoFingerResult(emptyList(), layout)
        coordinator.onTwoFingerResult(listOf(path(3)), layout)

        assertEquals("", editor.text)
    }

    @Test
    fun `failed inferred host replacement finalizes then clears the active run`() {
        val editor = FakeEditor()
        val finalized = mutableListOf<List<String>>()
        val coordinator = coordinator(editor, SpacingMode.INFER_SPACES, dictionary("in", "the"), finalized::add)

        coordinator.onSingleSwipe(path(1), layout)
        editor.failReplacements = true
        coordinator.onSingleSwipe(path(2), layout)

        assertEquals("in", editor.text)
        assertEquals(listOf(listOf("in")), finalized)
    }

    @Test
    fun `poll returns the resolved split result through the coordinator seam`() {
        val editor = FakeEditor()
        val expected = SplitWordParts(listOf("in"), listOf(path(1)), listOf(100L), 350L)
        val coordinator = coordinator(
            editor,
            SpacingMode.INFER_SPACES,
            dictionary("in"),
            pollSplitParts = { atMs -> if (atMs == 350L) expected else null },
        )

        assertEquals(SplitPollOutcome.Resolved(expected), coordinator.poll(350L))
    }

    @Test
    fun `poll reports pending split input without invalidating inference`() {
        val editor = FakeEditor()
        val coordinator = coordinator(
            editor,
            SpacingMode.INFER_SPACES,
            dictionary("in"),
            pollSplitParts = { null },
            isSplitPending = { true },
        )

        coordinator.onSingleSwipe(path(1), layout)

        assertEquals(SplitPollOutcome.Pending, coordinator.poll(350L))
        coordinator.onNonSwipeInput()
        assertEquals("in", editor.text)
    }

    @Test
    fun `malformed multi path metadata deterministically finalizes the active inference run`() {
        val editor = FakeEditor()
        val finalized = mutableListOf<List<String>>()
        val coordinator = coordinator(editor, SpacingMode.INFER_SPACES, dictionary("in"), finalized::add)

        coordinator.onSingleSwipe(path(1), layout)
        coordinator.onRecognizedMultiPathResult(
            parts = listOf(path(3), path(4)),
            candidates = listOf(listOf(candidate("th")), listOf(candidate("ere"))),
            touchDownAtMs = listOf(100L),
            graceWindowMs = 350L,
        )

        assertEquals("in", editor.text)
        assertEquals(listOf(listOf("in")), finalized)
    }

    @Test
    fun `recognized three path result is accepted as one gesture event`() {
        val editor = FakeEditor()
        val coordinator = coordinator(editor, SpacingMode.MANUAL, dictionary("in", "the", "re"))

        coordinator.onRecognizedMultiPathResult(
            parts = listOf(path(1), path(2), path(3)),
            candidates = listOf(
                listOf(candidate("in")),
                listOf(candidate("the")),
                listOf(candidate("re")),
            ),
            touchDownAtMs = listOf(100L, 120L, 140L),
            graceWindowMs = 350L,
        )

        assertEquals("inthere", editor.text)
    }

    private fun coordinator(
        editor: FakeEditor,
        mode: SpacingMode,
        dictionary: List<WordEntry>,
        onFinalized: (List<String>) -> Unit = {},
        hasFollowingWhitespace: () -> Boolean = { false },
        pollSplitParts: (Long) -> SplitWordParts? = { null },
        isSplitPending: () -> Boolean = { false },
        // Mid-sentence by default (non-empty, no terminal punctuation) so existing
        // scenarios that aren't about capitalization stay unaffected by it; tests that
        // care about capitalization pass their own textBeforeCursor explicitly.
        textBeforeCursor: () -> String = { "mid-sentence " },
        segmenter: InferenceSegmenter = InferenceSegmenter(),
        recognize: (GesturePath, KeyboardLayout) -> List<ScoredCandidate> = { path, _ -> candidatesFor(path) },
    ) = SwipeTypingCoordinator(
        segmenter = segmenter,
        spacingMode = { mode },
        recognize = recognize,
        dictionary = { dictionary },
        previousWords = { emptyList() },
        cursorPosition = editor::cursor,
        replaceHostSpan = editor::replace,
        onFinalizedWords = { _, words, _ -> onFinalized(words) },
        textBeforeCursor = textBeforeCursor,
        hasFollowingWhitespace = hasFollowingWhitespace,
        pollSplitParts = pollSplitParts,
        isSplitPending = isSplitPending,
    )

    private fun candidatesFor(path: GesturePath): List<ScoredCandidate> = when (path.points.firstOrNull()?.x?.toInt()) {
        1 -> listOf(candidate("in"))
        2 -> listOf(candidate("the"))
        3 -> listOf(candidate("th"))
        4 -> listOf(candidate("ere"))
        5 -> listOf(candidate("inthe"))
        6 -> listOf(candidate("some"))
        7 -> listOf(candidate("thing"))
        8 -> listOf(candidate("a"))
        9 -> listOf(candidate("hello"))
        else -> emptyList()
    }

    private fun candidate(word: String) = ScoredCandidate(WordEntry(word, 1.0), 1.0)

    private fun contextRevisionCandidatesFor(path: GesturePath): List<ScoredCandidate> =
        when (path.points.firstOrNull()?.x?.toInt()) {
            21 -> listOf(candidate("in"))
            22 -> listOf(candidate("to"))
            23 -> listOf(candidate("the"))
            else -> emptyList()
        }

    private fun dictionary(vararg words: String) = words.map { WordEntry(it, 1.0) }

    private fun path(id: Int) = GesturePath(
        listOf(GesturePoint(id.toFloat(), 0f, 0L), GesturePoint(id.toFloat(), 1f, 1L)),
    )

    private class FakeEditor {
        var text = ""
        private var cursorPosition = 0
        val replacements = mutableListOf<Pair<HostTextSpan, String>>()

        fun cursor(): Int = cursorPosition

        var failReplacements = false

        fun replace(span: HostTextSpan, replacement: String): Boolean {
            if (failReplacements) return false
            replacements += span to replacement
            text = text.replaceRange(span.start, span.end, replacement)
            cursorPosition = span.start + replacement.length
            return true
        }
    }
}
