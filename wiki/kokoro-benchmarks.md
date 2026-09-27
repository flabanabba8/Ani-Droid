---
machine: build-host / Android emulator / ANDROID_DEVICE
subsystem: Kokoro precision benchmarks
last-verified: 2026-09-15
status: emulator comparison complete; ARM candidate validation pending
---
# Kokoro precision comparison

User requested FP16 and selective quantization benchmarks before a recommendation. The comparison below used FP32 as baseline. A subsequent user request selected the benchmarked selective8 model for the next phone build; see [current Kokoro status](kokoro.md).

## Emulator results

API 36 x86_64 emulator, sherpa-onnx 1.13.8 Android runtime, CPU provider, two threads, af_heart, 1× speed. Fresh process per variant; same three original-text fixtures (51, 102, 318 characters), each repeated twice. PSS sampled every 250 ms includes app/runtime/test overhead. Decimal MB throughout.

| Variant | Model MB | Loaded PSS MB | Peak PSS MB | Load seconds | Generation seconds | Output audio seconds | Compute seconds / audio second |
|---|---:|---:|---:|---:|---:|---:|---:|
| FP32 baseline | 325.6 | 594.7 | 808.9 | 1.405 | 26.760 | 49.153 | 0.544 |
| Published FP16 | 163.5 | 595.4 | 802.2 | 1.293 | 26.999 | 49.152 | 0.549 |
| Selective unsigned 8-bit | 176.4 | 457.3 | 665.3 | 1.098 | 27.449 | 48.748 | 0.563 |

Lower compute/audio ratio is faster; below 1 means generation exceeded playback speed in this test. Load time is separate. These small timing differences do not demonstrate a speed improvement; there was one run order, not repeated randomized trials. ANDROID_DEVICE is ARM and may behave differently.

FP16 halved disk size but did not meaningfully reduce CPU-runtime memory or latency. This is consistent with [ONNX Runtime CPU float16 limitations](https://onnxruntime.ai/docs/performance/model-optimizations/float16.html). Selective8 reduced peak memory by **143.6 MB (17.7%)**, with similar/slightly slower synthesis. Recommend it as the next **phone memory/quality trial**, not as a proven speed upgrade or automatic default.

## Audio checks

No clipped samples in the exported PCM. Maximum narrow-tone prominence across six clips:

| Variant | 4.8 kHz prominence | 9.6 kHz prominence |
|---|---:|---:|
| FP32 | 2.20 dB | 1.85 dB |
| FP16 | 4.06 dB | 5.43 dB |
| Selective8 | 0.88 dB | 0.49 dB |

All below the existing 8 dB regression limit. Welch measurement: 100 ms Hann windows, 50% overlap, median neighboring 40–190 Hz bands. This tests the known ringing defect, not intelligibility, prosody or perceived quality. Only one voice and three texts were tested; user listening and broader ARM testing remain necessary. Example comparison files are saved under ignored `tools/artifacts/kokoro-benchmark-audio/`.

## Candidate provenance

- FP32: existing checksum-pinned Sherpa Kokoro 1.0 model.
- FP16: [published Kokoro 1.0 export](https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.1), upstream SHA-256 `f3a290d384fbb27966d462905c71a46cef9e5fd00516b40df32a0b4afe77ac96`. Added Sherpa metadata from the baseline without altering the tensor graph. An initial direct FP16 conversion of the baseline segfaulted during desktop runtime loading; it was not used for the successful benchmark.
- Selective8: local dynamic QUInt8 conversion of the baseline, excluding the entire `/decoder/generator` subtree. File SHA-256 `84514ba144a99ac1e00d2ab60ebf9207ba7794b3b86c96ecb2ed9fb983f54f51`; reproduced by the preparation tool. This is a new experimental conversion, not the previously installed int8 model. The rationale comes from [exporter observations about sensitive audio layers](https://github.com/adrianlyjak/kokoro-onnx-export); that project's quality claim is not a substitute for these tests.

[Preparation scripts and exact commands](../tools/kokoro-benchmark/README.md). Python ONNX Runtime 1.30.0 is used for conversion; inference uses the runtime bundled in the pinned Android AAR.

## ANDROID_DEVICE observations during user's FP32 test

Read-only sampling from 11:02:04 to 11:09:35 local time, 180 observations. App-attributed RAM peaked at **843.9 MB**. Early readings were 780–795 MB with roughly 930–975 MB system memory available. Early battery temperature 33.6–33.7°C, processor sensor around 40°C, Android thermal status 0. The user returned to Vertex during observation; RAM dropped to about 200 MB and saved engine was verified Vertex. These are process readings, not model-only allocations.

A separate 62.5-second cache observation found seven new WAVs containing 30.97 seconds of audio including padding. This is observed cache growth, not an isolated synthesis-speed measurement: it can include buffering pauses, user actions and incomplete in-flight segments. Do not extrapolate battery life from this short session. Raw logs are in `tools/artifacts/poo-kokoro-{resources,throughput}.jsonl`; they contain no book text or credentials.

Subsequent selective8 phone monitoring peaked at **578.4 MB PSS** across 180 samples; user reports it feels faster. This was a separate real-use session, not matched input against FP32's 843.9 MB peak, so do not claim a controlled percentage improvement. Long-paragraph delay prompted the later sentence-queue change.
