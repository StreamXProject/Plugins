# Embedded terminal

Only terminal-view and terminal-emulator source/resources/JNI are vendored from
https://github.com/termux/termux-app at commit 8629e632fcb95da272221be327db653fb24befe9.
The Termux application, bootstrap and package manager are not included.

Upstream license is retained in upstream/LICENSE.md; retain source headers and
its Apache-2.0 exceptions when redistributing. These sources remain available in
this directory with the extension source.

The build adapts TerminalSession to public ParcelFileDescriptor APIs, replacing
private FileDescriptor reflection and System.exit, and gives reader/writer
threads independent duplicated descriptors. The PTY JNI is linked for 16 KB
Android pages. New integration code is Kotlin.
