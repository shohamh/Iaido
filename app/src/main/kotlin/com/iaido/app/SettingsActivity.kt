package com.iaido.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    private val settingsStore by lazy {
        PreferenceDataStoreFactory.create { applicationContext.preferencesDataStoreFile("settings") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IaidoTheme { SettingsScreen() } }
    }

    @Composable
    private fun SettingsScreen() {
        var preview by remember { mutableStateOf("") }
        var cascadeDepth by remember { mutableStateOf(SettingsDefaults.CASCADE_DEPTH) }
        var graceWindowMs by remember { mutableStateOf(SettingsDefaults.GRACE_WINDOW_MS) }
        var addWord by remember { mutableStateOf("") }
        var forgetWord by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Ready") }
        var appUpdateState by remember { mutableStateOf<AppUpdateUiState>(AppUpdateUiState.Idle) }
        val appUpdateClient = remember { AppUpdateClient(this@SettingsActivity) }

        LaunchedEffect(Unit) {
            val preferences = settingsStore.data.first()
            cascadeDepth = preferences[cascadeDepthKey] ?: SettingsDefaults.CASCADE_DEPTH
            graceWindowMs = preferences[graceWindowKey] ?: SettingsDefaults.GRACE_WINDOW_MS
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
}

internal object SettingsDefaults {
    const val CASCADE_DEPTH = 2
    const val GRACE_WINDOW_MS = 350
    val CASCADE_DEPTH_RANGE = 0..4
    val GRACE_WINDOW_RANGE = 300..400
}

private val commandBindingKey = stringPreferencesKey("command_binding_language")
private val cascadeDepthKey = intPreferencesKey("flow_correction_depth")
private val graceWindowKey = intPreferencesKey("split_grace_window_ms")
