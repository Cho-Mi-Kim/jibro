package org.jibro.notice;

import java.util.List;
import java.util.UUID;

import org.jibro.collection.extraction.ExtractionModels;
import org.jibro.global.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AutomaticReviewRepository {
    public static final String POLICY = "official-rules-v12";
    private static final String EVIDENCE_TOKEN = "encode(sha256(convert_to(jsonb_build_object('sources',e.sources,'status',e.status,'checkedAt',extract(epoch FROM e.finished_at))::text,'UTF8')),'hex')";
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public AutomaticReviewRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record ExtractionSnapshot(ExtractionModels.Draft draft, String fingerprint) {
    }

    /**
     * Read the exact evidence and its identity together, before compilation starts.
     */
    public ExtractionSnapshot snapshot(UUID id) {
        return db.query("SELECT e.*, " + EVIDENCE_TOKEN + " AS evidence_token FROM notice_extraction e WHERE e.id=?", (rs, row) -> {
            var finished = rs.getTimestamp("finished_at");
            var draft = new ExtractionModels.Draft(rs.getString("id"), rs.getString("notice_id"), rs.getInt("source_revision"),
                rs.getString("status"), finished == null ? null : finished.toInstant(), rs.getString("message"),
                List.of(json.readValue(rs.getString("sources"), ExtractionModels.Source[].class)));
            return new ExtractionSnapshot(draft, rs.getString("evidence_token"));
        }, id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    /**
     * Caller holds the notice lock also used by extraction replacement and per-file retries.
     */
    public boolean matchesCurrentEvidence(UUID notice, int revision, UUID extraction, String fingerprint) {
        if (fingerprint == null) return false;
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM notice_extraction e WHERE e.id=? AND e.notice_id=? AND e.source_revision=?"
                + " AND e.status IN ('READY','PARTIAL','FAILED') AND e.finished_at IS NOT NULL AND " + EVIDENCE_TOKEN + "=?"
                + " AND e.id=(SELECT x.id FROM notice_extraction x WHERE x.notice_id=e.notice_id AND x.source_revision=e.source_revision ORDER BY x.created_at DESC,x.id DESC LIMIT 1))",
            Boolean.class, extraction, notice, revision, fingerprint));
    }

    public boolean manual(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM review_audit WHERE notice_id=?)", Boolean.class, id));
    }

    public boolean closed(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM notice n WHERE n.id=? AND NOT ("
            + AutomaticReviewEligibility.NOT_CLOSED_SQL + "))", Boolean.class, id));
    }

    public boolean decided(UUID id, int revision, String phase) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM automatic_notice_review WHERE notice_id=? AND applied_revision=? AND phase=? AND policy=?)",
            Boolean.class, id, revision, phase, POLICY));
    }

    public void record(NoticeEntity n, int before, UUID extraction, String evidenceFingerprint, String phase, boolean pass, String reason) {
        record(n, before, extraction, evidenceFingerprint, phase, pass ? "PASS" : "HOLD", reason, List.of());
    }

    /**
     * Preserve the exact unresolved scope even after a later notice version replaces the public content.
     */
    public void record(NoticeEntity n, int before, UUID extraction, String evidenceFingerprint, String phase,
                       String decision, String reason, List<HousingModels.ReviewIssue> reviewIssues) {
        if (!List.of("PASS", "PARTIAL", "HOLD").contains(decision)
            || "PARTIAL".equals(decision) != !reviewIssues.isEmpty()
            || "PARTIAL".equals(decision) && !"GUIDANCE".equals(phase))
            throw new IllegalArgumentException("INVALID_AUTOMATIC_REVIEW_DISPOSITION");
        db.update("INSERT INTO automatic_notice_review(notice_id,source_revision,applied_revision,extraction_id,extraction_fingerprint,policy,phase,decision,reason,source_fingerprint,review_issues,content_fingerprint) VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex'))",
            n.id, before, n.revision, extraction, evidenceFingerprint, POLICY, phase, decision, reason,
            n.sourceFingerprint, json.writeValueAsString(reviewIssues), n.content);
    }

    public boolean decidedExtraction(UUID extraction, String fingerprint) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM automatic_notice_review WHERE extraction_id=? AND extraction_fingerprint=? AND policy=?)", Boolean.class, extraction, fingerprint, POLICY));
    }

    public List<UUID> pendingExtraction() {
        return db.queryForList("""
            SELECT n.id FROM notice n WHERE n.source_key LIKE 'LH:%%' AND n.basic_review='PASS'
            AND n.guide_review='HOLD' AND NOT n.automatic_review_blocked
            AND n.lifecycle_state='NORMAL' AND n.collected_at IS NOT NULL AND n.source_fingerprint IS NOT NULL
            AND NOT EXISTS(SELECT 1 FROM review_audit m WHERE m.notice_id=n.id)
            AND NOT EXISTS(SELECT 1 FROM notice_extraction e WHERE e.notice_id=n.id AND e.source_revision=n.revision)
            AND %s
            ORDER BY n.collected_at DESC LIMIT 10
            """.formatted(AutomaticReviewEligibility.NOT_CLOSED_SQL), UUID.class);
    }

    public List<UUID> pendingBasic() {
        return db.queryForList("""
            SELECT n.id FROM notice n WHERE n.source_key LIKE 'LH:%%' AND n.collected_at IS NOT NULL
            AND n.basic_review='HOLD' AND n.source_fingerprint IS NOT NULL AND NOT n.automatic_review_blocked
            AND n.lifecycle_state='NORMAL'
            AND %s
            AND NOT EXISTS(SELECT 1 FROM review_audit m WHERE m.notice_id=n.id)
            AND NOT EXISTS(SELECT 1 FROM automatic_notice_review a WHERE a.notice_id=n.id
                AND a.applied_revision=n.revision AND a.phase='BASIC' AND a.policy=?)
            ORDER BY n.collected_at DESC LIMIT 10
            """.formatted(AutomaticReviewEligibility.NOT_CLOSED_SQL), UUID.class, POLICY);
    }

    public boolean requiresFollowupComparison(UUID noticeId) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM notice_correction WHERE corrected_notice_id=?)
                OR EXISTS(SELECT 1 FROM notice n JOIN published_notice_version v ON v.notice_id=n.id
                    WHERE n.id=? AND n.change_review AND v.preparation IS NOT NULL)
            """, Boolean.class, noticeId, noticeId));
    }

    public List<UUID> pendingGuidance() {
        return db.queryForList("""
            SELECT e.id FROM notice_extraction e JOIN notice n ON n.id=e.notice_id
            WHERE e.source_revision=n.revision AND n.basic_review='PASS' AND n.guide_review='HOLD'
            AND n.source_key LIKE 'LH:%%' AND NOT n.automatic_review_blocked AND e.status IN ('READY','PARTIAL','FAILED')
            AND n.lifecycle_state='NORMAL' AND n.collected_at IS NOT NULL AND n.source_fingerprint IS NOT NULL
            AND NOT EXISTS(SELECT 1 FROM review_audit m WHERE m.notice_id=n.id)
            AND NOT EXISTS(SELECT 1 FROM automatic_notice_review a WHERE a.extraction_id=e.id AND a.policy=? AND a.extraction_fingerprint=%s)
            AND e.id=(SELECT x.id FROM notice_extraction x WHERE x.notice_id=n.id AND x.source_revision=n.revision ORDER BY x.created_at DESC,x.id DESC LIMIT 1)
            AND %s
            ORDER BY e.finished_at LIMIT 10
            """.formatted(EVIDENCE_TOKEN, AutomaticReviewEligibility.NOT_CLOSED_SQL), UUID.class, POLICY);
    }
}
