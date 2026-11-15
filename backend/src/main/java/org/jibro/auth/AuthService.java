package org.jibro.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.jibro.global.common.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
    public record User(String id, String name, String email, String role, boolean trial) {
    }

    public record Tokens(String accessToken, User user, String refresh) {
    }

    private final AuthRepository repository;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String dummyHash;
    private final TrialAccounts trials;
    private final LoginAttemptLimiter attempts;
    private final LoginSessions sessions;
    private final TransactionTemplate loginTransaction;

    public AuthService(AuthRepository repository, PasswordEncoder passwords, JwtEncoder encoder, TrialAccounts trials,
                       LoginAttemptLimiter attempts, LoginSessions sessions, PlatformTransactionManager transactions) {
        this.trials = trials;
        this.attempts = attempts;
        this.sessions = sessions;
        this.loginTransaction = new TransactionTemplate(transactions);
        this.repository = repository;
        this.passwords = passwords;
        this.encoder = encoder;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public User user(String id) {
        var user = repository.findActiveUser(UUID.fromString(id)).orElseThrow(AuthService::unauthorized);
        return new User(user.id().toString(), user.name(), user.email(), user.role(), user.trial());
    }

    @Transactional
    public void signup(String name, String email, String password) {
        if (name.trim().length() < 2 || password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(400, "INVALID_INPUT", "이름은 2자 이상, 비밀번호는 10자 이상·UTF-8 72바이트 이하여야 합니다.");
        repository.insertPasswordAccount(
            UUID.randomUUID(), name.trim(), email.trim().toLowerCase(Locale.ROOT), passwords.encode(password));
    }

    public Tokens login(String email, String password) {
        return login(email, password, "internal");
    }

    public Tokens login(String email, String password, String remoteAddress) {
        UUID attempt = attempts.begin(email, remoteAddress);
        boolean successful = false;
        try {
            var tokens = loginTransaction.execute(status -> {
                var credentials = repository.lockCredentials(email.trim().toLowerCase(Locale.ROOT));
                String hash = credentials.map(AuthRepository.Credentials::passwordHash).orElse(null);
                boolean valid = passwords.matches(password, hash == null ? dummyHash : hash);
                if (!valid || hash == null) throw unauthorized();
                return issue(credentials.orElseThrow(AuthService::unauthorized).id().toString());
            });
            successful = true;
            return tokens;
        } finally {
            attempts.finish(attempt, successful);
        }
    }

    @Transactional
    public Tokens startTrial(String token) {
        if (token != null && token.length() <= 200) {
            var owner = repository.lockTrialTokenOwner(hash(token));
            if (owner.isPresent()) {
                if (!owner.get().trial())
                    throw new ApiException(409, "ALREADY_SIGNED_IN", "기존 계정에서 로그아웃한 뒤 체험해 주세요.");
                if (owner.get().revoked()) throw unauthorized();
                return refresh(token);
            }
        }
        return issue(trials.create().toString());
    }

    @Transactional
    public Tokens refresh(String token) {
        if (token == null || token.length() > 200) throw unauthorized();
        // Serialize issuance and credential revocation on the owner before locking a token.
        if (!repository.lockRefreshOwner(hash(token)))
            throw unauthorized();
        var refresh = repository.lockRefreshToken(hash(token)).orElseThrow(AuthService::unauthorized);
        if (refresh.revoked() || refresh.session() == null || refresh.expiresAt().isBefore(Instant.now()))
            throw unauthorized();
        repository.revokeRefreshToken(hash(token));
        return issue(refresh.user().toString(), refresh.session());
    }

    @Transactional
    public void logout(String token) {
        logout(token, null);
    }

    @Transactional
    public void logout(String token, Jwt jwt) {
        if (jwt != null && jwt.getClaimAsString("sid") != null) {
            UUID user = UUID.fromString(jwt.getSubject()), session = UUID.fromString(jwt.getClaimAsString("sid"));
            var owner = repository.lockLogoutOwner(user);
            if (owner.isEmpty()) return;
            if (owner.get().trial()) trials.remove(user);
            else sessions.revoke(user, session);
            return;
        }
        if (token == null || token.length() > 200) return;
        String digest = hash(token);
        // Lock the owner first, as refresh and credential revocation do. A refresh/logout race
        // cannot issue a usable token after the current member session has been revoked.
        var owners = repository.lockLogoutOwnerByToken(digest);
        if (owners.isEmpty()) return;
        var tokens = repository.lockRefreshToken(digest);
        if (tokens.isEmpty()) return;
        var owner = owners.get();
        UUID user = owner.id();
        if (owner.trial()) {
            // Preserve the trial policy: an already rotated cookie alone cannot destroy its account.
            if (!tokens.get().revoked()) trials.remove(user);
        } else if (tokens.get().session() != null) {
            sessions.revoke(user, tokens.get().session());
        } else repository.revokeRefreshToken(digest);
    }

    @Transactional
    public Tokens social(String provider, String subject, String name, String email) {
        var existing = repository.findIdentityOwner(provider, subject);
        if (!existing.isEmpty()) {
            var owner = existing.get();
            // Disconnect locks the owner before removing an identity. Recheck after acquiring that
            // same lock so an OAuth login already in flight cannot revive a removed sign-in method.
            if (!repository.lockActiveAccount(owner) || !repository.ownsIdentity(owner, provider, subject))
                throw unauthorized();
            return issue(owner.toString());
        }
        // Email coincidence never authorizes linking an existing identity.
        if (email == null || email.isBlank()) throw new ApiException(409, "EMAIL_REQUIRED", "제공자의 이메일 동의가 필요합니다.");
        UUID id = UUID.randomUUID();
        String displayName = name == null || name.isBlank() ? "새 사용자" : name.substring(0, Math.min(30, name.length()));
        repository.insertSocialAccount(id, displayName, email.toLowerCase(Locale.ROOT), provider, subject);
        return issue(id.toString());
    }

    private Tokens issue(String id) {
        return issue(id, null);
    }

    private Tokens issue(String id, UUID session) {
        if (!repository.lockActiveAccount(UUID.fromString(id)))
            throw unauthorized();
        User user = user(id);
        UUID owner = UUID.fromString(id);
        if (session == null) session = sessions.create(owner);
        else sessions.renew(owner, session);
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("jibro").audience(List.of("jibro-web"))
            .subject(id).issuedAt(now).expiresAt(now.plus(15, ChronoUnit.MINUTES)).claim("sid", session.toString()).claim("role", user.trial() ? "TRIAL" : user.role()).claim("sv", repository.securityVersion(owner)).build();
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.insertRefreshToken(hash(refresh), owner, now.plus(7, ChronoUnit.DAYS), session);
        return new Tokens(access, user, refresh);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(401, "UNAUTHORIZED", "로그인 정보를 확인해 주세요.");
    }
}
