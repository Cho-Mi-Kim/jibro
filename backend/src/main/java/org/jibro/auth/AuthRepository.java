package org.jibro.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Authentication persistence. Callers own transactions and the owner-before-token lock order.
 */
@Repository
public class AuthRepository {
    record User(UUID id, String name, String email, String role, boolean trial) {
    }

    record Credentials(UUID id, String passwordHash) {
    }

    record TrialTokenOwner(boolean trial, boolean revoked) {
    }

    record LogoutOwner(UUID id, boolean trial) {
    }

    record RefreshToken(UUID user, Instant expiresAt, boolean revoked, UUID session) {
    }

    private final JdbcTemplate db;

    public AuthRepository(JdbcTemplate db) {
        this.db = db;
    }

    Optional<User> findActiveUser(UUID id) {
        return db.query("SELECT id,name,CASE WHEN trial_expires_at IS NULL THEN email ELSE '' END,role,trial_expires_at IS NOT NULL FROM app_user WHERE id=? AND NOT disabled AND (trial_expires_at IS NULL OR trial_expires_at>now())",
                (r, i) -> new User(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4), r.getBoolean(5)), id).stream().findFirst();
    }

    void insertPasswordAccount(UUID id, String name, String email, String passwordHash) {
        db.update("INSERT INTO app_user(id,name,email,password_hash) VALUES (?,?,?,?)", id, name, email, passwordHash);
    }

    Optional<Credentials> lockCredentials(String email) {
        return db.query("SELECT id,password_hash FROM app_user WHERE email=? AND NOT disabled FOR UPDATE",
                (r, i) -> new Credentials(r.getObject(1, UUID.class), r.getString(2)), email).stream().findFirst();
    }

    Optional<TrialTokenOwner> lockTrialTokenOwner(String hash) {
        return db.query("SELECT u.trial_expires_at IS NOT NULL AS trial,t.revoked FROM refresh_token t JOIN app_user u ON u.id=t.user_id WHERE t.hash=? AND t.expires_at>now() AND NOT u.disabled AND (u.trial_expires_at IS NULL OR u.trial_expires_at>now()) FOR UPDATE OF u",
                (r, i) -> new TrialTokenOwner(r.getBoolean(1), r.getBoolean(2)), hash).stream().findFirst();
    }

    boolean lockRefreshOwner(String hash) {
        return !db.queryForList("SELECT u.id FROM app_user u JOIN refresh_token t ON t.user_id=u.id WHERE t.hash=? AND NOT u.disabled FOR UPDATE OF u", UUID.class, hash).isEmpty();
    }

    Optional<RefreshToken> lockRefreshToken(String hash) {
        return db.query("SELECT user_id,expires_at,revoked,session_id FROM refresh_token WHERE hash=? FOR UPDATE",
                (r, i) -> new RefreshToken(r.getObject(1, UUID.class), r.getTimestamp(2).toInstant(), r.getBoolean(3), r.getObject(4, UUID.class)), hash).stream().findFirst();
    }

    void revokeRefreshToken(String hash) {
        db.update("UPDATE refresh_token SET revoked=true WHERE hash=?", hash);
    }

    Optional<LogoutOwner> lockLogoutOwner(UUID user) {
        return db.query("SELECT trial_expires_at IS NOT NULL AS trial FROM app_user WHERE id=? AND NOT disabled FOR UPDATE",
                (r, i) -> new LogoutOwner(user, r.getBoolean(1)), user).stream().findFirst();
    }

    Optional<LogoutOwner> lockLogoutOwnerByToken(String hash) {
        return db.query("SELECT u.id,u.trial_expires_at IS NOT NULL AS trial FROM app_user u JOIN refresh_token t ON u.id=t.user_id WHERE t.hash=? AND t.expires_at>now() FOR UPDATE OF u",
                (r, i) -> new LogoutOwner(r.getObject(1, UUID.class), r.getBoolean(2)), hash).stream().findFirst();
    }

    Optional<UUID> findIdentityOwner(String provider, String subject) {
        return db.queryForList("SELECT user_id FROM login_identity WHERE provider=? AND subject=?", UUID.class, provider, subject).stream().findFirst();
    }

    boolean lockActiveAccount(UUID owner) {
        return !db.queryForList("SELECT id FROM app_user WHERE id=? AND NOT disabled FOR UPDATE", UUID.class, owner).isEmpty();
    }

    boolean ownsIdentity(UUID owner, String provider, String subject) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM login_identity WHERE provider=? AND subject=? AND user_id=?)", Boolean.class, provider, subject, owner));
    }

    void insertSocialAccount(UUID id, String name, String email, String provider, String subject) {
        db.update("INSERT INTO app_user(id,name,email) VALUES (?,?,?)", id, name, email);
        db.update("INSERT INTO login_identity(provider,subject,user_id) VALUES (?,?,?)", provider, subject, id);
    }

    int securityVersion(UUID owner) {
        return db.queryForObject("SELECT security_version FROM app_user WHERE id=?", Integer.class, owner);
    }

    void insertRefreshToken(String hash, UUID owner, Instant expiresAt, UUID session) {
        db.update("INSERT INTO refresh_token(hash,user_id,expires_at,session_id) VALUES (?,?,?,?)", hash, owner, Timestamp.from(expiresAt), session);
    }
}
