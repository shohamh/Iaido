package com.iaido.app

import android.content.Context
import android.os.SystemClock
import com.iaido.core.commands.CommandBindingSet
import com.iaido.core.dictionary.PersonalDictionarySnapshot
import com.iaido.core.language.Language
import com.iaido.core.recognition.SessionCorrectionHistorySnapshot
import com.iaido.core.state.KeyboardProfileSnapshot
import com.iaido.core.state.KeyboardStateSnapshot
import com.iaido.core.state.TypingSessionSnapshot
import com.iaido.core.typing.SpacingMode

/**
 * Debug/instrumentation bridge for restoring a validated keyboard snapshot into the IME service.
 * Baselines are deliberately process-local: they carry no Android runtime handles and cannot
 * accidentally become a user-facing persistence format.
 */
class DebugKeyboardStateAdapter(private val context: Context) {
    fun saveBaseline(): String = saveBaseline(defaultBaseline())

    fun saveBaseline(snapshot: KeyboardStateSnapshot): String {
        val id = synchronized(BASELINES) {
            val next = baselineSequence++
            BASELINES[next.toString()] = snapshot
            next.toString()
        }
        return id
    }

    fun restoreBaseline(id: String): Long = restore(requireBaseline(id))

    fun restore(snapshot: KeyboardStateSnapshot): Long {
        val preferences = context.getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, Context.MODE_PRIVATE)
        val requestId = synchronized(REQUEST_LOCK) {
            preferences.getLong(DebugAutoSpaceFixtures.STATE_REQUEST_ID_KEY, 0L) + 1L
        }
        preferences.edit()
            .putString(DebugAutoSpaceFixtures.STATE_REQUEST_JSON_KEY, KeyboardStateJsonCodec.encode(snapshot))
            .putLong(DebugAutoSpaceFixtures.STATE_REQUEST_ID_KEY, requestId)
            .remove(DebugAutoSpaceFixtures.STATE_RESPONSE_ERROR_KEY)
            .commit()

        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val responseId = preferences.getLong(DebugAutoSpaceFixtures.STATE_RESPONSE_ID_KEY, -1L)
            if (responseId == requestId) {
                val error = preferences.getString(DebugAutoSpaceFixtures.STATE_RESPONSE_ERROR_KEY, null)
                check(error == null) { "IME rejected keyboard state snapshot $requestId: $error" }
                val revision = preferences.getLong(DebugAutoSpaceFixtures.RUNTIME_READY_REVISION_KEY, -1L)
                check(revision >= 0L) { "IME acknowledged snapshot $requestId without a ready revision" }
                return revision
            }
            check(responseId < requestId) {
                "IME acknowledged unexpected keyboard state request $responseId (expected $requestId)"
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        error("Timed out waiting for IME state restore request $requestId")
    }

    private fun requireBaseline(id: String): KeyboardStateSnapshot = synchronized(BASELINES) {
        BASELINES[id] ?: error("Unknown debug keyboard state baseline: $id")
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val POLL_INTERVAL_MS = 25L
        private val REQUEST_LOCK = Any()
        private val BASELINES = mutableMapOf<String, KeyboardStateSnapshot>()
        private var baselineSequence = 0L

        fun defaultBaseline(): KeyboardStateSnapshot = KeyboardStateSnapshot(
            profile = KeyboardProfileSnapshot(
                spacingMode = SpacingMode.MANUAL,
                flowCorrectionDepth = SettingsDefaults.CASCADE_DEPTH,
                splitGraceWindowMs = SettingsDefaults.GRACE_WINDOW_MS.toLong(),
                commandBindings = CommandBindingSet().bindings,
                preferredLanguage = Language.ENGLISH,
                dictionary = PersonalDictionarySnapshot(),
            ),
            session = TypingSessionSnapshot(
                language = Language.ENGLISH,
                correctionHistory = SessionCorrectionHistorySnapshot(nextId = 0, entries = emptyList()),
                cursorPosition = 0,
                pendingCandidates = emptyList(),
            ),
        )
    }
}
