package org.jibro.collection.attachments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import org.jibro.global.common.ApiException;
import org.jibro.notice.NoticeService;
import org.jibro.collection.extraction.ExtractionStore;
import org.jibro.collection.extraction.EvidenceRelocationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions around the network scan; no database lock is held while downloading. */
// JdbcTemplate translates database errors; scan/domain exceptions keep their original contract.
@Component
public class AttachmentCheckStore {
    public record Target(String label, String url, String key) {}
    public record Scan(UUID id, UUID noticeId, int revision, List<Target> targets) {}
    public record Download(Target target, AttachmentFingerprintClient.Fingerprint fingerprint, String failure) {}
    public record Result(String label, String url, String status, String message,
                         String previousHash, String currentHash, Long bytes) {}
    public record Run(String id, String status, String startedAt, String finishedAt, String message, List<Result> results) {}
    private final JdbcTemplate db;
    private final NoticeService notices;
    private final ExtractionStore extraction;
    private final EvidenceRelocationStore relocation;

    public AttachmentCheckStore(JdbcTemplate db, NoticeService notices, ExtractionStore extraction, EvidenceRelocationStore relocation) {
        this.db = db;
        this.notices = notices;
        this.extraction = extraction;
        this.relocation = relocation;
    }

    public Instant lastAttempt(UUID id) {
        var time = db.queryForObject("SELECT max(attempted_at) FROM attachment_fingerprint WHERE notice_id=?", java.sql.Timestamp.class, id);
        return time == null ? Instant.MIN : time.toInstant();
    }

    @Transactional
    public Scan begin(UUID noticeId, String actor) {
        var snapshot = notices.attachmentSnapshot(noticeId);
        db.update("UPDATE attachment_check_run SET status='FAILED',finished_at=now(),message='중단된 점검입니다. 다시 확인해 주세요.' WHERE notice_id=? AND status='RUNNING' AND started_at<now()-interval '5 minutes'", noticeId);
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM attachment_check_run WHERE notice_id=? AND status='RUNNING')", Boolean.class, noticeId)))
            throw new ApiException(409, "ATTACHMENT_CHECK_RUNNING", "이 공고의 첨부를 확인하고 있습니다. 잠시 후 다시 확인해 주세요.");
        Map<String, Target> targets = new LinkedHashMap<>();
        for (var evidence : snapshot.content().attachments()) {
            if (evidence.url() == null || evidence.url().isBlank()) continue;
            String url = evidence.url().split("#", 2)[0];
            String key = key(url);
            targets.putIfAbsent(key, new Target(evidence.label(), url, key));
        }
        // A reviewed location can cite an official file omitted from the attachment list.
        // Restrict this supplement to the verified baseline registry; never fetch arbitrary evidence URLs.
        var content = snapshot.content();
        var extra = new ArrayList<org.jibro.notice.HousingModels.Evidence>();
        if (content.questions() != null) content.questions().forEach(item -> { if (item != null && item.evidence() != null) extra.add(item.evidence()); });
        if (content.documents() != null) content.documents().forEach(item -> { if (item != null && item.evidence() != null) extra.add(item.evidence()); });
        if (content.schedules() != null) content.schedules().forEach(item -> { if (item != null && item.evidence() != null) extra.add(item.evidence()); });
        if (content.reviewIssues() != null) content.reviewIssues().forEach(item -> { if (item != null && item.evidence() != null) extra.addAll(item.evidence()); });
        for (var evidence : extra) {
            if (evidence == null || evidence.url() == null) continue;
            String url = evidence.url().split("#", 2)[0];
            if (relocation.baselineHash(url) != null) targets.putIfAbsent(key(url), new Target(evidence.label(), url, key(url)));
        }
        Map<String, Instant> attempts = new HashMap<>();
        db.query("SELECT url_key,attempted_at FROM attachment_fingerprint WHERE notice_id=?", rs -> {
            attempts.put(rs.getString(1), rs.getTimestamp(2).toInstant());
        }, noticeId);
        var ordered = targets.values().stream().sorted(Comparator.comparing(t -> attempts.getOrDefault(t.key(), Instant.MIN))).toList();
        UUID id = UUID.randomUUID();
        // now() is fixed at transaction start; several checks in one transaction must retain their actual order.
        db.update("INSERT INTO attachment_check_run(id,notice_id,actor_id,notice_revision,status,started_at) VALUES (?,?,?,?,'RUNNING',clock_timestamp())",
                id, noticeId, actor == null ? null : UUID.fromString(actor), snapshot.revision());
        return new Scan(id, noticeId, snapshot.revision(), ordered);
    }

    @Transactional
    public Run finish(Scan scan, List<Download> downloads) {
        var current = notices.attachmentSnapshot(scan.noticeId());
        String existing = db.queryForObject("SELECT status FROM attachment_check_run WHERE id=? FOR UPDATE", String.class, scan.id());
        if (!"RUNNING".equals(existing)) return run(scan.id());
        // A changed draft/review or a newer scan must never acquire an old downloaded baseline.
        boolean superseded = current.revision() != scan.revision();
        int changed = 0, failed = 0, deferred = 0, baseline = 0;
        int ordinal = 0;
        List<Download> changedFiles = new ArrayList<>();
        for (var download : downloads) {
            var target = download.target();
            var old = db.query("SELECT sha256 FROM attachment_fingerprint WHERE notice_id=? AND url_key=?", (rs, row) -> rs.getString(1), scan.noticeId(), target.key());
            String previous = old.isEmpty() ? null : old.get(0);
            var value = download.fingerprint();
            String status, message;
            if (value == null) {
                boolean pending = "DEFERRED".equals(download.failure());
                status = pending ? "DEFERRED" : "FAILED";
                message = pending ? "이번 실행 한도를 넘었습니다. 다시 확인하면 미확인 첨부부터 처리합니다." : failureMessage(download.failure());
                if (pending) deferred++; else failed++;
            } else if (previous == null && relocation.differsFromKnownBaseline(target.url(), value.sha256())) {
                previous = relocation.baselineHash(target.url());
                status = "CHANGED"; message = "기존 근거의 원문과 현재 파일이 다릅니다. 재검수가 필요합니다."; changed++; changedFiles.add(download);
            } else if (previous == null) {
                status = "BASELINE"; message = "첫 비교 기준을 등록했습니다. 이전 내용과의 동일 여부는 판단하지 않습니다."; baseline++;
            } else if (previous.equals(value.sha256()) && relocation.needsReconciliation(target.url(), value.sha256())) {
                status = "CHANGED"; message = "현재 파일을 기존 근거와 자동 대조합니다."; changed++; changedFiles.add(download);
            } else if (previous.equals(value.sha256())) {
                status = "UNCHANGED"; message = "직전 성공 검사와 파일 내용이 같습니다.";
            } else {
                status = "CHANGED"; message = "같은 주소의 파일 내용이 바뀌었습니다. 원문 재검수가 필요합니다."; changed++; changedFiles.add(download);
            }
            db.update("INSERT INTO attachment_check_result(run_id,ordinal,label,url,status,message,previous_hash,current_hash,byte_size) VALUES (?,?,?,?,?,?,?,?,?)",
                    scan.id(), ordinal++, target.label(), target.url(), status, message, previous, value == null ? null : value.sha256(), value == null ? null : value.bytes());
            if (!superseded && !status.equals("DEFERRED")) {
                if (value == null) {
                    db.update("INSERT INTO attachment_fingerprint(notice_id,url_key,url) VALUES (?,?,?) ON CONFLICT(notice_id,url_key) DO UPDATE SET attempted_at=now()", scan.noticeId(), target.key(), target.url());
                } else {
                    db.update("INSERT INTO attachment_fingerprint(notice_id,url_key,url,sha256,byte_size,media_type,checked_at) VALUES (?,?,?,?,?,?,now()) ON CONFLICT(notice_id,url_key) DO UPDATE SET sha256=excluded.sha256,byte_size=excluded.byte_size,media_type=excluded.media_type,checked_at=now(),attempted_at=now()",
                            scan.noticeId(), target.key(), target.url(), value.sha256(), value.bytes(), value.mediaType());
                }
            }
        }
        if (!superseded && changed > 0) {
            notices.attachmentChanged(scan.noticeId(), scan.revision(), "첨부 내용 변경 " + changed + "건 감지 · 점검 " + scan.id());
            if (!notices.applicationClosed(scan.noticeId())) {
                for (var download : changedFiles) relocation.markPending(download.target().url(), download.fingerprint().sha256());
                extraction.enqueue(scan.noticeId(), false);
            }
        }
        String status = superseded ? "SUPERSEDED" : failed + deferred == 0 ? "SUCCEEDED" : failed == downloads.size() ? "FAILED" : "PARTIAL";
        String message = superseded ? "점검 중 공고가 수정되어 결과를 반영하지 않았습니다. 다시 확인해 주세요."
                : downloads.isEmpty() ? "검사할 첨부 주소가 등록되어 있지 않습니다."
                : "첨부 " + downloads.size() + "건 · 변경 " + changed + "건 · 기준 등록 " + baseline + "건 · 실패 " + failed + "건 · 다음 점검 " + deferred + "건";
        db.update("UPDATE attachment_check_run SET status=?,finished_at=clock_timestamp(),message=? WHERE id=?", status, message, scan.id());
        return run(scan.id());
    }

    @Transactional(readOnly = true)
    public List<Run> recent(UUID noticeId) {
        notices.entity(noticeId);
        return db.query("SELECT id FROM attachment_check_run WHERE notice_id=? ORDER BY started_at DESC,id DESC LIMIT 20", (rs, n) -> (UUID) rs.getObject(1), noticeId)
                .stream().map(this::run).toList();
    }

    private Run run(UUID id) {
        var results = db.query("SELECT label,url,status,message,previous_hash,current_hash,byte_size FROM attachment_check_result WHERE run_id=? ORDER BY ordinal", (rs, n) ->
                new Result(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), (Long) rs.getObject(7)), id);
        return db.queryForObject("SELECT id,status,started_at,finished_at,message FROM attachment_check_run WHERE id=?", (rs, n) -> {
            Instant started = rs.getTimestamp(3).toInstant();
            boolean expired = rs.getString(2).equals("RUNNING") && started.plusSeconds(300).isBefore(Instant.now());
            return new Run(rs.getString(1), expired ? "FAILED" : rs.getString(2), started.toString(),
                    rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant().toString(),
                    expired ? "중단된 점검입니다. 다시 확인해 주세요." : rs.getString(5), results);
        }, id);
    }

    static String key(String url) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String failureMessage(String code) {
        String reason = switch (code == null ? "FAILED" : code) {
            case "TIMEOUT" -> "15초 안에 파일을 받지 못했습니다.";
            case "ATTACHMENT_URL_REJECTED", "ATTACHMENT_ADDRESS_REJECTED" -> "허용된 공식 첨부 주소가 아닙니다.";
            case "ATTACHMENT_HTTP_STATUS" -> "공식 서버에서 파일을 정상적으로 받지 못했습니다.";
            case "ATTACHMENT_SIZE_REJECTED" -> "파일이 20MiB 제한을 넘었습니다.";
            case "ATTACHMENT_ENCODING_REJECTED", "ATTACHMENT_CONTENT_REJECTED" -> "지원하는 PDF·문서 파일로 확인되지 않았습니다.";
            default -> "다운로드 또는 파일 검증에 실패했습니다.";
        };
        return reason + " 기존 기준은 유지합니다.";
    }
}
