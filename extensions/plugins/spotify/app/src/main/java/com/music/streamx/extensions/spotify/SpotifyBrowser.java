package com.music.streamx.extensions.spotify;

import java.net.URI;
import android.webkit.PermissionRequest;
import android.webkit.WebView;

/** Common browser setup for the visible login and private metadata session. */
final class SpotifyBrowser {
    static void configure(WebView view) {
        // Keep the installed Chromium version instead of advertising a stale desktop build.
        String agent = view.getSettings().getUserAgentString();
        view.getSettings().setUserAgentString(agent.replace("; wv", "").replace("Version/4.0 ", ""));
        view.getSettings().setMediaPlaybackRequiresUserGesture(true);
    }
    static void configureRenderer(WebView view) {
        configureDesktopRenderer(view);
    }
    static void configureDesktop(WebView view, boolean desktop) {
        if (!desktop) {
            String agent = view.getSettings().getUserAgentString();
            if (agent.contains("X11; Linux x86_64")) {
                view.getSettings().setUserAgentString(null);
            }
            configure(view);
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) {
                androidx.webkit.UserAgentMetadata current = androidx.webkit.WebSettingsCompat.getUserAgentMetadata(view.getSettings());
                androidx.webkit.WebSettingsCompat.setUserAgentMetadata(view.getSettings(), new androidx.webkit.UserAgentMetadata.Builder(current)
                    .setPlatform("Android").setMobile(true).build());
            }
            view.getSettings().setUseWideViewPort(false);
            view.getSettings().setLoadWithOverviewMode(false);
            return;
        }
        configure(view);
        java.util.regex.Matcher version = java.util.regex.Pattern.compile("Chrome/([0-9.]+)").matcher(view.getSettings().getUserAgentString());
        if (version.find()) view.getSettings().setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + version.group(1) + " Safari/537.36");
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) {
            androidx.webkit.UserAgentMetadata current = androidx.webkit.WebSettingsCompat.getUserAgentMetadata(view.getSettings());
            androidx.webkit.WebSettingsCompat.setUserAgentMetadata(view.getSettings(), new androidx.webkit.UserAgentMetadata.Builder(current)
                .setPlatform("Linux").setPlatformVersion("").setArchitecture("x86").setBitness(64)
                .setModel("").setMobile(false).setWow64(false).build());
        }
        view.getSettings().setUseWideViewPort(true);
        view.getSettings().setLoadWithOverviewMode(true);
        view.getSettings().setSupportZoom(true);
        view.getSettings().setBuiltInZoomControls(true);
        view.getSettings().setDisplayZoomControls(false);
    }
    static void configureDesktopRenderer(WebView view) {
        configure(view);
        java.util.regex.Matcher version = java.util.regex.Pattern.compile("Chrome/([0-9.]+)").matcher(view.getSettings().getUserAgentString());
        if (version.find()) view.getSettings().setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + version.group(1) + " Safari/537.36");
        // A desktop UA string alone can still advertise Android/mobile through
        // Chromium's client hints. Keep both halves of the desktop-site profile consistent.
        if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) {
            androidx.webkit.UserAgentMetadata current=androidx.webkit.WebSettingsCompat.getUserAgentMetadata(view.getSettings());
            androidx.webkit.WebSettingsCompat.setUserAgentMetadata(view.getSettings(),new androidx.webkit.UserAgentMetadata.Builder(current)
                .setPlatform("Linux").setPlatformVersion("").setArchitecture("x86").setBitness(64)
                .setModel("").setMobile(false).setWow64(false).build());
        }
        view.getSettings().setUseWideViewPort(true);
        view.getSettings().setLoadWithOverviewMode(true);
        view.getSettings().setMediaPlaybackRequiresUserGesture(false);
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1280, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1280, 900);
        view.onResume();
    }
    static boolean protectedMediaAllowed(String origin, String resource) {
        try {
            URI uri = URI.create(origin);
            return "https".equals(uri.getScheme()) && "open.spotify.com".equals(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443)
                && PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource);
        } catch (Exception ignored) { return false; }
    }
    static void permission(PermissionRequest request) {
        // Grant only the DRM identifier, never microphone/camera or future resources.
        for (String resource : request.getResources()) {
            if (protectedMediaAllowed(request.getOrigin().toString(), resource)) {
                request.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID}); return;
            }
        }
        request.deny();
    }
}
