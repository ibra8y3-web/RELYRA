#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT/app/build/generated/mls-jniLibs}"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if ! command -v cargo >/dev/null 2>&1 || ! command -v rustup >/dev/null 2>&1; then
  echo "Rust and rustup are required to build RELYRA MLS." >&2
  exit 2
fi

if [[ -z "$SDK_ROOT" || ! -d "$SDK_ROOT" ]]; then
  echo "ANDROID_HOME or ANDROID_SDK_ROOT must point to an installed Android SDK." >&2
  exit 2
fi

NDK_ROOT="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  NDK_ROOT="$(find "$SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
fi
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  echo "Android NDK not found. Install an NDK side-by-side with Android SDK Manager." >&2
  exit 2
fi
export ANDROID_NDK_HOME="$NDK_ROOT"

if ! command -v cargo-ndk >/dev/null 2>&1; then
  cargo install cargo-ndk --version 4.1.2 --locked
fi

rustup target add aarch64-linux-android x86_64-linux-android
mkdir -p "$OUT_DIR"

(
  cd "$ROOT/native/mls"
  cargo ndk \
    -t arm64-v8a \
    -t x86_64 \
    -p 29 \
    -o "$OUT_DIR" \
    build --release
)

test -s "$OUT_DIR/arm64-v8a/librelyra_mls.so"
test -s "$OUT_DIR/x86_64/librelyra_mls.so"
echo "RELYRA OpenMLS native libraries built for arm64-v8a and x86_64."
