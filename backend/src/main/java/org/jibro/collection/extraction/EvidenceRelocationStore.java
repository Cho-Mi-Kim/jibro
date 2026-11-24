package org.jibro.collection.extraction;

import java.net.URI;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.EvidencePartMatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Updates verified location mappings in the extraction checkpoint transaction, never on a viewer request.
 */
@Component
public class EvidenceRelocationStore {
    record Baseline(String sha, List<String> digests) {
    }

    private final JdbcTemplate db;
    private final Map<String, Baseline> baselines;

    public EvidenceRelocationStore(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        try (var input = getClass().getResourceAsStream("/evidence/evidence-manifests.json")) {
            if (input == null) throw new IllegalStateException("Evidence manifest is missing");
            var tree = json.readTree(input);
            Map<String, Baseline> result = new HashMap<>();
            tree.properties().forEach(entry -> {
                var value = entry.getValue();
                var sha = value.path("sha256").asText();
                List<String> digests = new ArrayList<>();
                value.path("parts").forEach(part -> {
                    var digest = part.path("digest").asText();
                    if (digest.matches("[0-9a-f]{64}")) digests.add(digest);
                });
                if (entry.getKey().matches("[0-9]{1,20}") && sha.matches("[0-9a-f]{64}"))
                    result.put(entry.getKey(), new Baseline(sha, List.copyOf(new LinkedHashSet<>(digests))));
            });
            baselines = Map.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalStateException("Evidence manifest cannot be loaded", failure);
        }
    }

    static String fileId(String url) {
        try {
            var uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"apply.lh.or.kr".equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getUserInfo() != null || !"/lhapply/lhFile.do".equals(uri.getPath()) || uri.getRawQuery() == null
                    || !uri.getRawQuery().matches("fileid=[0-9]{1,20}")) return null;
            return uri.getRawQuery().substring(7);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    public String baselineHash(String url) {
        var id = fileId(url);
        var baseline = id == null ? null : baselines.get(id);
        return baseline == null ? null : baseline.sha();
    }

    public boolean differsFromKnownBaseline(String url, String sha) {
        var id = fileId(url);
        var baseline = id == null ? null : baselines.get(id);
        return baseline != null && !baseline.sha().equals(sha);
    }

    /**
     * Backfills changed files already fingerprinted before background relocation was installed.
     */
    public boolean needsReconciliation(String url, String currentSha) {
        var id = fileId(url);
        var baseline = id == null ? null : baselines.get(id);
        if (baseline == null || baseline.digests().isEmpty() || currentSha == null || baseline.sha().equals(currentSha))
            return false;
        Integer count = db.queryForObject("""
                SELECT count(*) FROM evidence_location_resolution WHERE file_id=? AND baseline_sha=? AND current_sha=?
                """, Integer.class, id, baseline.sha(), currentSha);
        return count == null || count < baseline.digests().size();
    }

    /**
     * Called inside the same transaction that records the new official attachment fingerprint.
     */
    public void markPending(String url, String currentSha) {
        var id = fileId(url);
        var baseline = id == null ? null : baselines.get(id);
        if (baseline == null || currentSha == null || !currentSha.matches("[0-9a-f]{64}")) return;
        for (var digest : baseline.digests())
            db.update("""
                    INSERT INTO evidence_location_resolution(file_id,baseline_sha,part_digest,current_sha,state)
                    VALUES (?,?,?,?,'PENDING')
                    ON CONFLICT(file_id,baseline_sha,part_digest) DO UPDATE SET current_sha=excluded.current_sha,
                        state='PENDING',current_part=NULL,current_location=NULL,checked_at=now()
                    WHERE evidence_location_resolution.current_sha IS DISTINCT FROM excluded.current_sha
                    """, id, baseline.sha(), digest, currentSha);
    }

    /**
     * Runs under the extraction checkpoint revision/worker token; only the detected current digest can win.
     */
    public void reconcile(List<Source> sources) {
        for (var source : sources) {
            if (source == null || !Set.of("EXTRACTED", "EMPTY").contains(source.status()) || source.sha256() == null)
                continue;
            var id = fileId(source.url());
            var baseline = id == null ? null : baselines.get(id);
            if (baseline == null) continue;
            var matches = EvidencePartMatcher.uniqueParts(source.context());
            for (var digest : baseline.digests()) {
                var match = matches.get(digest);
                db.update("""
                                UPDATE evidence_location_resolution SET state=?,current_part=?,current_location=?,checked_at=now()
                                WHERE file_id=? AND baseline_sha=? AND part_digest=? AND current_sha=? AND state IN ('PENDING','REVIEW')
                                """, match == null ? "REVIEW" : "MATCH", match == null ? null : match.index(),
                        match == null ? null : match.location(), id, baseline.sha(), digest, source.sha256());
            }
        }
    }

    /**
     * Final extraction failures remain visibly unresolved instead of pending forever.
     */
    public void finishUnresolved(UUID noticeId) {
        db.update("""
                UPDATE evidence_location_resolution r SET state='REVIEW',current_part=NULL,current_location=NULL,checked_at=now()
                WHERE r.state='PENDING' AND EXISTS (
                    SELECT 1 FROM attachment_fingerprint f WHERE f.notice_id=? AND f.sha256=r.current_sha
                        AND f.url=('https://apply.lh.or.kr/lhapply/lhFile.do?fileid=' || r.file_id))
                """, noticeId);
    }

    public Optional<Map<String, Object>> resolved(String fileId, String baselineSha, String digest) {
        return db.query("""
                SELECT current_sha,current_part,current_location FROM evidence_location_resolution
                WHERE file_id=? AND baseline_sha=? AND part_digest=? AND state='MATCH'
                """, (rs, n) -> Map.<String, Object>of("sha256", rs.getString(1).strip(), "part", rs.getInt(2), "location", rs.getString(3)), fileId, baselineSha, digest).stream().findFirst();
    }
}
