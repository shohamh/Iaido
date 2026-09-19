package com.iaido.app

import com.iaido.core.language.Language
import com.iaido.core.testing.ResearchFixtureCodec
import com.iaido.core.testing.ResearchFixtureKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Contract tests for the reviewed research-fixture format that the telemetry server exports and
 * core-engine/IME regression tests replay. The codec lives in `core-engine` test fixtures (so
 * pure core-engine tests can load the same bundles) and is deliberately strict: a fixture that
 * silently lost or invented a field would let a regression test pass against data the device
 * never recorded.
 */
class ResearchFixtureCodecTest {
    @Test
    fun `sample bundle decodes into replayable traces and corrections`() {
        val bundle = ResearchFixtureCodec.decode(sampleBundle())

        assertEquals(ResearchFixtureCodec.CURRENT_SCHEMA_VERSION, bundle.schemaVersion)
        assertEquals(listOf("fixture-0001", "fixture-0002"), bundle.fixtures.map { it.fixtureId })

        val trace = bundle.fixtures.first { it.kind == ResearchFixtureKind.GESTURE_TRACE }.trace!!
        assertEquals("swipe", trace.classification)
        assertEquals(Language.ENGLISH, trace.language)
        assertEquals("qwerty", trace.layoutId)
        assertEquals(1, trace.algorithmVersion)
        assertEquals(2, trace.points.size)
        assertEquals(4, trace.points.first().pointerId)
        assertEquals(42L, trace.points.last().timeOffsetMs)
        assertEquals(0.31f, trace.points.last().x)

        val correction = bundle.fixtures.first { it.kind == ResearchFixtureKind.CORRECTION }
        val correctionRecord = correction.correction!!
        assertEquals("teh", correctionRecord.sourceText)
        assertEquals("the", correctionRecord.finalText)
        assertEquals(listOf("the", "ten"), correctionRecord.candidates)
        assertNull(correction.trace)
    }

    @Test
    fun `the codec accepts every classification and correction action the device emits`() {
        ResearchTraceClassification.ALL.forEach { classification ->
            val bundle = ResearchFixtureCodec.decode(
                bundleWith(traceFixture(classification = classification)),
            )
            assertEquals(classification, bundle.fixtures.single().trace!!.classification)
        }
        CorrectionAction.entries.forEach { action ->
            val bundle = ResearchFixtureCodec.decode(
                bundleWith(correctionFixture(action = action.wireName)),
            )
            assertEquals(action.wireName, bundle.fixtures.single().correction!!.action)
        }
    }

    @Test
    fun `unsupported schema versions and empty bundles are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode("""{"schema_version":2,"fixtures":[${traceFixture()}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode("""{"schema_version":1,"fixtures":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode("""{"fixtures":[${traceFixture()}]}""")
        }
    }

    @Test
    fun `unknown fields and kinds are rejected rather than ignored`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(
                bundleWith(traceFixture(extraField = ""","sentence_context":"the secret document"""")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(bundleWith("""{"fixture_id":"fixture-0001","kind":"text_sample"}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(
                """{"schema_version":1,"fixtures":[${traceFixture()}],"operator_token":"leak"}""",
            )
        }
    }

    @Test
    fun `trace fixtures reject missing points, out-of-range coordinates, and over-count points`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(bundleWith(traceFixture(points = null)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(
                bundleWith(traceFixture(points = """[{"pointer_id":4,"action":0,"time_offset_ms":0,"x":1.01,"y":0.25}]""")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(
                bundleWith(traceFixture(points = """[{"pointer_id":4,"action":0,"time_offset_ms":0,"x":0.25}]""")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(
                bundleWith(traceFixture(points = """[{"pointer_id":4,"action":0,"time_offset_ms":9,"x":0.25,"y":0.25},{"pointer_id":4,"action":2,"time_offset_ms":1,"x":0.25,"y":0.25}]""")),
            )
        }
        val oversized = (0..ResearchFixtureCodec.MAX_POINTS).joinToString(",") {
            """{"pointer_id":0,"action":0,"time_offset_ms":$it,"x":0.5,"y":0.5}"""
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(bundleWith(traceFixture(points = "[$oversized]")))
        }
    }

    @Test
    fun `correction fixtures reject empty, oversized, and over-count spans`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(bundleWith(correctionFixture(sourceText = "")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(
                bundleWith(
                    correctionFixture(
                        finalText = "a".repeat(ResearchFixtureCodec.MAX_SPAN_CODE_POINTS + 1),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(
                bundleWith(
                    correctionFixture(
                        candidates = (1..ResearchFixtureCodec.MAX_CANDIDATES + 1)
                            .joinToString(",") { "\"the\"" },
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(bundleWith(correctionFixture(action = "silent_rewrite")))
        }
    }

    @Test
    fun `fixture ids must be unique and non-blank`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(bundleWith(traceFixture(), traceFixture()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(bundleWith(traceFixture(fixtureId = "")))
        }
    }

    @Test
    fun `duplicate fixture ids are rejected even when the fixtures differ`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResearchFixtureCodec.decode(
                bundleWith(traceFixture(), correctionFixture(fixtureId = "fixture-0001")),
            )
        }
    }

    private fun sampleBundle(): String =
        javaClass.getResourceAsStream("/research-fixtures-v1.sample.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalStateException("Sample research fixture bundle is missing")

    private fun bundleWith(vararg fixtures: String): String =
        """{"schema_version":1,"fixtures":[${fixtures.joinToString(",")}]}"""

    private fun traceFixture(
        fixtureId: String = "fixture-0001",
        classification: String = "swipe",
        points: String? = """[{"pointer_id":4,"action":0,"time_offset_ms":0,"x":0.25,"y":0.25}]""",
        extraField: String = "",
    ): String {
        val pointsField = points?.let { ""","points":$it""" } ?: ""
        return """
            {
              "fixture_id":"$fixtureId",
              "kind":"gesture_trace",
              "classification":"$classification",
              "language":"ENGLISH",
              "layout_id":"qwerty",
              "algorithm_version":1$pointsField$extraField
            }
        """.trimIndent()
    }

    private fun correctionFixture(
        fixtureId: String = "fixture-0002",
        action: String = "manual_edit",
        sourceText: String = "teh",
        finalText: String = "the",
        candidates: String = "\"the\",\"ten\"",
    ): String = """
        {
          "fixture_id":"$fixtureId",
          "kind":"correction",
          "action":"$action",
          "source_text":"$sourceText",
          "final_text":"$finalText",
          "candidates":[$candidates],
          "algorithm_version":1
        }
    """.trimIndent()
}
