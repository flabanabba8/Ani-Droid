# Gemini voice research and direction

Checked 2026-09-15 for `gemini-3.1-flash-tts-preview`. The app's catalog joins Google's [Gemini-TTS gender table](https://docs.cloud.google.com/text-to-speech/docs/gemini-tts#voice_options) with its [30 voice descriptions](https://ai.google.dev/gemini-api/docs/speech-generation#voice-options). These are published voice tendencies, not listening-test results or guaranteed acoustic measurements. Google does not give per-voice pitch ranges in these tables. We do not invent frequency, semitone, accent or age measurements.

| Voice | Google gender label | Published trait |
| --- | --- | --- |
| Achernar | Female | Soft |
| Achird | Male | Friendly |
| Algenib | Male | Gravelly |
| Algieba | Male | Smooth |
| Alnilam | Male | Firm |
| Aoede | Female | Breezy |
| Autonoe | Female | Bright |
| Callirrhoe | Female | Easy-going |
| Charon | Male | Informative |
| Despina | Female | Smooth |
| Enceladus | Male | Breathy |
| Erinome | Female | Clear |
| Fenrir | Male | Excitable |
| Gacrux | Female | Mature |
| Iapetus | Male | Clear |
| Kore | Female | Firm |
| Laomedeia | Female | Upbeat |
| Leda | Female | Youthful |
| Orus | Male | Firm |
| Pulcherrima | Female | Forward |
| Puck | Male | Upbeat |
| Rasalgethi | Male | Informative |
| Sadachbia | Male | Lively |
| Sadaltager | Male | Knowledgeable |
| Schedar | Male | Even |
| Sulafat | Female | Warm |
| Umbriel | Male | Easy-going |
| Vindemiatrix | Female | Gentle |
| Zephyr | Female | Bright |
| Zubenelgenubi | Male | Casual |

## How the app uses this

`VoiceCatalog.kt` is the shared source for settings, character choices, LLM analysis and TTS prompts. Settings and Characters show each voice's name, gender label and trait. Narrator preview remains available in Settings; Characters previews the selected character using the current mode.

The text LLM sees the entire catalog and selected narrator, returns a schema-constrained `suggestedVoice`, and writes a character delivery style informed by the documented traits. For example, the catalog offers lively and upbeat options for energetic roles and gravelly or mature options where the text supports them. These casting choices are model judgments, not additional facts claimed by Google. Distinct mode uses a manual override first, then the model's suggestion, then the existing stable gender-pool fallback for old/offline records. Performance mode always retains the narrator.

Every directed line includes the actual selected voice's profile. A male-listed voice performing a female character receives relative higher/lighter-register guidance; a female-listed voice performing a male character receives relative lower/fuller-register guidance. This works for manually cross-cast distinct voices too. Same-gender, unknown and nonbinary characters receive no automatic gender-based pitch shift. Directions favor natural, modest changes and let explicit character instructions override the defaults. The voice's trait is a starting point, not an instruction to sound upbeat during a sad scene.

Analysis cache identity includes catalog version, narrator voice and character mode. Changing those causes fresh analysis on the next play. New audio prompts naturally get different audio-cache keys. Existing manual character edits and quote overrides remain intact; manually written instructions can still be edited if they refer to a previous narrator. The obsolete saved `narratorGender` field remains readable for compatibility but does not drive casting or direction.

This is prompt-based acting, not DSP pitch shifting or guaranteed speaker conversion. A subjective listening test is still needed to judge each voice's performance. No claim is made that all 30 voices have been individually auditioned.
