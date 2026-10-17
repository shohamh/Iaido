package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

class ShapePathScorer(
    private val resamplePointCount: Int = ScoringConstants.RESAMPLE_POINT_COUNT,
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
        var user: Sampled? = null
        var userCorners: List<Int> = emptyList()

        val scored = ArrayList<ScoredCandidate>(candidates.size)
        for (entry in candidates) {
            val ideal = state.idealFor(entry.word) ?: continue
            if (user == null) {
                val normalizedUser = layout.normalize(path)
                user = resample(
                    FloatArray(normalizedUser.points.size) { normalizedUser.points[it].x },
                    FloatArray(normalizedUser.points.size) { normalizedUser.points[it].y },
                )
                userCorners = turningPointIndices(user)
            }
            scored += ScoredCandidate(entry, scoreOne(user, userCorners, entry, ideal))
        }
        return scored
    }

    private fun scoreOne(user: Sampled, userCorners: List<Int>, entry: WordEntry, ideal: Ideal): Double {

        val shapeDistance = elasticDistance(user, ideal.samples)
        val cornerBonus = cornerMatchBonus(userCorners, ideal.corners)
        val frequencyTerm = ln(entry.frequency.coerceAtLeast(ScoringConstants.MIN_FREQUENCY))

        // Distance is a cost (lower is better), so it's subtracted; the
        // corner bonus and frequency term add to the score.
        return -ScoringConstants.SHAPE_DISTANCE_WEIGHT * shapeDistance +
            ScoringConstants.CORNER_BONUS_WEIGHT * cornerBonus +
            ScoringConstants.FREQUENCY_WEIGHT * frequencyTerm
    }

    private fun stateFor(layout: KeyboardLayout): LayoutState {
        val current = layoutState
        if (current != null && current.layout === layout) return current
        return LayoutState(layout).also { layoutState = it }
    }

    /** Sum of point-to-point distances between two equal-length resampled paths. */
    private fun elasticDistance(a: Sampled, b: Sampled): Double {
        var total = 0.0
        for (i in a.xs.indices) {
            val dx = (a.xs[i] - b.xs[i]).toDouble()
            val dy = (a.ys[i] - b.ys[i]).toDouble()
            total += sqrt(dx * dx + dy * dy)
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

    private class Sampled(val xs: FloatArray, val ys: FloatArray)

    private class Ideal(val samples: Sampled, val corners: List<Int>)

    /** Per-layout memo: key letters and each word's ideal (key-to-key) path, as an LRU. */
    private inner class LayoutState(val layout: KeyboardLayout) {
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
        val NOT_SCOREABLE = Ideal(Sampled(FloatArray(0), FloatArray(0)), emptyList())
    }
}
