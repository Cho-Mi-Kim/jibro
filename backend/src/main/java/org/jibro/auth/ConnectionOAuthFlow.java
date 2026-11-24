package org.jibro.auth;

import java.io.Serializable;
import java.util.UUID;

import jakarta.servlet.http.*;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Keeps the link purpose inside Spring's state-validated request, never in a callback query flag.
 */
final class ConnectionOAuthFlow implements OAuth2AuthorizationRequestResolver, AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    static final String PENDING = ConnectionOAuthFlow.class.getName() + ".pending";
    static final String BINDING = ConnectionOAuthFlow.class.getName() + ".binding";
    static final String CALLBACK = ConnectionOAuthFlow.class.getName() + ".callback";

    record Link(UUID intent, String provider) implements Serializable {
    }

    private final DefaultOAuth2AuthorizationRequestResolver resolver;
    private final HttpSessionOAuth2AuthorizationRequestRepository repository = new HttpSessionOAuth2AuthorizationRequestRepository();
    private final AccountConnections connections;

    ConnectionOAuthFlow(ClientRegistrationRepository registrations, AccountConnections connections) {
        resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        this.connections = connections;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return decorate(request, resolver.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return decorate(request, resolver.resolve(request, registrationId));
    }

    private OAuth2AuthorizationRequest decorate(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
        if (authorization == null) return null;
        if (!"1".equals(request.getParameter("connect"))) {
            cancel(request);
            return authorization;
        }
        try {
            var link = pending(request);
            String provider = authorization.getAttribute("registration_id");
            if (link == null || !link.provider.equals(provider)) throw AccountConnections.invalidIntent();
            connections.authorize(link.intent, provider, binding(request), authorization.getState());
            return OAuth2AuthorizationRequest.from(authorization).attributes(a -> a.put(CALLBACK, link))
                    .additionalParameters(p -> p.put("prompt", "select_account")).build();
        } catch (RuntimeException ex) {
            cancel(request);
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_connection"));
        }
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return repository.loadAuthorizationRequest(request);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization, HttpServletRequest request, HttpServletResponse response) {
        repository.saveAuthorizationRequest(authorization, request, response);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        var authorization = repository.removeAuthorizationRequest(request, response);
        if (authorization != null && authorization.getAttribute(CALLBACK) instanceof Link link)
            request.setAttribute(CALLBACK, link);
        return authorization;
    }

    static Link callback(HttpServletRequest request) {
        return request.getAttribute(CALLBACK) instanceof Link link ? link : null;
    }

    static Link pending(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(PENDING) instanceof Link link ? link : null;
    }

    static String binding(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(BINDING) instanceof String value ? value : "";
    }

    void cancel(HttpServletRequest request) {
        var link = pending(request);
        if (link != null) connections.cancel(link.intent);
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(PENDING);
            session.removeAttribute(BINDING);
        }
    }
}
