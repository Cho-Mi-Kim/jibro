package org.jibro.member;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SavedNoticeRepository {
    private final JdbcTemplate db;

    public SavedNoticeRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<UUID> findOwned(UUID user) {
        return db.queryForList("SELECT n.id FROM saved_notice f JOIN notice n ON n.id=f.notice_id WHERE f.user_id=? AND n.basic_review='PASS' AND NOT n.service_excluded AND (n.source_key IS NULL OR n.source_key NOT LIKE 'local-sample-%') ORDER BY f.created_at DESC,n.id", UUID.class, user);
    }

    public void remove(UUID user, UUID notice) {
        db.update("DELETE FROM saved_notice WHERE user_id=? AND notice_id=?", user, notice);
    }

    public Optional<Integer> lockPublicVersion(UUID notice) {
        return db.queryForList("SELECT version FROM notice WHERE id=? AND basic_review='PASS' AND NOT service_excluded AND (source_key IS NULL OR source_key NOT LIKE 'local-sample-%') FOR SHARE", Integer.class, notice)
                .stream().findFirst();
    }

    public void save(UUID user, UUID notice, int version) {
        db.update("INSERT INTO saved_notice(user_id,notice_id,saved_version) VALUES(?,?,?) ON CONFLICT DO NOTHING", user, notice, version);
    }
}
