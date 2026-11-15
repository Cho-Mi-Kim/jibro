package org.jibro.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for proof-bound social identity changes; transactions are owned by AccountConnections. */
@Repository
public class AccountConnectionRepository {
    record Owner(String email, String password, int version) {}
    record Intent(UUID user, String provider, String bindingHash, int version, Instant expiresAt, String stateHash) {}

    private final JdbcTemplate db;

    public AccountConnectionRepository(JdbcTemplate db) {
        this.db = db;
    }

    List<String> connectedProviders(UUID user) {
        return db.queryForList("SELECT provider FROM login_identity WHERE user_id=?", String.class, user);
    }

    boolean hasRecentProof(UUID user) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM account_connection_proof WHERE user_id=? AND created_at>now()-interval '1 minute')", Boolean.class, user));
    }

    void saveProof(UUID user, String tokenHash, int version, Instant expiresAt) {
        db.update("INSERT INTO account_connection_proof(user_id,token_hash,security_version,expires_at) VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET token_hash=EXCLUDED.token_hash,security_version=EXCLUDED.security_version,created_at=now(),expires_at=EXCLUDED.expires_at,failures=0",
            user, tokenHash, version, Timestamp.from(expiresAt));
    }

    boolean hasProvider(UUID user, String provider) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM login_identity WHERE user_id=? AND provider=?)", Boolean.class, user, provider));
    }

    void insertIntent(UUID intent, UUID user, String provider, String bindingHash, int version, Instant expiresAt) {
        db.update("INSERT INTO account_connection_intent(id,user_id,provider,binding_hash,security_version,expires_at) VALUES(?,?,?,?,?,?)", intent, user, provider, bindingHash, version, Timestamp.from(expiresAt));
    }

    boolean deleteIntent(UUID intent) {
        return db.update("DELETE FROM account_connection_intent WHERE id=?", intent) == 1;
    }

    boolean bindIntentState(UUID intent, String stateHash) {
        return db.update("UPDATE account_connection_intent SET state_hash=? WHERE id=? AND state_hash IS NULL", stateHash, intent) == 1;
    }

    boolean insertIdentityIfAvailable(UUID user, String provider, String subject) {
        return db.update("INSERT INTO login_identity(provider,subject,user_id) VALUES(?,?,?) ON CONFLICT DO NOTHING", provider, subject, user) == 1;
    }

    void deleteIdentity(UUID user, String provider) {
        db.update("DELETE FROM login_identity WHERE user_id=? AND provider=?", user, provider);
    }

    void invalidateCredentials(UUID user) {
        db.update("UPDATE app_user SET security_version=security_version+1 WHERE id=?", user);
        db.update("DELETE FROM refresh_token WHERE user_id=?", user);
        db.update("DELETE FROM account_ticket WHERE user_id=?", user);
    }

    void deletePendingConnections(UUID user) {
        db.update("DELETE FROM account_connection_intent WHERE user_id=? OR expires_at<=now()", user);
        db.update("DELETE FROM account_connection_proof WHERE user_id=? OR expires_at<=now()", user);
    }

    Optional<Owner> findOwner(UUID user, boolean lock) {
        return db.query("SELECT email,password_hash,security_version FROM app_user WHERE id=? AND NOT disabled AND trial_expires_at IS NULL" + (lock ? " FOR UPDATE" : ""),
            (r, i) -> new Owner(r.getString(1), r.getString(2), r.getInt(3)), user).stream().findFirst();
    }

    boolean isReauthenticationBlocked(UUID user) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM account_connection_attempt WHERE user_id=? AND blocked_until>now())", Boolean.class, user));
    }

    void deleteExpiredReauthenticationBlock(UUID user) {
        db.update("DELETE FROM account_connection_attempt WHERE user_id=? AND blocked_until<=now()", user);
    }

    void clearReauthenticationFailures(UUID user) {
        db.update("DELETE FROM account_connection_attempt WHERE user_id=?", user);
    }

    Optional<String> findUsableProofHash(UUID user, int version) {
        return db.queryForList("SELECT token_hash FROM account_connection_proof WHERE user_id=? AND security_version=? AND expires_at>now() AND failures<5", String.class, user, version).stream().findFirst();
    }

    void acceptEmailProof(UUID user) {
        db.update("DELETE FROM account_connection_proof WHERE user_id=?", user);
        db.update("UPDATE app_user SET email_verified=true WHERE id=?", user);
        clearReauthenticationFailures(user);
    }

    void incrementProofFailures(UUID user) {
        db.update("UPDATE account_connection_proof SET failures=failures+1 WHERE user_id=?", user);
    }

    void recordReauthenticationFailure(UUID user) {
        db.update("INSERT INTO account_connection_attempt(user_id,failures) VALUES(?,1) ON CONFLICT(user_id) DO UPDATE SET failures=account_connection_attempt.failures+1,blocked_until=CASE WHEN account_connection_attempt.failures>=4 THEN now()+interval '15 minutes' ELSE NULL END", user);
    }

    Optional<Intent> findIntent(UUID id) {
        return db.query("SELECT user_id,provider,binding_hash,security_version,expires_at,state_hash FROM account_connection_intent WHERE id=?",
            (r, i) -> new Intent(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getInt(4), r.getTimestamp(5).toInstant(), r.getString(6)), id).stream().findFirst();
    }
}
