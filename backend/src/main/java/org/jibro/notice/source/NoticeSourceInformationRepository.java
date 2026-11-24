package org.jibro.notice.source;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.global.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only, current-source projection. No compiler, collection worker or publication state is invoked.
 */
@Repository
public class NoticeSourceInformationRepository {
    private static final String PUBLIC = "n.basic_review='PASS' AND NOT n.service_excluded AND (n.source_key IS NULL OR n.source_key NOT LIKE 'local-sample-%')";
    private static final String TOKEN = "encode(sha256(convert_to(jsonb_build_object('sources',e.sources,'status',e.status,'checkedAt',extract(epoch FROM e.finished_at))::text,'UTF8')),'hex')";
    private static final String CURRENT = """
        e.notice_id=n.id AND e.source_revision=n.revision
        AND e.source_fingerprint IS NOT DISTINCT FROM n.source_fingerprint
        AND e.status IN ('READY','PARTIAL','FAILED') AND e.finished_at IS NOT NULL
        AND e.id=(SELECT x.id FROM notice_extraction x WHERE x.notice_id=n.id AND x.source_revision=n.revision
            ORDER BY x.created_at DESC,x.id DESC LIMIT 1)
        """;

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public NoticeSourceInformationRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record Snapshot(UUID noticeId, int revision, UUID extractionId, String fingerprint, Instant checkedAt,
                           List<Source> sources) {
    }

    public int publishedRevision(UUID noticeId) {
        return db.query("SELECT n.revision FROM notice n WHERE n.id=? AND " + PUBLIC,
            (rs, row) -> rs.getInt("revision"), noticeId).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    public List<NoticeSourceInformationModels.Section> confirmedItems(UUID noticeId, int revision) {
        // UNCHANGED receipts refer back to the producing bundle. A newer failed check, extraction,
        // changed notice or passed deadline immediately removes these cards from the public view.
        return db.query("""
                SELECT r.sources->'confirmedItems' FROM notice n
                JOIN notice_followup_queue q ON q.notice_id=n.id
                JOIN automatic_followup_review r ON r.notice_id=n.id AND r.bundle_hash=q.last_bundle_hash
                JOIN notice_extraction e ON e.id=r.primary_extraction_id AND e.notice_id=n.id
                WHERE n.id=? AND n.revision=? AND n.basic_review='PASS' AND n.guide_review='HOLD'
                  AND n.lifecycle_state='NORMAL' AND NOT n.automatic_review_blocked AND n.start_date IS NOT NULL AND n.end_date IS NOT NULL
                  AND NOT EXISTS(SELECT 1 FROM notice_correction c WHERE c.original_notice_id=n.id)
                  AND q.last_result_revision=n.revision AND r.result_revision=n.revision
                  AND r.state IN ('ITEMS','INTAKE') AND q.last_outcome IN ('ITEMS','INTAKE','UNCHANGED')
                  AND q.last_content_fingerprint=r.result_content_fingerprint
                  AND r.result_content_fingerprint=encode(sha256(convert_to(n.content::text,'UTF8')),'hex')
                  AND r.source_fingerprint=n.source_fingerprint AND e.source_fingerprint=n.source_fingerprint
                  AND r.primary_fingerprint=%s AND r.policy=q.last_policy
                  AND e.id=(SELECT x.id FROM notice_extraction x WHERE x.notice_id=n.id ORDER BY x.created_at DESC,x.id DESC LIMIT 1)
                  AND jsonb_typeof(r.sources->'confirmedItems')='array' AND %s
                ORDER BY r.finished_at DESC LIMIT 1
                """.formatted(TOKEN, org.jibro.notice.AutomaticReviewEligibility.NOT_CLOSED_SQL),
            (rs, row) -> List.of(json.readValue(rs.getString(1), NoticeSourceInformationModels.Section[].class)),
            noticeId, revision).stream().findFirst().orElse(List.of());
    }

    public Optional<Snapshot> latest(UUID noticeId, int revision) {
        return db.query("SELECT n.revision,e.id,e.sources,e.finished_at," + TOKEN + " AS token FROM notice n JOIN notice_extraction e ON e.notice_id=n.id"
                + " WHERE n.id=? AND n.revision=? AND " + PUBLIC + " AND " + CURRENT,
            (rs, row) -> new Snapshot(noticeId, rs.getInt("revision"), rs.getObject("id", UUID.class), rs.getString("token"),
                rs.getTimestamp("finished_at").toInstant(), List.of(json.readValue(rs.getString("sources"), Source[].class))),
            noticeId, revision).stream().findFirst();
    }

    /**
     * Recheck publication, revision, newest job, source fingerprint and exact extraction content in one query.
     */
    public boolean current(Snapshot snapshot) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM notice n JOIN notice_extraction e ON e.notice_id=n.id"
                + " WHERE n.id=? AND n.revision=? AND e.id=? AND " + PUBLIC + " AND " + CURRENT + " AND " + TOKEN + "=?)",
            Boolean.class, snapshot.noticeId(), snapshot.revision(), snapshot.extractionId(), snapshot.fingerprint()));
    }
}
