package com.music.streamx.cloudstream;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Bounded diagnostic events. Callers pass fixed messages, never credentials or raw driver errors. */
final class EngineLog {
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    static synchronized void add(String message) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message;
        if (lines.size() == 160) lines.removeFirst();
        lines.addLast(line);
        android.util.Log.i("CloudStream", message);
    }
    static synchronized String snapshot() { return String.join("\n", lines); }
    static synchronized void clear() { lines.clear(); }
}
