package org.jibro.notice;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SourceReviewRepository {
    private final JdbcTemplate db;
    public SourceReviewRepository(JdbcTemplate db) { this.db = db; }
    public boolean applied(String fingerprint) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM notice_source_review WHERE package_fingerprint=?)", Boolean.class, fingerprint));
    }
    /** Amend only the exact AI-assisted publication the editor reviewed, never an intervening decision. */
    public boolean matchesPublication(UUID id, int revision, String fingerprint, String content) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM notice_source_review r WHERE r.notice_id=? AND r.result_revision=?
                AND r.package_fingerprint=? AND r.disposition IN ('PASS','PARTIAL')
                AND r.payload #> '{review,content}' = ?::jsonb
                AND NOT EXISTS(SELECT 1 FROM review_audit a WHERE a.notice_id=r.notice_id))
            """, Boolean.class, id, revision, fingerprint, content));
    }
    public void record(UUID id, int before, int after, String outcome, String fingerprint, String payload) {
        db.update("INSERT INTO notice_source_review(id,notice_id,source_revision,result_revision,disposition,package_fingerprint,reviewer_kind,payload) VALUES(?,?,?,?,?,?,'AI_ASSISTED_SOURCE_REVIEW',?::jsonb)",
            UUID.randomUUID(), id, before, after, outcome, fingerprint, payload);
    }
}
