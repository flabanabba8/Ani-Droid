#!/usr/bin/env bash
# Installs a user-space Android SDK + JDK 21 + emulator image. No root needed.
# Re-runnable: skips pieces that already exist.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
JDK_DIR="$HOME/.jdks/temurin-21"
CLT_VER="15859902"
API="36"
IMG="system-images;android-${API};google_apis;x86_64"

mkdir -p "$SDK" "$HOME/.jdks"

if [ ! -x "$JDK_DIR/bin/javac" ]; then
  echo "==> Downloading Temurin JDK 21"
  tmp=$(mktemp -d)
  curl -fL --retry 3 -o "$tmp/jdk.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$JDK_DIR"
  tar -xzf "$tmp/jdk.tar.gz" -C "$JDK_DIR" --strip-components=1
  rm -rf "$tmp"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> Downloading Android command-line tools"
  tmp=$(mktemp -d)
  curl -fL --retry 3 -o "$tmp/clt.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CLT_VER}_latest.zip"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
export ANDROID_HOME="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"

echo "==> Accepting licenses"
yes | sdkmanager --licenses >/dev/null 2>&1 || true

echo "==> Installing SDK packages"
sdkmanager --install \
  "platform-tools" \
  "platforms;android-${API}" \
  "platforms;android-37.2" \
  "build-tools;36.0.0" \
  "emulator" \
  "$IMG"

if ! avdmanager list avd 2>/dev/null | grep -q 'Name: reader'; then
  echo "==> Creating AVD 'reader'"
  echo no | avdmanager create avd -n reader -k "$IMG" -d pixel_7 --force >/dev/null
  cfg="$HOME/.config/.android/avd/reader.avd/config.ini"
  [ -f "$cfg" ] || cfg="$HOME/.android/avd/reader.avd/config.ini"
  if [ -f "$cfg" ]; then
    sed -i 's/^hw.keyboard=.*/hw.keyboard=yes/' "$cfg"
    grep -q '^hw.keyboard=' "$cfg" || echo 'hw.keyboard=yes' >> "$cfg"
    grep -q '^hw.ramSize=' "$cfg" || echo 'hw.ramSize=4096' >> "$cfg"
    grep -q '^disk.dataPartition.size=' "$cfg" || echo 'disk.dataPartition.size=6G' >> "$cfg"
  fi
fi

echo "==> Done. Add to your shell profile:"
echo "  export JAVA_HOME=$JDK_DIR"
echo "  export ANDROID_HOME=$SDK"
echo "  export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$PATH"
