package org.jibro.preparation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PreparationDeletionRepository {
    public record Owner(String email, String password, int version, boolean trial) {
    }

    public record Preparation(int revision, String title) {
    }

    private final JdbcTemplate db;

    public PreparationDeletionRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<Owner> findOwner(UUID user) {
        return owner(user, false);
    }

    public Optional<Owner> lockOwner(UUID user) {
        return owner(user, true);
    }

    private Optional<Owner> owner(UUID user, boolean lock) {
        return db.query("SELECT email,password_hash,security_version,trial_expires_at IS NOT NULL FROM app_user WHERE id=? AND NOT disabled AND (trial_expires_at IS NULL OR trial_expires_at>now())" + (lock ? " FOR UPDATE" : ""),
                (r, i) -> new Owner(r.getString(1), r.getString(2), r.getInt(3), r.getBoolean(4)), user).stream().findFirst();
    }

    public Optional<Preparation> findOwned(UUID user, UUID id) {
        return preparation(user, id, false);
    }

    public Optional<Preparation> lockOwned(UUID user, UUID id) {
        return preparation(user, id, true);
    }

    private Optional<Preparation> preparation(UUID user, UUID id, boolean lock) {
        return db.query("SELECT revision,snapshot->'preparation'->>'title' AS title FROM preparation WHERE id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Preparation(rs.getInt("revision"), rs.getString("title")), id, user).stream().findFirst();
    }

    public boolean hasRecentProof(UUID user) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM preparation_deletion_proof WHERE user_id=? AND created_at>now()-interval '1 minute')", Boolean.class, user));
    }

    public void removeExpiredProofs(UUID user) {
        db.update("DELETE FROM preparation_deletion_proof WHERE user_id=? AND expires_at<=now()", user);
    }

    public void saveProof(UUID preparation, UUID user, int revision, int securityVersion, String hash, Instant expiresAt) {
        db.update("INSERT INTO preparation_deletion_proof(preparation_id,user_id,revision,security_version,token_hash,expires_at) VALUES(?,?,?,?,?,?) ON CONFLICT(preparation_id) DO UPDATE SET revision=EXCLUDED.revision,security_version=EXCLUDED.security_version,token_hash=EXCLUDED.token_hash,created_at=now(),expires_at=EXCLUDED.expires_at",
                preparation, user, revision, securityVersion, hash, Timestamp.from(expiresAt));
    }

    public boolean deleteOwnedRevision(UUID user, UUID preparation, int revision) {
        return db.update("DELETE FROM preparation WHERE id=? AND user_id=? AND revision=?", preparation, user, revision) == 1;
    }

    public List<String> findValidProofHashes(UUID user, UUID preparation, int revision, int securityVersion) {
        return db.queryForList("SELECT token_hash FROM preparation_deletion_proof WHERE user_id=? AND preparation_id=? AND revision=? AND security_version=? AND expires_at>now()", String.class, user, preparation, revision, securityVersion);
    }

    public void removeProof(UUID preparation) {
        db.update("DELETE FROM preparation_deletion_proof WHERE preparation_id=?", preparation);
    }

}
