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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile

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
        var status by mutableStateOf("Global gesture bindings")
        MaterialTheme {
            Column(Modifier.padding(24.dp)) {
                Text("NinjaKeys Settings", style = MaterialTheme.typography.headlineSmall)
                Text(status, Modifier.padding(top = 16.dp))
                Button(
                    onClick = { status = "Language: two-finger horizontal" },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Gestures") }
                Button(onClick = { status = "Learning reset requested" }) { Text("Reset learning") }
            }
        }
    }
}

private val commandBindingKey = stringPreferencesKey("command_binding_language")
