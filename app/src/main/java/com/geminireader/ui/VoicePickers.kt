package com.geminireader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.geminireader.analysis.*

@Composable fun CartesiaVoicePicker(label: String, voice: String, automatic: Boolean = false, change: (String) -> Unit) =
    CustomVoicePicker(label, voice, automatic, CartesiaVoices.names, CartesiaVoices::label, CartesiaVoices::valid,
        "Cartesia voice ID", "Paste the voice ID from Cartesia, not its name or a URL. Your account must have access.", change = change)

@Composable fun DeepgramVoicePicker(label: String, voice: String, automatic: Boolean = false, change: (String) -> Unit) =
    CustomVoicePicker(label, voice, automatic, DeepgramVoices.names, DeepgramVoices::label, DeepgramVoices::valid,
        "Deepgram voice model", "Enter an English Flux voice model, such as flux-haley-en.",
        customLabel = "Custom / other voice model", change = change)

@Composable fun ElevenVoicePicker(label: String, voice: String, automatic: Boolean = false, change: (String) -> Unit) =
    CustomVoicePicker(label, voice, automatic, ElevenVoices.names, ElevenVoices::label, ElevenVoices::valid,
        "ElevenLabs voice ID", "Paste the voice ID from ElevenLabs, not its name or a URL. Your account must have access.", change = change)

@Composable fun FishVoicePicker(label: String, voice: String, automatic: Boolean = false, change: (String) -> Unit) =
    CustomVoicePicker(label, voice, automatic, FishVoices.names, FishVoices::label, FishVoices::valid,
        "Fish voice ID", "Enter an Fish voice ID from your voice library, the 32-character ID in its Fish Audio page URL.", change = change)

@Composable fun InworldVoicePicker(label: String, voice: String, automatic: Boolean = false, change: (String) -> Unit) =
    CustomVoicePicker(label, voice, automatic, InworldVoices.names, InworldVoices::label, InworldVoices::valid,
        "Inworld voice ID", "Enter an Inworld voice ID from your voice library, such as Sarah.", change = change)

@Composable fun SpeechifyVoicePicker(label: String, voice: String, automatic: Boolean = false, change: (String) -> Unit) =
    CustomVoicePicker(label, voice, automatic, SpeechifyVoices.names, SpeechifyVoices::label, SpeechifyVoices::valid,
        "Speechify voice ID", "Enter an Speechify voice ID from your voice library, such as geffen_32.", change = change)

@Composable private fun CustomVoicePicker(
    label: String, voice: String, automatic: Boolean,
    names: List<String>, display: (String) -> String, valid: (String) -> Boolean,
    fieldLabel: String, hint: String, customLabel: String = "Custom / other voice ID",
    change: (String) -> Unit
) {
    var custom by remember { mutableStateOf(voice !in names && !(automatic && voice.isBlank())) }
    Column {
        Choice(label, if (custom) "custom" else voice, (if (automatic) listOf("") else emptyList()) + names + "custom",
            { if (it == "custom") customLabel else display(it) }) {
            custom = it == "custom"
            change(if (custom) "" else it)
        }
        if (custom) {
            OutlinedTextField(voice, { change(it.trim()) }, modifier = Modifier.fillMaxWidth(),
                label = { Text(fieldLabel) }, singleLine = true,
                isError = voice.isNotBlank() && !valid(voice),
                supportingText = { Text(hint) })
        }
    }
}
