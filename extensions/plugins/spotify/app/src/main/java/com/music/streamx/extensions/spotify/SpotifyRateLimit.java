package com.music.streamx.extensions.spotify;

/** One cooldown shared by account confirmation, feeds, and provider instances. */
final class SpotifyRateLimit {
    private static long until;
    private static boolean serverSpecified;
    static final class Limited extends IllegalStateException {
        final long until;
        final boolean serverSpecified;
        Limited(long until, boolean serverSpecified, long now) {
            super(serverSpecified ? "Spotify asked us to wait " + remaining(until, now) + " seconds."
                : "Spotify could not verify this session (HTTP 429). Retry in " + remaining(until, now) + " seconds.");
            this.until = until; this.serverSpecified = serverSpecified;
        }
    }
    static long remaining(long until, long now) {
        if (until <= now) return 0;
        long delta = until - now;
        return delta / 1000 + (delta % 1000 == 0 ? 0 : 1);
    }
    static long deadline(String header, long now) {
        long seconds = 60;
        if (specified(header)) seconds = Math.max(1, Long.parseLong(header.trim()));
        if (seconds > (Long.MAX_VALUE - now) / 1000) return Long.MAX_VALUE;
        return now + seconds * 1000;
    }
    static boolean specified(String header) {
        try { return header != null && Long.parseLong(header.trim()) >= 0; } catch (NumberFormatException ignored) { return false; }
    }
    static synchronized void check(long now) {
        if (until > now) throw new Limited(until, serverSpecified, now);
    }
    static synchronized Limited record(String header, long now) {
        long next = deadline(header, now);
        if (next >= until) { until = next; serverSpecified = specified(header); }
        return new Limited(until, serverSpecified, now);
    }
}
