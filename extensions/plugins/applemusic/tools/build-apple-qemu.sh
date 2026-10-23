#!/usr/bin/env bash
# Packages QEMU's dependency closure in the APK, with guest images downloaded later.
set -euo pipefail
qemu_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$qemu_root"
python tools/apple-qemu/prepare.py
python tools/apple-qemu/package.py
qemu_ndk=${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/27.0.12077973}
"$qemu_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang++" \
 -std=c++11 -O2 -static-libstdc++ -Wl,-z,max-page-size=16384 tools/apple-qemu/launcher.cpp \
 -o app/src/main/jniLibs/arm64-v8a/libwrapper_exec.so
