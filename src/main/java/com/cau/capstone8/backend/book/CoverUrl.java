package com.cau.capstone8.backend.book;

import java.net.URI;
import java.net.URISyntaxException;

/** Syntax-only projection of stored reviewed URLs; never performs network I/O. */
public final class CoverUrl {
    private CoverUrl() {}

    public static String reviewed(String image, String source) {
        return eligible(image) && eligible(source) ? image : null;
    }

    private static boolean eligible(String value) {
        if (value == null || value.length() > 2048) return false;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null
                    && host.matches("(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,63}")
                    && !host.equalsIgnoreCase("localhost") && !host.toLowerCase(java.util.Locale.ROOT).endsWith(".localhost")
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (URISyntaxException ignored) {
            return false;
        }
    }
}
