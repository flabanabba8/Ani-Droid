package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.geminireader.analysis.FishVoices

@Composable fun FishVoicePicker(label: String, voice: String, automatic: Boolean = false, change: (String) -> Unit) {
    var custom by remember { mutableStateOf(voice !in FishVoices.names && !(automatic && voice.isBlank())) }
    val selected = if (custom) "custom" else voice
    Column {
        Choice(label, selected, (if (automatic) listOf("") else emptyList()) + FishVoices.names + "custom",
            { if (it == "custom") "Custom / other voice ID" else FishVoices.label(it) }) {
            if (it == "custom") { custom = true; change("") } else { custom = false; change(it) }
        }
        if (custom) {
            OutlinedTextField(voice, { change(it.trim()) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Fish voice ID") }, singleLine = true,
                isError = voice.isNotBlank() && !FishVoices.valid(voice),
                supportingText = { Text("Enter an Fish voice ID from your voice library, the 32-character ID in its Fish Audio page URL.") })
        }
    }
}
