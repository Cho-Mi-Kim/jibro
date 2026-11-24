package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import org.jibro.collection.extraction.BoundedDocumentParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit, read-only live audit. Normal unit and deployment test runs never contact official sites.
 */
@EnabledIfEnvironmentVariable(named = "JIBRO_GUIDE_LIVE_AUDIT", matches = "true")
class GuideSourceLiveAuditTest {
    @Test
    void readsEveryCurrentOfficialSourceWithoutPublishingGuideChanges() throws Exception {
        var json = JsonMapper.builder().build();
        var catalog = new GuideCatalog(json);
        var client = new GuideSourceClient(new BoundedDocumentParser(), json);
        var urls = catalog.items().stream().flatMap(guide -> Stream.concat(Stream.of(guide.evidence()), guide.additionalEvidence().stream()))
            .map(HousingModels.Evidence::url).distinct().sorted().toList();
        var executor = Executors.newFixedThreadPool(2);
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> snapshots = Collections.synchronizedList(new ArrayList<>());
        try {
            var pending = urls.stream().map(url -> executor.submit(() -> {
                var fetched = client.fetch(url);
                var row = new LinkedHashMap<String, Object>();
                row.put("url", url);
                row.put("status", fetched.status().name());
                row.put("code", fetched.code());
                if (fetched.snapshot() != null) {
                    row.put("finalUrl", fetched.snapshot().finalUrl());
                    row.put("title", fetched.snapshot().title());
                    row.put("characters", fetched.snapshot().text().length());
                    row.put("hash", fetched.snapshot().contentHash());
                    snapshots.add(Map.of("requestedUrl", fetched.snapshot().requestedUrl(), "finalUrl", fetched.snapshot().finalUrl(),
                        "title", fetched.snapshot().title(), "text", fetched.snapshot().text(), "contentHash", fetched.snapshot().contentHash(),
                        "checkedAt", fetched.snapshot().checkedAt().toString(), "redirects", fetched.snapshot().redirects()));
                }
                return row;
            })).toList();
            for (var future : pending) results.add(future.get(5, TimeUnit.MINUTES));
        } finally {
            executor.shutdownNow();
        }
        Path output = Path.of("../.tools/qa/guide-auto-sources/live-audit.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        Files.writeString(output.resolveSibling("snapshots.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshots));
        assertEquals(37, urls.size());
        assertTrue(results.stream().allMatch(row -> row.get("status").equals("AVAILABLE")), "See read-only live audit output for fixed failure codes.");
    }
}
