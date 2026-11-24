package org.jibro.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrialAccountRepository {
    private final JdbcTemplate db;

    public TrialAccountRepository(JdbcTemplate db) {
        this.db = db;
    }

    void insert(UUID id) {
        db.update("INSERT INTO app_user(id,name,email,trial_expires_at) VALUES (?,?,?,now()+interval '24 hours')",
                id, "체험 사용자", "trial:" + id);
    }

    boolean lockTrial(UUID id) {
        return !db.queryForList("SELECT id FROM app_user WHERE id=? AND trial_expires_at IS NOT NULL FOR UPDATE", UUID.class, id).isEmpty();
    }

    void deleteTrialAndPrivateData(UUID id) {
        db.update("DELETE FROM preparation WHERE user_id=?", id);
        db.update("DELETE FROM refresh_token WHERE user_id=?", id);
        db.update("DELETE FROM app_user WHERE id=? AND trial_expires_at IS NOT NULL", id);
    }

    List<UUID> lockExpiredTrials() {
        return db.queryForList("SELECT id FROM app_user WHERE trial_expires_at<=now() ORDER BY trial_expires_at LIMIT 200 FOR UPDATE SKIP LOCKED", UUID.class);
    }
}
