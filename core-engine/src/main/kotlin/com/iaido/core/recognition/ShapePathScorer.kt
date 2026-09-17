package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import kotlin.math.ln
import kotlin.math.sqrt

class ShapePathScorer(
    private val resamplePointCount: Int = ScoringConstants.RESAMPLE_POINT_COUNT,
) : PathScorer {

    override fun score(
        path: GesturePath,
        candidates: List<WordEntry>,
        layout: KeyboardLayout,
    ): List<ScoredCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val userPath = layout.normalize(path).resample(resamplePointCount)

        return candidates
            .map { entry -> ScoredCandidate(entry, scoreOne(userPath, entry, layout)) }
            .sortedByDescending { it.score }
    }

    private fun scoreOne(userPath: GesturePath, entry: WordEntry, layout: KeyboardLayout): Double {
        val idealPath = layout.normalize(idealPathFor(entry.word, layout)).resample(resamplePointCount)

        val shapeDistance = elasticDistance(userPath, idealPath)
        val cornerBonus = cornerMatchBonus(userPath, idealPath)
        val frequencyTerm = ln(entry.frequency.coerceAtLeast(ScoringConstants.MIN_FREQUENCY))

        // Distance is a cost (lower is better), so it's subtracted; the
        // corner bonus and frequency term add to the score.
        return -ScoringConstants.SHAPE_DISTANCE_WEIGHT * shapeDistance +
            ScoringConstants.CORNER_BONUS_WEIGHT * cornerBonus +
            ScoringConstants.FREQUENCY_WEIGHT * frequencyTerm
    }

    /** A word's "ideal path" is straight lines connecting its successive key centers. */
    private fun idealPathFor(word: String, layout: KeyboardLayout): GesturePath {
        val points = word.lowercase().mapIndexed { i, c ->
            val key = layout.centerOf(c)
            GesturePoint(key.x, key.y, i * 100L)
        }
        return GesturePath(points)
    }

    /** Sum of point-to-point distances between two equal-length resampled paths. */
    private fun elasticDistance(a: GesturePath, b: GesturePath): Double {
        var total = 0.0
        for (i in a.points.indices) {
            total += pointDistance(a.points[i], b.points[i])
        }
        return total
    }

    /**
     * Rewards paths whose direction changes ("corners") happen at similar
     * relative positions along the path -- this is what lets scoring
     * distinguish words with similar overall shape but different turning
     * points, per the gesture-recognition-architecture ticket.
     */
    private fun cornerMatchBonus(a: GesturePath, b: GesturePath): Double {
        val cornersA = turningPointIndices(a)
        val cornersB = turningPointIndices(b)
        if (cornersA.isEmpty() && cornersB.isEmpty()) return 1.0

        val matched = cornersA.count { indexA ->
            cornersB.any {
                indexB -> kotlin.math.abs(indexA - indexB) <= ScoringConstants.CORNER_MATCH_INDEX_TOLERANCE
            }
        }
        val totalCorners = maxOf(cornersA.size, cornersB.size, 1)
        return matched.toDouble() / totalCorners
    }

    private fun turningPointIndices(path: GesturePath): List<Int> {
        val indices = mutableListOf<Int>()
        val points = path.points
        for (i in 1 until points.size - 1) {
            val dx1 = points[i].x - points[i - 1].x
            val dy1 = points[i].y - points[i - 1].y
            val dx2 = points[i + 1].x - points[i].x
            val dy2 = points[i + 1].y - points[i].y
            val cross = dx1 * dy2 - dy1 * dx2
            if (kotlin.math.abs(cross) > ScoringConstants.CORNER_CROSS_PRODUCT_THRESHOLD) {
                indices.add(i)
            }
        }
        return indices
    }

    private fun pointDistance(p1: GesturePoint, p2: GesturePoint): Double {
        val dx = (p1.x - p2.x).toDouble()
        val dy = (p1.y - p2.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }
}
