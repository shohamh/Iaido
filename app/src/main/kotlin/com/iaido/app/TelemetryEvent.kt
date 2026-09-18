package com.iaido.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TelemetryEnvelope(
    val schemaVersion: Int,
    val eventId: String,
    val batchId: String,
    val installationId: String,
    val sessionId: String,
    val occurredAtMs: Long,
    val appVersion: String,
    val buildType: String,
    val androidApi: Int,
    val eventType: String,
    val payload: JsonObject,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported telemetry envelope version: $schemaVersion"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

sealed interface DiagnosticsEvent {
    data class GestureOutcome(val outcome: String) : DiagnosticsEvent
}

sealed interface ResearchEvent {
    data class TextSample(val text: String) : ResearchEvent
    data class GestureTrace(val points: List<List<Int>>) : ResearchEvent
}

object DiagnosticsEventCodec {
    private val json = Json

    fun encode(event: DiagnosticsEvent): String = when (event) {
        is DiagnosticsEvent.GestureOutcome -> buildJsonObject {
            put("event_type", JsonPrimitive("gesture_outcome"))
            put("outcome", JsonPrimitive(event.outcome))
        }.toString()
    }

    fun decode(jsonText: String): DiagnosticsEvent {
        val root = try {
            json.parseToJsonElement(jsonText).let { element ->
                require(element is JsonObject) { "Diagnostics event must be a JSON object" }
                element
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid diagnostics event", error)
        }

        require(root.keys.all { it == "event_type" || it == "outcome" }) {
            "Diagnostics events cannot contain research text or trace fields"
        }
        require(root["event_type"]?.jsonPrimitive?.content == "gesture_outcome") {
            "Unsupported diagnostics event type"
        }
        return DiagnosticsEvent.GestureOutcome(
            outcome = root["outcome"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing diagnostics outcome"),
        )
    }
}
