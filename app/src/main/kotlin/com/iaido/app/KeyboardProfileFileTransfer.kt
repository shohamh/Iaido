package com.iaido.app

import android.content.Context
import android.net.Uri
import com.iaido.core.state.KeyboardProfileSnapshot
import com.iaido.core.state.KeyboardStateSnapshot
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Encodes portable profile data without editor text or Android runtime handles. */
object KeyboardProfileFileTransfer {
    private const val FORMAT = "iaido-keyboard-profile"
    private const val FORMAT_VERSION = 1
    private const val CORE_ENGINE_VERSION = "1"
    private val json = Json

    fun encode(profile: KeyboardProfileSnapshot, appVersion: String): String {
        val state = KeyboardStateJsonCodec.encode(KeyboardStateSnapshot(profile = profile, session = null))
        return buildJsonObject {
            put("format", JsonPrimitive(FORMAT))
            put("formatVersion", JsonPrimitive(FORMAT_VERSION))
            put("appVersion", JsonPrimitive(appVersion))
            put("coreEngineVersion", JsonPrimitive(CORE_ENGINE_VERSION))
            put("sha256", JsonPrimitive(sha256(state)))
            put("state", json.parseToJsonElement(state))
        }.toString()
    }

    fun decode(contents: String): KeyboardProfileSnapshot {
        val root = json.parseToJsonElement(contents).jsonObject
        require(root.requiredString("format") == FORMAT) { "Unsupported Iaido profile format" }
        require(root.requiredInt("formatVersion") == FORMAT_VERSION) {
            "Unsupported Iaido profile format version"
        }
        require(root.requiredString("coreEngineVersion") == CORE_ENGINE_VERSION) {
            "Unsupported core-engine profile version"
        }
        val state = root.requiredObject("state").toString()
        require(root.requiredString("sha256") == sha256(state)) { "Profile checksum mismatch" }
        return KeyboardStateJsonCodec.decode(state).profile
    }

    fun write(context: Context, uri: Uri, profile: KeyboardProfileSnapshot, appVersion: String) {
        val contents = encode(profile, appVersion)
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(contents) }
            ?: error("Unable to open profile destination")
    }

    fun read(context: Context, uri: Uri): KeyboardProfileSnapshot =
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { decode(it.readText()) }
            ?: error("Unable to open profile source")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun JsonObject.required(name: String) = this[name] ?: error("Missing profile field: $name")

    private fun JsonObject.requiredString(name: String): String = required(name).jsonPrimitive.content

    private fun JsonObject.requiredInt(name: String): Int = required(name).jsonPrimitive.content.toIntOrNull()
        ?: error("Profile field '$name' must be an integer")

    private fun JsonObject.requiredObject(name: String): JsonObject = required(name).jsonObject
}
