package org.jibro.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Shared HTTP credential adapter for password and OAuth entry points.
 */
@Component
public class RefreshCookie {
    private final boolean secure;

    public RefreshCookie(@Value("${app.auth.secure-cookie:true}") boolean secure) {
        this.secure = secure;
    }

    public void write(HttpServletResponse response, String token) {
        set(response, token, 7 * 24 * 60 * 60);
    }

    public void clear(HttpServletResponse response) {
        set(response, "", 0);
    }

    private void set(HttpServletResponse response, String value, long age) {
        response.addHeader("Set-Cookie", ResponseCookie.from("jibro_refresh", value)
            .httpOnly(true).secure(secure).sameSite("Lax").path("/api/v1/auth")
            .maxAge(age).build().toString());
        response.setHeader("Cache-Control", "no-store");
    }
}
