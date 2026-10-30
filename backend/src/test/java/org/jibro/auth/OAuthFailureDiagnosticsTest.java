package org.jibro.auth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class OAuthFailureDiagnosticsTest {
    @Test
    void reportsSafeCodesAndCauseWithoutProviderResponseOrCredentials() {
        var failure = new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token_response"),
                "token=secret-value code=private-code email=private@example.invalid KOE010",
                new java.net.SocketTimeoutException("url=https://example.invalid/?client_secret=secret-value"));
        assertEquals("code=invalid_token_response providerCode=KOE010 cause=SocketTimeoutException reason=unspecified",
                OAuthFailureDiagnostics.summarize(failure));
    }

    @Test
    void rejectsArbitraryErrorCodeText() {
        var failure = new OAuth2AuthenticationException(new OAuth2Error("secret-value\ninjected-log"));
        assertEquals("code=unknown providerCode=none cause=OAuth2AuthenticationException reason=unspecified",
                OAuthFailureDiagnostics.summarize(failure));
    }

    @Test
    void classifiesProviderBodyWithoutLoggingTokenOrCallerIp() {
        var failure = new org.springframework.web.client.HttpClientErrorException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized",
                "{\"code\":-401,\"msg\":\"ip mismatched callerIp=private-ip token=private-token\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("code=unknown providerCode=-401 cause=HttpClientErrorException reason=ip_restricted",
                OAuthFailureDiagnostics.summarize(failure));
    }
}
