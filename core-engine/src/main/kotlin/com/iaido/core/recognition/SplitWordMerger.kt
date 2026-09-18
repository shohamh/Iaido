package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry

class SplitWordMerger(private var graceWindowMs: Long = 350L) {
    fun setGraceWindowMs(value: Long) {
        require(value >= 0L) { "Grace window must not be negative" }
        graceWindowMs = value
    }

    fun mergeParts(parts: List<String>, dictionary: List<WordEntry>): List<WordEntry> {
        if (parts.isEmpty() || parts.any { it.isEmpty() }) return emptyList()
        val merged = parts.joinToString(separator = "")
        return dictionary.filter { it.word == merged }
    }

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
