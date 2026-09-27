#!/usr/bin/env python3
"""Reproducibly prepare the bundled Android Kokoro runtime/model. Requires Python 3.11+; a cold model conversion also needs uv."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / ".cache/kokoro-android"
OUTPUT = ROOT / "app/build/generated/kokoro"
ASSETS = OUTPUT / "assets/kokoro"
VERSION = "kokoro-1.0-selective8-en-sherpa-1.13.8-v3"
FILES = {
    "sherpa-onnx-1.13.8.aar": ("v1.13.8", "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"),
    "kokoro-multi-lang-v1_0.tar.bz2": ("tts-models", "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298"),
}


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def download(item):
    name, (tag, expected) = item
    target = CACHE / name
    if target.exists() and digest(target) == expected:
        return target
    temporary = target.with_suffix(".download")
    print(f"Downloading {name}", flush=True)
    try:
        with urllib.request.urlopen(f"https://github.com/k2-fsa/sherpa-onnx/releases/download/{tag}/{name}", timeout=60) as source, temporary.open("wb") as out:
            shutil.copyfileobj(source, out)
        if digest(temporary) != expected:
            raise RuntimeError(f"Checksum mismatch: {name}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    return target


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=2) as workers:
        list(workers.map(download, FILES.items()))
    shutil.copyfile(CACHE / "sherpa-onnx-1.13.8.aar", OUTPUT / "sherpa-onnx.aar")
    staging = OUTPUT / "assets-staging"
    if staging.exists():
        shutil.rmtree(staging)
    shutil.copytree(ROOT / "docs/kokoro-licenses", staging / "kokoro-licenses")
    model = staging / "kokoro"
    model.mkdir(parents=True)
    keep = {"model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt"}
    with tarfile.open(CACHE / "kokoro-multi-lang-v1_0.tar.bz2") as archive:
        for member in archive:
            parts = Path(member.name).parts
            if len(parts) < 2 or parts[0] != "kokoro-multi-lang-v1_0":
                continue
            relative = Path(*parts[1:])
            if ".." in relative.parts or relative.is_absolute():
                raise RuntimeError("Invalid model archive path")
            if not member.isfile() or (str(relative) not in keep and relative.parts[0] != "espeak-ng-data"):
                continue
            target = model / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, target.open("wb") as out:
                shutil.copyfileobj(source, out)
    if not all((model / name).is_file() for name in keep):
        raise RuntimeError("Incomplete Kokoro archive")
    # Preserve the exact FP32 baseline for reproducible comparisons, then bundle only selective8.
    baseline = CACHE / "model.fp32.onnx"
    if not baseline.exists() or digest(baseline) != "b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11":
        shutil.copyfile(model / "model.onnx", baseline)
    expected = "84514ba144a99ac1e00d2ab60ebf9207ba7794b3b86c96ecb2ed9fb983f54f51"
    selective = CACHE / "model.selective8.onnx"
    if not selective.exists() or digest(selective) != expected:
        temporary = selective.with_suffix(".partial.onnx")
        try:
            subprocess.run(["uv", "run", "--isolated", "--no-project", "--python", "3.11",
                "--with", "onnx==1.22.0", "--with", "onnxruntime==1.30.0", "--with", "numpy==2.4.6",
                "python", str(ROOT / "tools/quantize-kokoro.py"), str(baseline), str(temporary)], check=True)
            if digest(temporary) != expected:
                raise RuntimeError("Selective Kokoro checksum mismatch")
            temporary.replace(selective)
        finally:
            temporary.unlink(missing_ok=True)
    shutil.copyfile(selective, model / "model.onnx")
    manifest = {"version": VERSION, "files": [
        {"path": str(p.relative_to(model)), "size": p.stat().st_size, "sha256": digest(p)}
        for p in sorted(model.rglob("*")) if p.is_file()
    ]}
    (model / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    destination = OUTPUT / "assets"
    if destination.exists():
        shutil.rmtree(destination)
    staging.rename(destination)
    subprocess.run(["python3", str(ROOT / "tools/package-kokoro-downloads.py")], check=True)
    print(f"Prepared {len(manifest['files'])} files: {sum(f['size'] for f in manifest['files']) / 1024**2:.1f} MiB", flush=True)


if __name__ == "__main__":
    main()
