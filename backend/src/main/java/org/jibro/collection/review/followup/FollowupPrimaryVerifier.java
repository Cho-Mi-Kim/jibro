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

/** Fresh primary evidence is required even when a later official notice resolves the difference. */
@Component
public class FollowupPrimaryVerifier {
    private final AttachmentFingerprintClient http;

    public FollowupPrimaryVerifier(AttachmentFingerprintClient http) { this.http = http; }

    public Instant verify(Draft draft, Content original) throws Exception {
        Instant started = Instant.now();
        if (draft == null || draft.sources().isEmpty() || draft.sources().size() > 51)
            throw new IllegalArgumentException("PRIMARY_SOURCE_INCOMPLETE");
        Map<String, Source> sources = new LinkedHashMap<>();
        for (Source source : draft.sources()) {
            if (source.url() == null || source.sha256() == null || !source.sha256().matches("[0-9a-f]{64}")
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
            if (!sources.get(file).sha256().equals(http.fetch(file).sha256()))
                throw new IllegalArgumentException("PRIMARY_ATTACHMENT_CHANGED");
        }
        return started;
    }
}
