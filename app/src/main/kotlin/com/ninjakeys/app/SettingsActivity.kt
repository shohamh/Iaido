package com.ninjakeys.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private val settingsStore by lazy {
        PreferenceDataStoreFactory.create { applicationContext.preferencesDataStoreFile("settings") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen() }
    }

    @Composable
    private fun SettingsScreen() {
        var status by remember { mutableStateOf("Global gesture bindings") }
        MaterialTheme {
            Column(Modifier.padding(24.dp)) {
                Text("NinjaKeys Settings", style = MaterialTheme.typography.headlineSmall)
                Text(status, Modifier.padding(top = 16.dp))
                Button(
                    onClick = {
                        lifecycleScope.launch {
                            settingsStore.edit { it[commandBindingKey] = "two-finger-horizontal" }
                            status = "Language: two-finger horizontal (saved)"
                        }
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Gestures") }
                Button(onClick = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val database = Room.databaseBuilder(applicationContext, PersonalDictionaryDatabase::class.java, "personal_dictionary.db")
                            .build()
                        database.overrides().reset()
                        database.close()
                        status = "Learning reset"
                    }
                }) { Text("Reset learning") }
            }
        }
    }
}

private val commandBindingKey = stringPreferencesKey("command_binding_language")
