---
machine: build-host
subsystem: tts-provider-evaluation
last-verified: 2026-09-15
status: API tested; app integration not implemented
---
# ElevenLabs evaluation

User proposed ElevenLabs as the next provider and supplied a key for testing. No key is saved in this repository or this document.

Three real HTTPS requests succeeded using the documentation's example voice ID, the same 60-character English passage, and pcm_24000 output. Each test was a single request from the development machine; timings include network and full response download and are not phone benchmarks or quality ratings.

| Model | Full request time | Audio duration |
| --- | ---: | ---: |
| eleven_flash_v2_5 | 0.981 s | 3.854 s |
| eleven_multilingual_v2 | 3.482 s | 3.854 s |
| eleven_v3 | 2.319 s | 4.160 s |

WAV samples are local ignored artifacts in tools/artifacts/elevenlabs-test/. The key allowed TTS and /v2/voices (10 voices on the first page). /v1/models returned HTTP 401 missing_permissions. Do not interpret the model-list permission failure as invalid credentials or inability to generate speech.

Proposed integration: account voice picker (including accessible custom voices), model selection for Flash v2.5, Multilingual v2 and v3, and separate ElevenLabs credentials. Retain Groq as a character-analysis option. Avoid mapping unsupported Google performance prompts directly into spoken text. Treat ElevenLabs pricing as plan-dependent; establish credit/dollar accounting before claiming precise spending estimates.

ElevenLabs is the next recommended provider for expressive narration and custom voices. Cartesia is a subsequent candidate for streaming TTS and voice cloning. No new phone build was installed during this evaluation.

Sources: [ElevenLabs models](https://elevenlabs.io/docs/overview/models), [speech API](https://elevenlabs.io/docs/api-reference/text-to-speech/convert), [Cartesia Sonic](https://docs.cartesia.ai/build-with-cartesia/tts-models/latest).

Subsequent work: app integration is now implemented; see [ElevenLabs](elevenlabs.md) for current status.
