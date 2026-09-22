package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import kotlin.math.sqrt

/** Prunes dictionary words whose letters plausibly lie along the drawn path, in order. */
class TrieCandidateGenerator(
    private val proximityThreshold: Float = ScoringConstants.PROXIMITY_THRESHOLD,
) : CandidateGenerator {

    private var indexedDictionary: List<WordEntry>? = null
    private var dictionaryIndex: Node = Node()

    override fun generateCandidates(
        path: GesturePath,
        layout: KeyboardLayout,
        dictionary: List<WordEntry>,
    ): List<WordEntry> {
        val normalizedPath = layout.normalize(path)
        val normalizedCenters = layout.keys.associate { key -> key.letter to layout.normalize(key) }
        val index = indexFor(dictionary)
        val matches = mutableSetOf<String>()
        collectMatches(index, normalizedPath, normalizedCenters, 0, matches)
        return dictionary.filter { it.word in matches }
    }

    private fun indexFor(dictionary: List<WordEntry>): Node {
        if (dictionary === indexedDictionary) return dictionaryIndex
        val root = Node()
        dictionary.forEach { entry ->
            if (entry.word.isBlank()) return@forEach
            var node = root
            entry.word.lowercase().forEach { letter ->
                node = node.children.getOrPut(letter) { Node() }
            }
            node.words += entry.word
        }
        indexedDictionary = dictionary
        dictionaryIndex = root
        return root
    }

    private fun collectMatches(
        node: Node,
        path: GesturePath,
        centers: Map<Char, com.iaido.core.layout.KeyPosition>,
        startIndex: Int,
        matches: MutableSet<String>,
    ) {
        node.words.forEach(matches::add)
        node.children.forEach { (letter, child) ->
            val key = centers[letter] ?: return@forEach
            val matchIndex = findNearestPointFrom(startIndex, key, path)
            if (matchIndex != -1) {
                collectMatches(child, path, centers, matchIndex, matches)
            }
        }
    }

    private fun findNearestPointFrom(startIndex: Int, key: com.iaido.core.layout.KeyPosition, path: GesturePath): Int =
        (startIndex until path.points.size).firstOrNull { i ->
            distance(path.points[i], key.x, key.y) <= proximityThreshold
        } ?: -1

    private fun distance(point: GesturePoint, x: Float, y: Float): Float {
        val dx = point.x - x
        val dy = point.y - y
        return sqrt(dx * dx + dy * dy)
    }

    private class Node {
        val children = linkedMapOf<Char, Node>()
        val words = mutableListOf<String>()
    }
}
