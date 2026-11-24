package org.jibro.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * A short-lived visitor pass, separate from login and user permissions.
 */
@Service
public class VisitorVerification {
    static final String ACTION = "site_entry";
    static final String COOKIE = "jibro_visitor";
    static final long LIFETIME = 12 * 60 * 60;
    final boolean enabled;
    final String siteKey;
    final String secretKey;
    final String hostname;
    private final boolean secure;
    private final byte[] signingKey;

    public VisitorVerification(Environment environment) {
        enabled = environment.getProperty("app.turnstile.enabled", Boolean.class, false);
        siteKey = environment.getProperty("app.turnstile.site-key", "");
        secretKey = environment.getProperty("app.turnstile.secret-key", "");
        hostname = URI.create(environment.getProperty("app.auth.frontend-url", "http://127.0.0.1:5173")).getHost();
        secure = environment.getProperty("app.auth.secure-cookie", Boolean.class, true);
        signingKey = environment.getProperty("app.auth.jwt-secret", "").getBytes(StandardCharsets.UTF_8);
        if (enabled && (siteKey.isBlank() || secretKey.isBlank() || hostname == null || signingKey.length < 32))
            throw new IllegalStateException("Turnstile requires site/secret keys, FRONTEND_URL and JWT_SECRET");
        if (enabled && (siteKey.matches("[123]x0+.*") || secretKey.matches("[123]x0+.*")))
            throw new IllegalStateException("Use a real Turnstile widget; test keys are not visitor protection");
    }

    public boolean verified(HttpServletRequest request) {
        if (!enabled) return true;
        if (request.getCookies() == null) return false;
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName()))
                return valid(cookie.getValue(), request.getHeader("User-Agent"), Instant.now().getEpochSecond());
        }
        return false;
    }

    boolean valid(String value, String agent, long now) {
        if (value == null || value.length() > 256) return false;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) return false;
        try {
            long issued = Long.parseLong(parts[0]);
            return issued <= now && now - issued < LIFETIME && MessageDigest.isEqual(
                    signature(parts[0], agent).getBytes(StandardCharsets.US_ASCII),
                    parts[1].getBytes(StandardCharsets.US_ASCII));
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    String pass(String agent, long now) {
        return now + "." + signature(Long.toString(now), agent);
    }

    private String signature(String issued, String agent) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(
                    ("jibro-visitor-v1\n" + hostname + "\n" + issued + "\n" + (agent == null ? "" : agent))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Visitor pass signing failed", failure);
        }
    }

    void admit(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE,
                        pass(request.getHeader("User-Agent"), Instant.now().getEpochSecond()))
                .httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(LIFETIME).build().toString());
        response.setHeader("Cache-Control", "no-store");
    }
}
