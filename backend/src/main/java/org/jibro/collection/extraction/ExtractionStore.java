package org.jibro.collection.extraction;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.jibro.global.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.jibro.collection.extraction.ExtractionModels.*;

// This storage boundary also reports domain conflicts; avoid persistence exception translation.
@Component
public class ExtractionStore {
    // Each file checkpoints independently. Allow its bounded download and up to five minutes of OCR
    // to finish before another worker may reclaim the job, including an explicit retry request.
    private static final Duration RUNNING_LEASE = Duration.ofMinutes(10);
    public record Job(UUID id, UUID noticeId, int revision, UUID claimToken) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final EvidenceRelocationStore relocation;
    public ExtractionStore(JdbcTemplate db, ObjectMapper json, EvidenceRelocationStore relocation) { this.db = db; this.json = json; this.relocation = relocation; }

    @Transactional
    public Draft enqueue(UUID noticeId, boolean force) {
        var rows = db.queryForList("SELECT revision,source_fingerprint FROM notice WHERE id=? FOR UPDATE", noticeId);
        if (rows.isEmpty()) throw ApiException.notFound();
        var n = rows.get(0);
        // An old active job must not block extraction of the current source. Its worker token is revoked.
        db.update("""
            UPDATE notice_extraction SET status='SUPERSEDED',claim_token=NULL,finished_at=now(),
                message='공고 또는 원문이 변경되어 이전 발췌 작업을 종료했습니다.'
            WHERE notice_id=? AND status IN ('QUEUED','RUNNING')
                AND (source_revision<>? OR source_fingerprint IS DISTINCT FROM ?)
            """, noticeId, n.get("revision"), n.get("source_fingerprint"));
        // Explicit retry keeps its existing meaning after an interrupted run: create a fresh job.
        if (force) db.update("UPDATE notice_extraction SET status='FAILED',claim_token=NULL,finished_at=now(),message='중단된 실행을 새 발췌 작업으로 대체합니다.' WHERE notice_id=? AND status='RUNNING' AND started_at<now()-(? * interval '1 second')", noticeId, RUNNING_LEASE.toSeconds());
        recover(noticeId);
        var active = db.queryForList("SELECT id FROM notice_extraction WHERE notice_id=? AND status IN ('QUEUED','RUNNING')", noticeId);
        if (!active.isEmpty()) return find((UUID)active.get(0).get("id"));
        if (!force) {
            // Recollection alone does not change evidence or reset the bounded retry budget.
            var completed = db.queryForList("""
                SELECT id FROM notice_extraction WHERE notice_id=? AND source_revision=?
                    AND source_fingerprint IS NOT DISTINCT FROM ?
                    AND status IN ('READY','PARTIAL','FAILED') AND finished_at IS NOT NULL
                ORDER BY created_at DESC,id DESC LIMIT 1
                """, noticeId, n.get("revision"), n.get("source_fingerprint"));
            if (!completed.isEmpty()) return find((UUID) completed.get(0).get("id"));
        }
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO notice_extraction(id,notice_id,source_revision,source_fingerprint,status,message) VALUES(?,?,?,?,'QUEUED','원문 발췌 대기 중')", id, noticeId, n.get("revision"), n.get("source_fingerprint"));
        return find(id);
    }

    @Transactional
    public Job claim() {
        return claimNext(null);
    }

    @Transactional
    public Job claim(UUID noticeId) {
        return claimNext(Objects.requireNonNull(noticeId));
    }

    private Job claimNext(UUID noticeId) {
        recover(noticeId);
        supersedeQueuedSources(noticeId);
        // A continuation retains its queue position; next_attempt_at only gates retry eligibility.
        // Recent notices get at most a one-day head start, so older waiting work still progresses.
        var rows = db.queryForList("""
            SELECT e.id,e.notice_id,e.source_revision FROM notice_extraction e JOIN notice n ON n.id=e.notice_id
            WHERE e.status='QUEUED' AND e.next_attempt_at<=now() AND e.source_revision=n.revision
                AND e.source_fingerprint IS NOT DISTINCT FROM n.source_fingerprint
                AND (?::uuid IS NULL OR e.notice_id=?)
                AND (?::uuid IS NOT NULL OR (%s))
            ORDER BY CASE WHEN n.basic_review='PASS' AND n.guide_review='HOLD' THEN 0
                          WHEN n.basic_review='PASS' THEN 1 ELSE 2 END,
                e.created_at - CASE WHEN n.published_date >= (now() AT TIME ZONE 'Asia/Seoul')::date - 30
                    THEN interval '24 hours' ELSE interval '0 hours' END,
                n.published_date DESC NULLS LAST,e.created_at,e.id
            FOR UPDATE OF e SKIP LOCKED LIMIT 1
            """.formatted(org.jibro.notice.AutomaticReviewEligibility.NOT_CLOSED_SQL), noticeId, noticeId, noticeId);
        if (rows.isEmpty()) return null;
        var row = rows.get(0); UUID id = (UUID) row.get("id");
        UUID token = UUID.randomUUID();
        db.update("UPDATE notice_extraction SET status='RUNNING',started_at=now(),finished_at=NULL,claim_token=?,message='공식 본문·첨부를 읽는 중' WHERE id=?", token, id);
        return new Job(id, (UUID) row.get("notice_id"), ((Number)row.get("source_revision")).intValue(), token);
    }

    private void supersedeQueuedSources(UUID noticeId) {
        // Lock only jobs, in the same way as claim; the queue never changes notice publication state.
        db.update("""
            WITH outdated AS (
                SELECT e.id FROM notice_extraction e JOIN notice n ON n.id=e.notice_id
                WHERE e.status='QUEUED' AND (e.source_revision<>n.revision
                    OR e.source_fingerprint IS DISTINCT FROM n.source_fingerprint)
                    AND (?::uuid IS NULL OR e.notice_id=?)
                FOR UPDATE OF e SKIP LOCKED
            )
            UPDATE notice_extraction e SET status='SUPERSEDED',claim_token=NULL,finished_at=now(),
                message='공고 또는 원문이 변경되어 이전 발췌 작업을 종료했습니다.'
            FROM outdated WHERE e.id=outdated.id
            """, noticeId, noticeId);
    }

    private void recover(UUID noticeId) {
        var stale = db.queryForList("""
            SELECT id,retries_used,sources FROM notice_extraction
            WHERE status='RUNNING' AND started_at<now()-(? * interval '1 second')
                AND (?::uuid IS NULL OR notice_id=?) FOR UPDATE SKIP LOCKED
            """, RUNNING_LEASE.toSeconds(), noticeId, noticeId);
        for (var row : stale) {
            int retries = ((Number) row.get("retries_used")).intValue();
            boolean retry = retries < 2;
            var sources = List.of(json.readValue(row.get("sources").toString(), Source[].class));
            db.update("UPDATE notice_extraction SET status=?,claim_token=NULL,finished_at=now(),retries_used=?,next_attempt_at=now()+(? * interval '1 second'),sources=?::jsonb,message=? WHERE id=?",
                    retry ? "QUEUED" : "FAILED", retry ? retries + 1 : retries, delay(retries),
                    json.writeValueAsString(retry ? retrySources(sources) : sources),
                    retry ? "중단된 발췌를 잠시 후 자동 재시도합니다." : "자동 재시도 한도에 도달했습니다. 원문을 확인해 주세요.", row.get("id"));
        }
    }

    @Transactional
    public void finish(Job job, List<Source> sources, String status, String message) {
        var revision = db.queryForList("SELECT revision FROM notice WHERE id=? FOR UPDATE", job.noticeId());
        if (revision.isEmpty()) return;
        var running = db.queryForList("SELECT retries_used FROM notice_extraction WHERE id=? AND status='RUNNING' AND claim_token=? FOR UPDATE", job.id(), job.claimToken());
        if (running.isEmpty()) return;
        int retries = ((Number) running.get(0).get("retries_used")).intValue();
        if (((Number)revision.get(0).get("revision")).intValue() != job.revision()) {
            status = "SUPERSEDED"; message = "추출 중 공고가 변경되어 적용할 수 없는 이전 발췌입니다. 다시 발췌해 주세요.";
        }
        boolean retry = Set.of("FAILED", "PARTIAL").contains(status) && retries < 2
                && (sources.isEmpty() || sources.stream().anyMatch(source -> source.status().equals("FAILED") || OcrProvenance.incomplete(source.context())));
        if (retry) {
            status = "QUEUED";
            message = "읽지 못한 자료를 " + delay(retries) + "초 후 자동 재시도합니다. (" + (retries + 1) + "/2)";
            sources = retrySources(sources);
        }
        if (!retry && Set.of("READY", "PARTIAL", "FAILED").contains(status)) relocation.finishUnresolved(job.noticeId());
        db.update("UPDATE notice_extraction SET status=?,message=?,sources=?::jsonb,finished_at=now(),claim_token=NULL,retries_used=?,next_attempt_at=now()+(? * interval '1 second') WHERE id=? AND status='RUNNING' AND claim_token=?",
                status, message, json.writeValueAsString(sources), retry ? retries + 1 : retries, retry ? delay(retries) : 0, job.id(), job.claimToken());
    }

    private int delay(int retriesUsed) { return retriesUsed == 0 ? 30 : 60; }

    @Transactional
    public boolean checkpoint(Job job, List<Source> sources) {
        boolean current = db.update("UPDATE notice_extraction e SET sources=?::jsonb,started_at=now() FROM notice n WHERE e.id=? AND e.notice_id=n.id AND n.revision=? AND e.status='RUNNING' AND e.claim_token=?",
            json.writeValueAsString(sources), job.id(), job.revision(), job.claimToken()) == 1;
        if (current) relocation.reconcile(sources);
        return current;
    }

    private List<Source> retrySources(List<Source> sources) {
        return sources.stream().map(source -> source.status().equals("FAILED") || OcrProvenance.incomplete(source.context())
                ? new Source(source.label(), source.url(), source.sha256(), "DEFERRED", "다시 읽기 대기 중", source.excerpts(), source.context())
                : source).toList();
    }

    public List<Draft> recent(UUID noticeId) {
        return db.query("SELECT * FROM notice_extraction WHERE notice_id=? ORDER BY created_at DESC LIMIT 10", (rs, row) -> draft(rs), noticeId);
    }
    public Draft find(UUID id) {
        return db.query("SELECT * FROM notice_extraction WHERE id=?", (rs, row) -> draft(rs), id).stream().findFirst().orElseThrow(ApiException::notFound);
    }
    @Transactional
    public Source updateFile(UUID noticeId, UUID draftId, Source source) {
        var current = db.queryForList("SELECT revision FROM notice WHERE id=? FOR UPDATE", noticeId);
        if (current.isEmpty()) throw ApiException.notFound();
        db.queryForList("SELECT id FROM notice_extraction WHERE id=? FOR UPDATE", draftId);
        var draft = find(draftId);
        if (!draft.noticeId().equals(noticeId.toString()) || draft.revision() != ((Number)current.get(0).get("revision")).intValue()
                || Set.of("QUEUED", "RUNNING", "SUPERSEDED").contains(draft.status())) throw ApiException.conflict();
        var sources = draft.sources().stream().map(s -> Objects.equals(s.url(), source.url()) ? source : s).toList();
        String status = sources.stream().allMatch(s -> s.status().equals("EXTRACTED") && !OcrProvenance.incomplete(s.context())) ? "READY"
                : sources.stream().anyMatch(s -> s.status().equals("EXTRACTED")) ? "PARTIAL" : "FAILED";
        db.update("UPDATE notice_extraction SET sources=?::jsonb,status=?,finished_at=now() WHERE id=?", json.writeValueAsString(sources), status, draftId);
        return source;
    }
    private Draft draft(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new Draft(rs.getString("id"), rs.getString("notice_id"), rs.getInt("source_revision"), rs.getString("status"),
                finished == null ? null : finished.toInstant(), rs.getString("message"), List.of(json.readValue(rs.getString("sources"), Source[].class)));
    }
}
