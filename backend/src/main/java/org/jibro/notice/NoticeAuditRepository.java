package org.jibro.notice;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NoticeAuditRepository {
    private final JdbcTemplate db;

    public NoticeAuditRepository(JdbcTemplate db) {
        this.db = db;
    }

    public boolean hadPublishedGuidance(UUID notice, String source, Instant deadline) {
        Timestamp cutoff = Timestamp.from(deadline);
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notice_source_review
                    WHERE notice_id=? AND disposition IN ('PASS','PARTIAL') AND reviewed_at<?
                    AND payload->'review'->'content'->'evidence'->>'url'=?
                    UNION ALL SELECT 1 FROM published_notice_version
                    WHERE notice_id=? AND published_at<? AND facts->>'source'=?
                    AND facts->>'guidance' IN ('제공','제공 · 일부 확인 필요'))
                """, Boolean.class, notice, cutoff, source, notice, cutoff, source));
    }

    public boolean listedAsAcceptingAfter(String source, Instant reviewed, Instant now) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM collection_response_attempt a
                    JOIN collection_response_payload p ON p.sha256=a.sha256
                    WHERE a.provider='LH' AND a.outcome='ACCEPTED' AND a.http_status=200
                    AND a.received_at>? AND a.received_at<=? AND EXISTS(
                        SELECT 1 FROM jsonb_path_query(CAST(p.body_json AS jsonb),'$[*].dsList[*]') row
                        WHERE row->>'DTL_URL'=? AND row->>'PAN_SS'='접수중'))
                """, Boolean.class, Timestamp.from(reviewed), Timestamp.from(now), source));
    }

    public void recordReview(UUID notice, UUID reviewer, int revision, String basic, String guide, String reason) {
        db.update("INSERT INTO review_audit(notice_id,reviewer_id,revision,basic_review,guide_review,reason) VALUES (?,?,?,?,?,?)",
                notice, reviewer, revision, basic, guide, reason);
    }

    public void recordServiceDecision(UUID notice, int revision, String decision, String origin, String reason) {
        db.update("INSERT INTO notice_service_decision(notice_id,revision,decision,origin,reason) VALUES (?,?,?,?,?)",
                notice, revision, decision, origin, reason);
    }

    /**
     * Legacy approved content may predate reviewer audits; an existing audit must match the current decision.
     */
    public boolean permitsInitialSourceBinding(UUID notice, int revision, String guide) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT NOT EXISTS(SELECT 1 FROM review_audit WHERE notice_id=?) OR EXISTS(
                    SELECT 1 FROM review_audit a WHERE a.notice_id=? AND a.revision=?
                    AND a.basic_review='PASS' AND a.guide_review=?
                    AND a.id=(SELECT id FROM review_audit WHERE notice_id=? ORDER BY revision DESC,id DESC LIMIT 1))
                """, Boolean.class, notice, notice, revision, guide, notice));
    }
}
