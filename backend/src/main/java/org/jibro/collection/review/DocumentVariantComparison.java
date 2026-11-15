package org.jibro.collection.review;

import java.text.Normalizer;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;

/**
 * Accepts lossless layout differences only. Matching words, numbers or character counts cannot
 * establish that subjects, table columns, negations and exceptions still have the same meaning.
 */
final class DocumentVariantComparison {
    private static final Pattern PAGE = Pattern.compile("(?:공고문\\s*)?(\\d{1,3})\\s*쪽");
    private static final Pattern PAGE_COUNTER = Pattern.compile("(?m)^\\s*-\\s*\\d+\\s*-\\s*$");
    private static final int MAX_PARTS = 200;
    private static final int MAX_CHARACTERS = 1_000_000;

    private DocumentVariantComparison() {}

    static boolean equivalent(Source first, Source second) {
        if (!complete(first.context()) || !complete(second.context())) return false;
        if (normalize(first.context()).equals(normalize(second.context()))) return true;
        // Physical pages may arrive in a different collection order. Only explicit, complete and
        // unique page numbers permit restoring their order; arbitrary paragraphs are never sorted.
        var firstPages = orderedPages(first.context());
        var secondPages = orderedPages(second.context());
        return firstPages != null && secondPages != null
            && normalize(firstPages).equals(normalize(secondPages));
    }

    private static boolean complete(List<Part> parts) {
        return parts != null && !parts.isEmpty() && parts.size() <= MAX_PARTS
            && parts.stream().allMatch(part -> part != null && part.text() != null && part.location() != null)
            && parts.stream().mapToLong(part -> part.text().length()).sum() <= MAX_CHARACTERS;
    }

    private static List<Part> orderedPages(List<Part> parts) {
        var pages = new TreeMap<Integer, Part>();
        for (Part part : parts) {
            var number = PAGE.matcher(part.location().strip());
            if (!number.matches() || pages.putIfAbsent(Integer.parseInt(number.group(1)), part) != null) return null;
        }
        if (pages.firstKey() != 1 || pages.lastKey() != pages.size()) return null;
        return List.copyOf(pages.values());
    }

    private static String normalize(List<Part> parts) {
        var text = new StringBuilder();
        for (Part part : parts) text.append('\n').append(PAGE_COUNTER.matcher(part.text()).replaceAll(""));
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("[\\s\\p{Zs}]+", "");
    }
}
