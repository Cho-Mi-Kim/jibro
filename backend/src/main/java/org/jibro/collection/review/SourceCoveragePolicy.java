package org.jibro.collection.review;

import java.util.*;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;

/** Distinguishes recruitment evidence from metadata and explicitly ancillary attachments. */
public final class SourceCoveragePolicy {
    private SourceCoveragePolicy() {}
    public record Coverage(Draft draft, Content original, List<Source> scheduleSources, List<String> issues) {
        public boolean ready() { return issues.isEmpty(); }
    }
    private static final Pattern AUXILIARY = Pattern.compile("평면도|배치도|위치도");
    private static final Pattern GOVERNING = Pattern.compile("공고|정정|조건|자격|소득|자산|예외|추가|수정|변경|제출");

    public static Coverage assess(Draft draft, Content original) {
        return assess(draft, original, false);
    }

    /** Only fully located differences of the same exact rule can reach scoped compilation. */
    public static Coverage assessScoped(Draft draft, Content original) {
        return assess(draft, original, true);
    }

    private static Coverage assess(Draft draft, Content original, boolean scoped) {
        var issues = new ArrayList<String>();
        if (draft == null || draft.checkedAt() == null || !Set.of("READY", "PARTIAL").contains(draft.status()))
            return new Coverage(draft, original, List.of(), List.of("SOURCE_NOT_FINISHED"));
        var groups = new LinkedHashMap<String, List<Source>>();
        var excluded = new HashSet<String>();
        var scheduleSources = new ArrayList<Source>();
        var sourceUrls = new HashSet<String>();
        draft.sources().forEach(s -> sourceUrls.add(s.url()));
        if (!sourceUrls.contains(original.evidence().url()) || original.attachments().stream().anyMatch(a -> !sourceUrls.contains(a.url())))
            issues.add("SOURCE_COVERAGE_INCOMPLETE");
        for (Source source : draft.sources()) {
            String label = source.label().replaceAll("\\s+", "");
            if (OfficialListingMetadata.onlyFields(source)) {
                excluded.add(source.url());
                // Metadata is not a second qualification document, but its dates remain a source
                // for cross-document schedule conflict checks and final evidence provenance.
                scheduleSources.add(source);
                continue;
            }
            if (!GOVERNING.matcher(label).find() && AUXILIARY.matcher(label).find()) {
                excluded.add(source.url());
                continue;
            }
            if (org.jibro.collection.extraction.OcrProvenance.contains(source))
                issues.add(org.jibro.collection.extraction.OcrProvenance.REVIEW_REQUIRED);
            groups.computeIfAbsent(stem(label), ignored -> new ArrayList<>()).add(source);
        }
        var selected = new ArrayList<Source>();
        for (var variants : groups.values()) {
            // A missing unknown/authoritative representation is not proof of equivalent contents.
            if (variants.stream().anyMatch(s -> !complete(s))) {
                variants.stream().filter(s -> !complete(s))
                    .forEach(s -> issues.add("AUTHORITATIVE_SOURCE_INCOMPLETE:" + s.label()));
                continue;
            }
            Source canonical = variants.stream().filter(s -> s.label().toLowerCase(Locale.ROOT).endsWith(".pdf")).findFirst().orElse(variants.get(0));
            boolean differs = variants.stream().anyMatch(s -> !DocumentVariantComparison.equivalent(canonical, s));
            boolean scopeable = differs && scoped && variants.stream()
                .allMatch(s -> sameRuleContext(canonical, s));
            if (scopeable) selected.addAll(variants);
            else {
                if (differs) issues.add("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:" + canonical.label());
                selected.add(canonical);
            }
            scheduleSources.addAll(variants);
            if (!scopeable) for (Source variant : variants) if (variant != canonical) excluded.add(variant.url());
        }
        if (selected.isEmpty()) issues.add("RECRUITMENT_DOCUMENT_REQUIRED");
        var retainedAttachments = original.attachments().stream().filter(a -> !excluded.contains(a.url())).toList();
        Content selectedOriginal = new Content(original.summary(), original.evidence(), retainedAttachments, original.questions(), original.documents(), original.schedules(), original.reviewIssues());
        // The top-level evidence remains the official listing; compilers use the selected fetched documents for rules.
        if (excluded.contains(original.evidence().url()) && !selected.isEmpty()) {
            Source first = selected.get(0);
            selectedOriginal = new Content(original.summary(), new Evidence(first.label(), first.url(), draft.checkedAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString()),
                retainedAttachments, original.questions(), original.documents(), original.schedules(), original.reviewIssues());
        }
        return new Coverage(new Draft(draft.id(), draft.noticeId(), draft.revision(), issues.isEmpty() ? "READY" : "PARTIAL", draft.checkedAt(), draft.message(), List.copyOf(selected)), selectedOriginal, List.copyOf(scheduleSources), List.copyOf(issues));
    }

    private static boolean complete(Source source) {
        return "EXTRACTED".equals(source.status()) && source.sha256() != null && source.sha256().matches("[a-fA-F0-9]{64}")
            && source.context() != null && !source.context().isEmpty() && source.context().size() <= 200
            && source.context().stream().allMatch(part -> part != null && part.location() != null && !part.location().isBlank()
                && part.text() != null && !part.text().replaceAll("[\\s\\p{Zs}]+", "").isEmpty()
                && !part.text().contains("[발췌 길이 제한]"))
            && source.context().stream().mapToLong(part -> part.text().length()).sum() <= 1_000_000;
    }

    private static boolean sameRuleContext(Source first, Source second) {
        return ruleContext(first).equals(ruleContext(second));
    }

    private record RuleContext(String kind, String key, String text) {}

    private static List<RuleContext> ruleContext(Source source) {
        var context = new ArrayList<RuleContext>();
        for (Part part : source.context()) for (String line : part.text().split("\\R")) {
            String compact = line.replaceAll("[\\s\\p{Zs}]+", "");
            if (compact.isEmpty()) continue;
            var rule = GuidanceRuleGrammar.parse(line.trim());
            // Preserve order, occurrence count and every surrounding clause. A newly added rule,
            // missing rule or changed subject/exception is not merely a different rule value.
            context.add(rule == null ? new RuleContext(null, null, compact) : new RuleContext(rule.kind(), rule.key(), null));
        }
        return context;
    }

    private static String stem(String label) { return label.toLowerCase(Locale.ROOT).replaceFirst("\\.(pdf|hwpx|hwp)$", ""); }
}
