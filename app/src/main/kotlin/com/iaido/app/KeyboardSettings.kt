package com.iaido.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.iaido.core.commands.CommandBinding
import com.iaido.core.commands.CommandBindingSet
import com.iaido.core.commands.GestureAction
import com.iaido.core.language.Language
import com.iaido.core.typing.SpacingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal object SettingsDefaults {
    const val CASCADE_DEPTH = 2
    const val GRACE_WINDOW_MS = 350
    const val SHOW_NUMBER_ROW = true
    val CASCADE_DEPTH_RANGE = 0..4
    val GRACE_WINDOW_RANGE = 300..400
}

internal val commandBindingKey = stringPreferencesKey("command_binding_language")
internal val cascadeDepthKey = intPreferencesKey("flow_correction_depth")
internal val graceWindowKey = intPreferencesKey("split_grace_window_ms")
internal val spacingModeKey = stringPreferencesKey("spacing_mode")
internal val preferredLanguageKey = stringPreferencesKey("preferred_language")
internal val showNumberRowKey = booleanPreferencesKey("show_number_row")
internal val showCandidateScoresKey = booleanPreferencesKey("show_candidate_scores")
internal val diagnosticsConsentKey = booleanPreferencesKey("diagnostics_enabled")
internal val diagnosticsConsentVersionKey = intPreferencesKey("diagnostics_consent_version")
internal val diagnosticsAcceptedAtMsKey = longPreferencesKey("diagnostics_accepted_at_ms")
internal val diagnosticsRevokedAtMsKey = longPreferencesKey("diagnostics_revoked_at_ms")
internal val diagnosticsPolicyDigestKey = stringPreferencesKey("diagnostics_policy_digest")
internal val researchConsentKey = booleanPreferencesKey("research_enabled")
internal val researchConsentVersionKey = intPreferencesKey("research_consent_version")
internal val researchAcceptedAtMsKey = longPreferencesKey("research_accepted_at_ms")
internal val researchRevokedAtMsKey = longPreferencesKey("research_revoked_at_ms")
internal val researchPolicyDigestKey = stringPreferencesKey("research_policy_digest")

internal val Context.settingsStore by preferencesDataStore(name = "settings")

internal data class KeyboardSettings(
    val spacingMode: SpacingMode = SpacingMode.INFER_SPACES,
    val flowCorrectionDepth: Int = SettingsDefaults.CASCADE_DEPTH,
    val splitGraceWindowMs: Long = SettingsDefaults.GRACE_WINDOW_MS.toLong(),
    val commandBindings: List<CommandBinding> = CommandBindingSet().bindings,
    val preferredLanguage: Language = Language.ENGLISH,
    val showNumberRow: Boolean = SettingsDefaults.SHOW_NUMBER_ROW,
    val showCandidateScores: Boolean = false,
) {
    init {
        require(flowCorrectionDepth in SettingsDefaults.CASCADE_DEPTH_RANGE)
        require(splitGraceWindowMs in SettingsDefaults.GRACE_WINDOW_RANGE)
        require(commandBindings.map { it.slot }.distinct().size == commandBindings.size)
        require(commandBindings.map { it.trigger }.distinct().size == commandBindings.size)
    }
}

internal interface KeyboardSettingsDataSource {
    fun read(): KeyboardSettings
    fun replace(settings: KeyboardSettings)
}

internal class DataStoreKeyboardSettingsDataSource(
    private val context: Context,
) : KeyboardSettingsDataSource {
    override fun read(): KeyboardSettings = runBlocking {
        keyboardSettingsFromPreferences(context.settingsStore.data.first())
    }

    override fun replace(settings: KeyboardSettings) {
        runBlocking {
            context.settingsStore.edit { preferences ->
                preferences[spacingModeKey] = spacingModeStoredValue(settings.spacingMode)
                preferences[cascadeDepthKey] = settings.flowCorrectionDepth
                preferences[graceWindowKey] = settings.splitGraceWindowMs.toInt()
                preferences[commandBindingKey] = languageBinding(settings.commandBindings).trigger
                preferences[preferredLanguageKey] = settings.preferredLanguage.name
                preferences[showNumberRowKey] = settings.showNumberRow
            }
        }
    }

    private fun languageBinding(bindings: List<CommandBinding>): CommandBinding =
        bindings.firstOrNull { it.slot == LANGUAGE_COMMAND_SLOT }
            ?: CommandBinding(LANGUAGE_COMMAND_SLOT, DEFAULT_LANGUAGE_TRIGGER, GestureAction.SWITCH_LANGUAGE)

    private companion object {
        const val LANGUAGE_COMMAND_SLOT = "language"
        const val DEFAULT_LANGUAGE_TRIGGER = "two-finger-horizontal"
    }
}

internal fun keyboardSettingsFromPreferences(preferences: Preferences): KeyboardSettings = KeyboardSettings(
    spacingMode = spacingModeFromStoredValue(preferences[spacingModeKey]),
    flowCorrectionDepth = preferences[cascadeDepthKey] ?: SettingsDefaults.CASCADE_DEPTH,
    splitGraceWindowMs = (preferences[graceWindowKey] ?: SettingsDefaults.GRACE_WINDOW_MS).toLong(),
    commandBindings = commandBindingsFromStoredValue(preferences[commandBindingKey]),
    preferredLanguage = languageFromStoredValue(preferences[preferredLanguageKey]),
    showNumberRow = preferences[showNumberRowKey] ?: SettingsDefaults.SHOW_NUMBER_ROW,
    showCandidateScores = preferences[showCandidateScoresKey] ?: false,
)

internal fun spacingModeFromStoredValue(value: String?): SpacingMode = when (value) {
    "manual" -> SpacingMode.MANUAL
    "after_swipe" -> SpacingMode.AFTER_SWIPE
    "infer_spaces" -> SpacingMode.INFER_SPACES
    else -> SpacingMode.INFER_SPACES
}

internal fun spacingModeStoredValue(mode: SpacingMode): String = when (mode) {
    SpacingMode.MANUAL -> "manual"
    SpacingMode.AFTER_SWIPE -> "after_swipe"
    SpacingMode.INFER_SPACES -> "infer_spaces"
}

internal fun languageFromStoredValue(value: String?): Language = value
    ?.let { stored -> Language.entries.firstOrNull { it.name == stored } }
    ?: Language.ENGLISH

internal fun commandBindingsFromStoredValue(value: String?): List<CommandBinding> {
    val defaults = CommandBindingSet().bindings
    val languageTrigger = value?.takeIf { it.isNotBlank() } ?: return defaults
    return defaults.map { binding ->
        if (binding.slot == "language") binding.copy(trigger = languageTrigger) else binding
    }
}
