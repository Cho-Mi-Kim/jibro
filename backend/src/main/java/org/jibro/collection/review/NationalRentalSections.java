package org.jibro.collection.review;

import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.Evidence;

/**
 * Keeps complete source spans beside concise guidance; no source text is truncated for approval.
 */
final class NationalRentalSections {
    private final Source source;
    private final String day;

    NationalRentalSections(Source source, Draft draft) {
        this.source = source;
        day = draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
    }

    String text() {
        return source.context().stream().map(Part::text).reduce("", (a, b) -> a + "\n" + b);
    }

    String compact() {
        return compact(text());
    }

    Evidence evidence(String... terms) {
        List<Part> parts = source.context().stream().filter(p -> {
            String text = compact(p.text());
            for (String term : terms) if (text.contains(compact(term))) return true;
            return false;
        }).toList();
        String locations = String.join(" · ", parts.stream().map(Part::location).distinct().toList());
        return new Evidence(source.label() + (locations.isEmpty() ? "" : " · " + locations), EvidenceLocationUrl.forTerms(source, terms), day);
    }

    boolean contains(String... terms) {
        String text = compact();
        for (String term : terms) if (!text.contains(compact(term))) return false;
        return true;
    }

    String between(String start, String end) {
        String text = compact();
        int from = text.indexOf(start), to = from < 0 ? -1 : text.indexOf(end, from + start.length());
        return from < 0 || to < 0 ? "" : text.substring(from, to);
    }

    static String compact(String text) {
        return Pattern.compile("[\\s\\p{Zs}]+").matcher(text).replaceAll("");
    }
}
