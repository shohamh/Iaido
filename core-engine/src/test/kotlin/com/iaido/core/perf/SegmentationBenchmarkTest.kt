package com.iaido.core.perf

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.recognition.CompactNgramScoreStore
import com.iaido.core.recognition.EmptyNgramScoreStore
import com.iaido.core.recognition.GestureRecognizer
import com.iaido.core.recognition.GestureUnit
import com.iaido.core.recognition.InferenceDecodingPolicy
import com.iaido.core.recognition.InferenceSegmenter
import com.iaido.core.recognition.NgramContextScorer
import com.iaido.core.recognition.NgramScoreStore
import com.iaido.core.recognition.ScoringConstants
import com.iaido.core.recognition.SegmentationOption
import com.iaido.core.recognition.ShapePathScorer
import com.iaido.core.recognition.TrieCandidateGenerator
import java.io.File
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Opt-in SEGMENTATION benchmark: replays phrases through GestureRecognizer (no context, as the app's
 * SwipeTypingCoordinator does) and InferenceSegmenter with previousWords and a growing window of up to 6 units,
 * sliding like SwipeTypingCoordinator.acceptInference. Swipes are SYNTHETIC (see [SyntheticSwipes]).
 *
 *   IAIDO_SEG=true  ./gradlew.bat :core-engine:cleanTest :core-engine:test --tests '*SegmentationBenchmarkTest*' --no-daemon -i
 *   IAIDO_SEG=grid  ... also coordinate-descends the segmenter parameters on the TUNE phrases.
 *   IAIDO_SEG=seq-grid ... coordinate-descends one-word sequence decoder parameters on separated TUNE phrases.
 *   IAIDO_SEG=scan  IAIDO_SEG_AXES="bw=0,0.5;twr=0,1;margin=1,2;floor=-2,0;cap=8;residual=0,1;fw=0.1,0.5"
 *                   (fwr=1 sets the segmenter frequency weight equal to bw) full product of the listed axes (twr = trigram weight / bigram weight) scored on the TUNE phrases;
 *                   the best IAIDO_SEG_TOP (default 4) are then reported on HELDOUT and TARGETED.
 *   IAIDO_SEG_LANG=en|he         language (default en; phrases in src/test/resources/segmentation)
 *   IAIDO_SEG_TUNE_FILE=<path>   optional phrase file to use instead of the checked-in tune phrases
 *   IAIDO_SEG_HELDOUT_FILE=<path> optional phrase file to use instead of the checked-in held-out phrases
 *   IAIDO_NGRAM_EN / IAIDO_NGRAM_HE=<path>  n-gram asset to evaluate (default: the shipped asset)
 *   IAIDO_NGRAM_COMPARE=name=<path>;name=<path>   extra assets reported side by side with the same swipes
 *   IAIDO_SEG_CFG="bw,tw,margin,floor,cap,residual[,fw]"  evaluate this segmenter configuration (default 1,1,1,-2,8,0,0.1)
 *   IAIDO_SEG_N=1.0              scales the number of tune/held-out phrases used
 *
 * Output lines start with `ACC SEG`. Metrics, per scenario ("sep": one swipe per word; "mixed": 12% of word pairs
 * swiped as one continuous path, 12% of words of 4+ letters swiped as two sequential fragments):
  *  - word acc: reference words recovered (LCS) for segmentation, positional matches for one-word-per-gesture sequence decoding;
 *  - split/join: wrong word boundaries, counted only on phrases whose letters were all recognized correctly so a
 *    boundary can be compared position by position (wrong split = extra output boundary per reference word,
 *    wrong join = missing reference boundary per interior reference boundary).
 *
 * The same run also prints `ACC SEQ` for the one-word-per-gesture decoder on separated held-out and targeted phrases,
 * comparing the shipped context asset with an empty context store. Those lines measure the normal spacing modes and
 * report candidate recall, changed words, harmful/recovered revisions, and changes to previously correct high-confidence
 * words (score gap at least 1.0). They do not measure real InputConnection replacement latency.
 */
class SegmentationBenchmarkTest {
    // ---- cases ---------------------------------------------------------------------------------
    private class PhraseCase(val history: List<String>, val words: List<String>)

    private sealed interface Part
    private class Tap(val word: String) : Part
    private class Swipes(val ref: List<String>, val units: List<GestureUnit>) : Part

    private class Replayable(val scenario: String, val history: List<String>, val parts: List<Part>)

    private class Config(
        val name: String,
        val bigramWeight: Double = 1.0,
        val trigramWeight: Double = 1.0,
        val margin: Double = 1.0,
        val floor: Double = -2.0,
        val cap: Double = 8.0,
        val residual: Boolean = false,
        val frequencyWeight: Double = 0.1,
        val extraWordFrequencyWeight: Double = frequencyWeight,
        val windowSize: Int = 3,
    ) {
        fun spec() = "bw=%.3g tw=%.3g margin=%.3g floor=%.3g cap=%.3g residual=%s fw=%.3g efw=%.3g".format(
            bigramWeight, trigramWeight, margin, floor, cap, residual, frequencyWeight, extraWordFrequencyWeight,
        )
        fun copy(
            bigramWeight: Double = this.bigramWeight, trigramWeight: Double = this.trigramWeight,
            margin: Double = this.margin, floor: Double = this.floor, cap: Double = this.cap,
            residual: Boolean = this.residual, frequencyWeight: Double = this.frequencyWeight, name: String = this.name,
            extraWordFrequencyWeight: Double = this.extraWordFrequencyWeight,
        ) = Config(name, bigramWeight, trigramWeight, margin, floor, cap, residual, frequencyWeight, extraWordFrequencyWeight, windowSize)
    }

    private class Totals {
        var words = 0; var matched = 0; var segments = 0; var exactSegments = 0
        var rawMatched = 0
        var refBoundaries = 0; var outBoundaries = 0; var wrongSplits = 0; var wrongJoins = 0
        val sequenceMetrics = SequenceEvaluationMetrics()
        fun add(o: Totals) {
            words += o.words; matched += o.matched; segments += o.segments; exactSegments += o.exactSegments
            rawMatched += o.rawMatched
            refBoundaries += o.refBoundaries; outBoundaries += o.outBoundaries
            wrongSplits += o.wrongSplits; wrongJoins += o.wrongJoins
            sequenceMetrics.add(o.sequenceMetrics)
        }
        val wordAcc get() = if (words == 0) 0.0 else 100.0 * matched / words
        fun line(includeSequenceMetrics: Boolean = false): String {
            val base = "words=%d wordAcc=%.2f (recognizer-only %.2f) segExact=%.1f%% | boundaries judged ref=%d out=%d: wrongSplit=%d (%.2f/100w) wrongJoin=%d (%.2f/100w)".format(
                words, wordAcc, if (words == 0) 0.0 else 100.0 * rawMatched / words,
                if (segments == 0) 0.0 else 100.0 * exactSegments / segments, refBoundaries, outBoundaries,
                wrongSplits, if (words == 0) 0.0 else 100.0 * wrongSplits / words,
                wrongJoins, if (words == 0) 0.0 else 100.0 * wrongJoins / words,
            )
            if (!includeSequenceMetrics) return base
            val metrics = sequenceMetrics.snapshot()
            return base + " | candidateRecall=%.2f%% (%d/%d) changed=%d harmful=%d recovered=%d highConfidenceCorrectChanged=%d/%d (threshold=1.0)".format(
                metrics.candidateRecall, metrics.candidateHits, metrics.candidateCount,
                metrics.changedWords, metrics.harmfulRevisions, metrics.recoveredRevisions,
                metrics.changedCorrectHighConfidence, metrics.correctHighConfidenceObservations,
            )
        }
    }

    // ---- entry ---------------------------------------------------------------------------------
    @Test
    fun segmentation() {
        val mode = System.getProperty("iaido.seg") ?: System.getenv("IAIDO_SEG")
        assumeTrue(mode == "true" || mode == "grid" || mode == "scan" || mode == "seq-grid")
        val scale = (System.getenv("IAIDO_SEG_N") ?: "1.0").toDouble()
        val lang = System.getenv("IAIDO_SEG_LANG") ?: "en"
        val dict = loadDictionary(lang)
        val layout = if (lang == "he") KeyboardLayout.hebrewTestLayout() else KeyboardLayout.qwertyTestLayout()
        val letters = layout.keys.map { it.letter }.toSet()
        fun typeable(cases: List<PhraseCase>) = cases.filter { c -> (c.history + c.words).all { w -> w.all { it in letters } } }
        val tunePhrases = System.getenv("IAIDO_SEG_TUNE_FILE")?.let(::loadPhraseFile) ?: loadPhrases("$lang-tune.txt")
        val heldoutPhrases = System.getenv("IAIDO_SEG_HELDOUT_FILE")?.let(::loadPhraseFile) ?: loadPhrases("$lang-heldout.txt")
        val tune = typeable(tunePhrases).take((400 * scale).toInt())
        val held = typeable(heldoutPhrases).take((400 * scale).toInt())
        val targeted = if (lang == "en") loadPhrases("en-targeted.txt") else emptyList()

        val tuneSet = generate("tune", tune, 1, layout, dict, 101L)
        val heldSet = generate("heldout", held, 1, layout, dict, 202L)
        val targetedSet = generate("targeted", targeted, 8, layout, dict, 303L)
        println("ACC SEG replayables: tune=${tuneSet.size} heldout=${heldSet.size} targeted=${targetedSet.size}")

        val stores = linkedMapOf<String, NgramScoreStore>()
        val primary = System.getenv("IAIDO_NGRAM_${lang.uppercase()}")?.let(::File) ?: assets().resolve("context-ngrams/$lang.ngram.bin")
        stores["primary(${primary.name})"] = loadStore(primary, dict)
        System.getenv("IAIDO_NGRAM_COMPARE")?.split(';')?.filter { it.isNotBlank() }?.forEach { spec ->
            val (name, path) = spec.split('=', limit = 2)
            stores[name] = loadStore(File(path), dict)
        }
        val cfgEnv = System.getenv("IAIDO_SEG_CFG")?.split(',')?.map { it.trim() }
        val production = if (cfgEnv != null && cfgEnv.size >= 6) {
            Config("cfg", cfgEnv[0].toDouble(), cfgEnv[1].toDouble(), cfgEnv[2].toDouble(), cfgEnv[3].toDouble(), cfgEnv[4].toDouble(), cfgEnv[5] == "1", cfgEnv.getOrNull(6)?.toDouble() ?: 0.1)
        } else Config(
            "production", ScoringConstants.SEGMENTER_BIGRAM_WEIGHT, ScoringConstants.SEGMENTER_TRIGRAM_WEIGHT, 1.0,
            frequencyWeight = ScoringConstants.SEGMENTER_FREQUENCY_WEIGHT,
            extraWordFrequencyWeight = ScoringConstants.SEGMENTER_EXTRA_WORD_FREQUENCY_WEIGHT,
        )
        val legacy = Config("legacy(bw=tw=1,fw=efw=0.1)", 1.0, 1.0, 1.0, frequencyWeight = 0.1)

        val sets = listOf("tune" to tuneSet, "heldout" to heldSet, "targeted" to targetedSet)
        for ((storeName, store) in stores) {
            println("ACC SEG === store $storeName, ${production.name}: ${production.spec()} ===")
            for ((setName, set) in sets) report("$storeName/$setName", set, store, production, dict)
            println("ACC SEG === store $storeName, ${legacy.name}: ${legacy.spec()} ===")
            for ((setName, set) in sets) report("$storeName/legacy/$setName", set, store, legacy, dict)
        }
        val first = stores.values.first()
        println("ACC SEG === no context at all (empty store) ===")
        for ((setName, set) in sets) report("none/$setName", set, EmptyNgramScoreStore, production, dict)

        // The decoder used by MANUAL and AFTER_SWIPE keeps one whole word per gesture while
        // reranking the active sequence. Report it separately on one-swipe-per-word phrases so
        // these results measure word choice without mixing in INFER_SPACES split/join behavior.
        for ((storeName, store) in stores) {
            for ((setName, set) in listOf("heldout" to heldSet, "targeted" to targetedSet)) {
                val separated = set.filter { it.scenario == "sep" }
                reportWholeWordSequence("$storeName/$setName", separated, store, production, dict)
            }
        }
        for ((setName, set) in listOf("heldout" to heldSet, "targeted" to targetedSet)) {
            reportWholeWordSequence("no-context/$setName", set.filter { it.scenario == "sep" }, EmptyNgramScoreStore, production, dict)
        }

        if (mode == "scan") scan(first, tuneSet, heldSet, targetedSet, production, dict)
        if (mode == "grid") {
            for ((storeName, store) in stores.entries.take(1)) { // tune the primary asset only
                val best = descend(store, tuneSet, production, dict)
                println("ACC SEG === TUNED for $storeName: ${best.spec()} ===")
                for ((setName, set) in sets) report("$storeName/tuned/$setName", set, store, best, dict)
            }
        }
        if (mode == "seq-grid") {
            val (storeName, store) = stores.entries.first()
            val tuneSeparated = tuneSet.filter { it.scenario == "sep" }
            val best = descendWholeWordSequence(store, tuneSeparated, production, dict)
            println("ACC SEQ === TUNED for $storeName: ${best.spec()} ===")
            reportWholeWordSequence("$storeName/tuned/tune", tuneSeparated, store, best, dict)
            reportWholeWordSequence("$storeName/tuned/heldout", heldSet.filter { it.scenario == "sep" }, store, best, dict)
            if (targetedSet.isNotEmpty()) {
                reportWholeWordSequence("$storeName/tuned/targeted", targetedSet.filter { it.scenario == "sep" }, store, best, dict)
            }
        }
        if (lang == "en") println("ACC SEG store-check bigram(of,course)=%.3f bigram(today,of)=%.3f bigram(day,of)=%.3f".format(
            first.bigram("of", "course"), first.bigram("today", "of"), first.bigram("day", "of")))
    }

    private fun scan(
        store: NgramScoreStore, tune: List<Replayable>, held: List<Replayable>, targeted: List<Replayable>,
        base: Config, dict: List<WordEntry>,
    ) {
        val axes = HashMap<String, List<Double>>()
        (System.getenv("IAIDO_SEG_AXES") ?: "").split(';').filter { it.contains('=') }.forEach { spec ->
            val (key, values) = spec.split('=', limit = 2)
            axes[key.trim()] = values.split(',').map { it.trim().toDouble() }
        }
        fun axis(key: String, default: Double) = axes[key] ?: listOf(default)
        val configs = ArrayList<Config>()
        for (bw in axis("bw", base.bigramWeight)) for (twr in axis("twr", 1.0)) for (margin in axis("margin", base.margin))
            for (floor in axis("floor", base.floor)) for (cap in axis("cap", base.cap)) for (res in axis("residual", if (base.residual) 1.0 else 0.0))
                for (fwAxis in axis("fw", base.frequencyWeight)) for (fwr in axis("fwr", 0.0)) for (efw in axis("efw", -1.0)) {
                    val fw = if (fwr > 0.0) bw * fwr else fwAxis // fwr: segmenter frequency weight as a multiple of bw
                    // efw: frequency weight of the 2nd+ word of a split group (default: same as fw)
                    configs += Config("scan", bw, bw * twr, margin, floor, cap, res > 0.5, fw, if (efw >= 0.0) efw else fw)
                }
        println("ACC SEG scan: ${configs.size} configurations on tune")
        val scored = configs.map { c ->
            val t = evaluate(tune, store, c, dict)
            val j = 0.6 * (t["sep"]?.wordAcc ?: 0.0) + 0.4 * (t["mixed"]?.wordAcc ?: 0.0)
            println("ACC SEG scan J=%.3f sepAcc=%.2f mixedAcc=%.2f sepSplit=%d sepJoin=%d mixSplit=%d mixJoin=%d ${c.spec()}".format(
                j, t["sep"]!!.wordAcc, t["mixed"]!!.wordAcc, t["sep"]!!.wrongSplits, t["sep"]!!.wrongJoins, t["mixed"]!!.wrongSplits, t["mixed"]!!.wrongJoins))
            j to c
        }.sortedByDescending { it.first }
        val top = (System.getenv("IAIDO_SEG_TOP") ?: "4").toInt()
        for ((rank, pair) in scored.take(top).withIndex()) {
            println("ACC SEG === scan rank ${rank + 1} J=%.3f ${pair.second.spec()} ===".format(pair.first))
            report("scan${rank + 1}/tune", tune, store, pair.second, dict)
            report("scan${rank + 1}/heldout", held, store, pair.second, dict)
            report("scan${rank + 1}/targeted", targeted, store, pair.second, dict)
        }
    }

    // ---- reporting / tuning --------------------------------------------------------------------------
    private fun report(label: String, set: List<Replayable>, store: NgramScoreStore, config: Config, dict: List<WordEntry>) {
        val byScenario = evaluate(set, store, config, dict)
        for ((scenario, totals) in byScenario.toSortedMap()) println("ACC SEG $label [$scenario] ${totals.line()}")
    }

    private fun reportWholeWordSequence(
        label: String,
        set: List<Replayable>,
        store: NgramScoreStore,
        config: Config,
        dict: List<WordEntry>,
    ) {
        val totals = evaluateWholeWordSequence(set, store, config, dict)
        println("ACC SEQ $label ${totals.line(includeSequenceMetrics = true)}")
    }

    private fun evaluateWholeWordSequence(
        set: List<Replayable>, store: NgramScoreStore, config: Config, dict: List<WordEntry>,
    ): Totals {
        val shaped = shape(store, config)
        val results = pool.invokeAll(set.map { replayable ->
            Callable { replayWholeWordSequence(replayable, shaped, config, dict) }
        })
        return Totals().also { total -> results.forEach { total.add(it.get()) } }
    }

    private fun descendWholeWordSequence(
        store: NgramScoreStore, tune: List<Replayable>, start: Config, dict: List<WordEntry>,
    ): Config {
        var best = start
        var bestAccuracy = evaluateWholeWordSequence(tune, store, best, dict).wordAcc
        println("ACC SEQ tune start wordAcc=%.3f ${best.spec()}".format(bestAccuracy))
        repeat(3) { round ->
            var improved = false
            fun tryCandidate(candidate: Config) {
                val accuracy = evaluateWholeWordSequence(tune, store, candidate, dict).wordAcc
                if (accuracy > bestAccuracy + 0.05) {
                    bestAccuracy = accuracy
                    best = candidate
                    improved = true
                    println("ACC SEQ tune r$round wordAcc=%.3f ${candidate.spec()}".format(accuracy))
                }
            }
            for (weight in doubleArrayOf(0.0, 0.15, 0.3, 0.5, 0.75, 1.0, 1.5)) tryCandidate(best.copy(bigramWeight = weight))
            for (weight in doubleArrayOf(0.0, 0.15, 0.3, 0.5, 0.75, 1.0, 1.5)) tryCandidate(best.copy(trigramWeight = weight))
            tryCandidate(best.copy(residual = !best.residual))
            for (margin in doubleArrayOf(0.25, 0.5, 1.0, 2.0, 3.0, 4.0)) tryCandidate(best.copy(margin = margin))
            for (floor in doubleArrayOf(-2.0, -1.0, 0.0)) tryCandidate(best.copy(floor = floor))
            for (cap in doubleArrayOf(4.0, 6.0, 8.0)) tryCandidate(best.copy(cap = cap))
            for (weight in doubleArrayOf(0.05, 0.1, 0.2, 0.3, 0.5, 0.75)) tryCandidate(best.copy(frequencyWeight = weight))
            if (!improved) return best
        }
        return best
    }

    /** Mirrors MANUAL/AFTER_SWIPE sequence reranking and the six-unit rolling window. */
    private fun replayWholeWordSequence(
        replayable: Replayable,
        store: NgramScoreStore,
        config: Config,
        dict: List<WordEntry>,
    ): Totals {
        val segmenter = InferenceSegmenter(
            contextScorer = NgramContextScorer(
                config.windowSize,
                config.bigramWeight,
                config.trigramWeight,
                scoreStore = store,
            ),
            confidenceMargin = config.margin,
            frequencyWeight = config.frequencyWeight,
            extraWordFrequencyWeight = config.extraWordFrequencyWeight,
        )
        val history = replayable.history.toMutableList()
        val totals = Totals()
        for (part in replayable.parts) {
            when (part) {
                is Tap -> history += part.word
                is Swipes -> {
                    var window = emptyList<GestureUnit>()
                    var current = emptyList<String>()
                    val produced = ArrayList<String>()
                    part.units.zip(part.ref).forEach { (swipe, referenceWord) ->
                        totals.sequenceMetrics.observeCandidate(
                            reference = referenceWord,
                            candidates = swipe.candidates.asSequence().flatten().map { it.word.word }.toList(),
                        )
                    }
                    for (unit in part.units) {
                        val sliding = window.size == MAX_UNITS
                        val previousWords = if (sliding) current.drop(1) else current
                        val previousUnits = if (sliding) window.drop(1) else window
                        if (sliding) {
                            val frozenWord = current.firstOrNull()
                                ?: window.first().topWords().joinToString(separator = "")
                            produced += frozenWord
                            history += frozenWord
                            window = window.drop(1) + unit
                        } else {
                            window = window + unit
                        }
                        val ranked = segmenter.rank(
                            units = window,
                            previousWords = history.takeLast(3),
                            dictionary = dict,
                            policy = InferenceDecodingPolicy.ONE_WORD_PER_GESTURE,
                        )
                        current = ranked.firstOrNull()?.words
                            ?: window.map { it.topWords().joinToString(separator = "") }
                        if (previousWords.isNotEmpty()) {
                            totals.sequenceMetrics.observeRevision(
                                previous = previousWords,
                                current = current,
                                reference = part.ref.drop(produced.size),
                                previousConfidenceGaps = previousUnits.mapIndexed { index, previousUnit ->
                                    previousUnit.confidenceGap(previousWords[index])
                                },
                            )
                        }
                    }
                    produced += current
                    history += current
                     scoreSequence(
                         ref = part.ref,
                        out = produced,
                        raw = part.units.flatMap { it.topWords() },
                        t = totals,
                    )
                }
            }
        }
        return totals
    }

    private fun objective(set: List<Replayable>, store: NgramScoreStore, config: Config, dict: List<WordEntry>): Double {
        val t = evaluate(set, store, config, dict)
        return 0.6 * (t["sep"]?.wordAcc ?: 0.0) + 0.4 * (t["mixed"]?.wordAcc ?: 0.0)
    }

    private fun descend(store: NgramScoreStore, set: List<Replayable>, start: Config, dict: List<WordEntry>): Config {
        var best = start
        var bestJ = objective(set, store, best, dict)
        println("ACC SEG tune start J=%.3f ${best.spec()}".format(bestJ))
        repeat(3) { round ->
            var improved = false
            fun try_(candidate: Config) {
                val j = objective(set, store, candidate, dict)
                if (j > bestJ + 0.005) { bestJ = j; best = candidate; improved = true; println("ACC SEG tune r$round J=%.3f ${candidate.spec()}".format(j)) }
            }
            for (v in doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0)) try_(best.copy(bigramWeight = v))
            for (v in doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0, 1.5)) try_(best.copy(trigramWeight = v))
            try_(best.copy(residual = !best.residual))
            for (v in doubleArrayOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0)) try_(best.copy(margin = v))
            for (v in doubleArrayOf(-2.0, -1.0, -0.5, 0.0)) try_(best.copy(floor = v))
            for (v in doubleArrayOf(3.0, 4.0, 6.0, 8.0)) try_(best.copy(cap = v))
            for (v in doubleArrayOf(0.05, 0.1, 0.2, 0.3, 0.5, 0.75, 1.0)) try_(best.copy(frequencyWeight = v))
            if (!improved) return best
        }
        return best
    }

    // ---- evaluation ---------------------------------------------------------------------------------
    private val pool = Executors.newFixedThreadPool(minOf(Runtime.getRuntime().availableProcessors(), 8)) { r ->
        Thread(r).apply { isDaemon = true }
    }

    private fun evaluate(set: List<Replayable>, store: NgramScoreStore, config: Config, dict: List<WordEntry>): Map<String, Totals> {
        val shaped = shape(store, config)
        val results = pool.invokeAll(set.map { r -> Callable { r.scenario to replay(r, shaped, config, dict) } })
        val byScenario = HashMap<String, Totals>()
        for (f in results) { val (scenario, totals) = f.get(); byScenario.getOrPut(scenario) { Totals() }.add(totals) }
        return byScenario
    }

    /** Applies the clamp window and (optionally) turns the trigram into the residual over its own bigram. */
    private fun shape(store: NgramScoreStore, c: Config): NgramScoreStore = object : NgramScoreStore {
        override fun bigram(previous: String, next: String) = store.bigram(previous, next).coerceIn(c.floor, c.cap)
        override fun trigram(first: String, second: String, next: String): Double {
            val t = store.trigram(first, second, next)
            return (if (c.residual) t - store.bigram(second, next) else t).coerceIn(c.floor, c.cap)
        }
    }

    /** Mirrors SwipeTypingCoordinator.acceptInference / slideInferenceWindow for INFER_SPACES mode. */
    private fun replay(r: Replayable, store: NgramScoreStore, c: Config, dict: List<WordEntry>): Totals {
        val segmenter = InferenceSegmenter(
            contextScorer = NgramContextScorer(c.windowSize, c.bigramWeight, c.trigramWeight, scoreStore = store),
            confidenceMargin = c.margin,
            frequencyWeight = c.frequencyWeight,
            extraWordFrequencyWeight = c.extraWordFrequencyWeight,
        )
        val history = r.history.toMutableList()
        fun previous() = history.takeLast(3)
        val totals = Totals()
        for (part in r.parts) {
            when (part) {
                is Tap -> history += part.word
                is Swipes -> {
                    var window = listOf<GestureUnit>()
                    var current = listOf<String>()
                    val produced = ArrayList<String>()
                    for (unit in part.units) {
                        if (window.size == MAX_UNITS) {
                            val finalized = segmenter.rank(listOf(window.first()), previous(), dict)
                            val rawFinalized = finalized.firstOrNull()?.words ?: window.first().topWords()
                            val retained = window.drop(1) + unit
                            val alternatives = segmenter.rank(retained, previous() + rawFinalized, dict)
                            current = alternatives.firstOrNull()?.words ?: unit.topWords()
                            produced += rawFinalized
                            history += rawFinalized
                            window = retained
                        } else {
                            window = window + unit
                            val alternatives = preserveSingleGestureTopWord(unit, segmenter.rank(window, previous(), dict))
                            current = alternatives.firstOrNull()?.words ?: unit.topWords()
                        }
                    }
                    produced += current
                    history += current
                    score(part.ref, produced, part.units.flatMap { it.topWords() }, totals)
                }
            }
        }
        return totals
    }

    private fun preserveSingleGestureTopWord(unit: GestureUnit, alternatives: List<SegmentationOption>): List<SegmentationOption> {
        val recognized = unit.topWords()
        val first = alternatives.firstOrNull() ?: return alternatives
        if (unit.paths.size != 1 || recognized.size != 1 || first.words.size != 1) return alternatives
        if (first.words == recognized) return alternatives
        return alternatives.sortedWith(compareByDescending<SegmentationOption> { it.words == recognized }.thenByDescending { it.score })
    }

    private fun GestureUnit.topWords(): List<String> = candidates.map { it.first().word.word }

    private fun GestureUnit.confidenceGap(selectedWord: String): Double {
        val scoresByWord = candidates.asSequence()
            .flatten()
            .groupBy { candidate -> candidate.word.word }
            .mapValues { (_, candidatesForWord) -> candidatesForWord.maxOf { candidate -> candidate.score } }
            .toList()
            .sortedByDescending { (_, score) -> score }
        if (scoresByWord.size < 2 || scoresByWord.first().first != selectedWord) return 0.0
        return scoresByWord[0].second - scoresByWord[1].second
    }

    private fun score(ref: List<String>, out: List<String>, raw: List<String>, t: Totals) {
        t.words += ref.size
        t.segments += 1
        t.matched += lcs(ref, out)
        t.rawMatched += lcs(ref, raw)
        if (ref == out) t.exactSegments += 1
        boundaryErrors(ref, out, t)
    }

    private fun scoreSequence(ref: List<String>, out: List<String>, raw: List<String>, t: Totals) {
        t.words += ref.size
        t.segments += 1
        t.matched += positionalWordMatches(ref, out)
        t.rawMatched += positionalWordMatches(ref, raw)
        if (ref == out) t.exactSegments += 1
        boundaryErrors(ref, out, t)
    }

    /**
     * Aligns the two letter strings (LCS) and judges a word boundary only where the neighbouring letters are matched
     * and adjacent on both sides, so recognition errors elsewhere in the phrase do not hide split/join mistakes.
     * Wrong split: an output boundary inside a reference word. Wrong join: a reference boundary missing in the output.
     */
    private fun boundaryErrors(ref: List<String>, out: List<String>, t: Totals) {
        val a = ref.joinToString(""); val b = out.joinToString("")
        val refCuts = cuts(ref); val outCuts = cuts(out)
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.length - 1 downTo 0) for (j in b.length - 1 downTo 0)
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val mapAB = IntArray(a.length) { -1 }
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { mapAB[i] = j; i++; j++ } else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
        }
        val mapBA = IntArray(b.length) { -1 }
        for (k in a.indices) if (mapAB[k] >= 0) mapBA[mapAB[k]] = k
        for (cut in refCuts) { // boundary between a[cut-1] and a[cut]
            val p = mapAB[cut - 1]; val q = mapAB[cut]
            if (p >= 0 && q == p + 1) { t.refBoundaries += 1; if (q !in outCuts) t.wrongJoins += 1 }
        }
        for (cut in outCuts) {
            val p = mapBA[cut - 1]; val q = mapBA[cut]
            if (p >= 0 && q == p + 1) { t.outBoundaries += 1; if (q !in refCuts) t.wrongSplits += 1 }
        }
    }

    private fun cuts(words: List<String>): Set<Int> {
        val result = HashSet<Int>(); var position = 0
        for (i in 0 until words.size - 1) { position += words[i].length; result += position }
        return result
    }

    private fun lcs(a: List<String>, b: List<String>): Int {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size)
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        return dp[a.size][b.size]
    }

    // ---- generation ---------------------------------------------------------------------------------
    private fun generate(
        name: String, cases: List<PhraseCase>, repeats: Int, layout: KeyboardLayout, dict: List<WordEntry>, seed: Long,
    ): List<Replayable> {
        val rng = Random(seed)
        val sigmas = doubleArrayOf(0.12, 0.18, 0.25)
        // Plan sequentially (deterministic), recognize in parallel.
        class Plan(val scenario: String, val history: List<String>, val items: List<Any>) // Tap | List<Pair<ref words..., texts>>
        class Piece(val text: String, val sigma: Double, val path: com.iaido.core.gesture.GesturePath)
        class RunPlan(val ref: List<String>, val pieces: List<Piece>)
        val plans = ArrayList<Plan>()
        for (case in cases) repeat(repeats) {
            for (scenario in listOf("sep", "mixed")) {
                val items = ArrayList<Any>()
                var run = ArrayList<String>()
                fun flushRun() {
                    if (run.isEmpty()) return
                    val pieces = ArrayList<Piece>()
                    var i = 0
                    while (i < run.size) {
                        val roll = if (scenario == "mixed") rng.nextDouble() else 1.0
                        val sigma = sigmas[rng.nextInt(sigmas.size)]
                        fun add(text: String) {
                            pieces += Piece(text, sigma, SyntheticSwipes.synthesize(layout, text, sigma, rng, HashSet()))
                        }
                        when {
                            roll < 0.12 && i + 1 < run.size -> { add(run[i] + run[i + 1]); i += 2 }
                            roll < 0.24 && run[i].length >= 4 -> {
                                val cut = 2 + rng.nextInt(run[i].length - 3)
                                add(run[i].substring(0, cut)); add(run[i].substring(cut)); i++
                            }
                            else -> { add(run[i]); i++ }
                        }
                    }
                    items += RunPlan(run.toList(), pieces)
                    run = ArrayList()
                }
                for (w in case.words) { if (w.length == 1) { flushRun(); items += w } else run += w }
                flushRun()
                plans += Plan(scenario, case.history, items)
            }
        }
        val recognizer = ThreadLocal.withInitial { GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer()) }
        val built = pool.invokeAll(plans.mapIndexed { index, plan ->
            Callable {
                var unitId = 0
                val parts = ArrayList<Part>()
                for (item in plan.items) {
                    if (item is String) { parts += Tap(item); continue }
                    item as RunPlan
                    val units = item.pieces.map { piece ->
                        val candidates = recognizer.get().recognize(piece.path, layout, dict).take(60)
                        if (candidates.isEmpty()) return@Callable null
                        GestureUnit("$name-$index-${unitId++}", listOf(piece.path), listOf(candidates), concurrent = false)
                    }
                    parts += Swipes(item.ref, units)
                }
                Replayable(plan.scenario, plan.history, parts)
            }
        }).mapNotNull { it.get() }
        println("ACC SEG generated $name: ${built.size}/${plans.size} replayables")
        return built
    }

    // ---- assets -------------------------------------------------------------------------------------
    private fun loadPhrases(name: String): List<PhraseCase> {
        val text = javaClass.getResourceAsStream("/segmentation/$name")?.bufferedReader()?.readText()
            ?: error("missing /segmentation/$name")
        return parsePhrases(text)
    }

    private fun loadPhraseFile(path: String): List<PhraseCase> = parsePhrases(File(path).readText(Charsets.UTF_8))

    private fun parsePhrases(text: String): List<PhraseCase> {
        return text.lines().filter { it.isNotBlank() }.map { line ->
            val (history, phrase) = line.split('|', limit = 2)
            PhraseCase(history.trim().split(' ').filter { it.isNotEmpty() }, phrase.trim().split(' ').filter { it.isNotEmpty() })
        }
    }

    private fun loadDictionary(lang: String): List<WordEntry> =
        assets().resolve("dictionary/$lang.csv").readLines(Charsets.UTF_8).drop(1).mapNotNull { line ->
            val c = line.split(',', limit = 2)
            val fr = c.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (c.size != 2 || c[0].isBlank() || fr == null || fr <= 0.0) null else WordEntry(c[0].trim(), fr)
        }

    private fun loadStore(file: File, dict: List<WordEntry>): CompactNgramScoreStore {
        val frequencies = dict.associate { it.word to it.frequency }
        return CompactNgramScoreStore.fromBytes(file.readBytes(), dict.mapIndexed { i, e -> e.word to i }.toMap()) { frequencies[it] ?: 0.0 }
    }

    private fun assets(): File {
        var dir = File(".").absoluteFile
        repeat(6) {
            val c = File(dir, "app/src/main/assets")
            if (c.isDirectory) return c
            dir = dir.parentFile ?: return@repeat
        }
        error("assets not found")
    }

    private companion object {
        const val MAX_UNITS = 6
    }
}
