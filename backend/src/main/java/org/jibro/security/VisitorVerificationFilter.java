package org.jibro.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Set;

import org.jibro.global.config.SecurityConfig;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class VisitorVerificationFilter extends OncePerRequestFilter {
    private static final Set<String> BOOTSTRAP = Set.of("/api/v1/health", "/api/v1/auth/csrf", "/api/v1/security/visitor");
    private final VisitorVerification visitor;

    public VisitorVerificationFilter(VisitorVerification visitor) {
        this.visitor = visitor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !visitor.enabled || BOOTSTRAP.contains(path)
                || !(path.startsWith("/api/") || path.startsWith("/oauth2/authorization/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!visitor.verified(request)) {
            response.setHeader("Cache-Control", "no-store");
            SecurityConfig.error(response, 403, "HUMAN_VERIFICATION_REQUIRED", "사이트 이용을 위해 사람 확인이 필요해요.");
            return;
        }
        chain.doFilter(request, response);
    }
}
