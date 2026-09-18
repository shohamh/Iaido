package com.iaido.app

import android.content.Context
import com.iaido.core.dictionary.WordEntry

/** Debug-asset fixtures used only by the connected auto-space journeys. */
internal data class DebugAutoSpaceFixture(
    val dictionary: List<WordEntry>,
    private val bigrams: Map<Pair<String, String>, Double>,
    private val trigrams: Map<Triple<String, String, String>, Double>,
) {
    fun bigram(previous: String, next: String): Double = bigrams[previous to next] ?: 0.0

    fun trigram(first: String, second: String, next: String): Double =
        trigrams[Triple(first, second, next)] ?: 0.0
}

/**
 * The fixture file exists only in the debug source set. Without that asset the
 * definitions map is empty, so release builds keep production data authoritative.
 */
internal object DebugAutoSpaceFixtures {
    fun active(context: Context): DebugAutoSpaceFixture? {
        val selected = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(FIXTURE_KEY, null)
            ?: return null
        return definitions(context)[selected]
    }

    private fun definitions(context: Context): Map<String, DebugAutoSpaceFixture> = synchronized(this) {
        cached ?: load(context).also { cached = it }
    }

    private fun load(context: Context): Map<String, DebugAutoSpaceFixture> = runCatching {
        val words = mutableMapOf<String, MutableList<WordEntry>>()
        val bigrams = mutableMapOf<String, MutableMap<Pair<String, String>, Double>>()
        val trigrams = mutableMapOf<String, MutableMap<Triple<String, String, String>, Double>>()
        context.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val fields = line.split('|').map(String::trim)
                if (fields.size < 3 || fields.first().startsWith('#')) return@forEach
                val fixture = fields[0]
                when (fields[1]) {
                    "word" -> fields.getOrNull(3)?.toDoubleOrNull()?.let { frequency ->
                        words.getOrPut(fixture, ::mutableListOf) += WordEntry(fields[2], frequency)
                    }
                    "bigram" -> fields.getOrNull(4)?.toDoubleOrNull()?.let { score ->
                        bigrams.getOrPut(fixture, ::mutableMapOf)[fields[2] to fields[3]] = score
                    }
                    "trigram" -> fields.getOrNull(5)?.toDoubleOrNull()?.let { score ->
                        trigrams.getOrPut(fixture, ::mutableMapOf)[Triple(fields[2], fields[3], fields[4])] = score
                    }
                }
            }
        }
        words.mapValues { (name, entries) ->
            DebugAutoSpaceFixture(entries, bigrams[name].orEmpty(), trigrams[name].orEmpty())
        }
    }.getOrDefault(emptyMap())

    private var cached: Map<String, DebugAutoSpaceFixture>? = null

    const val PREFERENCES = "debug_auto_space_fixtures"
    const val FIXTURE_KEY = "selected_fixture"
    const val ACTIVE_SPACING_MODE_KEY = "active_spacing_mode"
    const val RUNTIME_READY_REVISION_KEY = "runtime_ready_revision"
    const val STATE_REQUEST_ID_KEY = "state_request_id"
    const val STATE_REQUEST_JSON_KEY = "state_request_json"
    const val STATE_RESPONSE_ID_KEY = "state_response_id"
    const val STATE_RESPONSE_ERROR_KEY = "state_response_error"
    const val EXTRA_FIXTURE = "com.iaido.app.extra.AUTO_SPACE_FIXTURE"

    private const val ASSET_NAME = "auto-space-fixtures.txt"
}
