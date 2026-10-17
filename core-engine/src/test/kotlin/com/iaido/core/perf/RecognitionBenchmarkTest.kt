package com.iaido.core.perf

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.recognition.CompactNgramScoreStore
import com.iaido.core.recognition.GestureRecognizer
import com.iaido.core.recognition.GestureUnit
import com.iaido.core.recognition.InferenceSegmenter
import com.iaido.core.recognition.NgramContextScorer
import com.iaido.core.recognition.ShapePathScorer
import com.iaido.core.recognition.TrieCandidateGenerator
import com.iaido.core.testing.SwipeFixtures
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Opt-in micro-benchmark: run with `-Diaido.bench=true` (see PERF.md). Prints a timing table. */
class RecognitionBenchmarkTest {
    private val words = listOf("the", "hello", "there", "because", "important", "keyboard", "what", "understanding")

    @Test
    fun benchmark() {
        assumeTrue(System.getProperty("iaido.bench") == "true" || System.getenv("IAIDO_BENCH") == "true")
        val en = loadDictionary("en")
        val he = loadDictionary("he")
        val layout = KeyboardLayout.qwertyTestLayout()
        val ngramBytes = assets().resolve("context-ngrams/en.ngram.bin").readBytes()
        val store = CompactNgramScoreStore.fromBytes(ngramBytes, en.mapIndexed { i, e -> e.word to i }.toMap())
        val scorer = NgramContextScorer(scoreStore = store)
        val recognizer = GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer(), scorer)
        val paths = words.map { SwipeFixtures.pathThrough(layout, it) }

        fun once(name: String, block: () -> Unit) { val t = System.nanoTime(); block(); println("COLD %-44s %8.2f ms".format(name, (System.nanoTime() - t) / 1e6)) }
        once("parse en.csv (JVM, readLines)") { loadDictionary("en") }
        once("parse he.csv") { loadDictionary("he") }
        once("wordIds map (en, 28k)") { en.mapIndexed { i, e -> e.word to i }.toMap() }
        once("CompactNgramScoreStore init (en)") { CompactNgramScoreStore.fromBytes(ngramBytes, en.mapIndexed { i, e -> e.word to i }.toMap()) }
        val coldGen = TrieCandidateGenerator()
        once("first en swipe: trie build + generate") { coldGen.generateCandidates(paths[0], layout, en) }
        once("first he swipe: trie build + generate (56k)") { coldGen.generateCandidates(SwipeFixtures.pathThrough(KeyboardLayout.hebrewTestLayout(), "אני"), KeyboardLayout.hebrewTestLayout(), he) }
        once("PersonalDictionary(en) construct + entries()") { com.iaido.core.dictionary.PersonalDictionary(en).entries() }
        println("=== dictionary sizes: en=${en.size} he=${he.size}")
        // Production (English) builds a fresh list per swipe -> identity cache never hits.
        report("recognize en, fresh dict list each swipe (prod)") { i ->
            recognizer.recognize(paths[i % paths.size], layout, en.map { it.copy() }, listOf("it", "is"))
        }
        val personal = com.iaido.core.dictionary.PersonalDictionary(en)
        report("learning event + entries() + recognize (prod)") { i ->
            personal.reinforce(words[i % words.size])
            recognizer.recognize(paths[i % paths.size], layout, personal.entries(), listOf("it", "is"))
        }
        report("  stage: PersonalDictionary.reinforce+entries()") { i ->
            personal.reinforce(words[i % words.size]); personal.entries()
        }
        report("recognize en, same dict list (warm index)") { i ->
            recognizer.recognize(paths[i % paths.size], layout, en, listOf("it", "is"))
        }
        val gen = TrieCandidateGenerator()
        report("  stage: trie generateCandidates (warm)") { i -> gen.generateCandidates(paths[i % paths.size], layout, en) }
        val cands = paths.map { gen.generateCandidates(it, layout, en) }
        println("    candidates per word: ${cands.map { it.size }}")
        val shape = ShapePathScorer()
        report("  stage: ShapePathScorer.score") { i -> shape.score(paths[i % paths.size], cands[i % paths.size], layout) }
        report("  stage: n-gram scoring only") { i ->
            repeat(20) { scorer.score(listOf("it", "is"), words[i % words.size]) }
        }

        val segmenter = InferenceSegmenter(contextScorer = scorer)
        val units = paths.mapIndexed { i, p ->
            GestureUnit("u$i", listOf(p), listOf(recognizer.recognize(p, layout, en)), concurrent = false)
        }
        report("segmenter.rank 1 unit") { segmenter.rank(units.take(1), listOf("it"), en) }
        report("segmenter.rank 3 units") { i -> segmenter.rank(units.subList(i % 4, i % 4 + 3), listOf("it"), en) }
        report("segmenter.rank 6 units") { segmenter.rank(units.take(6), listOf("it"), en) }
        // Realistic typing: a fresh unit per swipe, re-ranking the growing window after each one.
        val perCall = ArrayList<Long>()
        val lastCall = ArrayList<Long>()
        repeat(40) {
            val fresh = paths.mapIndexed { i, p ->
                GestureUnit("f$i", listOf(p), listOf(recognizer.recognize(p, layout, en)), concurrent = false)
            }
            for (n in 1..6) {
                val t = System.nanoTime()
                segmenter.rank(fresh.take(n), listOf("it"), en)
                val d = System.nanoTime() - t
                perCall += d
                if (n == 6) lastCall += d
            }
        }
        println("%-52s mean=%8.2f ms  rank@6th swipe median=%8.2f ms".format(
            "segmenter sequence (fresh units, growing window)",
            perCall.average() / 1e6, lastCall.sorted()[lastCall.size / 2] / 1e6))
        if (System.getenv("IAIDO_PROFILE") == "true") {
            val main = Thread.currentThread()
            val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampler = Thread {
                while (running.get()) {
                    val st = main.stackTrace
                    val frame = st.firstOrNull { it.className.startsWith("com.iaido.core") }
                    val top = st.take(3).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
                    if (frame != null) counts.merge(frame.className.substringAfterLast('.') + "." + frame.methodName + ":" + frame.lineNumber + "  [" + top + "]", 1, Int::plus)
                    Thread.sleep(1)
                }
            }
            val sets = List(150) { paths.mapIndexed { i, p -> GestureUnit("p$i", listOf(p), listOf(recognizer.recognize(p, layout, en)), concurrent = false) } }
            sampler.start()
            for (fresh in sets) for (n in 1..6) segmenter.rank(fresh.take(n), listOf("it"), en)
            running.set(false); sampler.join()
            counts.entries.sortedByDescending { it.value }.take(14).forEach { println("PROF ${it.value} ${it.key}") }
        }
        report("recognize he (warm)") {
            recognizer.recognize(SwipeFixtures.pathThrough(KeyboardLayout.hebrewTestLayout(), "אני"), KeyboardLayout.hebrewTestLayout(), he)
        }
    }

    private fun report(name: String, block: (Int) -> Unit) {
        repeat(5) { block(it) }
        val n = 30
        val times = LongArray(n) { i ->
            val t = System.nanoTime(); block(i); System.nanoTime() - t
        }.sorted()
        fun ms(v: Long) = "%.2f".format(v / 1e6)
        println("%-52s median=%8s ms  p90=%8s ms  min=%8s ms".format(name, ms(times[n / 2]), ms(times[n * 9 / 10]), ms(times[0])))
    }

    private fun loadDictionary(lang: String): List<WordEntry> =
        assets().resolve("dictionary/$lang.csv").readLines(Charsets.UTF_8).drop(1).mapNotNull { line ->
            val c = line.split(',', limit = 2)
            val f = c.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (c.size != 2 || c[0].isBlank() || f == null || f <= 0.0) null else WordEntry(c[0].trim(), f)
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
