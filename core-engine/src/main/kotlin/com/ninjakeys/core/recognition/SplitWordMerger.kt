package com.ninjakeys.core.recognition

import com.ninjakeys.core.dictionary.WordEntry

class SplitWordMerger(private val graceWindowMs: Long = 350L) {
    fun merge(
        first: List<String>,
        second: List<String>,
        dictionary: List<WordEntry>,
        firstTouchMs: Long,
        secondTouchMs: Long,
    ): List<WordEntry> {
        if (kotlin.math.abs(firstTouchMs - secondTouchMs) > graceWindowMs) return emptyList()
        val prefixes = if (firstTouchMs <= secondTouchMs) first else second
        val suffixes = if (firstTouchMs <= secondTouchMs) second else first
        val words = prefixes.flatMap { left -> suffixes.map { right -> left + right } }.toSet()
        return dictionary.filter { it.word in words }
    }
}
