package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.geminireader.analysis.SpeechifyVoices

@Composable fun SpeechifyVoicePicker(label: String, voice: String, automatic: Boolean = false, change: (String) -> Unit) {
    var custom by remember { mutableStateOf(voice !in SpeechifyVoices.names && !(automatic && voice.isBlank())) }
    val selected = if (custom) "custom" else voice
    Column {
        Choice(label, selected, (if (automatic) listOf("") else emptyList()) + SpeechifyVoices.names + "custom",
            { if (it == "custom") "Custom / other voice ID" else SpeechifyVoices.label(it) }) {
            if (it == "custom") { custom = true; change("") } else { custom = false; change(it) }
        }
        if (custom) {
            OutlinedTextField(voice, { change(it.trim()) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Speechify voice ID") }, singleLine = true,
                isError = voice.isNotBlank() && !SpeechifyVoices.valid(voice),
                supportingText = { Text("Enter an Speechify voice ID from your voice library, such as geffen_32.") })
        }
    }
}
