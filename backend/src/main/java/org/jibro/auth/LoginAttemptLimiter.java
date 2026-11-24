package org.jibro.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jibro.global.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits admission and outcomes separately so a rejected login cannot roll back its limit.
 */
@Service
public class LoginAttemptLimiter {
    public enum Purpose {LOGIN, REAUTHENTICATION}

    private final LoginAttemptRepository repository;
    private final byte[] secret;

    public LoginAttemptLimiter(LoginAttemptRepository repository, @Value("${app.auth.jwt-secret}") String secret) {
        this.repository = repository;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID begin(String email, String remoteAddress, Purpose purpose) {
        String account = key("account:" + email.trim().toLowerCase(Locale.ROOT));
        String address = key("address:" + Objects.requireNonNullElse(remoteAddress, "internal"));
        // All instances lock the same ordered bucket pair before counting in-flight/failing attempts.
        repository.lockBuckets(lockKey(account), lockKey(address));
        repository.deleteOldAttempts();
        if (repository.countAccountFailuresAndPending(account) >= 5 || repository.countAddressFailuresAndPending(address) >= 30) {
            if (purpose == Purpose.REAUTHENTICATION)
                throw new ApiException(429, "REAUTHENTICATION_LIMIT", "본인 확인을 여러 번 실패했어요. 잠시 기다린 뒤 다시 시도해 주세요.");
            throw new ApiException(429, "LOGIN_RATE_LIMIT", "로그인 시도가 많습니다. 잠시 기다린 뒤 다시 시도해 주세요.");
        }
        UUID id = UUID.randomUUID();
        repository.reserveAttempt(id, account, address);
        return id;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(UUID id, boolean successful) {
        repository.completeAttempt(id, successful);
    }

    private long lockKey(String digest) {
        return ByteBuffer.wrap(HexFormat.of().parseHex(digest.substring(0, 16))).getLong();
    }

    String key(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
