package org.jibro.collection.review;

import java.time.Instant;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Actual parser outputs, combined without a hand-authored candidate or coverage waiver.
 */
public final class SuwonSupplementarySources {
    private SuwonSupplementarySources() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static <T> T load(String name, Class<T> type) throws Exception {
        try (var input = SuwonSupplementarySources.class.getResourceAsStream("/review/" + name)) {
            if (input == null) throw new IllegalStateException(name);
            return JSON.readValue(input, type);
        }
    }

    public static List<Source> sources() throws Exception {
        var additional = load("suwon-listing-and-sites.json", Source[].class);
        var forms = load("suwon-numbered-forms.json", Source[].class);
        try (var input = SuwonSupplementarySources.class.getResourceAsStream("/review/suwon-main-variants.json")) {
            var pair = JSON.readTree(input);
            return List.of(additional[0], JSON.treeToValue(pair.get("whole"), Source.class), JSON.treeToValue(pair.get("fragments"), Source.class),
                load("suwon-supply-inventory.json", Source.class), additional[1], forms[0], forms[1]);
        }
    }

    public static Draft draft(List<Source> sources) {
        return new Draft("extraction", "notice", 1, "READY", Instant.parse("2026-11-23T00:00:00Z"), "", sources);
    }

    public static Content original(List<Source> sources) {
        return new Content("공식 원문 안내", evidence(sources.get(0)), sources.stream().skip(1).map(SuwonSupplementarySources::evidence).toList(), List.of(), List.of(), List.of());
    }

    private static Evidence evidence(Source source) {
        return new Evidence(source.label(), source.url(), "2026-11-23");
    }

    public static GuidanceReviewCompiler.Assessment assess(List<Source> sources) {
        return GuidanceReviewCompiler.assess(draft(sources), original(sources));
    }

    public static Source replace(Source source, String before, String after) {
        return new Source(source.label(), source.url(), source.sha256(), source.status(), source.message(),
            source.excerpts().stream().map(e -> new Excerpt(e.category(), e.text().replace(before, after), e.location(), e.url())).toList(),
            source.context().stream().map(p -> new Part(p.location(), p.text().replace(before, after))).toList());
    }
}
