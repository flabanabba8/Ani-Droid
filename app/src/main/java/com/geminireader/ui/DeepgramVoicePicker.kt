package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.geminireader.analysis.DeepgramVoices

@Composable fun DeepgramVoicePicker(label: String, voice: String, automatic: Boolean = false, change: (String) -> Unit) {
    var custom by remember { mutableStateOf(voice !in DeepgramVoices.names && !(automatic && voice.isBlank())) }
    val selected = if (custom) "custom" else voice
    Column {
        Choice(label, selected, (if (automatic) listOf("") else emptyList()) + DeepgramVoices.names + "custom",
            { if (it == "custom") "Custom / other voice model" else DeepgramVoices.label(it) }) {
            if (it == "custom") { custom = true; change("") } else { custom = false; change(it) }
        }
        if (custom) {
            OutlinedTextField(voice, { change(it.trim()) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Deepgram voice model") }, singleLine = true,
                isError = voice.isNotBlank() && !DeepgramVoices.valid(voice),
                supportingText = { Text("Enter an English Flux voice model, such as flux-haley-en.") })
        }
    }
}
