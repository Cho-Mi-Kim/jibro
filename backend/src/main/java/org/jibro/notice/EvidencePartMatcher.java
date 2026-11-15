package org.jibro.notice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.OcrProvenance;

/** Resolves only one unchanged source part; changed or duplicated text remains unresolved. */
public final class EvidencePartMatcher {
    public record Match(int index, String location) {}
    private EvidencePartMatcher() {}
    public static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static java.util.Map<String, Match> uniqueParts(List<Part> parts) {
        // An unread portion could conceal another copy of the evidence.
        if (parts.stream().anyMatch(part -> part == null || OcrProvenance.contains(part)
            || part.text() == null || part.text().isBlank() || part.text().contains("[발췌 길이 제한]"))) return java.util.Map.of();
        var matches = new java.util.HashMap<String, Match>();
        var duplicates = new java.util.HashSet<String>();
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            if (part.location() == null) continue;
            var key = digest(part.text());
            if (matches.putIfAbsent(key, new Match(i, part.location())) != null) duplicates.add(key);
        }
        duplicates.forEach(matches::remove);
        return java.util.Map.copyOf(matches);
    }
    public static Match match(List<Part> parts, String expected) { return uniqueParts(parts).get(expected); }
}
