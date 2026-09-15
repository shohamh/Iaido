package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import kotlin.math.ln

/**
 * Ranks dictionary-valid spacing interpretations for the latest swipe units.
 *
 * The search keeps both the input run and the alternatives at fixed bounds so
 * it is safe to repeat after every completed swipe.
 */
class InferenceSegmenter(
    private val contextScorer: NgramContextScorer = NgramContextScorer(),
    private val confidenceMargin: Double = DEFAULT_CONFIDENCE_MARGIN,
    private val maxUnits: Int = MAX_GESTURE_UNITS,
    private val maxWordsPerGroup: Int = MAX_WORDS_PER_GROUP,
    private val maxAlternatives: Int = MAX_ALTERNATIVES,
    private val maxCandidatesPerPath: Int = MAX_CANDIDATES_PER_PATH,
) {
    init {
        require(confidenceMargin >= 0.0)
        require(maxUnits > 0)
        require(maxWordsPerGroup > 0)
        require(maxAlternatives > 0)
        require(maxCandidatesPerPath > 0)
    }

    fun rank(
        units: List<GestureUnit>,
        previousWords: List<String>,
        dictionary: List<WordEntry>,
    ): List<SegmentationOption> {
        val boundedUnits = units.takeLast(maxUnits)
        if (boundedUnits.isEmpty() || dictionary.isEmpty()) return emptyList()

        val dictionaryByWord = dictionary
            .groupBy { it.word }
            .mapValues { (_, entries) -> entries.maxBy { it.frequency } }
        val allOptions = rankOptions(boundedUnits, previousWords, dictionaryByWord, contextScorer)
        if (allOptions.isEmpty()) return emptyList()

        val currentInterpretation = rankOptions(
            boundedUnits,
            previousWords,
            dictionaryByWord,
            NgramContextScorer(),
        ).firstOrNull()?.let { baseline ->
            allOptions.firstOrNull { option ->
                option.words == baseline.words && option.sourceGestureIds == baseline.sourceGestureIds
            }
        } ?: allOptions.first()

        return allOptions
            .filter { option ->
                option == currentInterpretation ||
                    option.score <= currentInterpretation.score ||
                    option.score - currentInterpretation.score >= confidenceMargin
            }
            .take(maxAlternatives)
    }

    private fun rankOptions(
        units: List<GestureUnit>,
        previousWords: List<String>,
        dictionary: Map<String, WordEntry>,
        scorer: NgramContextScorer,
    ): List<SegmentationOption> {
        val states = MutableList(units.size + 1) { mutableListOf<Partial>() }
        states[0] += Partial.empty()

        for (start in units.indices) {
            if (states[start].isEmpty()) continue
            for (endExclusive in start + 1..units.size) {
                val groups = groupOptions(units.subList(start, endExclusive), dictionary)
                if (groups.isEmpty()) continue
                for (prefix in states[start]) {
                    for (group in groups) {
                        states[endExclusive] += prefix.append(group, previousWords, scorer)
                    }
                }
                retainTop(states[endExclusive])
            }
        }

        return states[units.size]
            .map { partial ->
                SegmentationOption(partial.words, partial.score, partial.sourceGestureIds)
            }
            .sortedWith(optionComparator)
            .take(maxAlternatives)
    }

    private fun groupOptions(
        units: List<GestureUnit>,
        dictionary: Map<String, WordEntry>,
    ): List<GroupOption> {
        var rawCandidates = listOf(RawCandidate.empty())
        for (unit in units) {
            val unitCandidates = rawCandidatesFor(unit, dictionary.values.toList())
            if (unitCandidates.isEmpty()) return emptyList()
            rawCandidates = rawCandidates.flatMap { prefix ->
                unitCandidates.map { candidate -> prefix.append(candidate) }
            }.sortedWith(rawComparator).take(maxAlternatives)
        }

        // A single, non-concurrent gesture unit's own recognized text can be split into
        // shorter dictionary words (e.g. "hello" -> "he"+"ll"+"o", or a lower-ranked raw
        // candidate like "ids" -> "i"+"d"+"s"). That alternative stays available -- e.g. for a
        // replacement reel -- matching the DP's normal behavior for ambiguous/low-confidence
        // recognitions and multi-unit boundary revision (SPLIT_REEL, "inthe" -> "in the", or a
        // genuine sequential/concurrent merge like "in"+"to" -> "into"). What must NOT happen is
        // scoring such a *single-unit self-split* using context evidence computed between the
        // split's OWN fragments (e.g. bigram("he", "ll"), bigram("i", "d")) as if "he"/"i" were
        // genuinely separate, previously-typed words -- they never were; they are a byproduct of
        // one swipe's own recognized text being cut into pieces. That is fundamentally different
        // from a multi-unit group (formed by merging >= 2 real, separately-swiped gesture
        // units), where the words genuinely were typed as separate units and bigram evidence
        // between them is legitimate. Partial.append() uses the flag below to skip only the
        // single-unit self-split's internal-context contribution, leaving frequency scoring,
        // option generation/ranking, and every multi-unit and concurrent-unit behavior
        // unchanged. See InferenceSegmenterTest's real-asset regression coverage for "hello"
        // and "there is".
        val isSingleNonConcurrentUnit = units.size == 1 && !units.single().concurrent

        return rawCandidates.flatMap { candidate ->
            dictionarySegmentations(candidate.text, dictionary).map { words ->
                GroupOption(
                    words = words,
                    score = candidate.score + words.sumOf { word -> frequencyScore(dictionary.getValue(word)) },
                    sourceGestureIds = units.map { it.id },
                    isSelfSplitOfSingleUnit = words.size > 1 && isSingleNonConcurrentUnit,
                )
            }
        }.distinctBy { option -> option.words }
            .sortedWith(groupComparator)
            .take(maxAlternatives)
    }

    private fun rawCandidatesFor(unit: GestureUnit, dictionary: List<WordEntry>): List<RawCandidate> {
        val rankedPaths = unit.candidates.map { candidates ->
            candidates.sortedWith(candidateComparator).take(maxCandidatesPerPath)
        }
        if (rankedPaths.any { it.isEmpty() }) return emptyList()

        val raw = if (unit.concurrent) {
            rankedPaths[0].flatMap { first ->
                rankedPaths[1].map { second ->
                    // This also keeps the existing SplitWordMerger as the source
                    // of truth for two-finger merged dictionary candidates.
                    val merged = SplitWordMerger().mergeParts(
                        listOf(first.word.word, second.word.word),
                        dictionary,
                    )
                    RawCandidate(
                        text = merged.firstOrNull()?.word ?: first.word.word + second.word.word,
                        score = first.score + second.score,
                    )
                }
            }
        } else {
            rankedPaths.single().map { candidate -> RawCandidate(candidate.word.word, candidate.score) }
        }

        return raw.groupBy { it.text }
            .map { (_, candidates) -> candidates.maxBy { it.score } }
            .sortedWith(rawComparator)
            .take(maxAlternatives)
    }

    private fun dictionarySegmentations(
        text: String,
        dictionary: Map<String, WordEntry>,
    ): List<List<String>> {
        val memo = mutableMapOf<Pair<Int, Int>, List<List<String>>>()
        fun visit(index: Int, wordsUsed: Int): List<List<String>> = memo.getOrPut(index to wordsUsed) {
            if (index == text.length) return@getOrPut listOf(emptyList())
            if (wordsUsed == maxWordsPerGroup) return@getOrPut emptyList()

            buildList {
                for (endExclusive in index + 1..text.length) {
                    val word = text.substring(index, endExclusive)
                    if (word !in dictionary) continue
                    visit(endExclusive, wordsUsed + 1).forEach { suffix ->
                        add(listOf(word) + suffix)
                    }
                }
            }
        }

        return visit(0, 0)
            .filter { it.isNotEmpty() && it.size <= maxWordsPerGroup }
            .sortedWith(wordsComparator)
    }

    private fun retainTop(options: MutableList<Partial>) {
        val retained = options.sortedWith(partialComparator)
            .distinctBy { it.words }
            .take(maxAlternatives)
        options.clear()
        options += retained
    }

    private fun frequencyScore(entry: WordEntry): Double =
        FREQUENCY_WEIGHT * ln(entry.frequency.coerceAtLeast(MIN_FREQUENCY))

    private data class RawCandidate(val text: String, val score: Double) {
        fun append(next: RawCandidate) = RawCandidate(text + next.text, score + next.score)

        companion object {
            fun empty() = RawCandidate("", 0.0)
        }
    }

    private data class GroupOption(
        val words: List<String>,
        val score: Double,
        val sourceGestureIds: List<String>,
        // True only for a self-split (words.size > 1) of a single, non-concurrent gesture
        // unit's own recognized text. Guards Partial.append() below against scoring the split
        // using context evidence between the split's own fragments, which never corresponded
        // to genuinely separate, previously-typed words (see groupOptions()).
        val isSelfSplitOfSingleUnit: Boolean = false,
    )

    private data class Partial(
        val words: List<String>,
        val score: Double,
        val sourceGestureIds: List<String>,
    ) {
        fun append(group: GroupOption, previousWords: List<String>, scorer: NgramContextScorer): Partial {
            val context = previousWords + words
            // A single-unit self-split (see groupOptions()) never went through a genuine word
            // boundary: its "words" are all fragments of one swipe's own recognized text, and
            // even the first fragment isn't a real word that was typed and then followed by the
            // rest -- it is one arbitrary cut point among several the DP tries. Scoring it
            // against real previous context would let a single common bigram hit (e.g. a very
            // frequent short word like "the") outweigh the whole word's frequency margin just as
            // easily as an internal fragment-to-fragment hit did (see InferenceSegmenterTest's
            // real-asset regression coverage for "hello" / "there is" / "there"). So a
            // self-split is scored on frequency alone, exactly as it would be with no context
            // scorer at all -- the same, known-good comparison that existed before the real
            // context scorer was wired in.
            val contextScore = if (group.isSelfSplitOfSingleUnit) {
                0.0
            } else {
                group.words.foldIndexed(0.0) { index, total, word ->
                    total + scorer.score(context + group.words.take(index), word)
                }
            }
            return Partial(
                words = words + group.words,
                score = score + group.score + contextScore,
                sourceGestureIds = sourceGestureIds + group.sourceGestureIds,
            )
        }

        companion object {
            fun empty() = Partial(emptyList(), 0.0, emptyList())
        }
    }

    companion object {
        private const val MAX_GESTURE_UNITS = 6
        private const val MAX_WORDS_PER_GROUP = 3
        private const val MAX_ALTERNATIVES = 16
        private const val MAX_CANDIDATES_PER_PATH = 8
        private const val DEFAULT_CONFIDENCE_MARGIN = 1.0
        private const val FREQUENCY_WEIGHT = 0.1
        private const val MIN_FREQUENCY = 1.0

        private val candidateComparator = compareByDescending<ScoredCandidate> { it.score }
            .thenBy { it.word.word }
        private val rawComparator = compareByDescending<RawCandidate> { it.score }
            .thenBy { it.text }
        private val wordsComparator = compareBy<List<String>> { it.size }
            .thenBy { it.joinToString(separator = "\u0000") }
        private val groupComparator = compareByDescending<GroupOption> { it.score }
            .thenBy { it.words.size }
            .thenBy { it.words.joinToString(separator = "\u0000") }
        private val partialComparator = compareByDescending<Partial> { it.score }
            .thenBy { it.words.size }
            .thenBy { it.words.joinToString(separator = "\u0000") }
        private val optionComparator = compareByDescending<SegmentationOption> { it.score }
            .thenBy { it.words.size }
            .thenBy { it.words.joinToString(separator = "\u0000") }
    }
}
