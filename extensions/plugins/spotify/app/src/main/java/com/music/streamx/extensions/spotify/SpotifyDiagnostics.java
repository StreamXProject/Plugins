package com.music.streamx.extensions.spotify;

/** Diagnostic output deliberately excludes cookie and storage values. */
final class SpotifyDiagnostics {
    static String cookies(String header) {
        if (header == null || header.isEmpty()) return "none visible\n";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String entry : header.split(";")) {
            String[] pair = entry.trim().split("=", 2);
            if (pair.length != 2) continue;
            if (++count > 40) { out.append("…\n"); break; }
            out.append(pair[0].replaceAll("[^a-zA-Z0-9_.-]", "?").substring(0, Math.min(80, pair[0].length())))
                .append(": present (").append(pair[1].length()).append(" characters)\n");
        }
        return out.toString();
    }
}
