package com.iaido.app

import android.Manifest
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.iaido.core.typing.SpacingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UPDATE_CHECK_INTERVAL_MS = 5 * 60 * 1000L

class SettingsActivity : ComponentActivity() {
    private val settingsStore get() = applicationContext.settingsStore
    private val skipAutomaticUpdateChecks: Boolean
        get() = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_SKIP_AUTOMATIC_UPDATE_CHECKS, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!skipAutomaticUpdateChecks) ReleaseMonitorScheduler.schedule(this, 0L)
        setContent { IaidoTheme { SettingsScreen() } }
    }

    @Composable
    private fun SettingsScreen() {
        var preview by remember { mutableStateOf("") }
        var cascadeDepth by remember { mutableStateOf(SettingsDefaults.CASCADE_DEPTH) }
        var graceWindowMs by remember { mutableStateOf(SettingsDefaults.GRACE_WINDOW_MS) }
        var spacingMode by remember { mutableStateOf(SpacingMode.INFER_SPACES) }
        var showCandidateScores by remember { mutableStateOf(false) }
        val installedVersionName = remember { appVersion() }
        var updateChannel by remember { mutableStateOf(updateChannelFromStoredValue(null, installedVersionName)) }
        var addWord by remember { mutableStateOf("") }
        var forgetWord by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Ready") }
        var appUpdateState by remember { mutableStateOf<AppUpdateUiState>(AppUpdateUiState.Idle) }
        var settingsLoaded by remember { mutableStateOf(false) }
        var notificationsEnabled by remember { mutableStateOf(updateNotificationsEnabled()) }
        val appUpdateClient = remember(updateChannel) {
            AppUpdateClient(this@SettingsActivity, channel = updateChannel)
        }
        val requestNotificationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            notificationsEnabled = granted && NotificationManagerCompat.from(this@SettingsActivity).areNotificationsEnabled()
        }
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
            showCandidateScores = preferences[showCandidateScoresKey] ?: false
            updateChannel = updateChannelFromStoredValue(preferences[updateChannelKey], installedVersionName)
            settingsLoaded = true
        }

        suspend fun checkForUpdates(openInstallPermission: Boolean) {
            appUpdateState = AppUpdateUiState.Checking
            val workflowStatus = withContext(Dispatchers.IO) {
                GitHubReleaseMonitorClient().releaseWorkflowStatus()
            }
            if (workflowStatus is ReleaseWorkflowStatus.Running) {
                appUpdateState = AppUpdateUiState.PendingRelease
                return
            }

            val downloadStartedAt = SystemClock.elapsedRealtime()
            appUpdateState = AppUpdateUiState.Downloading()
            val result = withContext(Dispatchers.IO) {
                appUpdateClient.update(
                    onDownloadProgress = { downloadedBytes, totalBytes ->
                        val elapsedMillis = SystemClock.elapsedRealtime() - downloadStartedAt
                        val etaMillis = estimateDownloadRemainingMillis(
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            elapsedMillis = elapsedMillis,
                        )
                        runOnUiThread {
                            if (appUpdateState is AppUpdateUiState.Downloading) {
                                appUpdateState = AppUpdateUiState.Downloading(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    etaMillis = etaMillis,
                                )
                            }
                        }
                    },
                )
            }
            appUpdateState = when (result) {
                AppUpdateResult.UpToDate -> AppUpdateUiState.UpToDate
                is AppUpdateResult.ReadyToInstall ->
                    AppUpdateUiState.ReadyToInstall(result.apk, result.versionCode, result.versionName)
                is AppUpdateResult.InstallPermissionRequired -> {
                    if (openInstallPermission) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                    AppUpdateUiState.PermissionRequired
                }
                is AppUpdateResult.Failed -> AppUpdateUiState.Failed(result.message)
            }
        }

        LaunchedEffect(updateChannel, settingsLoaded) {
            if (!settingsLoaded || skipAutomaticUpdateChecks) return@LaunchedEffect
            while (isActive) {
                checkForUpdates(openInstallPermission = false)
                delay(UPDATE_CHECK_INTERVAL_MS)
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
            contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Text(
                if (BuildConfig.DEBUG) "Iaido Debug Settings" else "Iaido Settings",
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            )
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
            Text("Update channel", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
            UpdateChannelSelector(
                selectedChannel = updateChannel,
                onChannelSelected = { channel ->
                    updateChannel = channel
                    appUpdateState = AppUpdateUiState.Idle
                    lifecycleScope.launch {
                        settingsStore.edit { it[updateChannelKey] = updateChannelStoredValue(channel) }
                        clearReleaseMonitorState(applicationContext)
                        ReleaseMonitorScheduler.schedule(applicationContext, 0L)
                    }
                },
            )
            Text("Download the newest signed ${updateChannel.displayName.lowercase()} Iaido APK from GitHub Releases.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsEnabled) {
                Button(onClick = { requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Allow update notifications")
                }
                Text("Enable notifications so Iaido can tell you when a new release is ready.")
            }
            Text("A release APK cannot update a debug build. If you installed Iaido from Android Studio, uninstall that build first; your settings and learned words will be removed.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = appUpdateButtonEnabled(appUpdateState),
                    onClick = {
                        lifecycleScope.launch { checkForUpdates(openInstallPermission = true) }
                    },
                ) { Text(appUpdateButtonLabel(appUpdateState)) }
                Button(
                    enabled = appUpdateInstallButtonEnabled(appUpdateState),
                    onClick = {
                        val ready = appUpdateState as? AppUpdateUiState.ReadyToInstall ?: return@Button
                        startActivity(appUpdateClient.installIntent(ready.apk))
                    },
                ) { Text("Install update") }
            }
            (appUpdateState as? AppUpdateUiState.Downloading)?.let { downloading ->
                downloading.progressFraction?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
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

            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                HorizontalDivider()
                Text("Debug", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show candidate scores")
                        Text("Display recognition scores on the active reel choices.")
                    }
                    Switch(
                        checked = showCandidateScores,
                        onCheckedChange = { enabled ->
                            showCandidateScores = enabled
                            lifecycleScope.launch {
                                settingsStore.edit { it[showCandidateScoresKey] = enabled }
                            }
                        },
                    )
                }
            }

            TelemetrySettingsSection(context = applicationContext, scope = lifecycleScope)

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

    private fun updateNotificationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                NotificationManagerCompat.from(this).areNotificationsEnabled())

    companion object {
        /** Test-only escape hatch for deterministic Settings/IME screenshot journeys. */
        const val EXTRA_SKIP_AUTOMATIC_UPDATE_CHECKS =
            "com.iaido.app.extra.SKIP_AUTOMATIC_UPDATE_CHECKS"
    }
}

internal data class SpacingModeOption(
    val mode: SpacingMode,
    val label: String,
    val selected: Boolean,
)

@Composable
internal fun UpdateChannelSelector(
    selectedChannel: UpdateChannel,
    onChannelSelected: (UpdateChannel) -> Unit,
) {
    Column {
        UpdateChannel.entries.forEach { channel ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = channel == selectedChannel,
                        onClick = { onChannelSelected(channel) },
                        role = Role.RadioButton,
                    ),
            ) {
                RadioButton(selected = channel == selectedChannel, onClick = null)
                Text(channel.displayName, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

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
