package org.jibro.notice;

import java.util.List;
import java.util.UUID;
import org.jibro.collection.review.ApplicationPeriodCompiler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ApplicationPeriodReviewRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ApplicationPeriodReviewRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public List<UUID> pending() {
        return db.queryForList("""
            SELECT e.id FROM notice n JOIN LATERAL (
                SELECT * FROM notice_extraction x WHERE x.notice_id=n.id AND x.source_revision=n.revision
                ORDER BY x.created_at DESC,x.id DESC LIMIT 1
            ) e ON true WHERE n.source_key LIKE 'LH:%%' AND n.basic_review='PASS'
            AND NOT n.automatic_review_blocked AND n.lifecycle_state='NORMAL'
            AND n.source_fingerprint IS NOT NULL AND n.collected_at IS NOT NULL
            AND e.source_fingerprint=n.source_fingerprint AND e.status IN ('READY','PARTIAL')
            AND NOT EXISTS(SELECT 1 FROM review_audit a WHERE a.notice_id=n.id)
            AND NOT EXISTS(SELECT 1 FROM application_period_review r WHERE r.extraction_id=e.id
                AND r.policy=? AND r.checked_at>=e.finished_at)
            AND %s ORDER BY e.finished_at LIMIT 10
            """.formatted(AutomaticReviewEligibility.NOT_CLOSED_SQL), UUID.class, ApplicationPeriodCompiler.POLICY);
    }

    boolean matchesSource(UUID extractionId, String sourceFingerprint) {
        return Boolean.TRUE.equals(db.queryForObject(
            "SELECT source_fingerprint=? FROM notice_extraction WHERE id=?", Boolean.class, sourceFingerprint, extractionId));
    }

    boolean alreadyReviewed(UUID extractionId, String evidenceFingerprint) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM application_period_review WHERE extraction_id=? AND evidence_fingerprint=? AND policy=?)
            """, Boolean.class, extractionId, evidenceFingerprint, ApplicationPeriodCompiler.POLICY));
    }

    void record(UUID noticeId, UUID extractionId, int sourceRevision, int resultRevision, String evidenceFingerprint,
                String decision, ApplicationPeriodCompiler.Result result) {
        db.update("""
            INSERT INTO application_period_review(id,notice_id,extraction_id,source_revision,result_revision,
                evidence_fingerprint,policy,decision,result) VALUES(?,?,?,?,?,?,?,?,?::jsonb)
            """, UUID.randomUUID(), noticeId, extractionId, sourceRevision, resultRevision,
            evidenceFingerprint, ApplicationPeriodCompiler.POLICY, decision, json.writeValueAsString(result));
    }
}
