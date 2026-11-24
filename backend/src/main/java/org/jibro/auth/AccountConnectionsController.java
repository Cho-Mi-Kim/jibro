package org.jibro.auth;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.jibro.global.common.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/member/connections")
public class AccountConnectionsController {
    public record Reauthenticate(@Size(max = 72) String password, @Size(max = 100) String code) {
        AccountConnections.Proof proof() {
            return new AccountConnections.Proof(password, code);
        }
    }

    private final AccountConnections connections;
    private final OAuthConfig config;

    public AccountConnectionsController(AccountConnections connections, OAuthConfig config) {
        this.connections = connections;
        this.config = config;
    }

    @GetMapping
    AccountConnections.Overview overview(@AuthenticationPrincipal Jwt jwt) {
        return connections.overview(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/proof")
    void proof(@AuthenticationPrincipal Jwt jwt) {
        connections.requestProof(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/{provider}/start")
    Map<String, String> start(@AuthenticationPrincipal Jwt jwt, @PathVariable String provider,
                              @Valid @RequestBody Reauthenticate body, HttpServletRequest request) {
        if (!config.providers().contains(provider))
            throw new ApiException(503, "PROVIDER_UNAVAILABLE", "이 환경에서는 해당 소셜 로그인이 설정되지 않았습니다.");
        String binding = AccountConnections.random();
        var intent = connections.begin(UUID.fromString(jwt.getSubject()), provider, binding, body.proof(), request.getRemoteAddr());
        var session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(ConnectionOAuthFlow.BINDING, binding);
        session.setAttribute(ConnectionOAuthFlow.PENDING, new ConnectionOAuthFlow.Link(intent, provider));
        return Map.of("authorizationUrl", "/oauth2/authorization/" + provider + "?connect=1");
    }

    @PostMapping("/{provider}/disconnect")
    void disconnect(@AuthenticationPrincipal Jwt jwt, @PathVariable String provider, @Valid @RequestBody Reauthenticate body, HttpServletRequest request) {
        connections.disconnect(UUID.fromString(jwt.getSubject()), provider, body.proof(), request.getRemoteAddr());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}
