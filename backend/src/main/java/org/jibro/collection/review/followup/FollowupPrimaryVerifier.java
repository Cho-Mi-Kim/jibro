package org.jibro.collection.review.followup;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.ExtractionModels.Draft;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.collection.extraction.OfficialDetailParser;
import org.jibro.notice.HousingModels.Content;
import org.springframework.stereotype.Component;

/**
 * Fresh primary evidence is required even when a later official notice resolves the difference.
 */
@Component
public class FollowupPrimaryVerifier {
    private final AttachmentFingerprintClient http;

    public FollowupPrimaryVerifier(AttachmentFingerprintClient http) {
        this.http = http;
    }

    public Instant verify(Draft draft, Content original) throws Exception {
        return verify(draft, original, false);
    }

    /** A missing original may support no claim. Verify the detail and recheck that the known file is still unavailable. */
    public Instant verifyItems(Draft draft, Content original) throws Exception {
        return verify(draft, original, true);
    }

    private Instant verify(Draft draft, Content original, boolean alternateItems) throws Exception {
        Instant started = Instant.now();
        if (draft == null || draft.sources().isEmpty() || draft.sources().size() > 51)
            throw new IllegalArgumentException("PRIMARY_SOURCE_INCOMPLETE");
        Map<String, Source> sources = new LinkedHashMap<>();
        for (Source source : draft.sources()) {
            boolean unavailable = alternateItems && "FAILED".equals(source.status()) && source.sha256() == null
                && !original.evidence().url().equals(source.url()) && source.context().isEmpty();
            if (source.url() == null || !unavailable && (source.sha256() == null || !source.sha256().matches("[0-9a-f]{64}"))
                || sources.putIfAbsent(source.url(), source) != null)
                throw new IllegalArgumentException("PRIMARY_SOURCE_INCOMPLETE");
        }
        String url = original.evidence().url();
        Source stored = sources.get(url);
        if (stored == null) throw new IllegalArgumentException("PRIMARY_DETAIL_MISSING");
        var current = OfficialDetailParser.parse(http.html(url), url);
        if (!current.parts().equals(stored.context())) throw new IllegalArgumentException("PRIMARY_DETAIL_CHANGED");
        var files = new HashSet<String>();
        current.attachments().forEach(link -> files.add(link.url()));
        files.remove(url);
        if (original.attachments().stream().anyMatch(link -> !link.url().equals(url) && !files.contains(link.url())))
            throw new IllegalArgumentException("PRIMARY_ATTACHMENT_SET_CHANGED");
        var expected = new HashSet<>(sources.keySet());
        expected.remove(url);
        if (!files.equals(expected)) throw new IllegalArgumentException("PRIMARY_ATTACHMENT_SET_CHANGED");
        for (String file : files) {
            if (Duration.between(started, Instant.now()).compareTo(Duration.ofSeconds(90)) > 0)
                throw new IllegalStateException("PRIMARY_VERIFICATION_DEFERRED");
            var source = sources.get(file);
            if (source.sha256() == null) {
                try {
                    http.fetch(file);
                } catch (java.util.concurrent.ExecutionException unavailable) {
                    if (unavailable.getCause() instanceof java.io.IOException) continue;
                    throw unavailable;
                } catch (java.io.IOException unavailable) {
                    if ("ATTACHMENT_HTTP_STATUS".equals(unavailable.getMessage())) continue;
                    throw unavailable;
                }
                throw new IllegalArgumentException("PRIMARY_ATTACHMENT_CHANGED");
            }
            var fingerprint = org.jibro.collection.attachments.HousingGraphic.matches(source.label(), file)
                ? http.fetchForSourceReview(file) : http.fetch(file);
            if (!source.sha256().equals(fingerprint.sha256()))
                throw new IllegalArgumentException("PRIMARY_ATTACHMENT_CHANGED");
        }
        return started;
    }
}
