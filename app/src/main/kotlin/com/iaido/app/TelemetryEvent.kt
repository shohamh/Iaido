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

enum class DiagnosticsOutcome {
    ACCEPTED,
    REJECTED,
    CANCELLED,
}

data class BoundedResearchText(val value: String) {
    init {
        require(value.length in 1..MAX_LENGTH) {
            "Research text must contain at most $MAX_LENGTH characters"
        }
    }

    companion object {
        const val MAX_LENGTH = 256
    }
}

data class NormalizedKeyboardPointer(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite()) {
            "Keyboard pointer coordinates must be finite"
        }
        require(x in 0f..1f && y in 0f..1f) {
            "Keyboard pointer coordinates must be normalized to [0, 1]"
        }
    }
}

class NormalizedGestureTrace(points: List<NormalizedKeyboardPointer>) {
    val points: List<NormalizedKeyboardPointer> = points.toList()

    init {
        require(points.isNotEmpty() && points.size <= MAX_POINTS) {
            "Gesture traces must contain 1-$MAX_POINTS pointers"
        }
    }

    companion object {
        const val MAX_POINTS = 512
    }
}

sealed interface DiagnosticsEvent {
    data class GestureOutcome(val outcome: DiagnosticsOutcome) : DiagnosticsEvent
}

sealed interface ResearchEvent {
    data class TextSample(val text: BoundedResearchText) : ResearchEvent
    data class GestureTrace(val trace: NormalizedGestureTrace) : ResearchEvent
}

object DiagnosticsEventCodec {
    private val json = Json

    fun encode(event: DiagnosticsEvent): String = when (event) {
        is DiagnosticsEvent.GestureOutcome -> buildJsonObject {
            put("event_type", JsonPrimitive("gesture_outcome"))
            put("outcome", JsonPrimitive(event.outcome.name))
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
        val outcome = root["outcome"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing diagnostics outcome")
        return DiagnosticsEvent.GestureOutcome(
            outcome = DiagnosticsOutcome.entries.firstOrNull { it.name == outcome }
                ?: throw IllegalArgumentException("Unsupported diagnostics outcome: $outcome"),
        )
    }
}
