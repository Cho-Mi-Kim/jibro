package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;

import java.time.*;
import java.util.*;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class GuideRefreshService {
    public record Report(int sourcesChecked, int sourceFailures, int confirmed, int updated, int pending,
                         boolean busy) {
    }

    private final GuideCatalog catalog;
    private final GuideRefreshRepository store;
    private final GuideSourceClient sources;
    private final GuideContentReviewService review;
    private final ObjectMapper json;

    public GuideRefreshService(GuideCatalog catalog, GuideRefreshRepository store, GuideSourceClient sources,
                               GuideContentReviewService review, ObjectMapper json) {
        this.catalog = catalog;
        this.store = store;
        this.sources = sources;
        this.review = review;
        this.json = json;
    }

    public Report refreshDue(Instant now) {
        UUID owner = UUID.randomUUID();
        if (!store.claim(owner, now)) return new Report(0, 0, 0, 0, 0, true);
        Report report = new Report(0, 0, 0, 0, 0, false);
        boolean completed = false;
        Runnable heartbeat = heartbeat(owner, now);
        try {
            store.synchronize(catalog.items(), now);
            int[] checks = checkSources(false, now, heartbeat);
            int confirmed = 0, updated = 0, pending = 0;
            for (Guide baseline : catalog.items()) {
                if (Thread.currentThread().isInterrupted()) break;
                heartbeat.run();
                String outcome = refreshEntry(baseline, now);
                if (outcome.equals("UPDATED")) updated++;
                else if (outcome.equals("CONFIRMED")) confirmed++;
                else if (outcome.equals("PENDING")) pending++;
            }
            report = new Report(checks[0], checks[1], confirmed, updated, pending, false);
            completed = !Thread.currentThread().isInterrupted();
            return report;
        } finally {
            store.release(owner, completed ? report : Map.of("status", "FAILED_OR_INTERRUPTED"), now);
        }
    }

    private Runnable heartbeat(UUID owner, Instant startedAt) {
        long started = System.nanoTime();
        return () -> {
            if (!store.renew(owner, startedAt.plusNanos(System.nanoTime() - started)))
                throw new IllegalStateException("Guide refresh lease lost");
        };
    }

    private int[] checkSources(boolean force, Instant now, Runnable heartbeat) {
        int checked = 0, failed = 0;
        Set<String> urls = new LinkedHashSet<>();
        catalog.items().forEach(guide -> GuideRefreshRepository.evidence(guide).forEach(e -> urls.add(e.url())));
        for (String url : urls) {
            var state = store.source(url);
            if (!force && state != null && state.nextCheck().isAfter(now)) continue;
            if (Thread.currentThread().isInterrupted()) break;
            heartbeat.run();
            GuideSourceClient.Result result;
            try {
                result = sources.fetch(url);
            } catch (RuntimeException failedSource) {
                result = new GuideSourceClient.Result(GuideSourceClient.Status.UNREACHABLE, null, "SOURCE_FETCH_FAILED");
            }
            store.recordSource(url, result, now);
            checked++;
            if (result.status() != GuideSourceClient.Status.AVAILABLE) failed++;
        }
        return new int[]{checked, failed};
    }

    private String refreshEntry(Guide baseline, Instant now) {
        var entry = store.entry(baseline.id());
        if (entry == null || !entry.baselineHash().equals(store.baselineHash(baseline))) return "SKIPPED";
        var states = sourceStates(baseline);
        if (states.values().stream().anyMatch(s -> s == null || !s.status().equals("AVAILABLE"))) {
            Instant attempted = states.values().stream().filter(Objects::nonNull).map(GuideRefreshRepository.SourceState::lastAttempt)
                .filter(Objects::nonNull).max(Instant::compareTo).orElse(now);
            String status = states.values().stream().anyMatch(s -> s == null || !s.status().equals("UNREADABLE") && !s.status().equals("AVAILABLE"))
                ? "UNREACHABLE" : "RECHECK";
            String outcome = "REVIEW_REQUIRED".equals(entry.outcome()) ? "REVIEW_REQUIRED" : "OFFICIAL_SOURCE_UNAVAILABLE";
            store.mark(entry, status, outcome, entry.reviewFingerprint(), null, attempted);
            return "PENDING";
        }
        Map<String, String> versions = new LinkedHashMap<>();
        states.forEach((url, state) -> versions.put(url, state.version()));
        Instant sourceChecked = states.values().stream().map(GuideRefreshRepository.SourceState::lastSuccess).min(Instant::compareTo).orElse(now);
        String fingerprint = GuideRefreshRepository.sha(GuideContentReviewPlan.POLICY + "\n" + entry.baselineHash() + "\n" + entry.revision() + "\n" + json.writeValueAsString(versions));
        if (versions.equals(entry.verifiedSources())) {
            if (entry.status().equals("CURRENT") && entry.verifiedAt() != null && !sourceChecked.isAfter(entry.verifiedAt()))
                return "UNCHANGED";
            return store.publish(entry, withSources(entry.guide(), baseline, states), versions, fingerprint,
                "SOURCE_UNCHANGED", "{}", sourceChecked, now) ? "CONFIRMED" : "SKIPPED";
        }
        boolean sameContent = !entry.verifiedSources().isEmpty() && states.entrySet().stream().allMatch(item -> {
            var previous = store.version(entry.verifiedSources().get(item.getKey()));
            return previous != null && previous.contentHash().equals(item.getValue().snapshot().contentHash());
        });
        if (sameContent)
            return store.publish(entry, withSources(entry.guide(), baseline, states), versions, fingerprint,
                "SOURCE_RELOCATED", "{}", sourceChecked, now) ? "CONFIRMED" : "SKIPPED";
        // A transport recovery must retry an unfinished review, not accept the newly observed fingerprint.
        if (fingerprint.equals(entry.reviewFingerprint())) {
            if ("REVIEW_REQUIRED".equals(entry.outcome())) {
                if (!entry.status().equals("RECHECK"))
                    store.mark(entry, "RECHECK", "REVIEW_REQUIRED", fingerprint, null, now);
                return "PENDING";
            }
            if (entry.nextReview() != null && entry.nextReview().isAfter(now)) return "PENDING";
        }
        var changes = new ArrayList<GuideContentReviewService.SourceChange>();
        int index = 0;
        for (var item : states.entrySet()) {
            var snapshot = item.getValue().snapshot();
            var previous = store.version(entry.verifiedSources().get(item.getKey()));
            changes.add(new GuideContentReviewService.SourceChange("source-" + (++index), snapshot.finalUrl(), snapshot.contentHash(),
                previous == null ? "" : previous.text(), snapshot.text()));
        }
        var decision = review.review(withSources(entry.guide(), baseline, states), changes, now);
        if (decision.status() == GuideContentReviewService.Status.CONFIRMED || decision.status() == GuideContentReviewService.Status.UPDATED) {
            Guide candidate = decision.candidate();
            if (candidate == null || !sameIdentity(entry.guide(), candidate)) {
                store.mark(entry, "RECHECK", "REVIEW_REQUIRED", fingerprint, null, now);
                return "PENDING";
            }
            boolean published = store.publish(entry, withSources(candidate, baseline, states), versions, fingerprint,
                decision.status().name(), decision.proofJson(), sourceChecked, now);
            return published ? decision.status().name() : "SKIPPED";
        }
        Instant retry = switch (decision.status()) {
            case DEFERRED -> now.plusSeconds(65);
            case FAILED ->
                now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).plusMinutes(1).toInstant();
            default -> null;
        };
        store.mark(entry, "RECHECK", decision.status().name(), fingerprint, retry, now);
        return "PENDING";
    }

    private Map<String, GuideRefreshRepository.SourceState> sourceStates(Guide baseline) {
        Map<String, GuideRefreshRepository.SourceState> result = new LinkedHashMap<>();
        GuideRefreshRepository.evidence(baseline).forEach(evidence -> result.put(evidence.url(), store.source(evidence.url())));
        return result;
    }

    private static boolean sameIdentity(Guide before, Guide after) {
        return before.id().equals(after.id()) && before.category().equals(after.category()) && before.title().equals(after.title())
            && after.body() != null && !after.body().isBlank() && after.scope() != null && !after.scope().isBlank();
    }

    private static Guide withSources(Guide content, Guide baseline, Map<String, GuideRefreshRepository.SourceState> states) {
        List<Evidence> updated = GuideRefreshRepository.evidence(baseline).stream().map(evidence -> {
            var source = states.get(evidence.url());
            return new Evidence(evidence.label(), source.snapshot().finalUrl(), source.lastSuccess().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString());
        }).toList();
        return new Guide(content.id(), content.category(), content.title(), content.body(), updated.get(0), content.scope(), updated.subList(1, updated.size()));
    }

    public String catalogFingerprint() {
        return GuideRefreshRepository.sha(json.writeValueAsString(catalog.items().stream().map(store::baselineHash).toList()));
    }

    /**
     * Internal one-time bootstrap for the just-reviewed packaged catalogue; never an HTTP or scheduled entry point.
     */
    public Report initializeReviewedBaseline(String expectedCatalogFingerprint, Instant now) {
        if (!catalogFingerprint().equals(expectedCatalogFingerprint)
            || catalog.items().stream().anyMatch(g -> !g.evidence().checkedAt().equals(now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString())))
            throw new IllegalArgumentException("검토한 당일 원고와 현재 가이드가 다릅니다.");
        UUID owner = UUID.randomUUID();
        if (!store.claim(owner, now)) return new Report(0, 0, 0, 0, 0, true);
        Report report = new Report(0, 0, 0, 0, 0, false);
        Runnable heartbeat = heartbeat(owner, now);
        try {
            store.synchronize(catalog.items(), now);
            int[] checks = checkSources(true, now, heartbeat);
            int confirmed = 0, pending = 0;
            for (Guide baseline : catalog.items()) {
                heartbeat.run();
                var entry = store.entry(baseline.id());
                if (!entry.verifiedSources().isEmpty() || !store.baselineHash(entry.guide()).equals(store.baselineHash(baseline)))
                    continue;
                var states = sourceStates(baseline);
                if (states.values().stream().anyMatch(s -> s == null || !s.status().equals("AVAILABLE"))) {
                    store.mark(entry, "UNREACHABLE", "BASELINE_SOURCE_UNAVAILABLE", null, null, now);
                    pending++;
                    continue;
                }
                Map<String, String> versions = new LinkedHashMap<>();
                states.forEach((url, state) -> versions.put(url, state.version()));
                String proof = json.writeValueAsString(Map.of("reviewedCatalog", expectedCatalogFingerprint, "reason", "Packaged content reviewed on this date; official source baseline captured without rewriting."));
                if (store.publish(entry, withSources(baseline, baseline, states), versions, null, "REVIEWED_CATALOG_BASELINE", proof, now))
                    confirmed++;
            }
            report = new Report(checks[0], checks[1], confirmed, 0, pending, false);
            return report;
        } finally {
            store.release(owner, report, now);
        }
    }
}
