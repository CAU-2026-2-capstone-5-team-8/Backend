package com.cau.capstone8.backend.account;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Runs before singleton creation, including database connections and seed runners. */
final class ProductionSafety {
    private ProductionSafety() {}
    static void validate(Environment env) {
        if (!env.matchesProfiles("production", "prod")) return;
        require("required".equals(env.getProperty("app.auth.mode", "required")), "Production requires authentication");
        require("http".equals(env.getProperty("ml.mode", "stub")), "Production requires HTTP ML mode");
        require(Arrays.stream(env.getActiveProfiles()).noneMatch(Set.of("demo", "local", "test", "local-assessment-bootstrap")::contains),
                "Production cannot use demo/local/test/bootstrap profiles");
        require("none".equalsIgnoreCase(env.getProperty("server.forward-headers-strategy", "none")),
                "Use explicit trusted proxies, not automatic forwarded-header rewriting");
        require(env.getProperty("server.tomcat.remoteip.remote-ip-header", "").isBlank()
                        && env.getProperty("server.tomcat.remoteip.protocol-header", "").isBlank(),
                "Production cannot enable Tomcat RemoteIpValve header rewriting");
        String address = env.getProperty("ml.base-url", "");
        try {
            URI uri = URI.create(address);
            require(Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPath().isEmpty() || uri.getPath().equals("/")), "Production requires an explicit ML origin");
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Production requires an explicit ML origin");
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
