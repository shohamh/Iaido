package com.iaido.app

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.iaido.core.typing.SpacingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    private val settingsStore get() = applicationContext.settingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IaidoTheme { SettingsScreen() } }
    }

    @Composable
    private fun SettingsScreen() {
        var preview by remember { mutableStateOf("") }
        var cascadeDepth by remember { mutableStateOf(SettingsDefaults.CASCADE_DEPTH) }
        var graceWindowMs by remember { mutableStateOf(SettingsDefaults.GRACE_WINDOW_MS) }
        var spacingMode by remember { mutableStateOf(SpacingMode.INFER_SPACES) }
        var addWord by remember { mutableStateOf("") }
        var forgetWord by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Ready") }
        var appUpdateState by remember { mutableStateOf<AppUpdateUiState>(AppUpdateUiState.Idle) }
        val appUpdateClient = remember { AppUpdateClient(this@SettingsActivity) }
        val exportProfile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            lifecycleScope.launch {
                status = runCatching {
                    withContext(Dispatchers.IO) {
                        val profile = readProfile()
                        KeyboardProfileFileTransfer.write(this@SettingsActivity, uri, profile, appVersion())
                    }
                    "Profile exported"
                }.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
            }
        }
        val importProfile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            lifecycleScope.launch {
                status = runCatching {
                    withContext(Dispatchers.IO) {
                        replaceProfile(KeyboardProfileFileTransfer.read(this@SettingsActivity, uri))
                    }
                    "Profile imported; reopen the keyboard to apply learned words"
                }.getOrElse { "Import failed: ${it.message ?: "invalid profile"}" }
            }
        }

        LaunchedEffect(Unit) {
            val preferences = settingsStore.data.first()
            cascadeDepth = preferences[cascadeDepthKey] ?: SettingsDefaults.CASCADE_DEPTH
            graceWindowMs = preferences[graceWindowKey] ?: SettingsDefaults.GRACE_WINDOW_MS
            spacingMode = spacingModeFromStoredValue(preferences[spacingModeKey])
        }

        Column(
            modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Iaido Settings", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            Text("Try the keyboard behavior here before leaving settings.")
            OutlinedTextField(
                value = preview,
                onValueChange = { preview = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Live preview") },
                singleLine = false,
            )

            HorizontalDivider()
            Text("Setup", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("Enable Iaido in Android system settings, then choose it from the keyboard picker.")
            Button(onClick = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) {
                Text("Open keyboard setup")
            }

            HorizontalDivider()
            Text("App updates", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("Download the newest signed Iaido APK from GitHub Releases.")
            Text("A release APK cannot update a debug build. If you installed Iaido from Android Studio, uninstall that build first; your settings and learned words will be removed.")
            Button(
                enabled = appUpdateButtonEnabled(appUpdateState),
                onClick = {
                    val ready = appUpdateState as? AppUpdateUiState.ReadyToInstall
                    if (ready != null) {
                        startActivity(appUpdateClient.installIntent(ready.apk))
                    } else {
                        lifecycleScope.launch {
                            appUpdateState = AppUpdateUiState.Checking
                            val result = withContext(Dispatchers.IO) {
                                appUpdateClient.update {
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        appUpdateState = AppUpdateUiState.Downloading
                                    }
                                }
                            }
                            appUpdateState = when (result) {
                                AppUpdateResult.UpToDate -> AppUpdateUiState.UpToDate
                                is AppUpdateResult.ReadyToInstall -> AppUpdateUiState.ReadyToInstall(result.apk, result.versionCode)
                                is AppUpdateResult.InstallPermissionRequired -> {
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:$packageName"),
                                        ),
                                    )
                                    AppUpdateUiState.PermissionRequired
                                }
                                is AppUpdateResult.Failed -> AppUpdateUiState.Failed(result.message)
                            }
                        }
                    }
                },
            ) { Text(appUpdateButtonLabel(appUpdateState)) }
            Text(
                appUpdateStatusLabel(appUpdateState),
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            )

            HorizontalDivider()
            Text("Profile migration", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("Export settings and learned words to move Iaido to another phone. Editor text is never included.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportProfile.launch("iaido-profile.json") }) { Text("Export profile") }
                Button(onClick = { importProfile.launch(arrayOf("application/json", "text/plain")) }) { Text("Import profile") }
            }

            HorizontalDivider()
            Text("Gestures", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("Two-finger commands and language switching use the bindings configured by the keyboard.")
            Button(onClick = {
                lifecycleScope.launch {
                    settingsStore.edit { it[commandBindingKey] = "two-finger-horizontal" }
                    status = "Default gesture binding saved"
                }
            }) { Text("Restore default gesture binding") }

            HorizontalDivider()
            Text("Typing", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            SpacingModeSelector(
                selectedMode = spacingMode,
                onModeSelected = { mode ->
                    spacingMode = mode
                    saveString(spacingModeKey, spacingModeStoredValue(mode))
                },
            )
            Text("Flow correction depth: $cascadeDepth")
            Slider(
                value = cascadeDepth.toFloat(),
                onValueChange = { value ->
                    cascadeDepth = value.toInt().coerceIn(SettingsDefaults.CASCADE_DEPTH_RANGE)
                },
                onValueChangeFinished = { saveInt(cascadeDepthKey, cascadeDepth) },
                valueRange = SettingsDefaults.CASCADE_DEPTH_RANGE.first.toFloat()..SettingsDefaults.CASCADE_DEPTH_RANGE.last.toFloat(),
                steps = SettingsDefaults.CASCADE_DEPTH_RANGE.last - SettingsDefaults.CASCADE_DEPTH_RANGE.first - 1,
            )
            Text("Two-handed grace window: ${graceWindowMs}ms")
            Slider(
                value = graceWindowMs.toFloat(),
                onValueChange = { value ->
                    graceWindowMs = value.toInt().coerceIn(SettingsDefaults.GRACE_WINDOW_RANGE)
                },
                onValueChangeFinished = { saveInt(graceWindowKey, graceWindowMs) },
                valueRange = SettingsDefaults.GRACE_WINDOW_RANGE.first.toFloat()..SettingsDefaults.GRACE_WINDOW_RANGE.last.toFloat(),
                steps = 3,
            )

            HorizontalDivider()
            Text("Dictionary & Learning", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = addWord,
                    onValueChange = { addWord = it },
                    label = { Text("Word to add") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    val word = addWord.trim()
                    if (word.isNotEmpty()) {
                        addLearning(word)
                        addWord = ""
                        status = "Added $word"
                    }
                }) { Text("Add") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    resetLearning()
                    status = "Learning reset"
                }) { Text("Reset all") }
                OutlinedTextField(
                    value = forgetWord,
                    onValueChange = { forgetWord = it },
                    label = { Text("Word to forget") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = {
                val word = forgetWord.trim()
                if (word.isNotEmpty()) {
                    forgetLearning(word)
                    forgetWord = ""
                    status = "Forgot $word"
                }
            }) { Text("Forget word") }

            HorizontalDivider()
            Text("Help", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("Swipe across letters to type. Flick a key upward for its number. Drag punctuation down to add a space. Use two fingers for split-word typing; a quick tap during a split repeats a letter.")
            Text(status, color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
        }
    }

    private fun saveInt(key: androidx.datastore.preferences.core.Preferences.Key<Int>, value: Int) {
        lifecycleScope.launch { settingsStore.edit { it[key] = value } }
    }

    private fun saveString(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        lifecycleScope.launch { settingsStore.edit { it[key] = value } }
    }

    private fun resetLearning() {
        lifecycleScope.launch(Dispatchers.IO) {
            val database = Room.databaseBuilder(applicationContext, PersonalDictionaryDatabase::class.java, "personal_dictionary.db")
                .addMigrations(PERSONAL_DICTIONARY_MIGRATION_1_2)
                .build()
            database.overrides().reset()
            database.close()
        }
    }

    private fun addLearning(word: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val database = Room.databaseBuilder(applicationContext, PersonalDictionaryDatabase::class.java, "personal_dictionary.db")
                .addMigrations(PERSONAL_DICTIONARY_MIGRATION_1_2)
                .build()
            RoomPersonalDictionaryRepository(database.overrides(), emptyList()).record(
                signal = com.iaido.core.dictionary.LearningSignal.EXPLICIT_ADD,
                replacement = word,
            )
            database.close()
        }
    }

    private fun forgetLearning(word: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val database = Room.databaseBuilder(applicationContext, PersonalDictionaryDatabase::class.java, "personal_dictionary.db")
                .addMigrations(PERSONAL_DICTIONARY_MIGRATION_1_2)
                .build()
            database.overrides().forget(word)
            database.close()
        }
    }

    private fun readProfile(): com.iaido.core.state.KeyboardProfileSnapshot {
        val database = Room.databaseBuilder(applicationContext, PersonalDictionaryDatabase::class.java, "personal_dictionary.db")
            .addMigrations(PERSONAL_DICTIONARY_MIGRATION_1_2)
            .build()
        return try {
            KeyboardProfileStore(
                DataStoreKeyboardSettingsDataSource(applicationContext),
                RoomPersonalDictionarySnapshotDataSource(database, emptyList()),
            ).read()
        } finally {
            database.close()
        }
    }

    private fun replaceProfile(profile: com.iaido.core.state.KeyboardProfileSnapshot) {
        val database = Room.databaseBuilder(applicationContext, PersonalDictionaryDatabase::class.java, "personal_dictionary.db")
            .addMigrations(PERSONAL_DICTIONARY_MIGRATION_1_2)
            .build()
        try {
            KeyboardProfileStore(
                DataStoreKeyboardSettingsDataSource(applicationContext),
                RoomPersonalDictionarySnapshotDataSource(database, emptyList()),
            ).replace(profile)
        } finally {
            database.close()
        }
    }

    private fun appVersion(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
}

internal data class SpacingModeOption(
    val mode: SpacingMode,
    val label: String,
    val selected: Boolean,
)

internal fun spacingModeOptions(selectedMode: SpacingMode): List<SpacingModeOption> = listOf(
    SpacingModeOption(SpacingMode.MANUAL, "Manual spacing", SpacingMode.MANUAL == selectedMode),
    SpacingModeOption(SpacingMode.AFTER_SWIPE, "Space after swipe", SpacingMode.AFTER_SWIPE == selectedMode),
    SpacingModeOption(SpacingMode.INFER_SPACES, "Infer spaces", SpacingMode.INFER_SPACES == selectedMode),
)

@Composable
internal fun SpacingModeSelector(
    selectedMode: SpacingMode,
    onModeSelected: (SpacingMode) -> Unit,
) {
    Column {
        spacingModeOptions(selectedMode).forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = option.selected,
                        onClick = { onModeSelected(option.mode) },
                        role = Role.RadioButton,
                    ),
            ) {
                RadioButton(selected = option.selected, onClick = null)
                Text(option.label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
