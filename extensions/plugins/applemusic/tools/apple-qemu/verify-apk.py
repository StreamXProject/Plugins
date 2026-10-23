#!/usr/bin/env python3
"""Reject release packaging that rewrites the patched QEMU ELF libraries."""
import argparse
import hashlib
from pathlib import Path
from zipfile import ZipFile


def verify(apk: Path) -> None:
    root = Path(__file__).resolve().parents[2]
    native = root / 'app/src/main/jniLibs/arm64-v8a'
    sources = sorted(native.glob('libsxq_*.so')) + [
        native / 'libqemu_exec.so', native / 'libwrapper_exec.so',
    ]
    if len(sources) <= 2:
        raise ValueError('Build the pinned QEMU runtime before verifying its APK')
    with ZipFile(apk) as archive:
        for source in sources:
            packaged = archive.read('lib/arm64-v8a/' + source.name)
            if hashlib.sha256(packaged).digest() != hashlib.sha256(source.read_bytes()).digest():
                raise ValueError(f'{apk.name}: {source.name} was changed during packaging; preserve patched QEMU libraries with keepDebugSymbols')
    print(f'{apk.name}: {len(sources)} QEMU native files preserved byte-for-byte')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path, nargs='+')
    for apk in parser.parse_args().apk:
        verify(apk)
