package org.jibro.notice;

import java.util.UUID;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SourceReviewRepository {
    private final JdbcTemplate db;

    public SourceReviewRepository(JdbcTemplate db) {
        this.db = db;
    }

    public boolean applied(String fingerprint) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM notice_source_review WHERE package_fingerprint=?)", Boolean.class, fingerprint));
    }

    /**
     * Amend only the exact AI-assisted publication the editor reviewed, never an intervening decision.
     */
    public boolean matchesPublication(UUID id, int revision, String fingerprint, String content) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM notice_source_review r WHERE r.notice_id=? AND r.result_revision=?
                AND r.package_fingerprint=? AND r.disposition IN ('PASS','PARTIAL')
                AND (CASE WHEN r.payload->>'scope' = 'SUMMARY_ONLY_EXPLICIT_EDIT'
                          AND NOT (r.payload ?? 'review')
                          THEN r.payload->'afterContent'
                          ELSE r.payload #> '{review,content}' END) = ?::jsonb
                AND NOT EXISTS(SELECT 1 FROM review_audit a WHERE a.notice_id=r.notice_id))
            """, Boolean.class, id, revision, fingerprint, content));
    }

    /**
     * PostgreSQL jsonb canonicalization is also used by the explicit local review tool.
     */
    public String contentFingerprint(String content) {
        return db.queryForObject("SELECT encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex')", String.class, content);
    }

    /**
     * Require the latest source publication for this notice, without superseding a human decision.
     */
    public Optional<String> editorialPredecessor(UUID id, int revision, String fingerprint) {
        var rows = db.queryForList("""
            SELECT r.payload::text FROM notice_source_review r
            WHERE r.notice_id=? AND r.result_revision<=? AND r.package_fingerprint=?
              AND r.disposition IN ('PASS','PARTIAL') AND r.payload ?? 'review'
              AND NOT EXISTS(SELECT 1 FROM notice_source_review newer WHERE newer.notice_id=r.notice_id AND newer.id<>r.id
                  AND (newer.result_revision>=r.result_revision OR newer.reviewed_at>r.reviewed_at))
              AND NOT EXISTS(SELECT 1 FROM review_audit a WHERE a.notice_id=r.notice_id)
            """, String.class, id, revision, fingerprint);
        return rows.stream().findFirst();
    }

    public void record(UUID id, int before, int after, String outcome, String fingerprint, String payload) {
        db.update("INSERT INTO notice_source_review(id,notice_id,source_revision,result_revision,disposition,package_fingerprint,reviewer_kind,payload) VALUES(?,?,?,?,?,?,'AI_ASSISTED_SOURCE_REVIEW',?::jsonb)",
            UUID.randomUUID(), id, before, after, outcome, fingerprint, payload);
    }
}
