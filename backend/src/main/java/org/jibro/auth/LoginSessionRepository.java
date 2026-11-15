package org.jibro.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LoginSessionRepository {
    private final JdbcTemplate db;

    public LoginSessionRepository(JdbcTemplate db) {
        this.db = db;
    }

    void insert(UUID id, UUID user, Instant expiresAt) {
        db.update("INSERT INTO auth_session(id,user_id,expires_at) VALUES (?,?,?)", id, user, Timestamp.from(expiresAt));
    }

    boolean lockActiveSession(UUID user, UUID id) {
        return !db.queryForList("SELECT id FROM auth_session WHERE id=? AND user_id=? AND NOT revoked AND expires_at>now() FOR UPDATE", UUID.class, id, user).isEmpty();
    }

    void extendExpiry(UUID id, Instant expiresAt) {
        db.update("UPDATE auth_session SET expires_at=? WHERE id=?", Timestamp.from(expiresAt), id);
    }

    void revokeSessionAndRefreshTokens(UUID user, UUID id) {
        db.update("UPDATE auth_session SET revoked=true WHERE id=? AND user_id=?", id, user);
        db.update("UPDATE refresh_token SET revoked=true WHERE session_id=? AND user_id=?", id, user);
    }

    public boolean isAccessSessionValid(String user, int version, String session) {
        // Keep textual UUID comparisons: malformed signed claims must be rejected, not cast in SQL.
        return Boolean.TRUE.equals(db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM app_user u JOIN auth_session s ON s.user_id=u.id WHERE u.id::text=? AND NOT u.disabled AND (u.trial_expires_at IS NULL OR u.trial_expires_at>now()) AND u.security_version=? AND s.id::text=? AND NOT s.revoked AND s.expires_at>now())",
            Boolean.class, user, version, session));
    }
}
