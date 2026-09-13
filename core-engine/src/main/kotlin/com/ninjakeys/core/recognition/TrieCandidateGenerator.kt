package com.ninjakeys.core.recognition

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.layout.KeyboardLayout
import kotlin.math.sqrt

/**
 * Prunes the dictionary to words whose letters plausibly lie along the
 * drawn path, in order. This is a straightforward per-word proximity
 * check rather than a literal trie traversal -- fine for Stage 1's small
 * test dictionary. A trie-backed implementation can replace this one
 * later without changing [CandidateGenerator] callers.
 */
class TrieCandidateGenerator(
    private val proximityThreshold: Float = 1.5f,
) : CandidateGenerator {

    override fun generateCandidates(
        path: GesturePath,
        layout: KeyboardLayout,
        dictionary: List<WordEntry>,
    ): List<WordEntry> {
        return dictionary.filter { entry -> isPlausible(entry.word, path, layout) }
    }

    private fun isPlausible(word: String, path: GesturePath, layout: KeyboardLayout): Boolean {
        var searchStartIndex = 0
        for (letter in word) {
            val key = layout.centerOf(letter)
            val matchIndex = findNearestPointFrom(searchStartIndex, key, path)
            if (matchIndex == -1) return false
            searchStartIndex = matchIndex
        }
        return true
    }

    private fun findNearestPointFrom(startIndex: Int, key: com.ninjakeys.core.layout.KeyPosition, path: GesturePath): Int {
        for (i in startIndex until path.points.size) {
            if (distance(path.points[i], key.x, key.y) <= proximityThreshold) {
                return i
            }
        }
        return -1
    }

    private fun distance(point: GesturePoint, x: Float, y: Float): Float {
        val dx = point.x - x
        val dy = point.y - y
        return sqrt(dx * dx + dy * dy)
    }
}
