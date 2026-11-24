package org.jibro.global.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fail before starting an externally reachable server with local-only settings.
 */
@Configuration
@Profile("production")
public class ProductionConfiguration {
    public ProductionConfiguration(Environment env) {
        if (Arrays.asList(env.getActiveProfiles()).contains("local-sample"))
            throw new IllegalStateException("production cannot be combined with local-sample");
        validate(env.getProperty("app.auth.frontend-url", ""),
                env.getProperty("app.auth.secure-cookie", Boolean.class, false),
                env.getProperty("spring.datasource.url", ""), env.getProperty("app.auth.jwt-secret", ""));
    }

    static void validate(String frontend, boolean secure, String database, String secret) {
        URI origin;
        try {
            origin = URI.create(frontend);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Set a valid production FRONTEND_URL");
        }
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || !(origin.getPort() == -1 || origin.getPort() == 443)
                || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/")) || !secure)
            throw new IllegalStateException("Production requires an HTTPS origin and secure cookies");
        if (!database.startsWith("jdbc:postgresql://")
                || !database.matches(".*[?&]sslmode=verify-full(?:&.*)?$")
                || !database.matches(".*[?&]sslrootcert=[^&]+(?:&.*)?$"))
            throw new IllegalStateException("Production DB requires verified TLS and a trusted root certificate");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32 || secret.toUpperCase().contains("REPLACE"))
            throw new IllegalStateException("Set a unique production JWT_SECRET of at least 32 bytes");
    }
}
