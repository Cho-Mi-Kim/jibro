package org.jibro.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side session lifetime shared by access tokens and every rotated refresh token.
 */
@Service
public class LoginSessions {
    private final LoginSessionRepository repository;

    public LoginSessions(LoginSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UUID create(UUID user) {
        UUID id = UUID.randomUUID();
        repository.insert(id, user, expiresAt());
        return id;
    }

    @Transactional
    public void renew(UUID user, UUID id) {
        if (id == null || !repository.lockActiveSession(user, id))
            throw unauthorized();
        repository.extendExpiry(id, expiresAt());
    }

    @Transactional
    public void revoke(UUID user, UUID id) {
        repository.revokeSessionAndRefreshTokens(user, id);
    }

    private Instant expiresAt() {
        return Instant.now().plus(7, ChronoUnit.DAYS);
    }

    private ApiException unauthorized() {
        return new ApiException(401, "UNAUTHORIZED", "로그인 정보를 확인해 주세요.");
    }
}
