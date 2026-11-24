package org.jibro.notice;

import static org.jibro.notice.FollowupReviewModels.*;
import static org.jibro.notice.HousingModels.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import org.jibro.collection.LhNoticeClient;
import org.jibro.global.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Leased, bounded follow-up work. Network reads are performed outside these short transactions.
 */
@Repository
public class FollowupReviewRepository {
    public static final String POLICY = "official-followup-v7-reading-failures";
    static final long VERIFICATION_TTL_SECONDS = 120;
    private static final String CONTENT_HASH = "encode(sha256(convert_to(n.content::text,'UTF8')),'hex')";
    // A verified summary is not a review of the remaining guidance; all other editorial protection stays in force.
    private static final String ELIGIBLE = """
            n.source_key LIKE 'LH:%' AND n.source_fingerprint IS NOT NULL AND n.collected_at IS NOT NULL
            AND n.basic_review='PASS' AND n.guide_review IN ('HOLD','PASS') AND n.lifecycle_state='NORMAL'
            AND NOT n.automatic_review_blocked
            AND NOT EXISTS(SELECT 1 FROM notice_correction c WHERE c.original_notice_id=n.id)
            AND NOT EXISTS(SELECT 1 FROM review_audit a WHERE a.notice_id=n.id)
            AND NOT EXISTS(SELECT 1 FROM notice_source_review r WHERE r.notice_id=n.id
                AND COALESCE(r.payload->>'scope','') <> 'VERIFIED_SUMMARY_ONLY')
            """ + " AND " + AutomaticReviewEligibility.NOT_CLOSED_SQL;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AutomaticReviewRepository extraction;
    private final org.jibro.notice.source.NoticeSourceInformationRepository information;

    public FollowupReviewRepository(JdbcTemplate db, ObjectMapper json, AutomaticReviewRepository extraction,
                                    org.jibro.notice.source.NoticeSourceInformationRepository information) {
        this.db = db;
        this.json = json;
        this.extraction = extraction;
        this.information = information;
    }

    public record PriorGuide(UUID noticeId, int revision, Content guide, UUID extractionId, String extractionToken,
                             String token,
                             List<org.jibro.notice.source.NoticeSourceInformationModels.Section> sections) {
    }

    /**
     * Only the verified correction graph selects another recruitment. Prefer its most recent reviewed edition.
     */
    public List<PriorGuide> priorGuides(UUID noticeId) {
        return db.query("""
                WITH ancestors AS (
                    SELECT c.original_notice_id AS root,c.original_notice_id AS id,0::bigint AS ord,2147483647 AS ceiling FROM notice_correction c WHERE c.corrected_notice_id=?
                    UNION ALL SELECT c.original_notice_id,(h.value->>'correctedId')::uuid,h.ord,2147483647
                      FROM notice_correction c CROSS JOIN LATERAL jsonb_array_elements(c.verification_history) WITH ORDINALITY h(value,ord)
                      WHERE c.corrected_notice_id=? AND h.value->>'correctedId'<>c.corrected_notice_id::text
                    UNION ALL SELECT n.id,n.id,0,e.source_revision-1 FROM notice n
                      CROSS JOIN LATERAL (SELECT source_revision FROM notice_extraction WHERE notice_id=n.id AND source_fingerprint=n.source_fingerprint
                        ORDER BY created_at DESC,id DESC LIMIT 1) e WHERE n.id=?
                ), editions AS (
                    SELECT a.*,g.* FROM ancestors a CROSS JOIN LATERAL (
                        SELECT x.* FROM (
                            SELECT r.result_revision AS rev,r.payload->'review'->'content' AS data,
                                CASE WHEN COALESCE(r.payload->'review'->'supplementalReferences','[]')='[]'::jsonb THEN (r.payload->'review'->>'extractionId')::uuid END AS ext,r.payload->'verification'->>'extractionFingerprint' AS stamp,1 AS priority
                                FROM notice_source_review r WHERE r.notice_id=a.id AND r.disposition IN ('PASS','PARTIAL')
                            UNION ALL SELECT r.result_revision,r.assessment->'content',CASE WHEN COALESCE(r.sources->'discovery'->'related','[]')='[]'::jsonb THEN r.primary_extraction_id END,r.primary_fingerprint,2
                                FROM automatic_followup_review r WHERE r.notice_id=a.id AND r.state IN ('PASS','PARTIAL')
                            UNION ALL SELECT v.revision,v.preparation,NULL,NULL,3 FROM published_notice_version v
                                WHERE v.notice_id=a.id AND v.preparation IS NOT NULL
                            UNION ALL SELECT r.result_revision,jsonb_build_object('questions','[]'::jsonb,'documents','[]'::jsonb,
                                'sections',r.sources->'confirmedItems'),NULL,NULL,4
                                FROM automatic_followup_review r WHERE r.notice_id=a.id AND r.state IN ('ITEMS','INTAKE')
                                AND jsonb_typeof(r.sources->'confirmedItems')='array' AND r.sources->'confirmedItems'<>'[]'::jsonb
                        ) x WHERE x.rev<=a.ceiling AND jsonb_typeof(x.data->'questions')='array' AND jsonb_typeof(x.data->'documents')='array'
                        ORDER BY x.rev DESC,x.priority LIMIT 1
                    ) g
                ), selected AS (SELECT DISTINCT ON (root) * FROM editions ORDER BY root,ord DESC)
                SELECT DISTINCT id,rev,(data-'sections')::text AS data,COALESCE(data->'sections','[]')::text AS sections,ext,stamp,encode(sha256(convert_to(data::text,'UTF8')),'hex') AS digest
                FROM selected ORDER BY id
                """, (rs, row) -> new PriorGuide(rs.getObject("id", UUID.class), rs.getInt("rev"), json.readValue(rs.getString("data"), Content.class),
                rs.getObject("ext", UUID.class), rs.getString("stamp"), rs.getString("digest"), List.of(json.readValue(rs.getString("sections"), org.jibro.notice.source.NoticeSourceInformationModels.Section[].class))), noticeId, noticeId, noticeId);
    }

    public static String priorToken(List<PriorGuide> guides) {
        return guides.stream().map(g -> g.noticeId() + "/" + g.revision() + "/" + g.token() + "/" + g.extractionToken()).collect(java.util.stream.Collectors.joining(","));
    }

    public Optional<org.jibro.collection.extraction.ExtractionModels.Draft> verifiedPrior(PriorGuide guide) {
        if (guide.extractionId() == null || guide.extractionToken() == null || !Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM notice_extraction WHERE id=? AND notice_id=?)", Boolean.class, guide.extractionId(), guide.noticeId())))
            return Optional.empty();
        var snapshot = extraction.snapshot(guide.extractionId());
        return guide.extractionToken().equals(snapshot.fingerprint()) && snapshot.draft().noticeId().equals(guide.noticeId().toString())
                ? Optional.of(snapshot.draft()) : Optional.empty();
    }

    @Transactional
    public void deferWithItems(Job job, String reason, Object audit) {
        db.update("UPDATE automatic_followup_review SET sources=jsonb_build_object('itemCoverage',?::jsonb) WHERE id=? AND lease_token=? AND state='RUNNING'",
                json.writeValueAsString(audit), job.runId(), job.leaseToken());
        defer(job, reason);
    }

    @Transactional
    public Optional<Job> claim() {
        return claimNext(null);
    }

    @Transactional
    public Optional<Job> claim(UUID noticeId) {
        return claimNext(Objects.requireNonNull(noticeId));
    }

    private Optional<Job> claimNext(UUID noticeId) {
        // Expired workers cannot finish a replacement's attempt or publish their old receipt.
        db.update("""
                UPDATE automatic_followup_review r SET state='SUPERSEDED',finished_at=clock_timestamp(),reason='FOLLOWUP_LEASE_EXPIRED'
                FROM notice_followup_queue q WHERE r.id=q.active_run_id AND q.lease_until<clock_timestamp()
                    AND r.state IN ('RUNNING','STAGED')
                """);
        db.update("""
                INSERT INTO notice_followup_queue(notice_id)
                SELECT n.id FROM notice n WHERE %s
                    AND NOT EXISTS(SELECT 1 FROM notice_followup_queue q WHERE q.notice_id=n.id)
                    AND (?::uuid IS NULL OR n.id=?)
                ORDER BY n.published_date DESC NULLS LAST,n.id LIMIT 50 ON CONFLICT DO NOTHING
                """.formatted(ELIGIBLE), noticeId, noticeId);
        var rows = db.queryForList("""
                SELECT n.id,n.revision,n.source_fingerprint,n.content::text AS content,n.start_date,n.end_date,
                    %s AS content_hash FROM notice_followup_queue q JOIN notice n ON n.id=q.notice_id
                WHERE %s AND q.next_check_at<=clock_timestamp()
                    AND (q.lease_until IS NULL OR q.lease_until<clock_timestamp())
                    AND (?::uuid IS NULL OR n.id=?)
                ORDER BY q.next_check_at - CASE WHEN n.published_date>=(clock_timestamp() AT TIME ZONE 'Asia/Seoul')::date-30
                    THEN interval '24 hours' ELSE interval '0 hours' END,q.created_at,n.id
                FOR UPDATE OF q SKIP LOCKED LIMIT 1
                """.formatted(CONTENT_HASH, ELIGIBLE), noticeId, noticeId);
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.get(0);
        var job = new Job(UUID.randomUUID(), (UUID) row.get("id"), ((Number) row.get("revision")).intValue(),
                (String) row.get("source_fingerprint"), (String) row.get("content_hash"), UUID.randomUUID());
        db.update("""
                UPDATE notice_followup_queue SET active_run_id=?,lease_token=?,lease_until=clock_timestamp()+interval '10 minutes'
                WHERE notice_id=?
                """, job.runId(), job.leaseToken(), job.noticeId());
        db.update("""
                        INSERT INTO automatic_followup_review(id,notice_id,lease_token,source_revision,source_fingerprint,
                            before_content_fingerprint,before_content,before_start_date,before_end_date,policy)
                        VALUES(?,?,?,?,?,?,?::jsonb,?,?,?)
                        """, job.runId(), job.noticeId(), job.leaseToken(), job.revision(), job.sourceFingerprint(),
                job.contentFingerprint(), row.get("content"), row.get("start_date"), row.get("end_date"), POLICY);
        return Optional.of(job);
    }

    /**
     * Old primary evidence is reusable only through an exact, unedited automatic publication.
     */
    public Optional<Primary> primary(Job job) {
        if (!current(job) || !active(job)) return Optional.empty();
        var rows = db.queryForList("""
                SELECT e.id,e.source_revision,n.source_key FROM notice_extraction e JOIN notice n ON n.id=e.notice_id
                WHERE n.id=? AND e.source_fingerprint IS NOT DISTINCT FROM n.source_fingerprint
                    AND e.status IN ('READY','PARTIAL','FAILED') AND e.finished_at IS NOT NULL
                    AND e.id=(SELECT x.id FROM notice_extraction x WHERE x.notice_id=n.id ORDER BY x.created_at DESC,x.id DESC LIMIT 1)
                """, job.noticeId());
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.get(0);
        UUID id = (UUID) row.get("id");
        var snapshot = extraction.snapshot(id);
        if (((Number) row.get("source_revision")).intValue() != job.revision() && !reusable(job, id, snapshot.fingerprint()))
            return Optional.empty();
        return original(snapshot, (String) row.get("source_key")).map(content -> new Primary(snapshot, content));
    }

    private Optional<Content> original(AutomaticReviewRepository.ExtractionSnapshot snapshot, String sourceKey) {
        var sources = snapshot.draft().sources();
        var mains = sources.stream().filter(s -> LhNoticeClient.sourceKey(s.url()).equals(sourceKey)).toList();
        if (mains.size() != 1 || snapshot.draft().checkedAt() == null) return Optional.empty();
        String checked = snapshot.draft().checkedAt().atZone(ApplicationPeriod.SEOUL).toLocalDate().toString();
        var main = mains.get(0);
        return Optional.of(new Content("공식 원문을 기준으로 준비 항목을 확인합니다.", new Evidence(main.label(), main.url(), checked),
                sources.stream().filter(s -> !s.url().equals(main.url())).map(s -> new Evidence(s.label(), s.url(), checked)).toList(),
                List.of(), List.of(), List.of()));
    }

    /**
     * Applied official dependencies survive withdrawal/re-extraction until explicit replacement is proven.
     */
    public Optional<String> latestDependencySources(UUID noticeId) {
        if (noticeId == null) return Optional.empty();
        var candidates = db.queryForList("""
                SELECT r.id,r.original_content->'evidence'->>'url' AS original_url,n.content->'evidence'->>'url' AS current_url
                FROM automatic_followup_review r JOIN notice n ON n.id=r.notice_id
                WHERE n.id=? AND r.state IN ('PASS','PARTIAL','UNCHANGED','REVIEW_REQUIRED','INTAKE','ITEMS') AND %s
                    AND (jsonb_array_length(coalesce(r.sources->'agencyWindows','[]'::jsonb))>0 OR CASE WHEN jsonb_typeof(r.sources->'discovery'->'related')='array'
                        THEN jsonb_array_length(r.sources->'discovery'->'related')>0 ELSE false END)
                ORDER BY r.finished_at DESC,r.id DESC
                """.formatted(ELIGIBLE), noticeId);
        // Inspect only small identity fields here; do not load every historical source bundle.
        return candidates.stream().filter(row -> samePrimaryIdentity((String) row.get("original_url"), (String) row.get("current_url")))
                .findFirst().map(row -> db.queryForObject("SELECT sources::text FROM automatic_followup_review WHERE id=?", String.class, row.get("id")));
    }

    private boolean samePrimaryIdentity(String previous, String current) {
        if (previous == null || current == null) return false;
        if (previous.equals(current)) return true;
        try {
            return OfficialCorrectionLink.identity(previous).equals(OfficialCorrectionLink.identity(current));
        } catch (IllegalArgumentException unrecognized) {
            return false;
        }
    }

    private boolean reusable(Job job, UUID id, String token) {
        return Boolean.TRUE.equals(db.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM automatic_notice_review a WHERE a.notice_id=? AND a.applied_revision=?
                            AND a.phase='GUIDANCE' AND a.decision IN ('PASS','PARTIAL') AND a.extraction_id=?
                            AND a.extraction_fingerprint=? AND a.source_fingerprint=? AND a.content_fingerprint=?)
                        OR EXISTS(SELECT 1 FROM automatic_followup_review r WHERE r.notice_id=? AND r.result_revision=?
                            AND r.state IN ('PASS','PARTIAL','UNCHANGED','REVIEW_REQUIRED','INTAKE','ITEMS') AND r.primary_extraction_id=?
                            AND r.primary_fingerprint=? AND r.source_fingerprint=? AND r.result_content_fingerprint=?)
                        """, Boolean.class, job.noticeId(), job.revision(), id, token, job.sourceFingerprint(), job.contentFingerprint(),
                job.noticeId(), job.revision(), id, token, job.sourceFingerprint(), job.contentFingerprint()));
    }

    public Optional<String> previousCoverage(Job job, String bundleHash) {
        return db.query("""
                SELECT (sources->'itemCoverage')::text FROM automatic_followup_review
                WHERE notice_id=? AND bundle_hash=? AND policy=? AND jsonb_typeof(sources->'itemCoverage')='object'
                  AND state IN ('PASS','PARTIAL','INTAKE','ITEMS','UNCHANGED','REVIEW_REQUIRED')
                ORDER BY checked_at DESC NULLS LAST LIMIT 1
                """, (rs, row) -> rs.getString(1), job.noticeId(), bundleHash, POLICY).stream().findFirst();
    }

    public boolean sameBundle(Job job, String bundleHash) {
        return current(job) && Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notice_followup_queue WHERE notice_id=? AND last_bundle_hash=?
                    AND last_result_revision=? AND last_content_fingerprint=? AND last_policy=?)
                """, Boolean.class, job.noticeId(), bundleHash, job.revision(), job.contentFingerprint(), POLICY));
    }

    /**
     * Completed output, including post-publication merges; a GET never reruns a partial compiler.
     */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Optional<org.jibro.collection.review.GuidanceReviewCompiler.Assessment> completedPreview(UUID noticeId, UUID extractionId, String fingerprint) {
        if (extractionId == null || fingerprint == null) return Optional.empty();
        var currentEvidence = extraction.snapshot(extractionId);
        if (!noticeId.toString().equals(currentEvidence.draft().noticeId())
                || !fingerprint.equals(currentEvidence.fingerprint())) return Optional.empty();
        var rows = db.queryForList("""
                SELECT n.revision,n.content::text AS content,n.start_date,n.end_date,n.guide_review,
                    producer.state,producer.assessment::text AS assessment,r.sources->>'priorGuideToken' AS prior_token
                FROM automatic_followup_review r
                JOIN notice n ON n.id=r.notice_id JOIN notice_followup_queue q ON q.notice_id=n.id
                JOIN notice_extraction e ON e.id=r.primary_extraction_id AND e.notice_id=n.id
                JOIN LATERAL (
                    SELECT p.state,p.assessment FROM automatic_followup_review p
                    WHERE p.notice_id=r.notice_id AND p.bundle_hash=r.bundle_hash AND p.policy=r.policy
                        AND p.result_revision=r.result_revision AND p.result_content_fingerprint=r.result_content_fingerprint
                        AND p.primary_extraction_id=r.primary_extraction_id AND p.primary_fingerprint=r.primary_fingerprint
                        AND p.state IN ('PASS','PARTIAL','REVIEW_REQUIRED','INTAKE','ITEMS')
                    ORDER BY p.finished_at DESC,p.id DESC LIMIT 1
                ) producer ON true
                WHERE n.id=? AND r.primary_extraction_id=? AND r.primary_fingerprint=? AND r.policy=?
                    AND r.state IN ('PASS','PARTIAL','UNCHANGED','REVIEW_REQUIRED','INTAKE','ITEMS')
                    AND r.result_revision=n.revision AND r.source_fingerprint=n.source_fingerprint
                    AND e.source_fingerprint=n.source_fingerprint
                    AND r.result_content_fingerprint=%s AND r.bundle_hash=q.last_bundle_hash
                    AND q.last_result_revision=n.revision AND q.last_content_fingerprint=%s
                    AND q.last_policy=? AND q.last_outcome IN ('PASS','PARTIAL','UNCHANGED','REVIEW_REQUIRED','INTAKE','ITEMS')
                    AND e.id=(SELECT x.id FROM notice_extraction x WHERE x.notice_id=n.id ORDER BY x.created_at DESC,x.id DESC LIMIT 1)
                    AND %s ORDER BY r.finished_at DESC,r.id DESC LIMIT 1
                """.formatted(CONTENT_HASH, CONTENT_HASH, ELIGIBLE), noticeId, extractionId, fingerprint, POLICY, POLICY);
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.get(0);
        if (!Objects.toString(row.get("prior_token"), "").equals(priorToken(priorGuides(noticeId))))
            return Optional.empty();
        String state = (String) row.get("state");
        var decision = json.readValue((String) row.get("assessment"), Decision.class);
        var content = json.readValue((String) row.get("content"), Content.class);
        return Optional.of(new org.jibro.collection.review.GuidanceReviewCompiler.Assessment("completed-official-review",
                Set.of("PASS", "PARTIAL").contains(state) && "PASS".equals(row.get("guide_review")), content,
                decision.blockers(), List.of(), row.get("start_date") == null ? null : ((java.sql.Date) row.get("start_date")).toLocalDate(),
                row.get("end_date") == null ? null : ((java.sql.Date) row.get("end_date")).toLocalDate(), state,
                information.confirmedItems(noticeId, ((Number) row.get("revision")).intValue())));
    }

    @Transactional
    public Receipt stage(Job job, Primary primary, String bundleHash, Instant checkedAt, String sourcesJson, Decision decision) {
        if (!hash(bundleHash) || checkedAt == null || checkedAt.isBefore(Instant.now().minusSeconds(VERIFICATION_TTL_SECONDS))
                || checkedAt.isAfter(Instant.now().plusSeconds(5)) || sourcesJson == null || sourcesJson.length() > 8_000_000
                || decision == null || !Set.of("READY", "REVIEW_REQUIRED", "INTAKE", "ITEMS", "UNCHANGED").contains(decision.state())
                || decision.blockers().size() > 100 || decision.blockers().stream().anyMatch(b -> b == null || b.isBlank() || b.length() > 500)
                || !current(job) || !active(job) || !primaryMatches(job, primary)) throw ApiException.conflict();
        var sourceTree = json.readTree(sourcesJson);
        if (!sourceTree.isObject() && !sourceTree.isArray())
            throw new IllegalArgumentException("FOLLOWUP_SOURCES_REQUIRED");
        int written = db.update("""
                        UPDATE automatic_followup_review SET state='STAGED',primary_extraction_id=?,primary_fingerprint=?,
                            original_content=?::jsonb,bundle_hash=?,checked_at=?,sources=?::jsonb,assessment=?::jsonb
                        WHERE id=? AND lease_token=? AND state='RUNNING'
                        """, UUID.fromString(primary.snapshot().draft().id()), primary.snapshot().fingerprint(),
                json.writeValueAsString(primary.originalContent()), bundleHash, Timestamp.from(checkedAt), sourcesJson,
                json.writeValueAsString(decision), job.runId(), job.leaseToken());
        if (written != 1) throw ApiException.conflict();
        return new Receipt(job.runId(), job.leaseToken(), bundleHash);
    }

    boolean primaryMatches(Job job, Primary expected) {
        if (expected == null) return false;
        return primary(job).filter(p -> p.snapshot().draft().id().equals(expected.snapshot().draft().id())
                && p.snapshot().fingerprint().equals(expected.snapshot().fingerprint())
                && p.originalContent().equals(expected.originalContent())).isPresent();
    }

    boolean current(Job job) {
        return job != null && Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM notice n WHERE n.id=? AND n.revision=?"
                        + " AND n.source_fingerprint=? AND " + CONTENT_HASH + "=? AND " + ELIGIBLE + ")", Boolean.class,
                job.noticeId(), job.revision(), job.sourceFingerprint(), job.contentFingerprint()));
    }

    boolean active(Job job) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notice_followup_queue WHERE notice_id=? AND active_run_id=? AND lease_token=?
                    AND lease_until>=clock_timestamp())
                """, Boolean.class, job.noticeId(), job.runId(), job.leaseToken()));
    }

    @Transactional
    public boolean renew(Job job) {
        return current(job) && db.update("""
                UPDATE notice_followup_queue SET lease_until=clock_timestamp()+interval '10 minutes'
                WHERE notice_id=? AND active_run_id=? AND lease_token=? AND lease_until>=clock_timestamp()
                """, job.noticeId(), job.runId(), job.leaseToken()) == 1;
    }

    Optional<Staged> staged(Receipt receipt) {
        if (receipt == null) return Optional.empty();
        return db.query("""
                SELECT * FROM automatic_followup_review WHERE id=? AND lease_token=? AND bundle_hash=?
                    AND policy=? AND state='STAGED' FOR UPDATE
                """, (rs, row) -> {
            var job = new Job(rs.getObject("id", UUID.class), rs.getObject("notice_id", UUID.class), rs.getInt("source_revision"),
                    rs.getString("source_fingerprint"), rs.getString("before_content_fingerprint"), rs.getObject("lease_token", UUID.class));
            var primary = new Primary(extraction.snapshot(rs.getObject("primary_extraction_id", UUID.class)),
                    json.readValue(rs.getString("original_content"), Content.class));
            // Keep the staged token, even if a per-file retry replaced evidence under the same ID.
            primary = new Primary(new AutomaticReviewRepository.ExtractionSnapshot(primary.snapshot().draft(), rs.getString("primary_fingerprint")), primary.originalContent());
            return new Staged(job, primary, rs.getString("bundle_hash"), rs.getTimestamp("checked_at").toInstant(),
                    json.readValue(rs.getString("assessment"), Decision.class), rs.getString("sources"));
        }, receipt.runId(), receipt.leaseToken(), receipt.bundleHash(), POLICY).stream().findFirst();
    }

    UUID noticeId(Receipt receipt) {
        return db.query("SELECT notice_id FROM automatic_followup_review WHERE id=? AND lease_token=?", (rs, row) -> rs.getObject(1, UUID.class),
                receipt.runId(), receipt.leaseToken()).stream().findFirst().orElse(null);
    }

    String contentHash(UUID noticeId) {
        return db.queryForObject("SELECT " + CONTENT_HASH + " FROM notice n WHERE n.id=?", String.class, noticeId);
    }

    void primaryInvalidated(Job job, int revision, String contentHash) {
        db.update("""
                UPDATE automatic_followup_review SET state='REVIEW_REQUIRED',finished_at=clock_timestamp(),
                    result_revision=?,result_content_fingerprint=?,reason='PRIMARY_SOURCE_CHANGED'
                WHERE id=? AND lease_token=? AND state IN ('RUNNING','STAGED')
                """, revision, contentHash, job.runId(), job.leaseToken());
        db.update("""
                UPDATE notice_followup_queue SET lease_token=NULL,lease_until=NULL,active_run_id=NULL,
                    last_bundle_hash=NULL,last_content_fingerprint=NULL,last_result_revision=NULL,
                    last_outcome='REVIEW_REQUIRED',next_check_at=clock_timestamp()+interval '1 minute'
                WHERE notice_id=? AND active_run_id=? AND lease_token=?
                """, job.noticeId(), job.runId(), job.leaseToken());
    }

    void completed(Staged staged, String state, int revision, String contentHash, String reason) {
        var job = staged.job();
        db.update("""
                UPDATE automatic_followup_review SET state=?,finished_at=clock_timestamp(),result_revision=?,
                    result_content_fingerprint=?,reason=? WHERE id=? AND state='STAGED'
                """, state, revision, contentHash, trim(reason), job.runId());
        db.update("""
                        UPDATE notice_followup_queue q SET lease_token=NULL,lease_until=NULL,active_run_id=NULL,failures=0,
                            last_bundle_hash=?,last_result_revision=?,last_content_fingerprint=?,last_policy=?,last_outcome=?,last_checked_at=?,
                            next_check_at=clock_timestamp()+interval '6 hours'
                        FROM notice n WHERE n.id=q.notice_id AND q.notice_id=? AND q.active_run_id=? AND q.lease_token=?
                        """, staged.bundleHash(), revision, contentHash, POLICY, state, Timestamp.from(staged.checkedAt()),
                job.noticeId(), job.runId(), job.leaseToken());
    }

    @Transactional
    public void fail(Job job, String reason) {
        unsuccessful(job, "FAILED", reason);
    }

    @Transactional
    public void defer(Job job, String reason) {
        unsuccessful(job, "DEFERRED", reason);
    }

    void unsuccessful(Job job, String state, String reason) {
        db.update("""
                UPDATE automatic_followup_review SET state=?,finished_at=clock_timestamp(),reason=?
                WHERE id=? AND lease_token=? AND state IN ('RUNNING','STAGED')
                """, state, trim(reason), job.runId(), job.leaseToken());
        db.update("""
                UPDATE notice_followup_queue SET lease_token=NULL,lease_until=NULL,active_run_id=NULL,
                    last_outcome=?,failures=least(failures+1,20),
                    next_check_at=clock_timestamp()+(least(21600,900*power(2,least(failures,5))) * interval '1 second')
                WHERE notice_id=? AND active_run_id=? AND lease_token=?
                """, state, job.noticeId(), job.runId(), job.leaseToken());
    }

    private boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private String trim(String reason) {
        return reason == null ? "FOLLOWUP_CHECK_FAILED" : reason.substring(0, Math.min(2000, reason.length()));
    }
}
