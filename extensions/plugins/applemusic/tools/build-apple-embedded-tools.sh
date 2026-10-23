#!/usr/bin/env bash
# Executables are APK native libraries so target-SDK 36 may execute them.
set -euo pipefail
embedded_root=$(cd "$(dirname "$0")/.." && pwd)
embedded_ndk=${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/27.0.12077973}
for embedded_arch in arm64 amd64; do
 if [ "$embedded_arch" = arm64 ]; then embedded_abi=arm64-v8a; embedded_triple=aarch64-linux-android; else embedded_abi=x86_64; embedded_triple=x86_64-linux-android; fi
 embedded_dest="$embedded_root/app/src/main/jniLibs/$embedded_abi"
 mkdir -p "$embedded_dest"
 (cd "$embedded_root/native/audio-tools"
  export GOOS=android GOARCH="$embedded_arch" CGO_ENABLED=1 CC="$embedded_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${embedded_triple}26-clang"
  go build -trimpath -buildmode=pie -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -o "$embedded_dest/libstreamx_alac_exec.so" ./cmd/streamx-alac
  go build -trimpath -buildmode=pie -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -o "$embedded_dest/libamdl_exec.so" .
 )
done
