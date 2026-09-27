---
machine: build-host / Android
subsystem: spending estimates
last-verified: 2026-09-15
status: implemented
---
# Spending estimates

Settings shows total recorded USD, the current calendar month (device timezone), model/provider totals and a per-book breakdown. This is local paid-rate usage estimation, not an imported Google bill. Tracking begins with this feature; previous requests cannot be reconstructed. Books are keyed by ID, so duplicate titles remain separate and deleting a book retains its spending history. Previews without book context are grouped under “Previews / other”.

Successful generation HTTP responses are recorded before audio validation. Thus repeated generation attempts with responses count separately, including responses without usable audio. Cache hits make no request and add no cost. Shared chapter analysis carries its original book identity even if the reader switches books or a playback waiter is canceled. Offline preparation carries the book identity too.

Reported input/output tokens are preferred. Text analysis includes thinking tokens and the supported models' cached-input discount. For speech without usage metadata, input is approximated at four text characters per token and generated audio at 25 tokens/second, before padding or speed changes. Missing text-analysis usage or unknown model rates are counted as unpriced responses and excluded from the dollar total. No historical backfill, free-tier/credit/tax accounting, failed HTTP/time-out accounting or custom-endpoint estimates are provided. Use Google Cloud billing for actual charges. An accounting storage error is shown in Settings and never triggers a synthesis retry.

The app persists only month, provider, model, book ID/title, response counts and the calculated cost. It does not save request bodies, credentials, audio or API URLs in the spending history. Costs are stored as calculated, so later rate-table updates do not reprice earlier usage.

Local Kokoro speech makes no generation HTTP request and adds no API charge. Google character analysis and explicit rewrites still contribute to the book estimate when Kokoro is selected.

## Standard paid rates verified September 15, 2026

USD per million input/output tokens:

- Gemini 3.1 Flash TTS Preview: 1 / 20.
- Gemini 2.5 Flash TTS: 0.50 / 10.
- Gemini 2.5 Pro TTS: 1 / 20.
- Gemini 2.5 Flash text: 0.30 / 2.50.
- Gemini 2.5 Flash-Lite text: 0.10 / 0.40.
- Gemini 2.5 Pro text: 1.25 / 10 through 200,000 input tokens; 2.50 / 15 above that threshold.

Sources: [Cloud TTS pricing](https://cloud.google.com/text-to-speech/pricing), [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing), [Vertex AI pricing](https://cloud.google.com/vertex-ai/generative-ai/pricing). Rates are a versioned snapshot; unsupported model names stay unpriced instead of inheriting a guessed rate.

Validation: spending unit tests cover persistence, concurrent updates, per-book separation, unknown models/missing usage, mock exclusion, audio estimation, cache replay, long-context rates, cached-input discounts and corrupt-history protection.

Groq GPT-OSS 20B/120B character analysis and explicit rewrites use reported prompt/completion tokens, with reported cached-input discounts, attributed to the book. See [Groq](groq.md).

Cartesia speech records successful responses per model/book as unpriced; subscription/free-credit billing is not inferred. See [Cartesia](cartesia.md).

## Deepgram — September 15, 2026

Deepgram Flux requests are estimated at $45/million Unicode code points, grouped by voice model and book. Credits and account discounts are not subtracted; cached replay has no new request. See [Deepgram](deepgram.md).

## Inworld — September 15, 2026

Inworld TTS-2 is estimated at $25/million characters before credits and plan discounts. Provider-reported character counts take precedence over the Unicode fallback. Costs are grouped by book.

## Speechify — September 15, 2026

Speechify reports billable character counts. Requests are valued at the $10/million Starter reference rate, marked estimated, before included allowance or discounts. Missing counts are unpriced. Free-tier generation does not imply a cash charge.

## Fish Audio — September 15, 2026

Fish Audio free-model requests are recorded by book at $0 through November 30, 2026, the currently published promotion end. Later requests are unpriced pending verification. Text analysis is tracked separately.

## Android TTS — September 15, 2026

Installed Android TTS adds no PageCast speech API charges or cloud spending rows. Optional passage rewrites are accounted for through their text provider. Any installed network-engine billing is outside PageCast accounting.