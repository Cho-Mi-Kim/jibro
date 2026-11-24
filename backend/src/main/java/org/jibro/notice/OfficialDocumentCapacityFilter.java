package org.jibro.notice;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.Semaphore;

import org.jibro.global.config.SecurityConfig;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Holds capacity from before downloading until the synchronous response body is written.
 */
@Component
@Order(-50)
public class OfficialDocumentCapacityFilter extends OncePerRequestFilter {
    private final Semaphore capacity = new Semaphore(2);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !(request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))
                || !path.startsWith("/api/v1/notices/official-pdf/") || path.endsWith("/relocate");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!capacity.tryAcquire()) {
            response.setHeader("Retry-After", "2");
            response.setHeader("Cache-Control", "no-store");
            SecurityConfig.error(response, 429, "OFFICIAL_DOCUMENT_BUSY", "공고문을 처리 중이에요. 잠시 후 다시 열어 주세요.");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            capacity.release();
        }
    }
}
