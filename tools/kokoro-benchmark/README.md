# Kokoro variant benchmark

Experimental tooling; does not change the bundled model or phone settings. Results and limitations are in [the benchmark report](../../wiki/kokoro-benchmarks.md).

## Prepare variants

Run from the repository root after `./gradlew prepareKokoro`:

```sh
uv venv --python 3.11 .cache/kokoro-android/benchmark-venv
uv pip install --python .cache/kokoro-android/benchmark-venv/bin/python -r tools/kokoro-benchmark/requirements.txt
.cache/kokoro-android/benchmark-venv/bin/python tools/kokoro-benchmark/prepare.py
```

FP16 uses a checksum-pinned published export, with Sherpa metadata added from the baseline. Selective8 uses ONNX Runtime dynamic unsigned 8-bit quantization while excluding the complete `/decoder/generator` waveform-generation subtree. This protects more than just the final convolution; it is not the original faulty int8 release.

## Run on emulator

First install the current main debug APK and use Settings → Kokoro → Download Kokoro to install the verified runtime and baseline model. Build/install the instrumentation APK, then copy each experimental model from `.cache/kokoro-android/benchmarks` into app-private `files/benchmark/` using `/data/local/tmp` and `run-as`.

```sh
source tools/env.sh
./gradlew assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell run-as com.geminireader mkdir -p files/benchmark
adb -s emulator-5554 push .cache/kokoro-android/model.fp32.onnx /data/local/tmp/pagecast-fp32.onnx
adb -s emulator-5554 shell 'cat /data/local/tmp/pagecast-fp32.onnx | run-as com.geminireader sh -c "cat > files/benchmark/fp32.onnx"'
adb -s emulator-5554 push .cache/kokoro-android/benchmarks/fp16.onnx /data/local/tmp/pagecast-fp16.onnx
adb -s emulator-5554 shell 'cat /data/local/tmp/pagecast-fp16.onnx | run-as com.geminireader sh -c "cat > files/benchmark/fp16.onnx"'
adb -s emulator-5554 push .cache/kokoro-android/benchmarks/selective8.onnx /data/local/tmp/pagecast-selective8.onnx
adb -s emulator-5554 shell 'cat /data/local/tmp/pagecast-selective8.onnx | run-as com.geminireader sh -c "cat > files/benchmark/selective8.onnx"'
adb -s emulator-5554 shell am instrument -w -e class com.geminireader.tts.KokoroBenchmark -e variant fp32 com.geminireader.test/androidx.test.runner.AndroidJUnitRunner
```

Repeat the instrumentation command with `fp16` and `selective8`. Each invocation gets a fresh process. It records loading time, six synthesis runs (three fixtures repeated twice), process CPU time, sampled peak PSS every 250 ms, per-run memory and raw WAV output. CPU provider, two threads, af_heart, 1× speed, one native sentence at a time.

Results are in private `files/benchmark/<variant>.json`; audio is `<variant>-<iteration>-<fixture>.wav`. Copy JSON into `tools/artifacts/kokoro-benchmark-<variant>.json` and WAVs into `tools/artifacts/kokoro-benchmark-audio/` with `adb exec-out run-as com.geminireader cat ...`. Run `analyze.py` in the same Python environment for Welch spectra, ringing prominence and clipping checks. Artifacts are ignored by Git.

A caught model error is recorded with `status: error`; inspect JSON as well as instrumentation output. Native crashes may prevent a JSON result. Memory sampling and emulator overhead affect numbers; a single run order is not a statistical latency study. No automated metric establishes subjective speech quality or ARM-device performance.
