package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import kotlin.math.ln

enum class InferenceDecodingPolicy {
    INFER_SPACES,
    ONE_WORD_PER_GESTURE,
}

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
    private val frequencyWeight: Double = FREQUENCY_WEIGHT,
    private val extraWordFrequencyWeight: Double = EXTRA_WORD_FREQUENCY_WEIGHT,
) {
    init {
        require(confidenceMargin >= 0.0)
        require(maxUnits > 0)
        require(maxWordsPerGroup > 0)
        require(maxAlternatives > 0)
        require(maxCandidatesPerPath > 0)
        require(frequencyWeight >= 0.0)
        require(extraWordFrequencyWeight >= 0.0)
    }

    @Synchronized
    fun rank(
        units: List<GestureUnit>,
        previousWords: List<String>,
        dictionary: List<WordEntry>,
        policy: InferenceDecodingPolicy = InferenceDecodingPolicy.INFER_SPACES,
    ): List<SegmentationOption> {
        val boundedUnits = units.takeLast(maxUnits)
        if (boundedUnits.isEmpty() || dictionary.isEmpty()) return emptyList()

        val view = dictionaryViewFor(dictionary)
        view.trimCaches()
        val run = Run(view)
        val allOptions = rankOptions(boundedUnits, previousWords, run, contextScorer, policy)
        if (allOptions.isEmpty()) return emptyList()

        val currentInterpretation = rankOptions(
            boundedUnits,
            previousWords,
            run,
            NgramContextScorer(),
            policy,
        ).firstOrNull()?.let { baseline ->
            allOptions.firstOrNull { option ->
                option.words == baseline.words &&
                    option.sourceGestureIds == baseline.sourceGestureIds &&
                    option.hypothesisMetadata == baseline.hypothesisMetadata
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

    @Volatile
    private var lastDictionaryView: DictionaryView? = null

    /** Dedupes by word (highest frequency wins); reused while the caller passes the same list. */
    private fun dictionaryViewFor(dictionary: List<WordEntry>): DictionaryView {
        lastDictionaryView?.let { if (it.source === dictionary) return it }
        val byWord = HashMap<String, WordEntry>(dictionary.size * 2)
        for (entry in dictionary) {
            val existing = byWord[entry.word]
            if (existing == null || entry.frequency > existing.frequency) byWord[entry.word] = entry
        }
        return DictionaryView(dictionary, byWord).also { lastDictionaryView = it }
    }

    private class DictionaryView(val source: List<WordEntry>, val byWord: Map<String, WordEntry>) {
        val maxWordLength: Int = byWord.keys.maxOfOrNull { it.length } ?: 0

        // Group options and per-unit raw candidates depend only on the units and this dictionary, so
        // they carry over between swipes: a new swipe only has to derive the spans ending at it.
        val raw = java.util.IdentityHashMap<GestureUnit, List<RawCandidate>>()
        val groups = HashMap<GroupOptionsKey, List<GroupOption>>()

        fun trimCaches() {
            if (raw.size > MAX_CACHED_UNITS) raw.clear()
            if (groups.size > MAX_CACHED_UNITS * 4) groups.clear()
        }

        /** Dictionary splits of a text depend only on the word set, so they outlive one rank call. */
        private val segmentations = HashMap<String, List<List<String>>>()

        fun cachedSegmentations(text: String, wordLimit: Int, compute: () -> List<List<String>>): List<List<String>> {
            val key = "$wordLimit:$text"
            segmentations[key]?.let { return it }
            if (segmentations.size >= MAX_CACHED_SEGMENTATIONS) segmentations.clear()
            return compute().also { segmentations[key] = it }
        }
    }

    /** Memo shared by every pass of one [rank] call; group/raw options do not depend on context. */
    private class Run(val dictionary: DictionaryView)

    /** Compares unit lists by element identity: units are immutable once appended to a transaction. */
    private class UnitsKey(val units: List<GestureUnit>) {
        private val hash = units.fold(1) { h, unit -> 31 * h + System.identityHashCode(unit) }
        override fun hashCode() = hash
        override fun equals(other: Any?): Boolean {
            if (other !is UnitsKey || other.units.size != units.size) return false
            for (i in units.indices) if (units[i] !== other.units[i]) return false
            return true
        }
    }

    private fun rankOptions(
        units: List<GestureUnit>,
        previousWords: List<String>,
        run: Run,
        scorer: NgramContextScorer,
        policy: InferenceDecodingPolicy,
    ): List<SegmentationOption> {
        val states = MutableList(units.size + 1) { mutableListOf<Partial>() }
        states[0] += Partial.empty(previousWords)

        for (start in units.indices) {
            if (states[start].isEmpty()) continue
            val maxEndExclusive = if (policy == InferenceDecodingPolicy.ONE_WORD_PER_GESTURE) {
                start + 1
            } else {
                units.size
            }
            for (endExclusive in start + 1..maxEndExclusive) {
                val span = units.subList(start, endExclusive)
                val spanKey = UnitsKey(span.toList())
                val groupKey = GroupOptionsKey(spanKey, policy)
                val groups = run.dictionary.groups.getOrPut(groupKey) { groupOptions(span, run, policy) }
                if (groups.isEmpty()) continue
                for (prefix in states[start]) {
                    for (group in groups) {
                        states[endExclusive] += prefix.append(group, previousWords, scorer)
                    }
                }
                retainTop(states[endExclusive], policy)
            }
        }

        return states[units.size]
            .map { partial ->
                SegmentationOption(
                    words = partial.words,
                    score = partial.score,
                    sourceGestureIds = partial.sourceGestureIds,
                    hypothesisMetadata = partial.hypothesisMetadata,
                )
            }
            .sortedWith(optionComparator)
            .take(maxAlternatives)
    }

    private fun groupOptions(
        units: List<GestureUnit>,
        run: Run,
        policy: InferenceDecodingPolicy,
    ): List<GroupOption> {
        val dictionary = run.dictionary.byWord
        val maxWordLength = run.dictionary.maxWordLength
        var rawCandidates = listOf(RawCandidate.empty())
        for (unit in units) {
            val unitCandidates = run.dictionary.raw.getOrPut(unit) { rawCandidatesFor(unit, run.dictionary) }
            if (unitCandidates.isEmpty()) return emptyList()
            rawCandidates = rawCandidates.flatMap { prefix ->
                unitCandidates.map { candidate -> prefix.append(candidate) }
            }.sortedWith(rawComparator).take(maxAlternatives)
        }

        // A gesture unit's own recognized text can be split into shorter dictionary words (e.g.
        // "hello" -> "he"+"ll"+"o", or a lower-ranked raw candidate like "ids" -> "i"+"d"+"s").
        // That alternative stays available -- e.g. for a replacement reel -- matching the DP's
        // normal behavior for ambiguous/low-confidence recognitions and multi-unit boundary
        // revision (SPLIT_REEL, "inthe" -> "in the", or a genuine sequential/concurrent merge
        // like "in"+"to" -> "into"). What must NOT happen is scoring such a self-split using
        // context evidence computed between fragments that were never genuinely separate,
        // previously-typed words (e.g. bigram("he", "ll"), bigram("i", "d")) -- they are a
        // byproduct of one or more swipes' own recognized text being cut into more pieces than
        // there were real touch paths. A concurrent (two-finger) unit contributes two real touch
        // paths; a single-finger unit contributes one. Only when the group's word count exceeds
        // the number of real touch paths across its units did at least one word boundary fall
        // inside a single touch path's own text rather than between two genuinely separate
        // swipes -- e.g. two single-finger units "wh"+"at" concatenate to "what", and "what" can
        // also be dictionary-split into "w"+"h"+"at": that group has 3 words against 2 real touch
        // paths, so at least one boundary (between "w" and "h") is an internal artifact, not a
        // real gesture boundary, and the whole group's context score is suppressed below. A
        // 2-word split of that same "what" text ("wh"+"at") has exactly 2 words for 2 real touch
        // paths -- one boundary per touch path -- so its context evidence is legitimate and kept.
        // Partial.append() uses the flag below to skip only an over-fragmented group's internal-
        // context contribution, leaving frequency scoring, option generation/ranking, and every
        // properly-bounded multi-unit and concurrent-unit behavior unchanged. See
        // InferenceSegmenterTest's real-asset regression coverage for "hello", "there is", and
        // "wh"+"at".
        val realTouchPathCount = units.sumOf { unit -> unit.paths.size }
        val wordLimit = if (policy == InferenceDecodingPolicy.ONE_WORD_PER_GESTURE) {
            1
        } else {
            maxOf(maxWordsPerGroup, units.maxOf { unit -> unit.paths.size })
        }

        return rawCandidates.flatMap { candidate ->
            run.dictionary.cachedSegmentations(candidate.text, wordLimit) {
                dictionarySegmentations(candidate.text, dictionary, maxWordLength, wordLimit)
            }.map { words ->
                GroupOption(
                    words = words,
                    score = candidate.score + candidate.languageWeight *
                        words.withIndex().sumOf { (index, word) -> frequencyScore(dictionary.getValue(word), index) },
                    sourceGestureIds = units.map { it.id },
                    hypothesisMetadata = candidate.hypothesisMetadata,
                    languageEvidenceWeight = candidate.languageWeight,
                    isOverFragmented = words.size > realTouchPathCount,
                )
            }
        }.sortedWith(groupComparator)
            .distinctBy { option ->
                GroupOptionIdentity(
                    words = option.words,
                    hypothesisMetadata = option.hypothesisMetadata,
                )
            }
            .take(maxAlternatives)
    }

    private fun rawCandidatesFor(unit: GestureUnit, view: DictionaryView): List<RawCandidate> {
        val rankedPaths = unit.candidates.map { candidates ->
            candidates.sortedWith(candidateComparator).take(maxCandidatesPerPath)
        }
        if (rankedPaths.any { it.isEmpty() }) return emptyList()

        val raw = if (unit.concurrent) {
            MultiPathOrderHypothesis.forEvent(
                paths = unit.paths,
                candidates = rankedPaths,
                touchDownAtMs = unit.touchDownAtMs,
                graceWindowMs = unit.graceWindowMs,
            ).flatMap { hypothesis ->
                candidateCombinations(hypothesis.candidates).map { selected ->
                    val parts = selected.map { candidate -> candidate.word.word }
                    val concatenated = parts.joinToString(separator = "")
                    val merged = if (parts.size == 2 && parts.any { it.isEmpty() }) {
                        null
                    } else {
                        view.byWord[concatenated]?.word
                    }
                    // The concatenated fallback remains available to dictionarySegmentations(),
                    // which yields boundary-preserving words for every ranked path combination.
                    RawCandidate(
                        text = merged ?: concatenated,
                        score = selected.sumOf { candidate -> candidate.score },
                        hypothesisMetadata = listOf(
                            HypothesisMetadata(
                                swappedPair = hypothesis.swappedPair,
                                touchDownDeltaMs = hypothesis.touchDownDeltaMs,
                                languageEvidenceWeight = hypothesis.languageEvidenceWeight,
                            ),
                        ),
                    )
                }
            }
        } else {
            rankedPaths.single().map { candidate ->
                RawCandidate(
                    text = candidate.word.word,
                    score = candidate.score,
                    hypothesisMetadata = emptyList(),
                )
            }
        }

        return raw.groupBy { candidate ->
            RawCandidateIdentity(
                text = candidate.text,
                hypothesisMetadata = candidate.hypothesisMetadata,
            )
        }
            .map { (_, candidates) -> candidates.maxBy { it.score } }
            .sortedWith(rawComparator)
            .take(maxAlternatives)
    }

    private fun candidateCombinations(
        rankedPaths: List<List<ScoredCandidate>>,
    ): List<List<ScoredCandidate>> = rankedPaths.fold(listOf(emptyList())) { combinations, ranked ->
        combinations.flatMap { prefix ->
            ranked.map { candidate -> prefix + candidate }
        }
    }

    private fun dictionarySegmentations(
        text: String,
        dictionary: Map<String, WordEntry>,
        maxWordLength: Int,
        wordLimit: Int = maxWordsPerGroup,
    ): List<List<String>> {
        val memo = HashMap<Int, List<List<String>>>()
        fun visit(index: Int, wordsUsed: Int): List<List<String>> = memo.getOrPut(index * (wordLimit + 1) + wordsUsed) {
            if (index == text.length) return@getOrPut listOf(emptyList())
            if (wordsUsed == wordLimit) return@getOrPut emptyList()

            buildList {
                for (endExclusive in index + 1..minOf(text.length, index + maxWordLength)) {
                    val word = text.substring(index, endExclusive)
                    if (word !in dictionary) continue
                    visit(endExclusive, wordsUsed + 1).forEach { suffix ->
                        add(listOf(word) + suffix)
                    }
                }
            }
        }

        return visit(0, 0)
            .filter { it.isNotEmpty() && it.size <= wordLimit }
            .sortedWith(wordsComparator)
    }

    private fun retainTop(options: MutableList<Partial>, policy: InferenceDecodingPolicy) {
        val ordered = options.sortedWith(partialComparator)
        val retained = if (policy == InferenceDecodingPolicy.ONE_WORD_PER_GESTURE) {
            // Future context depends only on the previous two output words. Keep the best
            // prefix for each suffix instead of a global beam that can discard the path a
            // later trigram needs. With at most 16 group candidates per gesture this remains
            // bounded at 256 suffix states.
            ordered.distinctBy { it.contextState() }
        } else {
            ordered.distinctBy { it.words }.take(maxAlternatives)
        }
        options.clear()
        options += retained
    }

    private fun frequencyScore(entry: WordEntry, indexInGroup: Int): Double =
        (if (indexInGroup == 0) frequencyWeight else extraWordFrequencyWeight) *
            ln(entry.frequency.coerceAtLeast(MIN_FREQUENCY))

    private data class RawCandidate(
        val text: String,
        val score: Double,
        val hypothesisMetadata: List<HypothesisMetadata>,
    ) {
        fun append(next: RawCandidate) = RawCandidate(
            text = text + next.text,
            score = score + next.score,
            hypothesisMetadata = hypothesisMetadata + next.hypothesisMetadata,
        )

        val languageWeight: Double
            get() = hypothesisMetadata.fold(1.0) { weight, metadata ->
                weight * metadata.languageEvidenceWeight
            }

        companion object {
            fun empty() = RawCandidate(
                text = "",
                score = 0.0,
                hypothesisMetadata = emptyList(),
            )
        }
    }

    private data class RawCandidateIdentity(
        val text: String,
        val hypothesisMetadata: List<HypothesisMetadata>,
    )

    private data class GroupOption(
        val words: List<String>,
        val score: Double,
        val sourceGestureIds: List<String>,
        val hypothesisMetadata: List<HypothesisMetadata>,
        val languageEvidenceWeight: Double,
        // True when the group's word count exceeds the number of real touch paths across its
        // source units, meaning at least one word boundary falls inside a single touch path's
        // own recognized text rather than between two genuinely separate swipes. Guards
        // Partial.append() below against scoring such a boundary using context evidence between
        // fragments that never corresponded to genuinely separate, previously-typed words (see
        // groupOptions()).
        val isOverFragmented: Boolean = false,
    )

    private data class GroupOptionIdentity(
        val words: List<String>,
        val hypothesisMetadata: List<HypothesisMetadata>,
    )

    private data class GroupOptionsKey(
        val units: UnitsKey,
        val policy: InferenceDecodingPolicy,
    )

    /**
     * A DP state. The word/id/metadata lists are materialized only when a state is compared on a
     * tie or retained, because most prefix x group products are discarded by [retainTop] on score.
     */
    private class Partial private constructor(
        private val parent: Partial?,
        private val group: GroupOption?,
        val score: Double,
        val wordCount: Int,
        rootContext: List<String>,
        /**
         * True when this state's last group split a single touch path's own text into several words.
         * Those words are arbitrary cut points, so the next group must not take the last fragment as
         * its context ("today" -> "to day" would otherwise earn the frequent bigram "day of").
         */
        private val endsInFragments: Boolean = false,
    ) {
        /** Last few words of previousWords + words: all the n-gram scorer can ever look at. */
        private val contextTail: List<String> by lazy(LazyThreadSafetyMode.NONE) {
            if (group == null) rootContext else (parent!!.contextTail + group.words).takeLast(CONTEXT_TAIL_WORDS)
        }

        fun contextState(): List<String> = contextTail.takeLast(CONTEXT_TAIL_WORDS - 1)

        val words: List<String> by lazy(LazyThreadSafetyMode.NONE) {
            if (group == null) emptyList() else parent!!.words + group.words
        }
        val sourceGestureIds: List<String> by lazy(LazyThreadSafetyMode.NONE) {
            if (group == null) emptyList() else parent!!.sourceGestureIds + group.sourceGestureIds
        }
        val hypothesisMetadata: List<HypothesisMetadata> by lazy(LazyThreadSafetyMode.NONE) {
            if (group == null) emptyList() else parent!!.hypothesisMetadata + group.hypothesisMetadata
        }
        val swappedPairCount: Int by lazy(LazyThreadSafetyMode.NONE) {
            hypothesisMetadata.count { it.swappedPair != null }
        }

        fun append(group: GroupOption, previousWords: List<String>, scorer: NgramContextScorer): Partial {
            // An over-fragmented group (see groupOptions()) never went through only genuine word
            // boundaries: at least one of its "words" is a fragment of a single touch path's own
            // recognized text, and even its first word isn't guaranteed to be a real word that
            // was typed and then followed by the rest -- it is one arbitrary cut point among
            // several the DP tries. Scoring it against real previous context would let a single
            // common bigram hit (e.g. a very frequent short word like "the") outweigh the whole
            // word's frequency margin just as easily as an internal fragment-to-fragment hit did
            // (see InferenceSegmenterTest's real-asset regression coverage for "hello" /
            // "there is" / "there" / "wh"+"at"). So an over-fragmented group is scored on
            // frequency alone, exactly as it would be with no context scorer at all -- the same,
            // known-good comparison that existed before the real context scorer was wired in.
            val contextScore = if (group.isOverFragmented) {
                0.0
            } else {
                val tail = if (endsInFragments) emptyList() else contextTail
                group.words.foldIndexed(0.0) { index, total, word ->
                    total + scorer.score(
                        if (index == 0) tail else tail + group.words.subList(0, index),
                        word,
                    )
                } * group.languageEvidenceWeight
            }
            return Partial(
                this,
                group,
                score + group.score + contextScore,
                wordCount + group.words.size,
                emptyList(),
                endsInFragments = group.isOverFragmented,
            )
        }

        companion object {
            fun empty(previousWords: List<String>) =
                Partial(null, null, 0.0, 0, previousWords.takeLast(CONTEXT_TAIL_WORDS))
        }
    }

    companion object {
        // NgramContextScorer's window is at most three words (trigram), so no more context is read.
        private const val CONTEXT_TAIL_WORDS = 3
        private const val MAX_CACHED_SEGMENTATIONS = 4096
        private const val MAX_CACHED_UNITS = 64
        private const val MAX_GESTURE_UNITS = 6
        private const val MAX_WORDS_PER_GROUP = 3
        private const val MAX_ALTERNATIVES = 16
        private const val MAX_CANDIDATES_PER_PATH = 8
        private const val DEFAULT_CONFIDENCE_MARGIN = 1.0
        // Unit candidates already carry ShapePathScorer's frequency term (ScoringConstants.FREQUENCY_WEIGHT);
        // this is the extra, segmentation-level contribution (it is what penalizes needless splits). Kept at
        // the historical 0.1 so that adding it on top of the stronger recognizer weight does not double-count
        // frequency and let the segmentation pass overturn a geometrically stronger swipe candidate.
        private const val FREQUENCY_WEIGHT = ScoringConstants.SEGMENTER_FREQUENCY_WEIGHT
        private const val EXTRA_WORD_FREQUENCY_WEIGHT = ScoringConstants.SEGMENTER_EXTRA_WORD_FREQUENCY_WEIGHT

        // The shipped dictionary stores frequency as a probability (all entries are well below
        // 1.0; the most common English word, "the", is ~0.054). A floor of 1.0 clamps every real
        // entry to the same value, making ln(frequency) zero for every word and silently
        // disabling the frequency term the DP relies on to prefer a common joined word (e.g.
        // "what") over a split into rare dictionary fragments (e.g. "wh" + "at"). The floor only
        // needs to stay below the smallest real frequency so ln() never sees zero/negative input.
        private const val MIN_FREQUENCY = 1e-9

        private val candidateComparator = compareByDescending<ScoredCandidate> { it.score }
            .thenBy { it.word.word }
        private val rawComparator = compareByDescending<RawCandidate> { it.score }
            .thenBy { it.text }
            .thenBy { candidate -> candidate.hypothesisMetadata.count { it.swappedPair != null } }
        private val wordsComparator = compareBy<List<String>> { it.size }
            .thenBy { it.joinToString(separator = "\u0000") }
        private val groupComparator = compareByDescending<GroupOption> { it.score }
            .thenBy { it.words.size }
            .thenBy { it.words.joinToString(separator = "\u0000") }
            .thenBy { option -> option.hypothesisMetadata.count { it.swappedPair != null } }
        private val partialComparator = compareByDescending<Partial> { it.score }
            .thenBy { it.wordCount }
            .thenBy { it.words.joinToString(separator = "\u0000") }
            .thenBy { partial -> partial.swappedPairCount }
        private val optionComparator = compareByDescending<SegmentationOption> { it.score }
            .thenBy { it.words.size }
            .thenBy { it.words.joinToString(separator = "\u0000") }
            .thenBy { option -> option.hypothesisMetadata.count { it.swappedPair != null } }
    }
}
