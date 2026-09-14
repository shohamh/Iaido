package com.iaido.core.recognition

/** Read-only n-gram evidence used by the recognizer and flow corrector. */
interface NgramScoreStore {
    fun bigram(previous: String, next: String): Double

    fun trigram(first: String, second: String, next: String): Double
}

object EmptyNgramScoreStore : NgramScoreStore {
    override fun bigram(previous: String, next: String): Double = 0.0

    override fun trigram(first: String, second: String, next: String): Double = 0.0
}
