package org.jibro.security;

import static org.junit.jupiter.api.Assertions.*;

import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class VisitorVerificationTest {
    static MockEnvironment environment() {
        return new MockEnvironment().withProperty("app.turnstile.enabled", "true")
                .withProperty("app.turnstile.site-key", "real-site-key")
                .withProperty("app.turnstile.secret-key", "real-secret-key")
                .withProperty("app.auth.frontend-url", "https://jibro.live")
                .withProperty("app.auth.jwt-secret", "visitor-pass-test-key-at-least-32-bytes");
    }

    @Test
    void passCannotBeForgedReusedByAnotherBrowserOrKeptAfterExpiry() {
        var visitor = new VisitorVerification(environment());
        var pass = visitor.pass("browser-one", 100000);
        assertTrue(visitor.valid(pass, "browser-one", 100001));
        assertFalse(visitor.valid(pass, "browser-two", 100001));
        assertFalse(visitor.valid(pass + "x", "browser-one", 100001));
        assertFalse(visitor.valid("100001." + pass.split("\\.")[1], "browser-one", 100001));
        assertFalse(visitor.valid(pass, "browser-one", 99999));
        assertFalse(visitor.valid(pass, "browser-one", 100000 + VisitorVerification.LIFETIME));
        assertFalse(visitor.valid("bad.pass", "browser-one", 100001));
    }

    @Test
    void requiresAllConfigurationAndRefusesPublicDummyKeys() {
        assertThrows(IllegalStateException.class, () -> new VisitorVerification(environment().withProperty("app.turnstile.secret-key", "")));
        assertThrows(IllegalStateException.class, () -> new VisitorVerification(environment().withProperty("app.turnstile.site-key", "1x00000000000000000000AA")));
    }

    @Test
    void cloudflareSuccessMustMatchBothOurHostnameAndAction() {
        var verifier = new TurnstileVerifier(new ObjectMapper(), new VisitorVerification(environment()));
        assertDoesNotThrow(() -> verifier.validate("{\"success\":true,\"hostname\":\"jibro.live\",\"action\":\"site_entry\"}"));
        for (String body : new String[]{
                "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}",
                "{\"success\":true,\"hostname\":\"attacker.example\",\"action\":\"site_entry\"}",
                "{\"success\":true,\"hostname\":\"jibro.live\",\"action\":\"login\"}",
                "{\"success\":true,\"hostname\":\"jibro.live\"}"}) {
            assertEquals(403, assertThrows(ApiException.class, () -> verifier.validate(body)).status);
        }
        assertEquals(503, assertThrows(ApiException.class, () -> verifier.validate("not-json")).status);
    }

    @Test
    void visitorPassIsHostOnlyHttpOnlySecureAndNeverALoginCredential() {
        var visitor = new VisitorVerification(environment());
        var response = new MockHttpServletResponse();
        visitor.admit(new MockHttpServletRequest(), response);
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=/"));
        assertFalse(cookie.contains("Domain="));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
}
