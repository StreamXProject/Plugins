#!/usr/bin/env bash
set -euo pipefail
# Run from Gradle; no tokens, keys or account data are build inputs.
TASK_NATIVE_ROOT=$(cd "$(dirname "$0")/../native" && pwd)
TASK_NDK=${1:?NDK directory is required}
TASK_JNI_OUTPUT=${2:?JNI output directory is required}
TASK_ABIS=${3:-arm64-v8a,armeabi-v7a,x86_64}
TASK_CARGO=${SPOTIFY_CARGO:-cargo}
if [[ -z "${SPOTIFY_CARGO:-}" ]] && ! command -v cargo >/dev/null 2>&1; then
  TASK_CARGO="${CARGO_HOME:-$HOME/.cargo}/bin/cargo"
fi
if ! command -v "$TASK_CARGO" >/dev/null 2>&1; then
  echo "Spotify native build requires Rust/Cargo. Install Rust with rustup or set SPOTIFY_CARGO." >&2
  exit 2
fi
# Cargo invokes rustc by name; include the selected installation's proxy directory.
TASK_CARGO=$(command -v "$TASK_CARGO")
export PATH="$(dirname "$TASK_CARGO"):$PATH"
export CARGO_BUILD_JOBS=${CARGO_BUILD_JOBS:-3}
TASK_CLANG="$TASK_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -d "$TASK_CLANG" ]]; then
  TASK_CLANG="$TASK_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
fi
# Fail before compiling dependencies when an Android standard library is missing.
TASK_MISSING_TARGETS=()
for TASK_ABI in ${TASK_ABIS//,/ }; do
  case "$TASK_ABI" in
    arm64-v8a) TASK_TARGET=aarch64-linux-android;;
    armeabi-v7a) TASK_TARGET=armv7-linux-androideabi;;
    x86_64) TASK_TARGET=x86_64-linux-android;;
    *) echo "Unsupported Spotify native ABI: $TASK_ABI" >&2; exit 2;;
  esac
  TASK_LIBDIR=$(rustc --print target-libdir --target "$TASK_TARGET")
  if ! compgen -G "$TASK_LIBDIR/libcore-*.rlib" >/dev/null; then
    TASK_MISSING_TARGETS+=("$TASK_TARGET")
  fi
done
if (( ${#TASK_MISSING_TARGETS[@]} )); then
  echo "Missing Rust Android target libraries for the active toolchain." >&2
  echo "Run: rustup target add ${TASK_MISSING_TARGETS[*]}" >&2
  exit 2
fi
for TASK_ABI in ${TASK_ABIS//,/ }; do
  case "$TASK_ABI" in
    arm64-v8a) TASK_TARGET=aarch64-linux-android; TASK_LINKER=aarch64-linux-android26-clang;;
    armeabi-v7a) TASK_TARGET=armv7-linux-androideabi; TASK_LINKER=armv7a-linux-androideabi26-clang;;
    x86_64) TASK_TARGET=x86_64-linux-android; TASK_LINKER=x86_64-linux-android26-clang;;
    *) exit 2;;
  esac
  TASK_KEY=${TASK_TARGET//-/_}
  export "CC_$TASK_KEY=$TASK_CLANG/$TASK_LINKER"
  export "AR_$TASK_KEY=$TASK_CLANG/llvm-ar"
  TASK_CARGO_KEY=$(printf '%s' "$TASK_KEY" | tr '[:lower:]' '[:upper:]')
  export "CARGO_TARGET_${TASK_CARGO_KEY}_LINKER=$TASK_CLANG/$TASK_LINKER"
  # Android 15/16 devices can use 16 KiB pages; align native ELF segments accordingly.
  export "CARGO_TARGET_${TASK_CARGO_KEY}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
  "$TASK_CARGO" build --locked --manifest-path "$TASK_NATIVE_ROOT/Cargo.toml" --release --target "$TASK_TARGET"
  mkdir -p "$TASK_JNI_OUTPUT/$TASK_ABI"
  cp "$TASK_NATIVE_ROOT/target/$TASK_TARGET/release/libstreamx_spotify_native.so" "$TASK_JNI_OUTPUT/$TASK_ABI/"
done
