package org.jibro.collection.regions;

import java.util.*;

import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class SupplyRegionRepository {
    record Job(UUID id, String url, String sourceKey, String fingerprint, String listingRegion, UUID token) {
    }

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public SupplyRegionRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    void enqueue(UUID id, LhNoticeClient.Item item) {
        db.update("""
                INSERT INTO notice_supply_scope(notice_id,detail_url,source_fingerprint)
                SELECT id,?,source_fingerprint FROM notice WHERE id=? AND source_key=? AND source_fingerprint=?
                ON CONFLICT(notice_id) DO UPDATE SET detail_url=excluded.detail_url, source_fingerprint=excluded.source_fingerprint,
                    evidence='{}',checked_at=NULL,next_check_at=now(),token=NULL
                WHERE notice_supply_scope.source_fingerprint<>excluded.source_fingerprint OR notice_supply_scope.detail_url<>excluded.detail_url
                """, item.url(), id, item.key(), item.fingerprint());
    }

    Optional<Job> claim() {
        return db.query("""
                        WITH candidate AS (
                            SELECT q.notice_id,n.source_key,n.region FROM notice_supply_scope q JOIN notice n ON n.id=q.notice_id
                            WHERE q.next_check_at <= now() AND n.source_fingerprint=q.source_fingerprint
                              AND n.basic_review='PASS' AND NOT n.service_excluded AND n.lifecycle_state='NORMAL'
                              AND ((NOT n.source_closed AND NOT n.service_closed AND n.content->>'applicationClosure' IS NULL
                                    AND (n.end_date IS NULL OR n.end_date >= (now() AT TIME ZONE 'Asia/Seoul')::date))
                                   OR n.end_date BETWEEN (now() AT TIME ZONE 'Asia/Seoul')::date-28 AND (now() AT TIME ZONE 'Asia/Seoul')::date)
                              AND NOT EXISTS (SELECT 1 FROM notice_correction c JOIN notice t ON t.id=c.corrected_notice_id
                                   WHERE c.original_notice_id=n.id AND t.basic_review='PASS' AND NOT t.service_excluded AND starts_with(t.source_key,'LH:'))
                            ORDER BY (right(n.region,2)=' 외') DESC,q.next_check_at,q.notice_id
                            LIMIT 1 FOR UPDATE OF q SKIP LOCKED
                        ) UPDATE notice_supply_scope q SET token=?,next_check_at=now()+interval '5 minutes'
                        FROM candidate c WHERE c.notice_id=q.notice_id
                        RETURNING q.notice_id,q.detail_url,c.source_key,q.source_fingerprint,c.region,q.token
                        """, (rs, i) -> new Job(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class)),
                UUID.randomUUID()).stream().findFirst();
    }

    List<Source> extractedAttachments(Job job) {
        return db.query("""
                        SELECT x.sources::text FROM notice_extraction x JOIN notice n ON n.id=x.notice_id
                        WHERE n.id=? AND x.source_fingerprint=? AND x.source_revision=n.revision
                        ORDER BY x.created_at DESC,x.id DESC LIMIT 1
                        """, (rs, i) -> List.of(json.readValue(rs.getString(1), Source[].class)), job.id(), job.fingerprint())
                .stream().findFirst().orElse(List.of());
    }

    void finish(Job job, Map<String, OfficialSupplyRegions.Proof> regions) {
        db.update("""
                UPDATE notice_supply_scope q SET evidence=coalesce(cast(? as jsonb),q.evidence),
                    checked_at=CASE WHEN ? THEN now() ELSE checked_at END,token=NULL,next_check_at=now()+make_interval(mins=>?)
                FROM notice n WHERE n.id=q.notice_id AND q.notice_id=? AND q.token=? AND q.source_fingerprint=n.source_fingerprint
                """, regions == null ? null : json.writeValueAsString(regions), regions != null, regions == null ? 15 : 360, job.id(), job.token());
    }
}
