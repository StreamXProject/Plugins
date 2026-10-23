package com.music.streamx.extensions.spotify;

import org.json.JSONObject;

/** Remote Connect metadata can precede local startup; only stable local audio proves a mismatch. */
final class SpotifySelectionGate {
    private long since = -1;
    void reset() { since = -1; }
    boolean failed(JSONObject status, boolean requested, long now) {
        boolean mismatch = status.has("observed_track_id") && !status.isNull("observed_track_id")
            && !status.optString("track_id", "").isEmpty()
            && !status.optString("track_id", "").equals(status.optString("observed_track_id", ""));
        boolean wrong = requested && (status.optBoolean("selection_restricted") || mismatch) && status.optBoolean("media_present")
            && "AUDIO".equals(status.optString("media_kind")) && (status.optInt("ready_state") >= 2 || status.optLong("position_ms") > 0) && !status.optBoolean("paused");
        if (!wrong) { reset(); return false; }
        if (since < 0 || now < since) since = now;
        return now - since >= 2500;
    }
}
