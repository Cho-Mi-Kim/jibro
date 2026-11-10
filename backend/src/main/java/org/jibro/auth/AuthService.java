package org.jibro.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.jibro.global.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    public record User(String id, String name, String email, String role, boolean trial) {
    }

    public record Tokens(String accessToken, User user, String refresh) {
    }

    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String dummyHash;
    private final TrialAccounts trials;

    public AuthService(JdbcTemplate db, PasswordEncoder passwords, JwtEncoder encoder, TrialAccounts trials) {
        this.trials = trials;
        this.db = db;
        this.passwords = passwords;
        this.encoder = encoder;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public User user(String id) {
        return db.query("SELECT id,name,CASE WHEN trial_expires_at IS NULL THEN email ELSE '' END,role,trial_expires_at IS NOT NULL FROM app_user WHERE id=? AND NOT disabled AND (trial_expires_at IS NULL OR trial_expires_at>now())", (rs, n) ->
                new User(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)), UUID.fromString(id))
            .stream().findFirst().orElseThrow(AuthService::unauthorized);
    }

    @Transactional
    public void signup(String name, String email, String password) {
        if (name.trim().length() < 2 || password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(400, "INVALID_INPUT", "이름은 2자 이상, 비밀번호는 10자 이상·UTF-8 72바이트 이하여야 합니다.");
        db.update("INSERT INTO app_user(id,name,email,password_hash) VALUES (?,?,?,?)",
            UUID.randomUUID(), name.trim(), email.trim().toLowerCase(Locale.ROOT), passwords.encode(password));
    }

    @Transactional
    public Tokens login(String email, String password) {
        var rows = db.queryForList("SELECT id,password_hash FROM app_user WHERE email=? AND NOT disabled FOR UPDATE", email.trim().toLowerCase(Locale.ROOT));
        String hash = rows.isEmpty() ? null : (String) rows.get(0).get("password_hash");
        boolean valid = passwords.matches(password, hash == null ? dummyHash : hash);
        if (!valid || hash == null) throw unauthorized();
        return issue(rows.get(0).get("id").toString());
    }

    @Transactional
    public Tokens startTrial(String token) {
        if (token != null && token.length() <= 200) {
            var owner = db.queryForList("SELECT u.trial_expires_at IS NOT NULL AS trial,t.revoked FROM refresh_token t JOIN app_user u ON u.id=t.user_id WHERE t.hash=? AND t.expires_at>now() AND NOT u.disabled AND (u.trial_expires_at IS NULL OR u.trial_expires_at>now()) FOR UPDATE OF u", hash(token));
            if (!owner.isEmpty()) {
                if (!Boolean.TRUE.equals(owner.get(0).get("trial")))
                    throw new ApiException(409, "ALREADY_SIGNED_IN", "기존 계정에서 로그아웃한 뒤 체험해 주세요.");
                if (Boolean.TRUE.equals(owner.get(0).get("revoked"))) throw unauthorized();
                return refresh(token);
            }
        }
        return issue(trials.create().toString());
    }

    @Transactional
    public Tokens refresh(String token) {
        if (token == null || token.length() > 200) throw unauthorized();
        // Serialize issuance and credential revocation on the owner before locking a token.
        if (db.queryForList("SELECT u.id FROM app_user u JOIN refresh_token t ON t.user_id=u.id WHERE t.hash=? AND NOT u.disabled FOR UPDATE OF u", UUID.class, hash(token)).isEmpty())
            throw unauthorized();
        var rows = db.queryForList("SELECT user_id,expires_at,revoked FROM refresh_token WHERE hash=? FOR UPDATE", hash(token));
        if (rows.isEmpty() || Boolean.TRUE.equals(rows.get(0).get("revoked"))
            || ((Timestamp) rows.get(0).get("expires_at")).toInstant().isBefore(Instant.now()))
            throw unauthorized();
        db.update("UPDATE refresh_token SET revoked=true WHERE hash=?", hash(token));
        return issue(rows.get(0).get("user_id").toString());
    }

    @Transactional
    public void logout(String token) {
        if (token == null) return;
        String digest = hash(token);
        var guest = db.queryForList("SELECT u.id FROM app_user u JOIN refresh_token t ON u.id=t.user_id WHERE t.hash=? AND NOT t.revoked AND u.trial_expires_at IS NOT NULL FOR UPDATE OF u", UUID.class, digest);
        if (guest.isEmpty()) db.update("UPDATE refresh_token SET revoked=true WHERE hash=?", digest);
        else trials.remove(guest.get(0));
    }

    @Transactional
    public Tokens social(String provider, String subject, String name, String email) {
        var existing = db.queryForList("SELECT user_id FROM login_identity WHERE provider=? AND subject=?", provider, subject);
        if (!existing.isEmpty()) return issue(existing.get(0).get("user_id").toString());
        // Email coincidence never authorizes linking an existing identity.
        if (email == null || email.isBlank()) throw new ApiException(409, "EMAIL_REQUIRED", "제공자의 이메일 동의가 필요합니다.");
        UUID id = UUID.randomUUID();
        String displayName = name == null || name.isBlank() ? "새 사용자" : name.substring(0, Math.min(30, name.length()));
        db.update("INSERT INTO app_user(id,name,email) VALUES (?,?,?)", id, displayName, email.toLowerCase(Locale.ROOT));
        db.update("INSERT INTO login_identity(provider,subject,user_id) VALUES (?,?,?)", provider, subject, id);
        return issue(id.toString());
    }

    private Tokens issue(String id) {
        if (db.queryForList("SELECT id FROM app_user WHERE id=? AND NOT disabled FOR UPDATE", UUID.class, UUID.fromString(id)).isEmpty())
            throw unauthorized();
        User user = user(id);
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("jibro").audience(List.of("jibro-web"))
            .subject(id).issuedAt(now).expiresAt(now.plus(15, ChronoUnit.MINUTES)).claim("role", user.trial() ? "TRIAL" : user.role()).claim("sv", db.queryForObject("SELECT security_version FROM app_user WHERE id=?", Integer.class, UUID.fromString(id))).build();
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO refresh_token(hash,user_id,expires_at) VALUES (?,?,?)",
            hash(refresh), UUID.fromString(id), Timestamp.from(now.plus(7, ChronoUnit.DAYS)));
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
