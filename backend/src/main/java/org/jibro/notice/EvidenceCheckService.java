package org.jibro.notice;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Service;

/**
 * Reachability assists a human review; it never publishes a notice or changes provenance dates.
 */
@Service
public class EvidenceCheckService {
    public record Result(String kind, String label, String url, String checkedAt, String status, Integer httpStatus) {
    }

    public record Report(int revision, int version, Instant checkedAt, List<Result> results) {
    }

    private record Probe(String status, Integer httpStatus) {
    }

    private static final int MAX_URLS = 10;
    private static final Duration START_BUDGET = Duration.ofSeconds(30);
    private final EvidenceSourceReader sources;
    private final AttachmentFingerprintClient transport;
    private final Semaphore capacity = new Semaphore(2);

    public EvidenceCheckService(EvidenceSourceReader sources, AttachmentFingerprintClient transport) {
        this.sources = sources;
        this.transport = transport;
    }

    public Report check(UUID id, int revision) {
        if (!capacity.tryAcquire())
            throw new ApiException(429, "EVIDENCE_CHECK_BUSY", "다른 링크를 점검 중입니다. 잠시 후 다시 시도해 주세요.");
        try {
            var snapshot = sources.read(id, revision);
            long start = System.nanoTime();
            Map<String, Probe> checked = new HashMap<>();
            var results = new ArrayList<Result>();
            for (var source : snapshot.sources()) {
                String key = requestKey(source.url());
                Probe result;
                if (key == null) result = new Probe("MISSING", null);
                else if (checked.containsKey(key)) result = checked.get(key);
                else if (Thread.currentThread().isInterrupted() || checked.size() >= MAX_URLS
                        || System.nanoTime() - start > START_BUDGET.toNanos())
                    result = new Probe("DEFERRED", null);
                else {
                    result = probe(key);
                    checked.put(key, result);
                }
                results.add(new Result(source.kind(), source.label(), source.url(), source.checkedAt(), result.status(), result.httpStatus()));
            }
            // Reject the entire stale report if another administrator saved a different revision.
            sources.read(id, revision);
            return new Report(revision, snapshot.version(), Instant.now(), List.copyOf(results));
        } finally {
            capacity.release();
        }
    }

    private Probe probe(String url) {
        try {
            int status = transport.probe(url);
            if (status >= 200 && status < 300) return new Probe("REACHABLE", status);
            if (status == 404 || status == 410) return new Probe("UNAVAILABLE", status);
            // Redirects, login walls and HEAD rejection need a manual browser check, not a broken-link verdict.
            return new Probe("MANUAL", status);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Probe("DEFERRED", null);
        } catch (IOException exception) {
            return new Probe("ATTACHMENT_URL_REJECTED".equals(exception.getMessage()) ? "UNSUPPORTED" : "UNCONFIRMED", null);
        } catch (Exception exception) {
            return new Probe("UNCONFIRMED", null);
        }
    }

    private String requestKey(String url) {
        if (url == null || url.isBlank()) return null;
        int fragment = url.indexOf('#');
        return fragment < 0 ? url : url.substring(0, fragment);
    }
}
