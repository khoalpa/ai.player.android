package com.aiplayer.android;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BrowserNavigationPolicy {
    private BrowserNavigationPolicy() {
    }

    static boolean isIncomingWebUrl(String rawUrl) {
        URI uri = parseUri(rawUrl);
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    static boolean canLoadInWebView(String rawUrl) {
        URI uri = parseUri(rawUrl);
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return true;
        }
        return "http".equalsIgnoreCase(scheme)
                && BrowserUrlUtils.shouldExtractMediaFromPage(rawUrl);
    }

    static boolean shouldOpenHttpPageExternally(String rawUrl) {
        URI uri = parseUri(rawUrl);
        return uri != null
                && "http".equalsIgnoreCase(uri.getScheme())
                && !BrowserUrlUtils.isDirectMediaUrl(rawUrl)
                && !BrowserUrlUtils.shouldExtractMediaFromPage(rawUrl);
    }

    static boolean canInjectExtractableNavigationBridge(String rawUrl) {
        URI uri = parseUri(rawUrl);
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            return false;
        }
        String host = uri.getHost();
        return host != null && !host.trim().isEmpty();
    }

    static List<String> alternateHttpsUrls(URI failedUri) {
        List<String> candidates = new ArrayList<>();
        if (failedUri == null) {
            return candidates;
        }
        String scheme = failedUri.getScheme();
        String host = failedUri.getHost();
        if (!"https".equalsIgnoreCase(scheme) || host == null || host.trim().isEmpty()) {
            return candidates;
        }

        String lowerHost = host.toLowerCase(Locale.US);
        if (lowerHost.startsWith("www.")) {
            candidates.add(rebuildUrl(failedUri, "https", host.substring(4)));
        } else {
            candidates.add(rebuildUrl(failedUri, "https", "www." + host));
        }
        return candidates;
    }

    static List<String> alternateHttpsUrls(String rawUrl) {
        URI uri = parseUri(rawUrl);
        return uri == null ? new ArrayList<>() : alternateHttpsUrls(uri);
    }

    private static String rebuildUrl(URI uri, String scheme, String host) {
        StringBuilder builder = new StringBuilder();
        builder.append(scheme).append("://").append(host);
        if (uri.getPort() >= 0) {
            builder.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        builder.append(path == null || path.isEmpty() ? "/" : path);
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            builder.append('?').append(query);
        }
        String fragment = uri.getRawFragment();
        if (fragment != null && !fragment.isEmpty()) {
            builder.append('#').append(fragment);
        }
        return builder.toString();
    }

    private static URI parseUri(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return null;
        }
        try {
            return URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
