package org.jibro.auth;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrialAccounts {
    private final JdbcTemplate db;

    public TrialAccounts(JdbcTemplate db) {
        this.db = db;
    }

    UUID create() {
        var id = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,name,email,trial_expires_at) VALUES (?,?,?,now()+interval '24 hours')",
            id, "체험 사용자", "trial:" + id);
        return id;
    }

    // The caller holds the account row lock. Only accounts explicitly marked as trials are removable.
    void remove(UUID id) {
        if (db.queryForList("SELECT id FROM app_user WHERE id=? AND trial_expires_at IS NOT NULL FOR UPDATE", UUID.class, id).isEmpty()) return;
        db.update("DELETE FROM preparation WHERE user_id=?", id);
        db.update("DELETE FROM refresh_token WHERE user_id=?", id);
        db.update("DELETE FROM app_user WHERE id=? AND trial_expires_at IS NOT NULL", id);
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void removeExpired() {
        for (var id : db.queryForList("SELECT id FROM app_user WHERE trial_expires_at<=now() ORDER BY trial_expires_at LIMIT 200 FOR UPDATE SKIP LOCKED", UUID.class))
            remove(id);
    }
}
