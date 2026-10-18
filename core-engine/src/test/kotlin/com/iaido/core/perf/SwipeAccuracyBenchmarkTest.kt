package com.iaido.core.perf

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.recognition.CompactNgramScoreStore
import com.iaido.core.recognition.GestureRecognizer
import com.iaido.core.recognition.NgramContextScorer
import com.iaido.core.recognition.ScoringConstants
import com.iaido.core.recognition.ShapePathScorer
import com.iaido.core.recognition.TrieCandidateGenerator
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Opt-in synthetic swipe-accuracy benchmark (deterministic, fixed seeds).
 *
 *   IAIDO_ACCURACY=true  ./gradlew.bat :core-engine:test --tests '*SwipeAccuracyBenchmarkTest*' --no-daemon -i
 *   IAIDO_ACCURACY=tune  ... also runs coordinate-descent weight tuning on the TUNE split and reports HELDOUT.
 *   IAIDO_WEIGHTS="dist,corner,freq,bigram,trigram,endErr,lenErr" overrides the evaluated weights
 *   IAIDO_ACC_N=1.0      scales sample counts.
 *   IAIDO_NGRAM_EN / IAIDO_NGRAM_HE=<path> evaluate another n-gram asset (e.g. the old NKG1 one or an experiment).
 *
 * Swipes are SYNTHETIC (dense noisy paths from a hand-written motion model), not recorded from users.
 * Candidate features are computed once per swipe, so any weight vector is re-scored without re-running the
 * recognizer; an integrity check confirms the feature-based ranking equals GestureRecognizer's.
 */
class SwipeAccuracyBenchmarkTest {

    // ---- features -------------------------------------------------------------------------
    // Weight vector: 0 distance, 1 corner, 2 ln(freq), 3 bigram, 4 trigram, 5 endpoint, 6 trim penalty per key.
    // feat[1..4] = corner, ln(freq), bigram, trigram; per-variant distance/endpoint live in vd/ve.
    private val featureCount = 5
    /** The constants shipped before the accuracy work (the baseline every report compares against). */
    private val legacyWeights = doubleArrayOf(2.1, 0.5, 0.1, 1.0, 1.0, 0.0, 1e9, 1e9)

    /** Whatever [ScoringConstants] currently ships. */
    private val productionWeights = doubleArrayOf(
        ScoringConstants.SHAPE_DISTANCE_WEIGHT, ScoringConstants.CORNER_BONUS_WEIGHT,
        ScoringConstants.FREQUENCY_WEIGHT, ScoringConstants.CONTEXT_WEIGHT, ScoringConstants.CONTEXT_WEIGHT,
        ScoringConstants.ENDPOINT_WEIGHT, ScoringConstants.TRIM_PENALTY_PER_KEY, 1e9,
    )
    private val smoothing = System.getenv("IAIDO_SMOOTH")?.toInt() ?: ScoringConstants.SMOOTHING_PASSES

    private class Sample(
        val target: String,
        val prev: List<String>,
        val sigma: Double,
        val tags: Set<String>,
        val path: GesturePath,
        val words: Array<String>,
        val feat: Array<FloatArray>,
        val vd: Array<FloatArray>,
        val ve: Array<FloatArray>,
        val trim: DoubleArray,
        val targetIndex: Int,
        val targetFreq: Double,
    ) {
        private fun rest(i: Int, w: DoubleArray) =
            w[1] * feat[1][i] + w[2] * feat[2][i] + w[3] * feat[3][i] + w[4] * feat[4][i]

        /** Final scores; trimmed readings only for candidates whose untrimmed score is within w[7] of the best. */
        fun scores(w: DoubleArray): DoubleArray {
            val n = words.size
            val out = DoubleArray(n)
            val gate = if (w.size > 7) w[7] else 1e9
            val withTrim = w[6] < 1e6 && vd.size > 1
            var best0 = -Double.MAX_VALUE
            for (i in 0 until n) {
                out[i] = -w[0] * vd[0][i] - w[5] * ve[0][i] + rest(i, w)
                if (out[i] > best0) best0 = out[i]
            }
            if (!withTrim) return out
            for (i in 0 until n) {
                if (out[i] < best0 - gate) continue
                var shape = -w[0] * vd[0][i] - w[5] * ve[0][i]
                for (v in 1 until vd.size) {
                    val c = -w[0] * vd[v][i] - w[5] * ve[v][i] - w[6] * trim[v]
                    if (c > shape) shape = c
                }
                out[i] = shape + rest(i, w)
            }
            return out
        }

        fun rank(w: DoubleArray): Int {
            if (targetIndex < 0) return Int.MAX_VALUE
            val sc = scores(w)
            val ts = sc[targetIndex]
            var better = 0
            for (i in sc.indices) if (i != targetIndex && sc[i] > ts) better++
            return better
        }

        fun top(w: DoubleArray): String {
            val sc = scores(w)
            var best = -1; var bs = -Double.MAX_VALUE
            for (i in sc.indices) if (sc[i] > bs) { bs = sc[i]; best = i }
            return if (best < 0) "" else words[best]
        }
    }

    private class SampleSet(val name: String, val samples: List<Sample>)

    private inner class Lang(
        val name: String,
        val layout: KeyboardLayout,
        val dict: List<WordEntry>,
        val store: CompactNgramScoreStore,
    ) {
        val letters = layout.keys.map { it.letter }.toSet()
        val eligible = dict.filter { it.word.length >= 2 && it.word.all { c -> c in letters } }
            .sortedByDescending { it.frequency }
        val freqOf = dict.associate { it.word to it.frequency }
        val generator = TrieCandidateGenerator((System.getenv("IAIDO_PROX") ?: ScoringConstants.PROXIMITY_THRESHOLD.toString()).toFloat())
        val scorer = ShapePathScorer(endpointWeight = 0.0, smoothingPasses = smoothing, trimPenaltyPerKey = 1.0, trimStepsKeys = (System.getenv("IAIDO_TRIM_STEPS")?.split(",")?.map { it.toDouble() }?.toDoubleArray() ?: ScoringConstants.TRIM_STEPS_KEYS))
        val head = eligible.take(2000)
        val successorCache = HashMap<String, List<Pair<WordEntry, Double>>>()
    }

    // ---- test entry ----------------------------------------------------------------------------
    @Test
    fun accuracy() {
        val mode = System.getProperty("iaido.accuracy") ?: System.getenv("IAIDO_ACCURACY")
        assumeTrue(mode == "true" || mode == "tune")
        val scale = (System.getenv("IAIDO_ACC_N") ?: "1.0").toDouble()
        val sigmas = doubleArrayOf(0.15, 0.25, 0.35)
        val en = loadLang("en", KeyboardLayout.qwertyTestLayout())
        val he = loadLang("he", KeyboardLayout.hebrewTestLayout())
        val override = System.getenv("IAIDO_WEIGHTS")?.split(',')?.map { it.trim().toDouble() }?.toDoubleArray()?.let { if (it.size < 8) it + DoubleArray(8 - it.size) { 1e9 } else it }
        var weights = override ?: productionWeights.copyOf()

        System.getenv("IAIDO_DEBUG_WORD")?.let { w ->
            val rng = Random(7)
            for (sg in sigmas) {
                val ss = List(200) { makeSample(en, w, emptyList(), sg, rng) }
                println("ACC debug '$w' sigma=$sg recall=${ss.count { it.targetIndex >= 0 }}/200 top1=${ss.count { it.rank(legacyWeights) == 0 }}")
                ss.firstOrNull { it.targetIndex < 0 }?.let { s ->
                    println("ACC   missed path: " + s.path.points.joinToString(" ") { "(%.1f,%.1f)".format(it.x, it.y) })
                }
            }
        }
        val enTune = buildSets(en, seed = 1001L, parity = 0, sigmas = sigmas, scale = scale)
        val enHeld = buildSets(en, seed = 2002L, parity = 1, sigmas = sigmas, scale = scale)
        integrityCheck(en, enTune.first().samples)
        timing(en, enTune.first().samples.take(300))

        println("ACC === LEGACY (pre-tuning) WEIGHTS ${fmt(legacyWeights)} ===")
        reportAll("EN-TUNE", enTune, legacyWeights)
        reportAll("EN-HELDOUT", enHeld, legacyWeights)
        if (!productionWeights.contentEquals(legacyWeights)) {
            println("ACC === CURRENT ScoringConstants ${fmt(productionWeights)} ===")
            reportAll("EN-TUNE", enTune, productionWeights)
            reportAll("EN-HELDOUT", enHeld, productionWeights)
        }

        if (mode == "tune") {
            weights = tune(enTune, weights)
            println("ACC === TUNED WEIGHTS ${fmt(weights)} ===")
        }
        if (override != null || mode == "tune") {
            reportAll("EN-TUNE", enTune, weights)
            reportAll("EN-HELDOUT", enHeld, weights)
            failures(enHeld.first { it.name.startsWith("token") }, legacyWeights, "legacy")
            failures(enHeld.first { it.name.startsWith("token") }, weights, "candidate")
        }

        sweep("EN", enHeld, weights)
        val heHeld = buildSets(he, seed = 3003L, parity = 1, sigmas = sigmas, scale = scale * 0.5)
        sweep("HE", heHeld, weights)
        println("ACC === HEBREW (not tuned on) legacy ===")
        reportAll("HE-HELDOUT", heHeld, legacyWeights)
        if (override != null || mode == "tune" || !productionWeights.contentEquals(legacyWeights)) {
            println("ACC === HEBREW candidate ===")
            reportAll("HE-HELDOUT", heHeld, weights)
        }
    }

    private fun sweep(tag: String, sets: List<SampleSet>, weights: DoubleArray) {
        val spec = System.getenv("IAIDO_SWEEP") ?: return
        val (idx, vals) = spec.split(':').let { it[0].toInt() to it[1].split(',').map(String::toDouble) }
        for (v in vals) {
            val w = weights.copyOf(); w[idx] = v
            if (idx == 3) w[4] = v
            val sb = StringBuilder("ACC sweep $tag idx=$idx v=$v")
            for (set in sets) {
                val r = set.samples.map { it.rank(w) }
                sb.append(" | %s top1=%.1f top3=%.1f".format(set.name.substringBefore('('), 100.0 * r.count { it < 1 } / r.size, 100.0 * r.count { it < 3 } / r.size))
            }
            println(sb)
        }
    }

    // ---- reporting -------------------------------------------------------------------------------
    private fun fmt(w: DoubleArray) = w.joinToString(",") { "%.4g".format(it) }

    private fun reportAll(tag: String, sets: List<SampleSet>, w: DoubleArray) {
        for (set in sets) {
            val line = StringBuilder()
            for (sigma in listOf(0.15, 0.25, 0.35, -1.0)) {
                val ss = set.samples.filter { sigma < 0 || it.sigma == sigma }
                if (ss.isEmpty()) continue
                val ranks = ss.map { it.rank(w) }
                fun p(k: Int) = 100.0 * ranks.count { it < k } / ranks.size
                val label = if (sigma < 0) "ALL" else "s=%.2f".format(sigma)
                line.append(" | %s n=%d top1=%.1f top3=%.1f top5=%.1f".format(label, ss.size, p(1), p(3), p(5)))
            }
            val rec = 100.0 * set.samples.count { it.targetIndex >= 0 } / set.samples.size
            println("ACC $tag ${set.name}$line | candidateRecall=%.1f".format(rec))
        }
        // Subgroups of the token set at all noise levels.
        val token = sets.firstOrNull { it.name.startsWith("token") } ?: return
        val groups = listOf("double", "skip", "len<=3", "len>=7", "clean")
        val sb = StringBuilder()
        for (g in groups) {
            val ss = token.samples.filter { g in it.tags }
            if (ss.isEmpty()) continue
            val r = ss.map { it.rank(w) }
            sb.append(" %s(n=%d top1=%.1f recall=%.1f)".format(g, ss.size, 100.0 * r.count { it < 1 } / r.size, 100.0 * ss.count { it.targetIndex >= 0 } / ss.size))
        }
        println("ACC $tag token-subgroups:$sb")
    }

    private fun failures(set: SampleSet, w: DoubleArray, label: String) {
        val tag = System.getenv("IAIDO_FAIL_TAG")
        val bad = set.samples.filter { it.rank(w) >= 1 && (tag == null || tag in it.tags) }.sortedByDescending { it.targetFreq }.take(15)
        println("ACC worst top-1 failures ($label weights), by target frequency:")
        for (s in bad) {
            val r = s.rank(w)
            println("ACC   %-12s -> %-12s rank=%s sigma=%.2f tags=%s".format(
                s.target, s.top(w), if (r == Int.MAX_VALUE) "not-candidate" else r.toString(), s.sigma, s.tags))
        }
    }

    // ---- tuning ----------------------------------------------------------------------------------
    private fun objective(sets: List<SampleSet>, w: DoubleArray): Double {
        var total = 0.0
        var norm = 0.0
        for (set in sets) {
            val weight = if (set.name.startsWith("type")) 0.5 else if (set.name.startsWith("context")) 0.7 else if (set.name.startsWith("wrong")) 0.35 else 1.0
            val ranks = set.samples.map { it.rank(w) }
            val n = ranks.size.toDouble()
            val v = ranks.count { it < 1 } / n + 0.3 * ranks.count { it < 3 } / n + 0.1 * ranks.count { it < 5 } / n
            total += weight * v
            norm += weight
        }
        return total / norm
    }

    private fun tune(sets: List<SampleSet>, start: DoubleArray): DoubleArray {
        val w = start.copyOf()
        val tie = System.getenv("IAIDO_TIE") != "false"
        w[4] = if (tie) w[3] else w[4]
        val tunable = (System.getenv("IAIDO_TUNE_IDX") ?: (if (tie) "1,2,3,5,6" else "1,2,3,4,5,6")).split(',').map { it.toInt() }
        var best = objective(sets, w)
        println("ACC tune start J=%.4f".format(best))
        repeat(6) { round ->
            var improved = false
            for (k in tunable) {
                val base = w[k]
                val candidates = if (base >= 1e6) listOf(1.0, 2.0, 4.0, 8.0) else if (base == 0.0) listOf(0.1, 0.3, 1.0, 3.0)
                else listOf(0.25, 0.5, 0.7, 0.85, 1.2, 1.5, 2.0, 3.0).map { base * it } + (if (k == 6) emptyList() else listOf(0.0))
                var bestV = base
                for (c in candidates) {
                    w[k] = c
                    if (k == 3 && tie) w[4] = c
                    val j = objective(sets, w)
                    if (j > best + 1e-5) { best = j; bestV = c; improved = true }
                }
                w[k] = bestV
                if (k == 3 && tie) w[4] = bestV
                println("ACC tune round=$round idx=$k -> %.4g J=%.4f".format(bestV, best))
            }
            if (!improved) return w
        }
        return w
    }

    // ---- sample sets -----------------------------------------------------------------------------
    private fun buildSets(lang: Lang, seed: Long, parity: Int, sigmas: DoubleArray, scale: Double): List<SampleSet> {
        val rng = Random(seed)
        val pool = lang.eligible.filter { (it.word.hashCode() and 1) == parity }
        val cum = DoubleArray(pool.size)
        var acc = 0.0
        pool.forEachIndexed { i, e -> acc += e.frequency; cum[i] = acc }
        fun tokenWord(): WordEntry {
            val r = rng.nextDouble() * acc
            var lo = 0; var hi = pool.size - 1
            while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < r) lo = m + 1 else hi = m }
            return pool[lo]
        }
        val top = pool.take(3000)
        val nTok = (1200 * scale).toInt(); val nType = (600 * scale).toInt(); val nCtx = (500 * scale).toInt()
        val token = ArrayList<Sample>(); val type = ArrayList<Sample>(); val ctx = ArrayList<Sample>()
        val unrelated = ArrayList<Sample>()
        for (sigma in sigmas) {
            repeat(nTok) { token += makeSample(lang, tokenWord().word, emptyList(), sigma, rng) }
            repeat(nType) { type += makeSample(lang, top[rng.nextInt(top.size)].word, emptyList(), sigma, rng) }
            var made = 0
            while (made < nCtx) {
                val c = contextCase(lang, pool, rng) ?: continue
                ctx += makeSample(lang, c.second, c.first, sigma, rng)
                made++
            }
            // Unrelated context: previous words and target drawn independently by frequency, as in free text where
            // the n-gram store usually has no (or only an accidental) entry for the pair.
            repeat(nCtx) {
                val prevWords = List(1 + rng.nextInt(2)) { pickWeighted(lang.head, rng)!!.word }
                unrelated += makeSample(lang, tokenWord().word, prevWords, sigma, rng)
            }
        }
        // Wrong-start / stray-tail variants on 3-9 letter words, with and without a real previous word.
        val variantSets = ArrayList<SampleSet>()
        val nVar = (200 * scale).toInt()
        for (variant in listOf("neighbor", "wrongrow", "numrow", "trail", "neighbor+trail")) {
            for (withCtx in listOf(false, true)) {
                val list = ArrayList<Sample>()
                for (sigma in sigmas) {
                    var made = 0
                    while (made < nVar) {
                        val prev: List<String>; val word: String
                        if (withCtx) {
                            val c = contextCase(lang, pool, rng) ?: continue
                            prev = c.first; word = c.second
                        } else { prev = emptyList(); word = tokenWord().word }
                        if (word.length !in 3..9) continue
                        list += makeSample(lang, word, prev, sigma, rng, variant)
                        made++
                    }
                }
                variantSets += SampleSet("wrong-start:$variant(${if (withCtx) "ctx" else "noctx"})", list)
            }
        }
        return listOf(
            SampleSet("token(freq-weighted,no-context)", token),
            SampleSet("type(top3000-uniform,no-context)", type),
            SampleSet("context(prev words from real n-grams)", ctx),
            SampleSet("unrelated-context(prev random, target by freq)", unrelated),
        ) + variantSets
    }

    /** Returns (previous words, target) where target follows the previous words in the n-gram data. */
    private fun contextCase(lang: Lang, targetPool: List<WordEntry>, rng: Random): Pair<List<String>, String>? {
        val head = lang.head
        val useTwo = rng.nextBoolean()
        val w1 = pickWeighted(head, rng) ?: return null
        val s1 = successors(lang, w1.word, lang.eligible.take(6000))
        if (s1.isEmpty()) return null
        if (!useTwo) {
            val t = pickSuccessor(s1, rng, targetPool) ?: return null
            return listOf(w1.word) to t
        }
        val w2 = pickSuccessor(s1, rng, null) ?: return null
        val s2 = successors(lang, w2, lang.eligible.take(6000))
        if (s2.isEmpty()) return null
        val t = pickSuccessor(s2, rng, targetPool) { cand -> lang.store.trigram(w1.word, w2, cand) } ?: return null
        return listOf(w1.word, w2) to t
    }

    private fun pickWeighted(list: List<WordEntry>, rng: Random): WordEntry? {
        val total = list.sumOf { it.frequency }
        var r = rng.nextDouble() * total
        for (e in list) { r -= e.frequency; if (r <= 0) return e }
        return list.lastOrNull()
    }

    private fun successors(lang: Lang, prev: String, universe: List<WordEntry>): List<Pair<WordEntry, Double>> =
        lang.successorCache.getOrPut(prev) {
            universe.mapNotNull { e ->
                val s = lang.store.bigram(prev, e.word)
                if (s > 0.0) e to s else null
            }
        }

    /** Samples a successor weighted by exp(bigram score) ~ count; optionally requiring membership in [targetPool]. */
    private fun pickSuccessor(
        list: List<Pair<WordEntry, Double>>,
        rng: Random,
        targetPool: List<WordEntry>?,
        extra: (String) -> Double = { 0.0 },
    ): String? {
        val allowed = if (targetPool == null) list else {
            val parity = targetPool.first().word.hashCode() and 1
            list.filter { (it.first.word.hashCode() and 1) == parity }
        }
        if (allowed.isEmpty()) return null
        val total = allowed.sumOf { exp(it.second + extra(it.first.word)) }
        var r = rng.nextDouble() * total
        for ((e, s) in allowed) { r -= exp(s + extra(e.word)); if (r <= 0) return e.word }
        return allowed.last().first.word
    }

    // ---- swipe synthesis -----------------------------------------------------------------------------
    private fun makeSample(lang: Lang, word: String, prev: List<String>, sigma: Double, rng: Random, variant: String? = null): Sample {
        val tags = HashSet<String>()
        if (word.zipWithNext().any { it.first == it.second }) tags += "double"
        if (word.length <= 3) tags += "len<=3"
        if (word.length >= 7) tags += "len>=7"
        val path = synthesize(lang.layout, word, sigma, rng, tags, variant)
        if ("skip" !in tags && "double" !in tags) tags += "clean"
        return extract(lang, word, prev, sigma, tags, path)
    }

    private fun synthesize(layout: KeyboardLayout, word: String, sigma: Double, rng: Random, tags: MutableSet<String>, variant: String? = null): GesturePath =
        SyntheticSwipes.synthesize(layout, word, sigma, rng, tags, variant)

    // ---- feature extraction ---------------------------------------------------------------------------
    private fun extract(lang: Lang, target: String, prev: List<String>, sigma: Double, tags: Set<String>, path: GesturePath): Sample {
        val cands = lang.generator.generateCandidates(path, lang.layout, lang.dict)
        val (kept, terms) = lang.scorer.variantTerms(path, cands, lang.layout)
        val n = kept.size
        val v = terms.trimKeys.size
        val f = Array(featureCount) { FloatArray(n) }
        for (i in 0 until n) {
            val w = kept[i].word
            f[1][i] = terms.corner[i]
            f[2][i] = ln(kept[i].frequency.coerceAtLeast(ScoringConstants.MIN_FREQUENCY)).toFloat()
            f[3][i] = if (prev.isNotEmpty()) lang.store.bigram(prev.last(), w).toFloat() else 0f
            f[4][i] = if (prev.size >= 2) lang.store.trigram(prev[prev.size - 2], prev.last(), w).toFloat() else 0f
        }
        val ids = Array(n) { kept[it].word }
        val keep = pruneIndices(f, terms, n, ids.indexOf(target))
        fun cut(a: FloatArray) = FloatArray(keep.size) { a[keep[it]] }
        val pf = Array(featureCount) { cut(f[it]) }
        val pvd = Array(v) { cut(terms.distance[it]) }
        val pve = Array(v) { cut(terms.endpoint[it]) }
        val pw = Array(keep.size) { ids[keep[it]] }
        val ti = pw.indexOf(target)
        return Sample(target, prev, sigma, tags, path, pw, pf, pvd, pve, terms.trimKeys, ti, lang.freqOf[target] ?: 0.0)
    }

    /** Bounds memory: keeps the union of the top candidates under a few generous weightings (+ the target). */
    private fun pruneIndices(f: Array<FloatArray>, t: ShapePathScorer.VariantTerms, n: Int, targetIdx: Int): IntArray {
        if (n <= 160) return IntArray(n) { it }
        val keep = LinkedHashSet<Int>()
        if (targetIdx >= 0) keep += targetIdx
        val v = t.trimKeys.size
        for (freqW in doubleArrayOf(0.1, 0.4, 1.0, 2.5)) {
            val s = DoubleArray(n) { i ->
                var shape = -Double.MAX_VALUE
                for (k in 0 until v) shape = max(shape, -2.1 * t.distance[k][i] - 25.0 * t.endpoint[k][i] - 3.0 * t.trimKeys[k])
                shape + 0.5 * f[1][i] + freqW * f[2][i] + f[3][i] + f[4][i]
            }
            (0 until n).sortedByDescending { s[it] }.take(60).forEach { keep += it }
        }
        return keep.toIntArray()
    }

    /** Wall time of GestureRecognizer.recognize on dense synthetic swipes with the shipped constants. */
    private fun timing(lang: Lang, samples: List<Sample>) {
        val recognizer = GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer(), NgramContextScorer(windowSize = 3, scoreStore = lang.store))
        repeat(2) { for (s in samples) recognizer.recognize(s.path, lang.layout, lang.dict, s.prev) }
        val t = LongArray(samples.size)
        samples.forEachIndexed { i, s -> val a = System.nanoTime(); recognizer.recognize(s.path, lang.layout, lang.dict, s.prev); t[i] = System.nanoTime() - a }
        t.sort()
        println("ACC timing recognize(dense swipes, shipped constants): median=%.2f ms p90=%.2f ms mean=%.2f ms (n=%d)".format(t[t.size / 2] / 1e6, t[t.size * 9 / 10] / 1e6, t.average() / 1e6, t.size))
    }

    private fun integrityCheck(lang: Lang, samples: List<Sample>) {
        val recognizer = GestureRecognizer(
            TrieCandidateGenerator(), ShapePathScorer(smoothingPasses = smoothing),
            NgramContextScorer(windowSize = 3, scoreStore = lang.store),
        )
        var checked = 0; var mismatch = 0
        for (s in samples.take(150)) {
            val real = recognizer.recognize(s.path, lang.layout, lang.dict, s.prev).firstOrNull()?.word?.word
            val mine = s.top(productionWeights)
            checked++
            if (real != mine) mismatch++
        }
        println("ACC integrity: feature-based top-1 vs GestureRecognizer: $mismatch/$checked mismatches (pruning/ties)")
    }

    // ---- assets ----------------------------------------------------------------------------------------
    private fun loadLang(code: String, layout: KeyboardLayout): Lang {
        val dict = assets().resolve("dictionary/$code.csv").readLines(Charsets.UTF_8).drop(1).mapNotNull { line ->
            val c = line.split(',', limit = 2)
            val fr = c.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (c.size != 2 || c[0].isBlank() || fr == null || fr <= 0.0) null else WordEntry(c[0].trim(), fr)
        }
        val override = System.getenv("IAIDO_NGRAM_$code".uppercase())
        val bytes = (if (override != null) File(override) else assets().resolve("context-ngrams/$code.ngram.bin")).readBytes()
        // Wired like AssetNgramScoreStoreRepository: the dictionary frequencies are NKG2's unigram prior.
        val frequencies = dict.associate { it.word to it.frequency }
        val store = CompactNgramScoreStore.fromBytes(bytes, dict.mapIndexed { i, e -> e.word to i }.toMap()) { frequencies[it] ?: 0.0 }
        return Lang(code, layout, dict, store)
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
}
