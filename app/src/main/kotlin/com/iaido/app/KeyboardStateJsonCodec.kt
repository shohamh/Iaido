package com.iaido.app

import com.iaido.core.commands.CommandBinding
import com.iaido.core.commands.GestureAction
import com.iaido.core.dictionary.NgramOverrideSnapshot
import com.iaido.core.dictionary.PersonalDictionarySnapshot
import com.iaido.core.dictionary.WordOverrideSnapshot
import com.iaido.core.language.Language
import com.iaido.core.recognition.SessionCorrectionHistorySnapshot
import com.iaido.core.recognition.SessionWord
import com.iaido.core.state.KeyboardProfileSnapshot
import com.iaido.core.state.KeyboardStateSnapshot
import com.iaido.core.state.TypingSessionSnapshot
import com.iaido.core.typing.SpacingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object KeyboardStateJsonCodec {
    private val json = Json

    fun encode(snapshot: KeyboardStateSnapshot): String = buildJsonObject {
        put("schemaVersion", JsonPrimitive(snapshot.schemaVersion))
        put("profile", encodeProfile(snapshot.profile))
        if (snapshot.session == null) put("session", JsonNull)
        else put("session", encodeSession(snapshot.session!!))
    }.toString()

    fun decode(jsonText: String): KeyboardStateSnapshot = try {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val schemaVersion = root.requiredInt("schemaVersion")
        require(schemaVersion == KeyboardStateSnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported keyboard snapshot version: $schemaVersion"
        }
        KeyboardStateSnapshot(
            schemaVersion = schemaVersion,
            profile = decodeProfile(root.requiredObject("profile")),
            session = root["session"]?.takeUnless { it == JsonNull }?.let(::decodeSession),
        )
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid keyboard state snapshot", error)
    }

    private fun encodeProfile(profile: KeyboardProfileSnapshot): JsonObject = buildJsonObject {
        put("schemaVersion", JsonPrimitive(profile.schemaVersion))
        put("spacingMode", JsonPrimitive(profile.spacingMode.name))
        put("flowCorrectionDepth", JsonPrimitive(profile.flowCorrectionDepth))
        put("splitGraceWindowMs", JsonPrimitive(profile.splitGraceWindowMs))
        put("preferredLanguage", JsonPrimitive(profile.preferredLanguage.name))
        put("commandBindings", buildJsonArray {
            profile.commandBindings.forEach { binding ->
                add(buildJsonObject {
                    put("slot", JsonPrimitive(binding.slot))
                    put("trigger", JsonPrimitive(binding.trigger))
                    put("action", JsonPrimitive(binding.action.name))
                })
            }
        })
        put("dictionary", encodeDictionary(profile.dictionary))
    }

    private fun decodeProfile(value: JsonObject): KeyboardProfileSnapshot {
        val schemaVersion = value.requiredInt("schemaVersion")
        require(schemaVersion == KeyboardProfileSnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported profile snapshot version: $schemaVersion"
        }
        return KeyboardProfileSnapshot(
            schemaVersion = schemaVersion,
            spacingMode = enumValue("spacingMode", value.requiredString("spacingMode"), SpacingMode.entries),
            flowCorrectionDepth = value.requiredInt("flowCorrectionDepth"),
            splitGraceWindowMs = value.requiredLong("splitGraceWindowMs"),
            commandBindings = value.requiredArray("commandBindings").map { binding ->
                val objectValue = binding.jsonObject
                CommandBinding(
                    slot = objectValue.requiredString("slot"),
                    trigger = objectValue.requiredString("trigger"),
                    action = enumValue("action", objectValue.requiredString("action"), GestureAction.entries),
                )
            },
            preferredLanguage = enumValue("preferredLanguage", value.requiredString("preferredLanguage"), Language.entries),
            dictionary = decodeDictionary(value.requiredObject("dictionary")),
        )
    }

    private fun encodeDictionary(dictionary: PersonalDictionarySnapshot): JsonObject = buildJsonObject {
        put("schemaVersion", JsonPrimitive(dictionary.schemaVersion))
        put("wordOverrides", buildJsonArray {
            dictionary.wordOverrides.forEach { override ->
                add(buildJsonObject {
                    put("word", JsonPrimitive(override.word))
                    put("boost", JsonPrimitive(override.boost))
                    put("uses", JsonPrimitive(override.uses))
                })
            }
        })
        put("ngramOverrides", buildJsonArray {
            dictionary.ngramOverrides.forEach { override ->
                add(buildJsonObject {
                    put("previousWord", JsonPrimitive(override.previousWord))
                    put("nextWord", JsonPrimitive(override.nextWord))
                    put("boost", JsonPrimitive(override.boost))
                    put("uses", JsonPrimitive(override.uses))
                })
            }
        })
        put("customWords", JsonArray(dictionary.customWords.map(::JsonPrimitive)))
    }

    private fun decodeDictionary(value: JsonObject): PersonalDictionarySnapshot {
        val schemaVersion = value.requiredInt("schemaVersion")
        require(schemaVersion == PersonalDictionarySnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported dictionary snapshot version: $schemaVersion"
        }
        return PersonalDictionarySnapshot(
            schemaVersion = schemaVersion,
            wordOverrides = value.requiredArray("wordOverrides").map { element ->
                val override = element.jsonObject
                WordOverrideSnapshot(
                    word = override.requiredString("word"),
                    boost = override.requiredDouble("boost"),
                    uses = override.requiredInt("uses"),
                )
            },
            ngramOverrides = value.requiredArray("ngramOverrides").map { element ->
                val override = element.jsonObject
                NgramOverrideSnapshot(
                    previousWord = override.requiredString("previousWord"),
                    nextWord = override.requiredString("nextWord"),
                    boost = override.requiredDouble("boost"),
                    uses = override.requiredInt("uses"),
                )
            },
            customWords = value.requiredArray("customWords").map { it.jsonPrimitive.content },
        )
    }

    private fun encodeSession(session: TypingSessionSnapshot): JsonObject = buildJsonObject {
        put("schemaVersion", JsonPrimitive(session.schemaVersion))
        put("language", JsonPrimitive(session.language.name))
        put("cursorPosition", JsonPrimitive(session.cursorPosition))
        put("pendingCandidates", JsonArray(session.pendingCandidates.map(::JsonPrimitive)))
        put("correctionHistory", buildJsonObject {
            put("schemaVersion", JsonPrimitive(session.correctionHistory.schemaVersion))
            put("nextId", JsonPrimitive(session.correctionHistory.nextId))
            put("entries", buildJsonArray {
                session.correctionHistory.entries.forEach { entry ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(entry.id))
                        put("start", JsonPrimitive(entry.start))
                        put("end", JsonPrimitive(entry.end))
                        put("original", JsonPrimitive(entry.original))
                        put("current", JsonPrimitive(entry.current))
                        put("candidates", JsonArray(entry.candidates.map(::JsonPrimitive)))
                        put("corrected", JsonPrimitive(entry.corrected))
                    })
                }
            })
        })
    }

    private fun decodeSession(value: JsonElement): TypingSessionSnapshot {
        val session = value.jsonObject
        val schemaVersion = session.requiredInt("schemaVersion")
        require(schemaVersion == TypingSessionSnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported typing-session snapshot version: $schemaVersion"
        }
        val history = session.requiredObject("correctionHistory")
        val historyVersion = history.requiredInt("schemaVersion")
        require(historyVersion == SessionCorrectionHistorySnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported correction-history snapshot version: $historyVersion"
        }
        return TypingSessionSnapshot(
            schemaVersion = schemaVersion,
            language = enumValue("language", session.requiredString("language"), Language.entries),
            correctionHistory = SessionCorrectionHistorySnapshot(
                schemaVersion = historyVersion,
                nextId = history.requiredInt("nextId"),
                entries = history.requiredArray("entries").map { element ->
                    val entry = element.jsonObject
                    SessionWord(
                        id = entry.requiredInt("id"),
                        start = entry.requiredInt("start"),
                        end = entry.requiredInt("end"),
                        original = entry.requiredString("original"),
                        current = entry.requiredString("current"),
                        candidates = entry.requiredArray("candidates").map { it.jsonPrimitive.content },
                        corrected = entry.requiredBoolean("corrected"),
                    )
                },
            ),
            cursorPosition = session.requiredInt("cursorPosition"),
            pendingCandidates = session.requiredArray("pendingCandidates").map { it.jsonPrimitive.content },
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(field: String, value: String, values: List<T>): T =
        values.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Invalid $field value: $value")

    private fun JsonObject.required(name: String): JsonElement =
        this[name] ?: throw IllegalArgumentException("Missing JSON field: $name")

    private fun JsonObject.requiredObject(name: String): JsonObject = required(name).jsonObject

    private fun JsonObject.requiredArray(name: String): JsonArray = required(name).jsonArray

    private fun JsonObject.requiredString(name: String): String = required(name).jsonPrimitive.let {
        it.content
    }

    private fun JsonObject.requiredInt(name: String): Int = required(name).jsonPrimitive.int

    private fun JsonObject.requiredLong(name: String): Long = required(name).jsonPrimitive.long

    private fun JsonObject.requiredDouble(name: String): Double = required(name).jsonPrimitive.double

    private fun JsonObject.requiredBoolean(name: String): Boolean = when (val value = required(name).jsonPrimitive.content) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("JSON field '$name' must be boolean")
    }
}
