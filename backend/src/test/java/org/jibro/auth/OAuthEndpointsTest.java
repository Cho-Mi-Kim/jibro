package org.jibro.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class OAuthEndpointsTest {
    @Test
    void pinsProductionCallbackToConfiguredPublicOrigin() {
        var environment = new MockEnvironment().withProperty("app.auth.frontend-url", "https://housing.example.invalid/");
        environment.setActiveProfiles("production");
        var endpoints = OAuthEndpoints.from(environment);
        assertEquals("https://housing.example.invalid", endpoints.callbackBase());
        assertEquals(endpoints.frontend(), endpoints.callbackBase());
        environment.setProperty("OAUTH_BASE_URL", "https://other.example.invalid");
        assertThrows(IllegalStateException.class, () -> OAuthEndpoints.from(environment));
    }

    @Test
    void localCallbackCanBeExplicitButUnsafeOriginsCannotBeUsed() {
        var environment = new MockEnvironment().withProperty("OAUTH_BASE_URL", "http://localhost:8080");
        assertEquals("http://localhost:8080", OAuthEndpoints.from(environment).callbackBase());
        for (String invalid : new String[]{"http://public.example.invalid", "https://user@host.invalid", "https://host.invalid/path", "https://host.invalid?token=x", "https://host.invalid#fragment"}) {
            environment.setProperty("OAUTH_BASE_URL", invalid);
            assertThrows(IllegalStateException.class, () -> OAuthEndpoints.from(environment));
        }
    }

    @Test
    void partialProviderConfigurationDoesNotSilentlyHideASetupFailure() {
        var environment = new MockEnvironment().withProperty("GOOGLE_CLIENT_ID", "client-id");
        assertThrows(IllegalStateException.class, () -> new OAuthConfig(environment));
        assertEquals(java.util.List.of(), new OAuthConfig(new MockEnvironment()).providers());
    }

    @Test
    void passwordAndSocialHttpEntryPointsUseTheSameProtectedCookieContract() {
        var response = new MockHttpServletResponse();
        var cookie = new RefreshCookie(true);
        cookie.write(response, "test-refresh-token");
        String header = response.getHeader("Set-Cookie");
        assertNotNull(header);
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("SameSite=Lax"));
        assertTrue(header.contains("Path=/api/v1/auth"));
        assertTrue(header.contains("Max-Age=604800"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        var cleared = new MockHttpServletResponse();
        cookie.clear(cleared);
        assertTrue(cleared.getHeader("Set-Cookie").contains("Max-Age=0"));
    }
}
