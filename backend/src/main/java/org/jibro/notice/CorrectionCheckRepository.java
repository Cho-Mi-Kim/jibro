package org.jibro.notice;

import java.util.*;

import org.jibro.collection.LhNoticeClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable, bounded collection follow-up. No remote requests or publication decisions here.
 */
@Repository
public class CorrectionCheckRepository {
    public record Job(UUID noticeId, String url, String fingerprint, UUID token) {
    }

    public record Source(UUID id, String url) {
    }

    private final JdbcTemplate db;

    public CorrectionCheckRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void enqueue(UUID id, LhNoticeClient.Item item) {
        var identity = OfficialCorrectionLink.identity(item.url());
        db.update("""
            INSERT INTO notice_correction_check(notice_id,official_url,identity_key,source_fingerprint)
            SELECT n.id,?,?,coalesce(n.source_fingerprint,'') FROM notice n WHERE n.id=? AND n.source_key=?
              AND %s
            ON CONFLICT(notice_id) DO UPDATE SET official_url=excluded.official_url,
              identity_key=excluded.identity_key, source_fingerprint=excluded.source_fingerprint,
              state='PENDING',token=NULL,lease_until=NULL,next_attempt_at=now(),attempts=0,result_code='SOURCE_CHANGED'
            WHERE notice_correction_check.source_fingerprint <> excluded.source_fingerprint
              OR notice_correction_check.official_url <> excluded.official_url
            """.formatted(AutomaticReviewEligibility.NOT_CLOSED_SQL), item.url(), key(identity), id, item.key());
    }

    public Optional<Job> claim() {
        return claim(null);
    }

    /**
     * Explicit bounded backfill may retry one ID immediately; all publication/closure/lease guards remain.
     */
    public Optional<Job> claim(UUID requestedId) {
        UUID token = UUID.randomUUID();
        String requested = requestedId == null ? "q.next_attempt_at<=now()" : "q.notice_id=CAST(? AS uuid)";
        Object[] args = requestedId == null ? new Object[]{token} : new Object[]{requestedId, token};
        return db.query("""
                WITH candidate AS (
                  SELECT q.notice_id FROM notice_correction_check q JOIN notice n ON n.id=q.notice_id
                  WHERE %s AND (q.lease_until IS NULL OR q.lease_until<now())
                    AND n.basic_review='PASS' AND n.lifecycle_state='NORMAL'
                    AND %s
                  ORDER BY CASE WHEN n.title LIKE '%%정정%%' THEN 0 ELSE 1 END,
                    q.next_attempt_at,n.published_date DESC NULLS LAST,q.notice_id
                  FOR UPDATE OF q SKIP LOCKED LIMIT 1
                ) UPDATE notice_correction_check q SET state='RUNNING',token=?,lease_until=now()+interval '5 minutes',
                    attempts=attempts+1 FROM candidate c WHERE q.notice_id=c.notice_id
                  RETURNING q.notice_id,q.official_url,q.source_fingerprint,q.token
                """.formatted(requested, AutomaticReviewEligibility.NOT_CLOSED_SQL), (r, i) -> new Job(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getObject(4, UUID.class)), args)
            .stream().findFirst();
    }

    boolean current(Job job) {
        return Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM notice_correction_check q JOIN notice n ON n.id=q.notice_id
              WHERE q.notice_id=? AND q.token=? AND q.state='RUNNING' AND q.lease_until>now()
                AND q.source_fingerprint=? AND coalesce(n.source_fingerprint,'')=q.source_fingerprint)
            """, Boolean.class, job.noticeId(), job.token(), job.fingerprint()));
    }

    public void finish(Job job, String code) {
        String state = code.equals("LINKED") ? "LINKED" : code.equals("NO_CORRECTION") ? "UNCHANGED" : "DEFERRED";
        int seconds = state.equals("LINKED") ? 21600 : state.equals("UNCHANGED") ? 86400 : 600;
        db.update("""
            UPDATE notice_correction_check SET state=?,result_code=?,checked_at=now(),token=NULL,lease_until=NULL,
                next_attempt_at=now()+make_interval(secs => ? * CASE WHEN ?='DEFERRED' THEN least(attempts,36) ELSE 1 END)
            WHERE notice_id=? AND token=? AND source_fingerprint=?
            """, state, code, seconds, state, job.noticeId(), job.token(), job.fingerprint());
    }

    List<Source> sources(String officialUrl) {
        var identity = OfficialCorrectionLink.identity(officialUrl);
        // Queue identity is indexed. Older records may only retain the source in evidence/extraction.
        var candidates = db.query("""
            SELECT n.id,q.official_url,n.source_key FROM notice_correction_check q JOIN notice n ON n.id=q.notice_id WHERE q.identity_key=?
            LIMIT 100
            """, (r, i) -> new String[]{r.getString(1), r.getString(2), r.getString(3)}, key(identity));
        if (candidates.isEmpty()) candidates = db.query("""
                SELECT id,content#>>'{evidence,url}',source_key FROM notice WHERE content#>>'{evidence,url}' ~ ?
                UNION
                SELECT DISTINCT n.id,s->>'url',n.source_key FROM notice_extraction e JOIN notice n ON n.id=e.notice_id
                  CROSS JOIN LATERAL jsonb_array_elements(e.sources) s WHERE s->>'url' ~ ?
                LIMIT 100
                """, (r, i) -> new String[]{r.getString(1), r.getString(2), r.getString(3)},
            "[?&]panId=" + identity.panId() + "(&|$)", "[?&]panId=" + identity.panId() + "(&|$)");
        var found = new LinkedHashMap<UUID, Source>();
        for (var row : candidates) {
            try {
                if (OfficialCorrectionLink.identity(row[1]).equals(identity) && LhNoticeClient.sourceKey(row[1]).equals(row[2])) {
                    UUID id = UUID.fromString(row[0]);
                    found.put(id, new Source(id, row[1]));
                }
            } catch (IllegalArgumentException ignored) { /* Not the bound official source. */ }
        }
        return List.copyOf(found.values());
    }

    private static String key(OfficialCorrectionLink.Identity id) {
        return String.join(":", id.system(), id.category(), id.type(), id.panId());
    }
}
