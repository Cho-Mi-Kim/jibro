package org.jibro.collection.review.summary;

import java.util.List;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.collection.extraction.OcrProvenance;

/** Reads only complete recruitment text, never listing metadata or a housing-type label alone. */
record SupportSummarySource(String heading, String text, List<String> qualificationLeads) {
    private static final Pattern AUXILIARY = Pattern.compile("동의서|서약서|신청서|평면도|배치도|목록|Q&A|질의응답|작성예시");
    private static final Pattern SECTION = Pattern.compile("(?m)^\\h*(?:(?:\\d+[.．)]|[■□▪])\\h*)?(?:신청\\h*자격|입주\\h*자격)[^\\r\\n]{0,60}(?:\\R|$)");

    static SupportSummarySource read(Source source) {
        if (!"EXTRACTED".equals(source.status()) || source.sha256() == null
            || !source.sha256().matches("[a-fA-F0-9]{64}") || OcrProvenance.contains(source)
            || source.context().isEmpty() || source.context().size() > 200
            || AUXILIARY.matcher(source.label()).find()) return null;
        var parts = source.context();
        if (parts.stream().anyMatch(p -> p == null || p.text() == null || p.location() == null
            || p.location().isBlank() || p.text().contains("[발췌 길이 제한]"))
            || parts.stream().mapToLong(p -> p.text().length()).sum() > 1_000_000) return null;
        String first = parts.get(0).text().replaceAll("^\\s*-?\\s*\\d+\\s*-?\\s*\\R", "");
        StringBuilder title = new StringBuilder();
        for (String line : first.split("\\R")) {
            String clean = line.strip();
            if (clean.matches("^(?:[▪■□•※‘']|[12][09]\\d{2}[.]).*") || clean.contains("이란?")) break;
            if (title.length() + clean.length() > 220) break;
            title.append(clean);
            if (title.toString().contains("모집") && (clean.endsWith("공고") || clean.endsWith("모집"))) break;
        }
        String heading = compact(title.toString());
        if (!heading.contains("모집") || !heading.contains("임대") && !heading.contains("행복주택")) return null;
        String raw = String.join("\n", parts.stream().map(p -> p.text()).toList());
        // Only actual section headings start a qualification block, not '(4. 신청자격 참조)'.
        var found = new java.util.ArrayList<String>();
        var matcher = SECTION.matcher(raw);
        while (matcher.find()) {
            String lead = compact(raw.substring(matcher.start(), Math.min(raw.length(), matcher.end() + 800)));
            found.add(lead);
        }
        return new SupportSummarySource(heading, compact(raw), List.copyOf(found));
    }

    boolean has(String regex) { return Pattern.compile(regex).matcher(text).find(); }
    boolean lead(String regex) { return qualificationLeads().stream().anyMatch(s -> Pattern.compile(regex).matcher(s).find()); }
    static String compact(String text) { return text.replaceAll("[\\s\\p{Zs}]+", ""); }
}
