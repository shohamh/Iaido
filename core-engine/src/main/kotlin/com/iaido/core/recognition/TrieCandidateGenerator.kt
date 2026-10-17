package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout
import kotlin.math.sqrt

/** Prunes dictionary words whose letters plausibly lie along the drawn path, in order. */
class TrieCandidateGenerator(
    private val proximityThreshold: Float = ScoringConstants.PROXIMITY_THRESHOLD,
) : CandidateGenerator {

    private val dictionaryIndexes = mutableListOf<IndexedDictionary>()

    override fun generateCandidates(
        path: GesturePath,
        layout: KeyboardLayout,
        dictionary: List<WordEntry>,
    ): List<WordEntry> {
        val normalizedPath = layout.normalize(path)
        val index = indexFor(dictionary)
        val trace = PathTrace(normalizedPath, layout, proximityThreshold, index.letters)
        val matched = IntList()
        collectMatches(index.root, trace, 0, matched)
        val indices = matched.sortedArray()
        return List(indices.size) { dictionary[indices[it]] }
    }

    /**
     * The trie depends only on the word sequence, not on frequencies, so a dictionary list that was
     * rebuilt with boosted frequencies (every learning event) reuses the existing trie.
     */
    private fun indexFor(dictionary: List<WordEntry>): IndexedDictionary {
        val cachedIndex = dictionaryIndexes.indexOfFirst { it.dictionary === dictionary }
            .takeIf { it >= 0 }
            ?: dictionaryIndexes.indexOfFirst { it.hasSameWords(dictionary) }
        if (cachedIndex >= 0) {
            val cached = dictionaryIndexes.removeAt(cachedIndex)
            cached.dictionary = dictionary
            dictionaryIndexes.add(0, cached)
            return cached
        }

        val letterIds = HashMap<Char, Int>()
        val root = Node(-1)
        val words = Array(dictionary.size) { dictionary[it].word }
        words.forEachIndexed { entryIndex, word ->
            if (word.isBlank()) return@forEachIndexed
            var node = root
            word.lowercase().forEach { letter ->
                val id = letterIds.getOrPut(letter) { letterIds.size }
                node = node.building.getOrPut(letter) { Node(id) }
            }
            node.entryIndexes.add(entryIndex)
        }
        root.freeze()
        val letters = CharArray(letterIds.size).also { chars -> letterIds.forEach { (letter, id) -> chars[id] = letter } }
        val indexed = IndexedDictionary(dictionary, words, root, letters)
        dictionaryIndexes.add(0, indexed)
        if (dictionaryIndexes.size > MAX_CACHED_DICTIONARY_INDEXES) {
            dictionaryIndexes.removeAt(dictionaryIndexes.lastIndex)
        }
        return indexed
    }

    private fun collectMatches(node: Node, trace: PathTrace, startIndex: Int, matches: IntList) {
        val entries = node.entryIndexes
        for (i in entries.indices) matches.add(entries[i])
        for (child in node.children) {
            val matchIndex = trace.firstMatchFrom(child.letterId, startIndex)
            if (matchIndex != -1) {
                collectMatches(child, trace, matchIndex, matches)
            }
        }
    }

    /**
     * Per-letter lookup of the first path point at or after an index that lies within the proximity
     * threshold of that letter's key, computed lazily once per letter instead of once per trie node.
     */
    private class PathTrace(
        path: GesturePath,
        private val layout: KeyboardLayout,
        private val threshold: Float,
        private val letters: CharArray,
    ) {
        private val xs = FloatArray(path.points.size) { path.points[it].x }
        private val ys = FloatArray(path.points.size) { path.points[it].y }
        private val tables = arrayOfNulls<IntArray>(letters.size)
        private val built = BooleanArray(letters.size)

        fun firstMatchFrom(letterId: Int, startIndex: Int): Int {
            if (!built[letterId]) {
                tables[letterId] = buildTable(letters[letterId])
                built[letterId] = true
            }
            val table = tables[letterId] ?: return -1
            return if (startIndex >= xs.size) -1 else table[startIndex]
        }

        private fun buildTable(letter: Char): IntArray? {
            val key = layout.normalizedCenters[letter] ?: return null
            val table = IntArray(xs.size)
            var next = -1
            for (i in xs.indices.reversed()) {
                val dx = xs[i] - key.x
                val dy = ys[i] - key.y
                if (sqrt(dx * dx + dy * dy) <= threshold) next = i
                table[i] = next
            }
            return table
        }
    }

    private class IntList {
        private var data = IntArray(64)
        private var size = 0

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun sortedArray(): IntArray = data.copyOf(size).also { it.sort() }
    }

    private class Node(val letterId: Int) {
        val building = linkedMapOf<Char, Node>()
        var children: Array<Node> = emptyArray()
        val entryIndexes = mutableListOf<Int>()

        /** Replaces the build-time map with a flat array for allocation-free traversal. */
        fun freeze() {
            children = building.values.toTypedArray()
            building.clear()
            children.forEach(Node::freeze)
        }
    }

    private class IndexedDictionary(
        var dictionary: List<WordEntry>,
        val words: Array<String>,
        val root: Node,
        val letters: CharArray,
    ) {
        fun hasSameWords(other: List<WordEntry>): Boolean {
            if (other.size != words.size) return false
            for (i in words.indices) {
                if (words[i] != other[i].word) return false
            }
            return true
        }
    }

    private companion object {
        const val MAX_CACHED_DICTIONARY_INDEXES = 2
    }
}
