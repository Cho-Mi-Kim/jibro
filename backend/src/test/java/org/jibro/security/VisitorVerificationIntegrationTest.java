package org.jibro.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.turnstile.enabled=true", "app.turnstile.site-key=real-site-key", "app.turnstile.secret-key=real-secret-key"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VisitorVerificationIntegrationTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    TurnstileVerifier verifier;

    @Test
    void directPublicApiIsBlockedButBootstrapAndHealthStayReachable() throws Exception {
        mvc.perform(get("/api/v1/security/visitor").servletPath("/api/v1/security/visitor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.siteKey").value("real-site-key"));
        mvc.perform(get("/api/v1/auth/csrf").servletPath("/api/v1/auth/csrf")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/meta").servletPath("/api/v1/meta"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HUMAN_VERIFICATION_REQUIRED"));
        mvc.perform(get("/oauth2/authorization/google").servletPath("/oauth2/authorization/google"))
                .andExpect(status().isForbidden());
    }

    @Test
    void verificationRequiresCsrfValidTokenAndOnlyThenAllowsPublicApiNotPrivateApi() throws Exception {
        mvc.perform(post("/api/v1/security/visitor").servletPath("/api/v1/security/visitor")
                .contentType("application/json").content("{\"token\":\"valid\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(verifier);
        var response = mvc.perform(post("/api/v1/security/visitor").servletPath("/api/v1/security/visitor").with(csrf())
                        .contentType("application/json").content("{\"token\":\"valid\"}"))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        verify(verifier).verify("valid");
        var cookie = response.getCookie(VisitorVerification.COOKIE);
        assertNotNull(cookie);
        mvc.perform(get("/api/v1/security/visitor").servletPath("/api/v1/security/visitor").cookie(cookie))
                .andExpect(jsonPath("$.verified").value(true));
        mvc.perform(get("/api/v1/meta").servletPath("/api/v1/meta").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me").servletPath("/api/v1/users/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectedAndMalformedTokensNeverIssueAPass() throws Exception {
        doThrow(new ApiException(403, "HUMAN_VERIFICATION_FAILED", "다시 확인해 주세요.")).when(verifier).verify("replayed");
        mvc.perform(post("/api/v1/security/visitor").servletPath("/api/v1/security/visitor").with(csrf())
                        .contentType("application/json").content("{\"token\":\"replayed\"}"))
                .andExpect(status().isForbidden()).andExpect(cookie().doesNotExist(VisitorVerification.COOKIE));
        mvc.perform(post("/api/v1/security/visitor").servletPath("/api/v1/security/visitor").with(csrf())
                        .contentType("application/json").content("{\"token\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(cookie().doesNotExist(VisitorVerification.COOKIE));
        mvc.perform(get("/api/v1/meta").servletPath("/api/v1/meta").cookie(new Cookie(VisitorVerification.COOKIE, "forged")))
                .andExpect(status().isForbidden());
    }
}
