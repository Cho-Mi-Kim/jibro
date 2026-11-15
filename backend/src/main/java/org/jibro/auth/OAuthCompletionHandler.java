package org.jibro.auth;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.*;

final class OAuthCompletionHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(OAuthCompletionHandler.class);
    private final AuthService auth;
    private final RefreshCookie refreshCookie;
    private final AccountConnections connections;
    private final String frontend;

    OAuthCompletionHandler(AuthService auth, RefreshCookie refreshCookie, AccountConnections connections, String frontend) {
        this.auth = auth;
        this.refreshCookie = refreshCookie;
        this.connections = connections;
        this.frontend = frontend;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        var link = ConnectionOAuthFlow.callback(request);
        boolean linking = link != null || ConnectionOAuthFlow.pending(request) != null;
        String destination;
        try {
            var token = (OAuth2AuthenticationToken) authentication;
            String provider = token.getAuthorizedClientRegistrationId();
            if (linking) {
                if (link == null) throw AccountConnections.invalidIntent();
                connections.complete(link.intent(), provider, token.getName(), ConnectionOAuthFlow.binding(request), request.getParameter("state"));
                destination = "/settings?connection=success";
            } else {
                Map<String, Object> attrs = token.getPrincipal().getAttributes();
                String email;
                String name;
                if (provider.equals("google")) {
                    email = Boolean.TRUE.equals(attrs.get("email_verified")) ? (String) attrs.get("email") : null;
                    name = (String) attrs.get("name");
                } else {
                    var account = attrs.get("kakao_account") instanceof Map<?, ?> map ? map : Map.of();
                    email = Boolean.TRUE.equals(account.get("is_email_valid")) && Boolean.TRUE.equals(account.get("is_email_verified")) ? (String) account.get("email") : null;
                    name = account.get("profile") instanceof Map<?, ?> profile ? (String) profile.get("nickname") : null;
                }
                refreshCookie.write(response, auth.social(provider, token.getName(), name, email).refresh());
                destination = "/auth/callback";
            }
        } catch (RuntimeException ex) {
            destination = linking ? "/settings?connection=failed" : "/login?oauthError=account";
        }
        finish(request);
        response.sendRedirect(frontend + destination);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        boolean linking = ConnectionOAuthFlow.pending(request) != null || ConnectionOAuthFlow.callback(request) != null;
        log.warn("OAuth authentication failed: {}", OAuthFailureDiagnostics.summarize(exception));
        finish(request);
        response.sendRedirect(frontend + (linking ? "/settings?connection=failed" : "/login?oauthError=provider"));
    }

    private void finish(HttpServletRequest request) {
        var link = ConnectionOAuthFlow.pending(request);
        if (link != null) connections.cancel(link.intent());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}
