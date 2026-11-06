package org.jibro.member;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jibro.global.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedNoticeService {
    public record Saved(UUID id, String title, String agency, String region, LocalDate endDate) {
    }

    private final JdbcTemplate db;

    public SavedNoticeService(JdbcTemplate db) {
        this.db = db;
    }

    public List<Saved> list(UUID user) {
        return db.query("SELECT n.id,n.title,n.agency,n.region,n.end_date FROM saved_notice f JOIN notice n ON n.id=f.notice_id WHERE f.user_id=? AND n.basic_review='PASS' ORDER BY f.created_at DESC,n.id",
            (r, i) -> new Saved(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4), r.getObject(5, LocalDate.class)), user);
    }

    @Transactional
    public void set(UUID user, UUID notice, boolean saved) {
        if (!saved) {
            db.update("DELETE FROM saved_notice WHERE user_id=? AND notice_id=?", user, notice);
            return;
        }
        var version = db.queryForList("SELECT version FROM notice WHERE id=? AND basic_review='PASS' FOR SHARE", Integer.class, notice);
        if (version.isEmpty()) throw ApiException.notFound();
        db.update("INSERT INTO saved_notice(user_id,notice_id,saved_version) VALUES(?,?,?) ON CONFLICT DO NOTHING", user, notice, version.get(0));
    }
}
