package org.jibro.collection.attachments;

import java.util.*;

import org.jibro.collection.LhNoticeClient;
import org.jibro.notice.AutomaticReviewEligibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OfficialFileRepository {
    record Job(UUID id, String url, String sourceKey, UUID token) {
    }

    private static final String ACTIVE = """
            n.basic_review='PASS' AND n.lifecycle_state='NORMAL'
            AND n.start_date IS NOT NULL AND n.end_date IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM notice_correction c JOIN notice replacement ON c.corrected_notice_id=replacement.id
                WHERE c.original_notice_id=n.id AND replacement.basic_review='PASS' AND NOT replacement.service_excluded
                  AND starts_with(replacement.source_key, 'LH:'))
            AND %s
            """.formatted(AutomaticReviewEligibility.NOT_CLOSED_SQL);
    private final JdbcTemplate db;

    public OfficialFileRepository(JdbcTemplate db) {
        this.db = db;
    }

    void enqueue(UUID id, LhNoticeClient.Item item) {
        db.update("""
                INSERT INTO notice_file_inventory (notice_id,detail_url,source_fingerprint)
                SELECT n.id,?,n.source_fingerprint FROM notice n
                WHERE n.id=? AND n.source_key=? AND n.source_fingerprint=? AND %s
                ON CONFLICT(notice_id) DO UPDATE SET detail_url=excluded.detail_url,source_fingerprint=excluded.source_fingerprint,
                    manifest=NULL,token=NULL,next_check_at=now()
                WHERE notice_file_inventory.source_fingerprint<>excluded.source_fingerprint
                   OR notice_file_inventory.detail_url<>excluded.detail_url
                """.formatted(AutomaticReviewEligibility.NOT_CLOSED_SQL), item.url(), id, item.key(), item.fingerprint());
    }

    Optional<Job> claim() {
        return db.query("""
                WITH next AS (
                    SELECT q.notice_id,n.source_key FROM notice_file_inventory q JOIN notice n ON n.id=q.notice_id
                    WHERE q.next_check_at<=now() AND q.source_fingerprint=n.source_fingerprint AND %s
                    ORDER BY q.next_check_at,q.notice_id LIMIT 1 FOR UPDATE OF q SKIP LOCKED
                ) UPDATE notice_file_inventory q SET token=?,next_check_at=now()+interval '15 minutes'
                  FROM next WHERE q.notice_id=next.notice_id RETURNING q.notice_id,q.detail_url,next.source_key,q.token
                """.formatted(ACTIVE), (r, i) -> new Job(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                r.getObject(4, UUID.class)), UUID.randomUUID()).stream().findFirst();
    }

    void finish(Job job, String manifest, boolean retry) {
        db.update("""
                UPDATE notice_file_inventory q SET manifest=coalesce(cast(? as jsonb),q.manifest),token=NULL,
                    next_check_at=now()+make_interval(mins => ?)
                FROM notice n WHERE n.id=q.notice_id AND q.notice_id=? AND q.token=?
                   AND n.source_fingerprint=q.source_fingerprint AND %s
                """.formatted(ACTIVE), manifest, retry ? 10 : 60, job.id(), job.token());
    }
}
