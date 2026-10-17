package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

class ShapePathScorer(
    private val resamplePointCount: Int = ScoringConstants.RESAMPLE_POINT_COUNT,
    private val distanceWeight: Double = ScoringConstants.SHAPE_DISTANCE_WEIGHT,
    private val cornerWeight: Double = ScoringConstants.CORNER_BONUS_WEIGHT,
    private val frequencyWeight: Double = ScoringConstants.FREQUENCY_WEIGHT,
    private val endpointWeight: Double = ScoringConstants.ENDPOINT_WEIGHT,
    private val smoothingPasses: Int = ScoringConstants.SMOOTHING_PASSES,
    private val trimPenaltyPerKey: Double = ScoringConstants.TRIM_PENALTY_PER_KEY,
    private val trimStepsKeys: DoubleArray = ScoringConstants.TRIM_STEPS_KEYS,
) : PathScorer {

    private var layoutState: LayoutState? = null

    override fun score(
        path: GesturePath,
        candidates: List<WordEntry>,
        layout: KeyboardLayout,
    ): List<ScoredCandidate> = scoreUnordered(path, candidates, layout).sortedByDescending { it.score }

    override fun scoreUnordered(
        path: GesturePath,
        candidates: List<WordEntry>,
        layout: KeyboardLayout,
    ): List<ScoredCandidate> {
        val state = stateFor(layout)
        // Prepared on the first scoreable candidate, so unscoreable-only input never touches the layout.
        var prepared: PreparedSwipe? = null
        val kept = ArrayList<WordEntry>(candidates.size)
        val ideals = ArrayList<Ideal>(candidates.size)
        val costs = ArrayList<Double>(candidates.size)
        val scores = ArrayList<Double>(candidates.size)
        var bestScore = -Double.MAX_VALUE
        for (entry in candidates) {
            val ideal = state.idealFor(entry.word) ?: continue
            if (prepared == null) prepared = prepare(path, layout, state)
            val full = prepared.variants[0].samples
            val cost = readingCost(full, ideal.samples, 0.0, Double.MAX_VALUE)
            val score = -cost + cornerWeight * cornerMatchBonus(prepared.corners, ideal.corners) +
                frequencyWeight * ln(entry.frequency.coerceAtLeast(ScoringConstants.MIN_FREQUENCY))
            kept += entry; ideals += ideal; costs += cost; scores += score
            if (score > bestScore) bestScore = score
        }
        val swipe = prepared ?: return emptyList()
        val scored = ArrayList<ScoredCandidate>(kept.size)
        for (k in kept.indices) {
            var score = scores[k]
            // A trimmed reading can only rescue a candidate that is already close to the leader.
            if (swipe.variants.size > 1 && score >= bestScore - ScoringConstants.TRIM_GATE) {
                var cost = costs[k]
                for (v in 1 until swipe.variants.size) {
                    val variant = swipe.variants[v]
                    val c = readingCost(variant.samples, ideals[k].samples, trimPenaltyPerKey * variant.trimKeys, cost)
                    if (c < cost) cost = c
                }
                score += costs[k] - cost
            }
            scored += ScoredCandidate(kept[k], score)
        }
        return scored
    }

    /** Weighted cost of reading the swipe as [samples] against [ideal]; stops early once it exceeds [bound]. */
    private fun readingCost(samples: Sampled, ideal: Sampled, base: Double, bound: Double): Double {
        var cost = base
        if (endpointWeight != 0.0) cost += endpointWeight * endpointDistance(samples, ideal)
        if (cost >= bound) return cost
        return cost + distanceWeight * elasticDistance(samples, ideal, (bound - cost) / distanceWeight)
    }

    /** One way of reading the swipe: the full path, or with a stray leading/trailing stretch trimmed off. */
    internal class Variant(val samples: Sampled, val trimKeys: Double)

    internal class PreparedSwipe(val variants: List<Variant>, val corners: List<Int>)

    /**
     * Users sometimes touch down on a neighbouring key, the number row or a wrong row, or drag on past the last
     * letter. Besides the full path, the swipe is therefore also read with up to ~1.6 key widths of arc length
     * trimmed from either end; each trimmed reading pays [trimPenaltyPerKey] per key so it never beats an equally
     * good untrimmed match.
     */
    internal fun prepare(path: GesturePath, layout: KeyboardLayout, state: LayoutState = stateFor(layout)): PreparedSwipe {
        val normalizedUser = layout.normalize(path)
        val ux = FloatArray(normalizedUser.points.size) { normalizedUser.points[it].x }
        val uy = FloatArray(normalizedUser.points.size) { normalizedUser.points[it].y }
        if (smoothingPasses > 0 && isDense(ux, uy)) repeat(smoothingPasses) { smooth(ux); smooth(uy) }
        val full = resample(ux, uy)
        val variants = ArrayList<Variant>()
        variants += Variant(full, 0.0)
        if (trimPenaltyPerKey < NO_TRIM_PENALTY && ux.size >= 6) {
            val cumulative = DoubleArray(ux.size)
            for (k in 1 until ux.size) {
                val dx = (ux[k] - ux[k - 1]).toDouble()
                val dy = (uy[k] - uy[k - 1]).toDouble()
                cumulative[k] = cumulative[k - 1] + sqrt(dx * dx + dy * dy)
            }
            val total = cumulative.last()
            val maxTrim = MAX_TRIM_FRACTION * total
            val seen = ArrayList<Pair<Double, Double>>()
            seen += 0.0 to 0.0
            for (lead in trimStepsKeys) for (trail in trimStepsKeys) {
                if (lead == 0.0 && trail == 0.0) continue
                val a = minOf(lead * state.keyWidth, maxTrim)
                val b = minOf(trail * state.keyWidth, maxTrim)
                if (seen.any { abs(it.first - a) < 0.2 * state.keyWidth && abs(it.second - b) < 0.2 * state.keyWidth }) continue
                seen += a to b
                val trimmed = trim(ux, uy, cumulative, a, total - b) ?: continue
                variants += Variant(resample(trimmed.first, trimmed.second), (a + b) / state.keyWidth)
            }
        }
        return PreparedSwipe(variants, turningPointIndices(full))
    }

    /** The polyline between arc lengths [from] and [to], or null if it would be degenerate. */
    private fun trim(xs: FloatArray, ys: FloatArray, cumulative: DoubleArray, from: Double, to: Double): Pair<FloatArray, FloatArray>? {
        if (to - from <= 1e-6) return null
        val outX = ArrayList<Float>(); val outY = ArrayList<Float>()
        fun at(s: Double): Pair<Float, Float> {
            var k = 1
            while (k < cumulative.size - 1 && cumulative[k] < s) k++
            val seg = cumulative[k] - cumulative[k - 1]
            val f = if (seg <= 0.0) 0.0 else ((s - cumulative[k - 1]) / seg).coerceIn(0.0, 1.0)
            return (xs[k - 1] + (xs[k] - xs[k - 1]) * f.toFloat()) to (ys[k - 1] + (ys[k] - ys[k - 1]) * f.toFloat())
        }
        val start = at(from)
        outX += start.first; outY += start.second
        for (k in 1 until xs.size - 1) if (cumulative[k] > from && cumulative[k] < to) { outX += xs[k]; outY += ys[k] }
        val end = at(to)
        outX += end.first; outY += end.second
        return outX.toFloatArray() to outY.toFloatArray()
    }

    /** Raw per-variant terms for tuning/diagnostics: index 0 is the untrimmed reading. */
    internal class VariantTerms(val trimKeys: DoubleArray, val distance: Array<FloatArray>, val endpoint: Array<FloatArray>, val corner: FloatArray)

    internal fun variantTerms(path: GesturePath, candidates: List<WordEntry>, layout: KeyboardLayout): Pair<List<WordEntry>, VariantTerms> {
        val state = stateFor(layout)
        val kept = ArrayList<WordEntry>(); val ideals = ArrayList<Ideal>()
        for (e in candidates) state.idealFor(e.word)?.let { kept += e; ideals += it }
        if (kept.isEmpty()) return kept to VariantTerms(DoubleArray(0), emptyArray(), emptyArray(), FloatArray(0))
        val swipe = prepare(path, layout, state)
        val v = swipe.variants.size
        val dist = Array(v) { FloatArray(kept.size) }
        val end = Array(v) { FloatArray(kept.size) }
        val corner = FloatArray(kept.size)
        for (i in kept.indices) {
            corner[i] = cornerMatchBonus(swipe.corners, ideals[i].corners).toFloat()
            for (k in 0 until v) {
                dist[k][i] = elasticDistance(swipe.variants[k].samples, ideals[i].samples, Double.MAX_VALUE).toFloat()
                end[k][i] = endpointDistance(swipe.variants[k].samples, ideals[i].samples).toFloat()
            }
        }
        return kept to VariantTerms(DoubleArray(v) { swipe.variants[it].trimKeys }, dist, end, corner)
    }

    private fun stateFor(layout: KeyboardLayout): LayoutState {
        val current = layoutState
        if (current != null && current.layout === layout) return current
        return LayoutState(layout).also { layoutState = it }
    }

    /**
     * Distance between the swipe's first/last sample and the word's first/last key centre. Users aim
     * the touch-down and lift at the first and last letter far more reliably than the path between
     * them, so this separates words such as "the" and "he"/"three" that the lock-step sum cannot.
     */
    private fun endpointDistance(a: Sampled, b: Sampled): Double {
        val last = a.xs.size - 1
        val dx0 = (a.xs[0] - b.xs[0]).toDouble()
        val dy0 = (a.ys[0] - b.ys[0]).toDouble()
        val dx1 = (a.xs[last] - b.xs[last]).toDouble()
        val dy1 = (a.ys[last] - b.ys[last]).toDouble()
        return sqrt(dx0 * dx0 + dy0 * dy0) + sqrt(dx1 * dx1 + dy1 * dy1)
    }

    /**
     * True when consecutive points are, on average, closer than [ScoringConstants.SMOOTHING_MAX_MEAN_SEGMENT]
     * (about half a key). Only such dense, finger-sampled paths carry jitter worth averaging out; a sparse
     * path (one point per key, as in fixtures or very fast flicks) already is its own shape.
     */
    private fun isDense(xs: FloatArray, ys: FloatArray): Boolean {
        if (xs.size < 8) return false
        var length = 0.0
        for (i in 1 until xs.size) {
            val dx = (xs[i] - xs[i - 1]).toDouble()
            val dy = (ys[i] - ys[i - 1]).toDouble()
            length += sqrt(dx * dx + dy * dy)
        }
        return length / (xs.size - 1) < ScoringConstants.SMOOTHING_MAX_MEAN_SEGMENT
    }

    /** One [1,2,1]/4 pass over the interior points, in place; end points stay fixed. */
    private fun smooth(values: FloatArray) {
        if (values.size < 3) return
        var previous = values[0]
        for (i in 1 until values.size - 1) {
            val current = values[i]
            values[i] = (previous + 2f * current + values[i + 1]) / 4f
            previous = current
        }
    }

    /** Sum of point-to-point distances between two equal-length resampled paths. */
    private fun elasticDistance(a: Sampled, b: Sampled, bound: Double): Double {
        var total = 0.0
        for (i in a.xs.indices) {
            val dx = (a.xs[i] - b.xs[i]).toDouble()
            val dy = (a.ys[i] - b.ys[i]).toDouble()
            total += sqrt(dx * dx + dy * dy)
            if (total > bound) return total
        }
        return total
    }

    /**
     * Rewards paths whose direction changes ("corners") happen at similar
     * relative positions along the path -- this is what lets scoring
     * distinguish words with similar overall shape but different turning
     * points, per the gesture-recognition-architecture ticket.
     */
    private fun cornerMatchBonus(cornersA: List<Int>, cornersB: List<Int>): Double {
        if (cornersA.isEmpty() && cornersB.isEmpty()) return 1.0

        val matched = cornersA.count { indexA ->
            cornersB.any { indexB -> abs(indexA - indexB) <= ScoringConstants.CORNER_MATCH_INDEX_TOLERANCE }
        }
        val totalCorners = maxOf(cornersA.size, cornersB.size, 1)
        return matched.toDouble() / totalCorners
    }

    private fun turningPointIndices(path: Sampled): List<Int> {
        val indices = mutableListOf<Int>()
        val xs = path.xs
        val ys = path.ys
        for (i in 1 until xs.size - 1) {
            val dx1 = xs[i] - xs[i - 1]
            val dy1 = ys[i] - ys[i - 1]
            val dx2 = xs[i + 1] - xs[i]
            val dy2 = ys[i + 1] - ys[i]
            val cross = dx1 * dy2 - dy1 * dx2
            if (abs(cross) > ScoringConstants.CORNER_CROSS_PRODUCT_THRESHOLD) {
                indices.add(i)
            }
        }
        return indices
    }

    /** Equal-arc-length resampling; mirrors [GesturePath.resample] on coordinate arrays. */
    private fun resample(xs: FloatArray, ys: FloatArray): Sampled {
        val count = resamplePointCount
        require(count >= 2) { "targetPointCount must be at least 2" }
        val n = xs.size
        if (n == 1) return Sampled(FloatArray(count) { xs[0] }, FloatArray(count) { ys[0] })

        val cumulative = FloatArray(n)
        val segmentLengths = FloatArray(n - 1)
        var total = 0f
        for (i in 0 until n - 1) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val length = sqrt(dx * dx + dy * dy)
            segmentLengths[i] = length
            total += length
            cumulative[i + 1] = total
        }
        if (total == 0f) return Sampled(FloatArray(count) { xs[0] }, FloatArray(count) { ys[0] })

        val step = total / (count - 1)
        val outX = FloatArray(count)
        val outY = FloatArray(count)
        var segment = 0
        for (sample in 0 until count) {
            if (sample == count - 1) {
                outX[sample] = xs[n - 1]
                outY[sample] = ys[n - 1]
                continue
            }
            val target = step * sample
            // First cumulative length strictly greater than the target, clamped to at least index 1.
            while (segment < n && cumulative[segment] <= target) segment++
            val segmentIndex = (if (segment >= n) -1 else segment).coerceAtLeast(1) - 1
            val fraction = ((target - cumulative[segmentIndex]) / segmentLengths[segmentIndex]).coerceIn(0f, 1f)
            outX[sample] = xs[segmentIndex] + (xs[segmentIndex + 1] - xs[segmentIndex]) * fraction
            outY[sample] = ys[segmentIndex] + (ys[segmentIndex + 1] - ys[segmentIndex]) * fraction
        }
        return Sampled(outX, outY)
    }

    internal class Sampled(val xs: FloatArray, val ys: FloatArray)

    internal class Ideal(val samples: Sampled, val corners: List<Int>)

    /** Per-layout memo: key letters and each word's ideal (key-to-key) path, as an LRU. */
    internal inner class LayoutState(val layout: KeyboardLayout) {
        /** Normalized width of one key: the smallest horizontal gap between neighbouring keys in a row. */
        val keyWidth: Double by lazy {
            val rows = layout.normalizedCenters.values.groupBy { Math.round(it.y * 1000f) }
            var min = Double.MAX_VALUE
            for (row in rows.values) {
                val xs = row.map { it.x.toDouble() }.sorted()
                for (k in 1 until xs.size) { val g = xs[k] - xs[k - 1]; if (g > 1e-4 && g < min) min = g }
            }
            if (min == Double.MAX_VALUE) 0.1 else min
        }

        private val keyLetters: Set<Char> = layout.keys.map { it.letter.lowercaseChar() }.toSet()

        // Plain map (cleared when full): an access-ordered LRU costs more per hit than it saves.
        private val ideals = HashMap<String, Ideal>()

        /** The word's ideal path, or null when it is blank or uses a letter the layout lacks. */
        fun idealFor(word: String): Ideal? {
            ideals[word]?.let { return if (it === NOT_SCOREABLE) null else it }
            if (ideals.size >= MAX_CACHED_IDEALS) ideals.clear()
            val ideal = computeIdeal(word)
            ideals[word] = ideal ?: NOT_SCOREABLE
            return ideal
        }

        private fun computeIdeal(word: String): Ideal? {
            if (word.isBlank()) return null
            val letters = word.lowercase()
            if (!letters.all { it in keyLetters }) return null
            val centers = layout.normalizedCenters
            val xs = FloatArray(letters.length) { centers.getValue(letters[it]).x }
            val ys = FloatArray(letters.length) { centers.getValue(letters[it]).y }
            val samples = resample(xs, ys)
            return Ideal(samples, turningPointIndices(samples))
        }
    }

    private companion object {
        const val MAX_CACHED_IDEALS = 16384
        const val MAX_TRIM_FRACTION = 0.3
        const val NO_TRIM_PENALTY = 1e8
        val NOT_SCOREABLE = Ideal(Sampled(FloatArray(0), FloatArray(0)), emptyList())
    }
}
