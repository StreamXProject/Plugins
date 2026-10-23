# librespot source

The adjacent MIT-licensed source is pinned to UPSTREAM_COMMIT from
https://github.com/librespot-org/librespot. The upstream LICENSE is retained.

One integration patch replaces the non-Premium process::exit in core/src/session.rs
with a return. The Android wrapper independently rejects non-Premium or unknown
account attributes before creating the audio player. This prevents a library
restriction from terminating the extension process; it does not lift the restriction.
No client identity, operating-system spoofing, licence decoder or private Echo
library is added. Native sessions use the existing signed-in user's access token.

The playback worker's process::exit calls are changed to worker panics. A closed
player-event channel becomes a recoverable backend error and web fallback; invalid
player state cannot terminate the entire extension process. The PCM sink clears
JNI exceptions before returning an audio-output error.

A diagnostic patch carries a fixed failure-stage string from the track loader to
the Unavailable event. It preserves the existing loading/decryption logic and
exports no raw upstream error, token, audio key or URL. The Android wrapper uses
this to distinguish a Premium entitlement check from subsequent track failures.

SpClient optionally accepts a verified browser bearer/client-token pair with a
bounded expiry for HTTP requests. StreamX uses this to keep the existing browser
authorization together rather than deriving a mobile login5 HTTP identity.
Expired pairs return an authentication error; defaults for other clients are unchanged.
This patch does not alter Premium checks, audio-key requests or audio decryption.
