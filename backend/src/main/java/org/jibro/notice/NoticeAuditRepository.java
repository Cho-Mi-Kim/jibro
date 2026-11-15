package org.jibro.notice;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NoticeAuditRepository {
    private final JdbcTemplate db;

    public NoticeAuditRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void recordReview(UUID notice, UUID reviewer, int revision, String basic, String guide, String reason) {
        db.update("INSERT INTO review_audit(notice_id,reviewer_id,revision,basic_review,guide_review,reason) VALUES (?,?,?,?,?,?)",
                notice, reviewer, revision, basic, guide, reason);
    }

    public void recordServiceDecision(UUID notice, int revision, String decision, String origin, String reason) {
        db.update("INSERT INTO notice_service_decision(notice_id,revision,decision,origin,reason) VALUES (?,?,?,?,?)",
            notice, revision, decision, origin, reason);
    }

    /** Legacy approved content may predate reviewer audits; an existing audit must match the current decision. */
    public boolean permitsInitialSourceBinding(UUID notice, int revision, String guide) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT NOT EXISTS(SELECT 1 FROM review_audit WHERE notice_id=?) OR EXISTS(
                SELECT 1 FROM review_audit a WHERE a.notice_id=? AND a.revision=?
                AND a.basic_review='PASS' AND a.guide_review=?
                AND a.id=(SELECT id FROM review_audit WHERE notice_id=? ORDER BY revision DESC,id DESC LIMIT 1))
            """, Boolean.class, notice, notice, revision, guide, notice));
    }
}
