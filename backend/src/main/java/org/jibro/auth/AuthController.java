package org.jibro.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

import org.springframework.core.env.Environment;
import org.jibro.global.common.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService service;
    private final RefreshCookie refreshCookie;
    private final boolean trialEnabled;

    public AuthController(AuthService service, RefreshCookie refreshCookie, Environment environment) {
        this.trialEnabled = environment.matchesProfiles("local-sample");
        this.service = service;
        this.refreshCookie = refreshCookie;
    }

    public record Signup(@NotBlank @Size(max = 30) String name, @NotBlank @Email @Size(max = 254) String email,
                         @NotBlank @Size(min = 10, max = 72) String password) {
    }

    public record Login(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {
    }

    public record Session(String accessToken, AuthService.User user) {
    }

    @GetMapping("/auth/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping("/auth/signup")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    void signup(@Valid @RequestBody Signup body) {
        service.signup(body.name(), body.email(), body.password());
    }

    @PostMapping("/auth/login")
    Session login(@Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        // No caller-supplied X-Forwarded-For/X-Real-IP is trusted here. A deployment using a
        // reverse proxy must configure its trusted forwarding boundary separately.
        var tokens = service.login(body.email(), body.password(), request.getRemoteAddr());
        clearOAuthSession(request);
        return session(tokens, response);
    }

    @PostMapping("/auth/trial")
    Session trial(@CookieValue(name = "jibro_refresh", required = false) String token, HttpServletRequest request, HttpServletResponse response) {
        if (!trialEnabled) throw ApiException.notFound();
        var tokens = service.startTrial(token);
        clearOAuthSession(request);
        return session(tokens, response);
    }

    @PostMapping("/auth/refresh")
    Session refresh(@CookieValue(name = "jibro_refresh", required = false) String token, HttpServletResponse response) {
        try {
            return session(service.refresh(token), response);
        } catch (ApiException failure) {
            // A stale HttpOnly cookie must not trap the browser in refresh/trial retries.
            // Keep rejecting its token; only clear the unusable client credential.
            if (failure.status == 401) refreshCookie.clear(response);
            throw failure;
        }
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void logout(@CookieValue(name = "jibro_refresh", required = false) String token, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request, HttpServletResponse response) {
        service.logout(token, jwt);
        clearOAuthSession(request);
        refreshCookie.clear(response);
    }

    @GetMapping("/users/me")
    AuthService.User me(@AuthenticationPrincipal Jwt jwt) {
        return service.user(jwt.getSubject());
    }

    private Session session(AuthService.Tokens tokens, HttpServletResponse response) {
        refreshCookie.write(response, tokens.refresh());
        return new Session(tokens.accessToken(), tokens.user());
    }

    private void clearOAuthSession(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }

}
