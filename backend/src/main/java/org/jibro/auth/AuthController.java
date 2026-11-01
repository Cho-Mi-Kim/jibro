package org.jibro.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService service;
    private final boolean secure;

    public AuthController(AuthService service, @Value("${app.auth.secure-cookie:true}") boolean secure) {
        this.service = service;
        this.secure = secure;
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
    Session login(@Valid @RequestBody Login body, HttpServletResponse response) {
        return session(service.login(body.email(), body.password()), response);
    }

    @PostMapping("/auth/refresh")
    Session refresh(@CookieValue(name = "jibro_refresh", required = false) String token, HttpServletResponse response) {
        return session(service.refresh(token), response);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void logout(@CookieValue(name = "jibro_refresh", required = false) String token, HttpServletResponse response) {
        service.logout(token);
        cookie(response, "", 0);
    }

    @GetMapping("/users/me")
    AuthService.User me(@AuthenticationPrincipal Jwt jwt) {
        return service.user(jwt.getSubject());
    }

    public Session session(AuthService.Tokens tokens, HttpServletResponse response) {
        cookie(response, tokens.refresh(), 7 * 24 * 60 * 60);
        return new Session(tokens.accessToken(), tokens.user());
    }

    private void cookie(HttpServletResponse response, String value, long age) {
        response.addHeader("Set-Cookie", ResponseCookie.from("jibro_refresh", value).httpOnly(true).secure(secure).sameSite("Lax").path("/api/v1/auth").maxAge(age).build().toString());
    }
}
