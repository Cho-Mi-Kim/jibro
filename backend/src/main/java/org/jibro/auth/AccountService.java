package org.jibro.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import org.jibro.global.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    public record Profile(String name, String email, String region, boolean verified, boolean passwordLogin,
                          String mailMode) {
    }

    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final AccountMail mail;
    private final String frontend;
    private final SecureRandom random = new SecureRandom();

    public AccountService(JdbcTemplate db, PasswordEncoder encoder, AccountMail mail, @Value("${app.auth.frontend-url}") String frontend) {
        this.db = db;
        this.encoder = encoder;
        this.mail = mail;
        this.frontend = frontend.replaceAll("/+$", "");
    }

    public Profile profile(UUID id) {
        return db.query("SELECT name,CASE WHEN trial_expires_at IS NULL THEN email ELSE '' END,interest_region,email_verified,password_hash IS NOT NULL FROM app_user WHERE id=? AND NOT disabled",
            (r, i) -> new Profile(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4), r.getBoolean(5), mail.mode()), id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    @Transactional
    public Profile update(UUID id, String name, String region) {
        name = name.strip();
        if (name.length() < 2 || name.length() > 30) throw invalid();
        db.update("UPDATE app_user SET name=?,interest_region=? WHERE id=? AND NOT disabled", name, region.strip(), id);
        return profile(id);
    }

    private static ApiException invalid() {
        return new ApiException(400, "INVALID_INPUT", "입력한 값을 다시 확인해 주세요.");
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void changeSecret(UUID id, String password) {
        if (password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalid();
        db.update("UPDATE app_user SET password_hash=?,security_version=security_version+1 WHERE id=? AND NOT disabled", encoder.encode(password), id);
        db.update("DELETE FROM refresh_token WHERE user_id=?", id);
        db.update("DELETE FROM account_ticket WHERE user_id=?", id);
    }

    @Transactional
    public void password(UUID id, String oldValue, String newValue) {
        var hashes = db.queryForList("SELECT password_hash FROM app_user WHERE id=? AND NOT disabled FOR UPDATE", String.class, id);
        if (hashes.isEmpty() || hashes.get(0) == null || !encoder.matches(oldValue, hashes.get(0)))
            throw new ApiException(400, "BAD_PASSWORD", "현재 비밀번호가 일치하지 않습니다.");
        changeSecret(id, newValue);
    }

    @Transactional
    public void requestReset(String email) {
        requireMail();
        var users = db.queryForList("SELECT id FROM app_user WHERE email=? AND NOT disabled AND password_hash IS NOT NULL FOR UPDATE", UUID.class, email.strip().toLowerCase(Locale.ROOT));
        if (!users.isEmpty()) issue(users.get(0), "reset");
    }

    private void requireMail() {
        if (mail.mode().equals("unavailable")) throw new ApiException(503, "MAIL_UNAVAILABLE", "메일 발송 설정이 필요합니다.");
    }

    @Transactional
    public void issue(UUID id, String purpose) {
        requireMail();
        var rows = db.queryForList("SELECT email FROM app_user WHERE id=? AND NOT disabled FOR UPDATE", String.class, id);
        if (rows.isEmpty()) throw ApiException.notFound();
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM account_ticket WHERE user_id=? AND purpose=? AND created_at>now()-interval '1 minute')", Boolean.class, id, purpose)))
            return;
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String label = switch (purpose) {
            case "reset" -> "비밀번호 재설정";
            case "verify" -> "이메일 확인";
            case "delete" -> "회원 탈퇴 확인";
            default -> throw invalid();
        };
        // A delivery failure must not distinguish a registered address at the recovery endpoint.
        try {
            mail.send(rows.get(0), "집으로 · " + label, frontend + "/account/confirm#" + purpose + "=" + token + "\n\n30분 동안 한 번만 사용할 수 있습니다. 직접 요청했다면 페이지에서 확인해 주세요.");
        } catch (RuntimeException ex) {
            if (purpose.equals("reset")) return;
            throw new ApiException(503, "MAIL_FAILED", "메일을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        db.update("INSERT INTO account_ticket VALUES(?,?,?,now(),?) ON CONFLICT(user_id,purpose) DO UPDATE SET token_hash=EXCLUDED.token_hash,created_at=EXCLUDED.created_at,expires_at=EXCLUDED.expires_at", id, purpose, digest(token), Timestamp.from(Instant.now().plusSeconds(1800)));
    }

    private UUID consume(String purpose, String token, UUID requiredOwner) {
        var rows = db.queryForList("SELECT user_id FROM account_ticket WHERE purpose=? AND token_hash=? AND expires_at>now()", UUID.class, purpose, digest(token));
        if (rows.isEmpty() || requiredOwner != null && !requiredOwner.equals(rows.get(0))) throw badLink();
        UUID id = rows.get(0);
        if (db.queryForList("SELECT id FROM app_user WHERE id=? AND NOT disabled FOR UPDATE", UUID.class, id).isEmpty())
            throw badLink();
        if (db.update("DELETE FROM account_ticket WHERE user_id=? AND purpose=? AND token_hash=? AND expires_at>now()", id, purpose, digest(token)) != 1)
            throw badLink();
        return id;
    }

    private ApiException badLink() {
        return new ApiException(400, "INVALID_LINK", "만료되었거나 이미 사용된 링크입니다. 새 링크를 요청해 주세요.");
    }

    @Transactional
    public void reset(String token, String password) {
        changeSecret(consume("reset", token, null), password);
    }

    @Transactional
    public void verify(String token) {
        db.update("UPDATE app_user SET email_verified=true WHERE id=?", consume("verify", token, null));
    }

    @Transactional
    public void delete(UUID user, String token, String confirmation) {
        if (!"회원 탈퇴".equals(confirmation)) throw invalid();
        consume("delete", token, user);
        db.update("DELETE FROM preparation WHERE user_id=?", user);
        db.update("DELETE FROM push_device WHERE user_id=?", user);
        db.update("DELETE FROM alert_delivery WHERE user_id=?", user);
        db.update("DELETE FROM saved_notice WHERE user_id=?", user);
        db.update("DELETE FROM alert_read WHERE user_id=?", user);
        db.update("DELETE FROM alert_preferences WHERE user_id=?", user);
        db.update("DELETE FROM refresh_token WHERE user_id=?", user);
        db.update("DELETE FROM account_ticket WHERE user_id=?", user);
        db.update("DELETE FROM login_identity WHERE user_id=?", user);
        // Keep only the anonymous actor key needed by existing review/collection audit records.
        db.update("UPDATE app_user SET disabled=true,security_version=security_version+1,name='탈퇴 회원',email=?,password_hash=NULL,interest_region='',email_verified=false,role='USER' WHERE id=?", "deleted:" + user, user);
    }
}
