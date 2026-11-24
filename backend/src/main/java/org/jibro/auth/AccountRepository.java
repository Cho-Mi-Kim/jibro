package org.jibro.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Account profile, credentials and one-use account ticket persistence.
 */
@Repository
public class AccountRepository {
    record Profile(String name, String email, String region, boolean verified, boolean passwordLogin) {
    }

    record PasswordCredential(String hash) {
    }

    private final JdbcTemplate db;

    public AccountRepository(JdbcTemplate db) {
        this.db = db;
    }

    Optional<Profile> findProfile(UUID id) {
        return db.query("SELECT name,CASE WHEN trial_expires_at IS NULL THEN email ELSE '' END,interest_region,email_verified,password_hash IS NOT NULL FROM app_user WHERE id=? AND NOT disabled",
                (r, i) -> new Profile(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4), r.getBoolean(5)), id).stream().findFirst();
    }

    void updateProfile(UUID id, String name, String region) {
        db.update("UPDATE app_user SET name=?,interest_region=? WHERE id=? AND NOT disabled", name, region, id);
    }

    void replacePasswordAndInvalidateCredentials(UUID id, String passwordHash) {
        db.update("UPDATE app_user SET password_hash=?,security_version=security_version+1 WHERE id=? AND NOT disabled", passwordHash, id);
        db.update("DELETE FROM refresh_token WHERE user_id=?", id);
        db.update("DELETE FROM account_ticket WHERE user_id=?", id);
        db.update("DELETE FROM account_connection_intent WHERE user_id=?", id);
        db.update("DELETE FROM account_connection_proof WHERE user_id=?", id);
    }

    Optional<PasswordCredential> lockPasswordCredential(UUID id) {
        return db.query("SELECT password_hash FROM app_user WHERE id=? AND NOT disabled FOR UPDATE",
                (r, i) -> new PasswordCredential(r.getString(1)), id).stream().findFirst();
    }

    Optional<UUID> lockPasswordAccountByEmail(String email) {
        return db.queryForList("SELECT id FROM app_user WHERE email=? AND NOT disabled AND password_hash IS NOT NULL FOR UPDATE", UUID.class, email).stream().findFirst();
    }

    Optional<String> lockEmail(UUID id) {
        return db.queryForList("SELECT email FROM app_user WHERE id=? AND NOT disabled FOR UPDATE", String.class, id).stream().findFirst();
    }

    boolean hasRecentTicket(UUID id, String purpose) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM account_ticket WHERE user_id=? AND purpose=? AND created_at>now()-interval '1 minute')", Boolean.class, id, purpose));
    }

    void saveTicket(UUID id, String purpose, String tokenHash, Instant expiresAt) {
        db.update("INSERT INTO account_ticket VALUES(?,?,?,now(),?) ON CONFLICT(user_id,purpose) DO UPDATE SET token_hash=EXCLUDED.token_hash,created_at=EXCLUDED.created_at,expires_at=EXCLUDED.expires_at", id, purpose, tokenHash, Timestamp.from(expiresAt));
    }

    Optional<UUID> findTicketOwner(String purpose, String tokenHash) {
        return db.queryForList("SELECT user_id FROM account_ticket WHERE purpose=? AND token_hash=? AND expires_at>now()", UUID.class, purpose, tokenHash).stream().findFirst();
    }

    boolean lockActiveAccount(UUID id) {
        return !db.queryForList("SELECT id FROM app_user WHERE id=? AND NOT disabled FOR UPDATE", UUID.class, id).isEmpty();
    }

    boolean consumeTicket(UUID id, String purpose, String tokenHash) {
        return db.update("DELETE FROM account_ticket WHERE user_id=? AND purpose=? AND token_hash=? AND expires_at>now()", id, purpose, tokenHash) == 1;
    }

    void markEmailVerified(UUID id) {
        db.update("UPDATE app_user SET email_verified=true WHERE id=?", id);
    }

    void anonymizeAccountAndDeletePrivateData(UUID user) {
        db.update("DELETE FROM preparation WHERE user_id=?", user);
        db.update("DELETE FROM push_device WHERE user_id=?", user);
        db.update("DELETE FROM alert_delivery WHERE user_id=?", user);
        db.update("DELETE FROM saved_notice WHERE user_id=?", user);
        db.update("DELETE FROM alert_read WHERE user_id=?", user);
        db.update("DELETE FROM alert_preferences WHERE user_id=?", user);
        db.update("DELETE FROM refresh_token WHERE user_id=?", user);
        db.update("DELETE FROM account_ticket WHERE user_id=?", user);
        db.update("DELETE FROM login_identity WHERE user_id=?", user);
        db.update("DELETE FROM account_connection_intent WHERE user_id=?", user);
        db.update("DELETE FROM account_connection_proof WHERE user_id=?", user);
        // Preserve the anonymous actor key referenced by review/collection audit records.
        db.update("UPDATE app_user SET disabled=true,security_version=security_version+1,name='탈퇴 회원',email=?,password_hash=NULL,interest_region='',email_verified=false,role='USER' WHERE id=?", "deleted:" + user, user);
    }
}
