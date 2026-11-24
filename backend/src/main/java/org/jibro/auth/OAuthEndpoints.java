package org.jibro.auth;

import java.net.URI;
import java.util.Set;

import org.springframework.core.env.Environment;

/**
 * Configured origins prevent Host/forwarded headers from choosing an OAuth destination.
 */
record OAuthEndpoints(String frontend, String callbackBase) {
    static OAuthEndpoints from(Environment environment) {
        boolean production = environment.matchesProfiles("production");
        String frontend = origin(environment.getProperty("app.auth.frontend-url", "http://127.0.0.1:5173"), production);
        String configured = environment.getProperty("OAUTH_BASE_URL", "");
        String callback = origin(configured.isBlank()
                ? production ? frontend : "http://127.0.0.1:8080" : configured, production);
        if (production && !callback.equals(frontend))
            throw new IllegalStateException("Production OAuth must use the configured frontend origin");
        return new OAuthEndpoints(frontend, callback);
    }

    private static String origin(String value, boolean production) {
        try {
            URI uri = URI.create(value);
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
            boolean https = "https".equals(uri.getScheme());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || !(https || !production && loopback && "http".equals(uri.getScheme()))
                    || production && uri.getPort() != -1 && uri.getPort() != 443)
                throw new IllegalArgumentException();
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IllegalStateException("OAuth origins require HTTPS or a local loopback HTTP origin");
        }
    }
}
