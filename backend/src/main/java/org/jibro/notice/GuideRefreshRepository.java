package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class GuideRefreshRepository {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public record SourceState(String url, String version, String status, Instant lastAttempt,
                              Instant lastSuccess, Instant nextCheck, int failures,
                              GuideSourceClient.Snapshot snapshot) {
    }

    public record EntryState(String id, String baselineHash, Guide guide, long revision, String status,
                             Map<String, String> verifiedSources, Instant verifiedAt, Instant attemptedAt,
                             String reviewFingerprint, String outcome, Instant nextReview) {
    }

    public GuideRefreshRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public String baselineHash(Guide guide) {
        return sha(json.writeValueAsString(withFreshness(guide, null)));
    }

    static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Guide withFreshness(Guide guide, GuideFreshness freshness) {
        return new Guide(guide.id(), guide.category(), guide.title(), guide.body(), guide.evidence(), guide.scope(), guide.additionalEvidence(), freshness);
    }

    @Transactional
    public void synchronize(List<Guide> baseline, Instant now) {
        db.queryForObject("SELECT pg_advisory_xact_lock(718421060)", Object.class);
        for (Guide guide : baseline) {
            var existing = entry(guide.id());
            String hash = baselineHash(guide);
            if (existing != null && !existing.baselineHash().equals(hash))
                history(existing, guide, Map.of(), "PACKAGED_GUIDE_REPLACED", "{}", now);
            db.update("""
                INSERT INTO guide_entry_state(guide_id,baseline_hash,published_json,last_verified_at)
                VALUES(?,?,?::jsonb,?) ON CONFLICT(guide_id) DO UPDATE SET
                baseline_hash=excluded.baseline_hash,published_json=excluded.published_json,verified_sources='{}'::jsonb,
                revision=guide_entry_state.revision+1,status='CURRENT',last_verified_at=excluded.last_verified_at,
                last_attempt_at=NULL,last_review_fingerprint=NULL,last_outcome=NULL,next_review_at=NULL
                WHERE guide_entry_state.baseline_hash<>excluded.baseline_hash
                """, guide.id(), hash, json.writeValueAsString(withFreshness(guide, null)), stamp(baselineTime(guide)));
            for (Evidence source : evidence(guide))
                db.update("INSERT INTO guide_source_state(source_url,next_check_at) VALUES(?,?) ON CONFLICT DO NOTHING", source.url(), stamp(now));
        }
    }

    public List<Guide> current(List<Guide> baseline) {
        Map<String, EntryState> entries = new HashMap<>();
        db.query("SELECT * FROM guide_entry_state", (row, i) -> readEntry(row)).forEach(entry -> entries.put(entry.id(), entry));
        return baseline.stream().map(guide -> {
            EntryState row = entries.get(guide.id());
            if (row == null || !row.baselineHash().equals(baselineHash(guide))) return guide;
            return withFreshness(row.guide(), new GuideFreshness(row.status(), date(row.verifiedAt()), date(row.attemptedAt()), null));
        }).toList();
    }

    public EntryState entry(String id) {
        return db.query("SELECT * FROM guide_entry_state WHERE guide_id=?", (row, i) -> readEntry(row), id).stream().findFirst().orElse(null);
    }

    private EntryState readEntry(java.sql.ResultSet row) throws java.sql.SQLException {
        return new EntryState(row.getString("guide_id"), row.getString("baseline_hash"), json.readValue(row.getString("published_json"), Guide.class),
            row.getLong("revision"), row.getString("status"), json.readValue(row.getString("verified_sources"), new TypeReference<Map<String, String>>() {
        }),
            instant(row.getTimestamp("last_verified_at")), instant(row.getTimestamp("last_attempt_at")), row.getString("last_review_fingerprint"),
            row.getString("last_outcome"), instant(row.getTimestamp("next_review_at")));
    }

    public SourceState source(String url) {
        return db.query("""
                SELECT s.*,v.snapshot_json FROM guide_source_state s LEFT JOIN guide_source_version v ON v.version_key=s.current_version WHERE source_url=?
                """, (row, i) -> new SourceState(url, row.getString("current_version"), row.getString("status"),
                instant(row.getTimestamp("last_attempt_at")), instant(row.getTimestamp("last_success_at")), instant(row.getTimestamp("next_check_at")),
                row.getInt("failure_count"), row.getString("snapshot_json") == null ? null : json.readValue(row.getString("snapshot_json"), GuideSourceClient.Snapshot.class)), url)
            .stream().findFirst().orElse(null);
    }

    public GuideSourceClient.Snapshot version(String key) {
        if (key == null) return null;
        return db.query("SELECT snapshot_json::text FROM guide_source_version WHERE version_key=?",
            (row, i) -> json.readValue(row.getString(1), GuideSourceClient.Snapshot.class), key).stream().findFirst().orElse(null);
    }

    @Transactional
    public void recordSource(String url, GuideSourceClient.Result result, Instant now) {
        if (result.status() == GuideSourceClient.Status.AVAILABLE) {
            var snapshot = result.snapshot();
            String key = sha(snapshot.finalUrl() + "\n" + snapshot.contentHash());
            db.update("INSERT INTO guide_source_version(version_key,snapshot_json,created_at) VALUES(?,?::jsonb,?) ON CONFLICT DO NOTHING",
                key, json.writeValueAsString(snapshot), stamp(now));
            db.update("""
                UPDATE guide_source_state SET current_version=?,status='AVAILABLE',last_attempt_at=?,last_success_at=?,
                next_check_at=?,failure_count=0,error_code=NULL WHERE source_url=?
                """, key, stamp(now), stamp(now), stamp(nextDaily(now)), url);
        } else {
            SourceState prior = source(url);
            int failures = prior == null ? 1 : prior.failures() + 1;
            long retryMinutes = Math.min(360, 30L << Math.min(4, failures - 1));
            db.update("""
                UPDATE guide_source_state SET status=?,last_attempt_at=?,next_check_at=?,failure_count=?,error_code=? WHERE source_url=?
                """, result.status().name(), stamp(now), stamp(now.plusSeconds(retryMinutes * 60)), failures, result.code(), url);
        }
    }

    @Transactional
    public boolean publish(EntryState expected, Guide candidate, Map<String, String> versions,
                           String fingerprint, String reason, String proof, Instant now) {
        return publish(expected, candidate, versions, fingerprint, reason, proof, now, now);
    }

    @Transactional
    public boolean publish(EntryState expected, Guide candidate, Map<String, String> versions,
                           String fingerprint, String reason, String proof, Instant verifiedAt, Instant attemptedAt) {
        Set<String> expectedUrls = expected.verifiedSources().isEmpty()
            ? new HashSet<>(evidence(expected.guide()).stream().map(Evidence::url).toList()) : expected.verifiedSources().keySet();
        if (versions.isEmpty() || versions.size() != evidence(expected.guide()).size()
            || !versions.keySet().equals(expectedUrls)
            || !expected.id().equals(candidate.id()) || !expected.guide().title().equals(candidate.title())
            || !expected.guide().category().equals(candidate.category())) return false;
        lockEntry(expected.id());
        EntryState actual = entry(expected.id());
        if (!sameEntry(expected, actual) || !currentVersions(versions)) return false;
        Guide clean = withFreshness(candidate, null);
        history(actual, clean, versions, reason, proof == null ? "{}" : proof, attemptedAt);
        db.update("""
            UPDATE guide_entry_state SET published_json=?::jsonb,verified_sources=?::jsonb,revision=revision+1,status='CURRENT',
            last_verified_at=?,last_attempt_at=?,last_review_fingerprint=?,last_outcome=?,next_review_at=NULL WHERE guide_id=?
            """, json.writeValueAsString(clean), json.writeValueAsString(versions), stamp(verifiedAt), stamp(attemptedAt), fingerprint, reason, expected.id());
        return true;
    }

    @Transactional
    public boolean mark(EntryState expected, String status, String outcome, String fingerprint, Instant nextReview, Instant now) {
        lockEntry(expected.id());
        if (!sameEntry(expected, entry(expected.id()))) return false;
        db.update("""
            UPDATE guide_entry_state SET status=?,last_attempt_at=?,last_outcome=?,last_review_fingerprint=?,next_review_at=? WHERE guide_id=?
            """, status, stamp(now), outcome, fingerprint, stamp(nextReview), expected.id());
        return true;
    }

    private void lockEntry(String id) {
        db.queryForList("SELECT guide_id FROM guide_entry_state WHERE guide_id=? FOR UPDATE", id);
    }

    private boolean sameEntry(EntryState expected, EntryState actual) {
        return actual != null && expected.revision() == actual.revision() && expected.baselineHash().equals(actual.baselineHash());
    }

    private boolean currentVersions(Map<String, String> versions) {
        for (var item : versions.entrySet()) {
            var rows = db.queryForList("SELECT current_version,status FROM guide_source_state WHERE source_url=? FOR SHARE", item.getKey());
            if (rows.size() != 1 || !"AVAILABLE".equals(rows.get(0).get("status")) || !item.getValue().equals(rows.get(0).get("current_version")))
                return false;
        }
        return true;
    }

    private void history(EntryState before, Guide after, Map<String, String> sources, String reason, String proof, Instant now) {
        db.update("""
                INSERT INTO guide_entry_history(id,guide_id,revision,baseline_hash,before_json,after_json,source_versions,reason,proof_json,created_at)
                VALUES(?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb,?)
                """, UUID.randomUUID(), before.id(), before.revision(), before.baselineHash(), json.writeValueAsString(before.guide()),
            json.writeValueAsString(withFreshness(after, null)), json.writeValueAsString(sources), reason, proof, stamp(now));
    }

    @Transactional
    public boolean claim(UUID owner, Instant now) {
        return db.update("UPDATE guide_refresh_lease SET owner=?,lease_until=? WHERE singleton=true AND (owner IS NULL OR lease_until<?)",
            owner, stamp(now.plusSeconds(3600)), stamp(now)) == 1;
    }

    public boolean renew(UUID owner, Instant now) {
        return db.update("UPDATE guide_refresh_lease SET lease_until=? WHERE singleton=true AND owner=? AND lease_until>?",
            stamp(now.plusSeconds(3600)), owner, stamp(now)) == 1;
    }

    @Transactional
    public void release(UUID owner, Object report, Instant now) {
        db.update("UPDATE guide_refresh_lease SET owner=NULL,lease_until=NULL,last_completed_at=?,last_result=?::jsonb WHERE singleton=true AND owner=?",
            stamp(now), json.writeValueAsString(report), owner);
    }

    static Instant nextDaily(Instant now) {
        ZonedDateTime local = now.atZone(KOREA);
        ZonedDateTime next = local.toLocalDate().atTime(6, 0).atZone(KOREA);
        return (next.isAfter(local) ? next : next.plusDays(1)).toInstant();
    }

    static List<Evidence> evidence(Guide guide) {
        var values = new ArrayList<Evidence>();
        values.add(guide.evidence());
        values.addAll(guide.additionalEvidence());
        return List.copyOf(values);
    }

    private static Instant baselineTime(Guide guide) {
        return LocalDate.parse(guide.evidence().checkedAt()).atStartOfDay(KOREA).toInstant();
    }

    private static String date(Instant value) {
        return value == null ? null : value.atZone(KOREA).toLocalDate().toString();
    }

    private static Timestamp stamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
