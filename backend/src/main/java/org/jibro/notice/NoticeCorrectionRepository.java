package org.jibro.notice;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class NoticeCorrectionRepository {
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record Evidence(HousingModels.Evidence source, String originalUrl, String correctedUrl,
                    String originalPanId, String correctedPanId, String originalHtmlSha256, String correctedHtmlSha256,
                    String historyUrl, String historyHtmlSha256, ReviewedLegacyCorrection.Review attachmentReview) {
        Evidence(HousingModels.Evidence source, String originalUrl, String correctedUrl, String originalPanId,
                 String correctedPanId, String originalHtmlSha256, String correctedHtmlSha256,
                 String historyUrl, String historyHtmlSha256) {
            this(source, originalUrl, correctedUrl, originalPanId, correctedPanId, originalHtmlSha256,
                    correctedHtmlSha256, historyUrl, historyHtmlSha256, null);
        }
    }

    record Link(UUID originalId, UUID correctedId, int originalRevision, int correctedRevision,
                String reason, Evidence evidence, Instant checkedAt) {
    }

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public NoticeCorrectionRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    boolean touches(UUID first, UUID second) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notice_correction
                WHERE original_notice_id IN (?,?) OR corrected_notice_id IN (?,?))
                """, Boolean.class, first, second, first, second));
    }

    void insert(Link link) {
        db.update("""
                        INSERT INTO notice_correction(original_notice_id,corrected_notice_id,original_revision,
                            corrected_revision,reason,evidence,checked_at) VALUES(?,?,?,?,?,?::jsonb,?)
                        """, link.originalId(), link.correctedId(), link.originalRevision(), link.correctedRevision(),
                link.reason(), json.writeValueAsString(link.evidence()), java.sql.Timestamp.from(link.checkedAt()));
    }

    /**
     * Serialize graph changes, never remote reads. Shared by editorial and automatic linking.
     */
    void lockGraph() {
        db.execute("SELECT pg_advisory_xact_lock(746214031)");
    }

    void replaceLatest(Link link) {
        var previous = find(link.originalId());
        if (previous.isEmpty()) {
            insert(link);
            return;
        }
        if (previous.get().correctedId().equals(link.correctedId())
                && previous.get().originalRevision() == link.originalRevision()
                && previous.get().correctedRevision() == link.correctedRevision()) return;
        db.update("""
                        UPDATE notice_correction SET verification_history=verification_history || jsonb_build_array(
                            jsonb_build_object('correctedId',corrected_notice_id,'originalRevision',original_revision,
                              'correctedRevision',corrected_revision,'reason',reason,'evidence',evidence,'checkedAt',checked_at)),
                          corrected_notice_id=?,original_revision=?,corrected_revision=?,reason=?,evidence=?::jsonb,checked_at=?
                        WHERE original_notice_id=?
                        """, link.correctedId(), link.originalRevision(), link.correctedRevision(), link.reason(),
                json.writeValueAsString(link.evidence()), java.sql.Timestamp.from(link.checkedAt()), link.originalId());
    }

    Optional<Link> find(UUID originalId) {
        return db.query("""
                        SELECT original_notice_id,corrected_notice_id,original_revision,corrected_revision,reason,
                               evidence::text,checked_at FROM notice_correction WHERE original_notice_id=?
                        """, (r, i) -> new Link(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getInt(3), r.getInt(4),
                        r.getString(5), json.readValue(r.getString(6), Evidence.class), r.getTimestamp(7).toInstant()), originalId)
                .stream().findFirst();
    }

    /**
     * Called under both notice locks after a fresh, bidirectional official verification.
     */
    boolean reverify(Link previous, Link current) {
        return db.update("""
                        UPDATE notice_correction SET verification_history = verification_history || jsonb_build_array(
                            jsonb_build_object('originalRevision',original_revision,'correctedRevision',corrected_revision,
                                'reason',reason,'evidence',evidence,'checkedAt',checked_at)),
                            original_revision=?,corrected_revision=?,reason=?,evidence=?::jsonb,checked_at=?
                        WHERE original_notice_id=? AND corrected_notice_id=? AND original_revision=? AND corrected_revision=?
                          AND checked_at=? AND evidence=?::jsonb
                        """, current.originalRevision(), current.correctedRevision(), current.reason(),
                json.writeValueAsString(current.evidence()), java.sql.Timestamp.from(current.checkedAt()),
                previous.originalId(), previous.correctedId(), previous.originalRevision(), previous.correctedRevision(),
                java.sql.Timestamp.from(previous.checkedAt()), json.writeValueAsString(previous.evidence())) == 1;
    }

    /**
     * Historical extraction URLs identify the collected source only; the service re-fetches its current HTML.
     */
    List<String> detailCandidates(UUID noticeId) {
        return db.queryForList("""
                SELECT DISTINCT source->>'url' FROM notice_extraction e
                CROSS JOIN LATERAL jsonb_array_elements(e.sources) source
                WHERE e.notice_id=? AND source->>'status'='EXTRACTED'
                  AND source->>'url' LIKE 'https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?%'
                LIMIT 52
                """, String.class, noticeId);
    }
}
