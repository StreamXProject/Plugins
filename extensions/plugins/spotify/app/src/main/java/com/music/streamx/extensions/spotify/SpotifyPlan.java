package com.music.streamx.extensions.spotify;

import org.json.JSONObject;
import java.util.Locale;

/** Only classify explicit server products; playback success and selected quality are not plan evidence. */
final class SpotifyPlan {
    static boolean permitsPlayback(JSONObject plan) {
        return java.util.Set.of("premium", "platinum").contains(plan.optString("tier", "unknown"));
    }
    static JSONObject parse(JSONObject response) throws Exception {
        JSONObject account = SpotifyGraph.at(response, "data", "me", "account");
        if (account == null || response.optJSONArray("errors") != null && response.getJSONArray("errors").length() > 0)
            throw new IllegalStateException("Spotify did not report your plan");
        String product = account.optString("product", "").toUpperCase(Locale.ROOT);
        String tier = switch (product) {
            case "FREE" -> "free";
            case "PREMIUM", "FAMILY", "STUDENT", "DUO" -> "premium";
            case "PLATINUM", "PREMIUM_PLATINUM" -> "platinum";
            default -> "unknown";
        };
        return new JSONObject().put("tier", tier).put("exact_tier", tier.equals("free") || tier.equals("platinum"));
    }
}
