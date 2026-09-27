package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.geminireader.analysis.CartesiaVoices

@Composable fun CartesiaVoicePicker(label: String, voice: String, automatic: Boolean = false, change: (String) -> Unit) {
    var custom by remember { mutableStateOf(voice !in CartesiaVoices.names && !(automatic && voice.isBlank())) }
    val selected = if (custom) "custom" else voice
    Column {
        Choice(label, selected, (if (automatic) listOf("") else emptyList()) + CartesiaVoices.names + "custom",
            { if (it == "custom") "Custom / other voice ID" else CartesiaVoices.label(it) }) {
            if (it == "custom") { custom = true; change("") } else { custom = false; change(it) }
        }
        if (custom) {
            OutlinedTextField(voice, { change(it.trim()) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Cartesia voice ID") }, singleLine = true,
                isError = voice.isNotBlank() && !CartesiaVoices.valid(voice),
                supportingText = { Text("Paste the voice ID from Cartesia, not its name or a URL. Your account must have access.") })
        }
    }
}
