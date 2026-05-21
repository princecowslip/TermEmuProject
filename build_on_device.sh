#!/usr/bin/env bash
# build_on_device.sh
# Bootstrap compilation script for Termux on-device builds.
# Checks for required tools, sets native environment flags, and triggers assembleDebug.

set -euo pipefail

echo "==> TermEmuProject: On-Device Build Bootstrap"

# Verify Termux environment
if [[ -z "${TERMUX_VERSION:-}" ]]; then
  echo "WARNING: TERMUX_VERSION not set. Ensure you are running inside Termux."
fi

# Check for required tools
for tool in ninja cmake java; do
  if ! command -v "$tool" &>/dev/null; then
    echo "ERROR: '$tool' not found. Install via: pkg install $tool"
    exit 1
  fi
done

echo "==> All required tools found."

# Resolve project root (directory of this script)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Isolate environment for clean native compilation
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/29.0.1}"
export PATH="$ANDROID_HOME/tools/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "==> ANDROID_HOME:     $ANDROID_HOME"
echo "==> ANDROID_NDK_HOME: $ANDROID_NDK_HOME"

# Set C++ feature flags for rtti + exceptions (required by Oboe / FFmpeg)
export ANDROID_CPP_FEATURES="rtti exceptions"

# Run Gradle assembleDebug using the local wrapper
echo "==> Running: ./gradlew assembleDebug"
./gradlew assembleDebug \
  --no-daemon \
  --stacktrace \
  -Pandroid.native.buildOutput=verbose

echo ""
echo "==> Build complete. APK located in:"
echo "    app/build/outputs/apk/debug/app-debug.apk"
