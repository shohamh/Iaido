package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InferenceSegmenterTest {
    private val path = GesturePath(listOf(GesturePoint(0f, 0f, 0L)))

    @Test
    fun `sequential gestures stay separate when their joined text is not a dictionary word`() {
        val dictionary = dictionary("in", "the")
        val options = InferenceSegmenter().rank(
            units = listOf(unit("first", "in"), unit("second", "the")),
            previousWords = emptyList(),
            dictionary = dictionary,
        )

        assertEquals(listOf("in", "the"), options.first().words)
        assertEquals(listOf("first", "second"), options.first().sourceGestureIds)
    }

    @Test
    fun `sequential gestures can join into one dictionary word`() {
        val options = InferenceSegmenter().rank(
            units = listOf(unit("some", "some"), unit("thing", "thing")),
            previousWords = emptyList(),
            dictionary = dictionary("some", "thing", "something"),
        )

        assertEquals(listOf("something"), options.first().words)
    }

    @Test
    fun `one gesture can split into two dictionary words`() {
        val options = InferenceSegmenter().rank(
            units = listOf(unit("joined", "inthe")),
            previousWords = emptyList(),
            dictionary = dictionary("in", "the"),
        )

        assertEquals(listOf("in", "the"), options.first().words)
    }

    @Test
    fun `one gesture can split into three dictionary words but not four`() {
        val segmenter = InferenceSegmenter()

        val threeWords = segmenter.rank(
            units = listOf(unit("three", "abc")),
            previousWords = emptyList(),
            dictionary = dictionary("a", "b", "c"),
        )
        val fourWords = segmenter.rank(
            units = listOf(unit("four", "abcd")),
            previousWords = emptyList(),
            dictionary = dictionary("a", "b", "c", "d"),
        )

        assertEquals(listOf("a", "b", "c"), threeWords.first().words)
        assertTrue(fourWords.isEmpty())
    }

    @Test
    fun `concurrent paths merge into one word by default`() {
        val options = InferenceSegmenter().rank(
            units = listOf(concurrentUnit("pair", "in", "to")),
            previousWords = emptyList(),
            dictionary = dictionary("in", "to", "into"),
        )

        assertEquals(listOf("into"), options.first().words)
    }

    @Test
    fun `concurrent paths preserve a boundary when context wins`() {
        val options = InferenceSegmenter(
            contextScorer = NgramContextScorer(bigrams = mapOf(("go" to "in") to 3.0)),
            confidenceMargin = 0.0,
        ).rank(
            units = listOf(concurrentUnit("pair", "in", "to")),
            previousWords = listOf("go"),
            dictionary = dictionary("in", "to", "into"),
        )

        assertEquals(listOf("in", "to"), options.first().words)
    }

    @Test
    fun `near simultaneous paths can prefer a swapped language hypothesis while wide paths preserve observed order`() {
        val near = InferenceSegmenter(confidenceMargin = 0.0).rank(
            units = listOf(multiPathUnit("near", listOf("a", "b"), listOf(100L, 135L))),
            previousWords = emptyList(),
            dictionary = listOf(WordEntry("ab", 0.01), WordEntry("ba", 0.1)),
        )
        val wide = InferenceSegmenter(confidenceMargin = 0.0).rank(
            units = listOf(multiPathUnit("wide", listOf("a", "b"), listOf(100L, 500L))),
            previousWords = emptyList(),
            dictionary = listOf(WordEntry("ab", 0.01), WordEntry("ba", 0.1)),
        )

        assertEquals(listOf("ba"), near.first().words)
        assertEquals(PathPair(0, 1), near.first().hypothesisMetadata.single().swappedPair)
        assertEquals(35L, near.first().hypothesisMetadata.single().touchDownDeltaMs)
        assertEquals(0.9, near.first().hypothesisMetadata.single().languageEvidenceWeight, 0.000001)
        assertEquals(listOf("ab"), wide.first().words)
        assertEquals(null, wide.first().hypothesisMetadata.single().swappedPair)
        assertEquals(400L, wide.first().hypothesisMetadata.single().touchDownDeltaMs)
        assertEquals(0.0, wide.first().hypothesisMetadata.single().languageEvidenceWeight, 0.000001)
    }

    @Test
    fun `shared timing weight does not reward a lower frequency swap with negative log evidence`() {
        val options = InferenceSegmenter(confidenceMargin = 0.0).rank(
            units = listOf(multiPathUnit("negative-log", listOf("a", "b"), listOf(100L, 135L))),
            previousWords = emptyList(),
            dictionary = listOf(WordEntry("ab", 0.01), WordEntry("ba", 0.008)),
        )

        assertEquals(listOf("ab"), options.first().words)
        assertEquals(null, options.first().hypothesisMetadata.single().swappedPair)
        assertEquals(0.9, options.first().hypothesisMetadata.single().languageEvidenceWeight, 0.000001)
    }

    @Test
    fun `concurrent paths retain lower ranked merged and boundary alternatives`() {
        val options = InferenceSegmenter(confidenceMargin = 0.0).rank(
            units = listOf(
                multiCandidatePathUnit(
                    id = "lower-ranked",
                    pathCandidates = listOf(
                        listOf("x" to 4.0, "in" to 3.0),
                        listOf("y" to 4.0, "to" to 3.0),
                    ),
                    touchDownAtMs = listOf(100L, 120L),
                ),
            ),
            previousWords = emptyList(),
            dictionary = dictionary("in", "to", "into"),
        )

        assertTrue(options.any { option -> option.words == listOf("into") })
        assertTrue(options.any { option -> option.words == listOf("in", "to") })
    }

    @Test
    fun `equal scoring concurrent candidate combinations use deterministic lexical order`() {
        val options = InferenceSegmenter(confidenceMargin = 0.0).rank(
            units = listOf(
                multiCandidatePathUnit(
                    id = "ties",
                    pathCandidates = listOf(
                        listOf("b" to 1.0, "a" to 1.0),
                        listOf("d" to 1.0, "c" to 1.0),
                    ),
                    touchDownAtMs = listOf(100L, 120L),
                ),
            ),
            previousWords = emptyList(),
            dictionary = dictionary("ac", "ad", "bc", "bd"),
        )

        assertEquals(
            listOf(listOf("ac"), listOf("ad"), listOf("bc"), listOf("bd")),
            options.map { option -> option.words },
        )
    }

    @Test
    fun `separate multi path events do not reorder candidates across their boundary`() {
        val options = InferenceSegmenter().rank(
            units = listOf(
                multiPathUnit("first", listOf("a", "b"), listOf(100L, 120L)),
                multiPathUnit("second", listOf("c", "d"), listOf(200L, 220L)),
            ),
            previousWords = emptyList(),
            dictionary = dictionary("ab", "ba", "cd", "dc", "abcd", "badc", "adbc"),
        )

        assertTrue(options.none { it.words == listOf("adbc") })
        assertTrue(options.all { it.sourceGestureIds == listOf("first", "second") })
    }

    @Test
    fun `context can change a sequential boundary`() {
        val options = InferenceSegmenter(
            contextScorer = NgramContextScorer(bigrams = mapOf(("good" to "to") to 3.0)),
            confidenceMargin = 0.0,
        ).rank(
            units = listOf(unit("to", "to"), unit("day", "day")),
            previousWords = listOf("good"),
            dictionary = dictionary("to", "day", "today"),
        )

        assertEquals(listOf("to", "day"), options.first().words)
    }

    @Test
    fun `low context gain leaves the current interpretation first`() {
        val options = InferenceSegmenter(
            contextScorer = NgramContextScorer(bigrams = mapOf(("good" to "to") to 0.5)),
            confidenceMargin = 1.0,
        ).rank(
            units = listOf(unit("to", "to"), unit("day", "day")),
            previousWords = listOf("good"),
            dictionary = dictionary("to", "day", "today"),
        )

        assertEquals(listOf("today"), options.first().words)
    }

    @Test
    fun `only the six most recent gesture units participate`() {
        val options = InferenceSegmenter().rank(
            units = (1..7).map { index -> unit(index.toString(), "a") },
            previousWords = emptyList(),
            dictionary = dictionary("a"),
        )

        assertEquals((2..7).map(Int::toString), options.first().sourceGestureIds)
        assertEquals(6, options.first().words.size)
    }

    @Test
    fun `equal scoring alternatives use dictionary order`() {
        val options = InferenceSegmenter().rank(
            units = listOf(unit("choice", "b", "a")),
            previousWords = emptyList(),
            dictionary = dictionary("b", "a"),
        )

        assertEquals(listOf("a"), options.first().words)
    }

    @Test
    fun `dictionary frequency decides otherwise equal candidate ranking`() {
        val options = InferenceSegmenter().rank(
            units = listOf(scoredUnit("frequency", "apple" to 1.0, "zebra" to 1.0)),
            previousWords = emptyList(),
            dictionary = listOf(WordEntry("apple", 1.0), WordEntry("zebra", 100.0)),
        )

        assertEquals(listOf("zebra"), options.first().words)
    }

    @Test
    fun `path fit decides otherwise equal candidate ranking`() {
        val options = InferenceSegmenter().rank(
            units = listOf(scoredUnit("path", "apple" to 1.0, "zebra" to 2.0)),
            previousWords = emptyList(),
            dictionary = listOf(WordEntry("apple", 1.0), WordEntry("zebra", 1.0)),
        )

        assertEquals(listOf("zebra"), options.first().words)
    }

    @Test
    fun `inference keeps the stronger swipe candidate over a more frequent near match`() {
        val options = InferenceSegmenter().rank(
            units = listOf(scoredUnit("hello", "hello" to -0.4, "help" to -0.65)),
            previousWords = emptyList(),
            dictionary = listOf(
                WordEntry("hello", 5.2480746025e-05),
                WordEntry("help", 5.6234132519e-04),
            ),
        )

        assertEquals(listOf("hello"), options.first().words)
    }

    @Test
    fun `a confident whole-word swipe does not fragment against the real shipped dictionary and ngram data`() {
        val entries = realDictionaryEntries()
        val store = CompactNgramScoreStore.fromBytes(
            realNgramBytes(),
            entries.mapIndexed { index, entry -> entry.word to index }.toMap(),
        )
        val segmenter = InferenceSegmenter(contextScorer = NgramContextScorer(scoreStore = store))

        val options = segmenter.rank(
            units = listOf(unit("swipe-1", "hello")),
            previousWords = emptyList(),
            dictionary = entries,
        )

        assertEquals(listOf("hello"), options.first().words)
    }

    @Test
    fun `sequential gestures join into a common word over a rare-word split against real dictionary data`() {
        val entries = realDictionaryEntries()
        val store = CompactNgramScoreStore.fromBytes(
            realNgramBytes(),
            entries.mapIndexed { index, entry -> entry.word to index }.toMap(),
        )
        val segmenter = InferenceSegmenter(contextScorer = NgramContextScorer(scoreStore = store))

        val options = segmenter.rank(
            units = listOf(unit("wh-unit", "wh"), unit("at-unit", "at")),
            previousWords = emptyList(),
            dictionary = entries,
        )

        assertEquals(listOf("what"), options.first().words)
    }

    /**
     * Loads the actual shipped app/src/main/assets/dictionary/en.csv, in its stable CSV row
     * order, exactly as EnglishDictionaryRepository does in production. Real dictionary rows
     * (including single/double-letter fragments like "he", "ll", "o") are what makes this
     * class of self-split regression reproducible -- the hand-rolled dictionaries used by every
     * other test in this file are too small to contain them.
     */
    private fun realDictionaryEntries(): List<WordEntry> =
        realAssetsRoot().resolve("dictionary/en.csv").readLines()
            .drop(1)
            .mapNotNull { line ->
                val columns = line.split(',', limit = 2)
                if (columns.size != 2) return@mapNotNull null
                val word = columns[0].trim()
                val frequency = columns[1].trim().toDoubleOrNull()
                if (word.isEmpty() || frequency == null || frequency <= 0.0) null
                else WordEntry(word, frequency)
            }

    /** Loads the actual shipped app/src/main/assets/context-ngrams/en.ngram.bin bytes. */
    private fun realNgramBytes(): ByteArray =
        realAssetsRoot().resolve("context-ngrams/en.ngram.bin").readBytes()

    /**
     * core-engine has no direct access to the app module's assets, so this resolves them via a
     * relative path from wherever the Gradle test task's working directory happens to be
     * (normally the core-engine project directory, but resolved defensively in case that ever
     * changes).
     */
    private fun realAssetsRoot(): File {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/assets")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate app/src/main/assets from working directory ${File(".").absolutePath}")
    }

    private fun dictionary(vararg words: String): List<WordEntry> =
        words.map { WordEntry(it, 1.0) }

    private fun unit(id: String, vararg words: String): GestureUnit = GestureUnit(
        id = id,
        paths = listOf(path),
        candidates = listOf(words.map { ScoredCandidate(WordEntry(it, 1.0), 1.0) }),
        concurrent = false,
    )

    private fun scoredUnit(id: String, vararg candidates: Pair<String, Double>): GestureUnit = GestureUnit(
        id = id,
        paths = listOf(path),
        candidates = listOf(candidates.map { (word, score) -> ScoredCandidate(WordEntry(word, 1.0), score) }),
        concurrent = false,
    )

    private fun concurrentUnit(id: String, first: String, second: String): GestureUnit = GestureUnit(
        id = id,
        paths = listOf(path, path),
        candidates = listOf(
            listOf(ScoredCandidate(WordEntry(first, 1.0), 1.0)),
            listOf(ScoredCandidate(WordEntry(second, 1.0), 1.0)),
        ),
        concurrent = true,
    )

    private fun multiPathUnit(id: String, words: List<String>, touchDownAtMs: List<Long>): GestureUnit = GestureUnit(
        id = id,
        paths = words.indices.map { path },
        candidates = words.map { word -> listOf(ScoredCandidate(WordEntry(word, 1.0), 1.0)) },
        concurrent = true,
        touchDownAtMs = touchDownAtMs,
        graceWindowMs = 350L,
    )

    private fun multiCandidatePathUnit(
        id: String,
        pathCandidates: List<List<Pair<String, Double>>>,
        touchDownAtMs: List<Long>,
    ): GestureUnit = GestureUnit(
        id = id,
        paths = pathCandidates.indices.map { path },
        candidates = pathCandidates.map { candidates ->
            candidates.map { (word, score) -> ScoredCandidate(WordEntry(word, 1.0), score) }
        },
        concurrent = true,
        touchDownAtMs = touchDownAtMs,
        graceWindowMs = 350L,
    )
}
