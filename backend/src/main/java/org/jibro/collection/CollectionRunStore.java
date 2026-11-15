package org.jibro.collection;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import org.jibro.notice.HousingModels.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// JdbcTemplate translates database errors; lease/domain exceptions must keep their original type.
@Component
public class CollectionRunStore {
    public record PageProgress(int completedScope, int completedPage, String signature,
                               int nextScope, int nextPage, int processed, boolean truncated) {
    }

    public record Work(UUID id, UUID token, String actor, int page, int processed, int retriesUsed, LocalDate queryDate,
                       int issues, List<String> categories, int scopeIndex, LocalDate queryFrom) {
        public CollectionQueryPlan.Query query(int index) {
            return new CollectionQueryPlan.Query(categories.get(index), queryFrom, queryDate);
        }
    }

    private final JdbcTemplate db;
    private final CollectionQueryPlan plan;
    private static final Duration LEASE = Duration.ofMinutes(2);

    public CollectionRunStore(JdbcTemplate db, CollectionQueryPlan plan) {
        this.db = db;
        this.plan = plan;
    }

    @Transactional
    public UUID enqueueManual(String actor, int page, Instant now) {
        if (page < 1 || page > CollectionQueryPlan.MAX_PAGE) throw new IllegalArgumentException();
        UUID id = UUID.randomUUID();
        LocalDate through = now.atZone(CollectionSchedule.ZONE).toLocalDate();
        db.update("INSERT INTO collection_run(id,status,message,actor_id,start_page,next_page,next_attempt_at,started_at,query_date,category_codes,query_from) VALUES (?,'QUEUED','공식 목록 수집 대기',?,?,?,?,?,?,?,?)",
            id, UUID.fromString(actor), page, page, Timestamp.from(now), Timestamp.from(now), through, plan.categoryCodes(), plan.from(through));
        return id;
    }

    @Transactional
    public void enqueueScheduled(Instant slot, Instant now) {
        // Only the current/latest scheduled slot is recovered after downtime; no burst of old hourly jobs.
        LocalDate through = slot.atZone(CollectionSchedule.ZONE).toLocalDate();
        db.update("INSERT INTO collection_run(id,status,message,trigger_type,scheduled_slot,next_attempt_at,started_at,query_date,category_codes,query_from) VALUES (?,'QUEUED','예약 공고 수집 대기','SCHEDULED',?,?,?,?,?,?) ON CONFLICT(scheduled_slot) WHERE scheduled_slot IS NOT NULL DO NOTHING",
            UUID.randomUUID(), Timestamp.from(slot), Timestamp.from(now), Timestamp.from(now), through, plan.categoryCodes(), plan.from(through));
    }

    @Transactional
    public Work claim(UUID requested, Instant now) {
        // A short cross-process mutex serializes claim/recovery, never held during external requests.
        db.queryForObject("SELECT pg_advisory_xact_lock(718421004)", Object.class);
        var stale = db.queryForList("SELECT id,retries_used FROM collection_run WHERE status='RUNNING' AND lease_until<=? FOR UPDATE", Timestamp.from(now));
        for (var row : stale) {
            int retries = ((Number) row.get("retries_used")).intValue();
            db.update("UPDATE collection_run SET status=?,retries_used=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,message=?,finished_at=? WHERE id=?",
                retries < 3 ? "RETRY_WAIT" : "FAILED", Math.min(3, retries + 1), retries < 3 ? Timestamp.from(now) : null,
                retries < 3 ? "중단된 수집을 마지막 페이지부터 재개합니다." : "실행 중단 재시도 한도에 도달했습니다. 수동 확인이 필요합니다.",
                retries < 3 ? null : Timestamp.from(now), row.get("id"));
        }
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM collection_run WHERE status='RUNNING')", Boolean.class)))
            return null;
        // Provider restrictions outlive an individual run, including one whose retry budget is exhausted.
        if (resumeAfter(now) != null) return null;
        String sql = "SELECT id,actor_id,next_page,processed_count,retries_used,query_date,issue_count,category_codes,scope_index,query_from FROM collection_run WHERE status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=?";
        List<Map<String, Object>> rows = requested == null
            ? db.queryForList(sql + " ORDER BY next_attempt_at,started_at,id LIMIT 1 FOR UPDATE", Timestamp.from(now))
            : db.queryForList(sql + " AND id=? FOR UPDATE", Timestamp.from(now), requested);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        UUID token = UUID.randomUUID();
        UUID id = (UUID) row.get("id");
        db.update("UPDATE collection_run SET status='RUNNING',attempt=attempt+1,lease_token=?,lease_until=?,next_attempt_at=NULL,message='공식 목록 수집 중' WHERE id=?", token, Timestamp.from(now.plus(LEASE)), id);
        return new Work(id, token, row.get("actor_id") == null ? null : row.get("actor_id").toString(), ((Number) row.get("next_page")).intValue(),
            ((Number) row.get("processed_count")).intValue(), ((Number) row.get("retries_used")).intValue(),
            ((java.sql.Date) row.get("query_date")).toLocalDate(), ((Number) row.get("issue_count")).intValue(),
            CollectionQueryPlan.parseCategories((String) row.get("category_codes")), ((Number) row.get("scope_index")).intValue(),
            ((java.sql.Date) row.get("query_from")).toLocalDate());
    }

    @Transactional
    public void heartbeat(Work work, Instant now) {
        lock(work, now);
    }

    @Transactional
    public <T> T withinLease(Work work, Instant now, Supplier<T> task) {
        lock(work, now);
        return task.get();
    }

    @Transactional
    public void requireNewPage(Work work, int scopeIndex, int page, String signature, Instant now) {
        lock(work, now);
        if (signature != null && Boolean.TRUE.equals(db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM collection_page_checkpoint WHERE run_id=? AND scope_index=? AND item_signature=? AND page<>?)",
            Boolean.class, work.id(), scopeIndex, signature, page)))
            throw new IllegalArgumentException("SOURCE_PAGINATION_REPEATED");
    }

    @Transactional
    public void checkpoint(Work work, PageProgress page, Instant now) {
        lock(work, now);
        if (page.signature() != null)
            db.update("INSERT INTO collection_page_checkpoint(run_id,scope_index,page,item_signature) VALUES(?,?,?,?) "
                    + "ON CONFLICT(run_id,scope_index,page) DO UPDATE SET item_signature=excluded.item_signature",
                work.id(), page.completedScope(), page.completedPage(), page.signature());
        db.update("UPDATE collection_run SET scope_index=?,next_page=?,processed_count=?,issue_count=issue_count+? WHERE id=?",
            page.nextScope(), page.nextPage(), page.processed(), page.truncated() ? 1 : 0, work.id());
    }

    @Transactional
    public void recordIssues(Work work, int issues, Instant now) {
        lock(work, now);
        db.update("UPDATE collection_run SET issue_count=issue_count+? WHERE id=?", issues, work.id());
    }

    @Transactional
    public void continuePages(Work work, String message, Instant now) {
        lock(work, now);
        db.update("UPDATE collection_run SET status='QUEUED',message=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL WHERE id=?",
            message, Timestamp.from(now), work.id());
    }

    @Transactional
    public void complete(Work work, String status, String message, Instant now) {
        lock(work, now);
        db.update("UPDATE collection_run SET status=?,message=?,finished_at=?,lease_token=NULL,lease_until=NULL,next_attempt_at=NULL WHERE id=?", status, message, Timestamp.from(now), work.id());
    }

    @Transactional
    public void failure(Work work, Throwable error, Instant now) {
        lock(work, now);
        Duration providerDelay = CollectionFailure.providerDelay(error);
        Instant providerDue = providerDelay == null ? null : now.plus(providerDelay);
        if (providerDue != null) {
            db.update("INSERT INTO collection_provider_hold(provider,hold_until,source_run_id) VALUES ('LH',?,?) "
                    + "ON CONFLICT(provider) DO UPDATE SET hold_until=GREATEST(collection_provider_hold.hold_until,excluded.hold_until),"
                    + "source_run_id=CASE WHEN excluded.hold_until>collection_provider_hold.hold_until THEN excluded.source_run_id ELSE collection_provider_hold.source_run_id END,updated_at=now()",
                Timestamp.from(providerDue), work.id());
        }
        boolean retry = CollectionFailure.retryable(error) && work.retriesUsed() < 3;
        Instant due = retry ? now.plus(CollectionFailure.delay(error, work.retriesUsed())) : null;
        String message = retry ? "일시적 외부 오류입니다. " + due.atZone(CollectionSchedule.ZONE).toLocalDateTime() + " 이후 마지막 페이지부터 재시도합니다."
            : "공식 API 호출 또는 응답 검증에 실패했습니다. 자동 재시도 대상이 아니거나 한도에 도달했습니다. 관리자 확인이 필요합니다.";
        if (!retry && providerDue != null)
            message += " 공식 서버의 요청 대기는 " + providerDue.atZone(CollectionSchedule.ZONE).toLocalDateTime() + "까지이며 새 수집에도 적용됩니다.";
        db.update("UPDATE collection_run SET status=?,retries_used=?,next_attempt_at=?,message=?,finished_at=?,lease_token=NULL,lease_until=NULL WHERE id=?",
            retry ? "RETRY_WAIT" : "FAILED", work.retriesUsed() + (retry ? 1 : 0), due == null ? null : Timestamp.from(due),
            message, retry ? null : Timestamp.from(now), work.id());
    }

    @Transactional(readOnly = true)
    public Instant providerResumeAt(Instant now) {
        return resumeAfter(now);
    }

    private Instant resumeAfter(Instant now) {
        Timestamp due = db.queryForObject("SELECT max(due) FROM (SELECT hold_until AS due FROM collection_provider_hold WHERE provider='LH' "
                + "UNION ALL SELECT next_attempt_at AS due FROM collection_run WHERE status='RETRY_WAIT') delays WHERE due>?",
            Timestamp.class, Timestamp.from(now));
        return due == null ? null : due.toInstant();
    }

    private void lock(Work work, Instant now) {
        var owned = db.queryForList("SELECT id FROM collection_run WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>? FOR UPDATE", work.id(), work.token(), Timestamp.from(now));
        if (owned.isEmpty()) throw new LeaseLost();
        db.update("UPDATE collection_run SET lease_until=? WHERE id=?", Timestamp.from(now.plus(LEASE)), work.id());
    }

    @Transactional(readOnly = true)
    public Collection view(UUID id) {
        return db.queryForObject("SELECT id,status,started_at,message FROM collection_run WHERE id=?", (rs, n) ->
            new Collection(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant().toString(), rs.getString(4)), id);
    }

    @Transactional(readOnly = true)
    public List<Collection> list() {
        return db.query("SELECT id,status,started_at,message FROM collection_run ORDER BY started_at DESC,id DESC LIMIT 100", (rs, n) ->
            new Collection(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant().toString(), rs.getString(4)));
    }

    @Transactional(readOnly = true)
    public int pending() {
        return db.queryForObject("SELECT count(*) FROM collection_run WHERE status IN ('QUEUED','RETRY_WAIT','RUNNING')", Integer.class);
    }

    public static final class LeaseLost extends IllegalStateException {
    }
}
