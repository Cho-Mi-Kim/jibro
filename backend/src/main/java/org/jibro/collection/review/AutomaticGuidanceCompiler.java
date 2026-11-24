package org.jibro.collection.review;

import java.net.URI;
import java.time.ZoneId;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.EvidenceValidation;
import org.jibro.notice.HousingModels.*;

/**
 * Compiles only fully covered, evidence-bound rules; no keyword or model-confidence publication.
 */
public final class AutomaticGuidanceCompiler {
    private AutomaticGuidanceCompiler() {
    }

    public record Result(boolean ready, List<String> issues, Content content,
                         Map<String, List<String>> applicantGroups) {
        public Result(boolean ready, List<String> issues, Content content) {
            this(ready, issues, content, Map.of());
        }
    }

    record LocatedRule(GuidanceRuleGrammar.Rule rule, Evidence evidence, String statement) {
    }

    private static final Set<String> HEADINGS = Set.of("신청 자격", "신청자격", "제출서류", "제출 서류", "공급 일정", "신청 일정");
    private static final Set<String> HOSTS = Set.of("apply.lh.or.kr", "www.lh.or.kr");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_SOURCES = 51;
    private static final int MAX_CONTEXT_PARTS = 200;
    private static final int MAX_CONTEXT_CHARS = 1_000_000;

    public static Result compile(Draft draft, Content original) {
        return compile(draft, original, Set.of(), List.of());
    }

    static Result compile(Draft draft, Content original, Set<String> superseded, List<LocatedRule> replacements) {
        var issues = new LinkedHashSet<String>();
        var rules = new LinkedHashMap<String, LocatedRule>();
        var allRules = new ArrayList<LocatedRule>();
        if (draft == null || original == null || !"READY".equals(draft.status()) || draft.checkedAt() == null
                || draft.sources() == null || draft.sources().isEmpty()) return hold("SOURCE_NOT_COMPLETE");
        if (draft.sources().size() > MAX_SOURCES) return hold("SOURCE_LIMIT_EXCEEDED");
        if (draft.sources().stream().anyMatch(org.jibro.collection.extraction.OcrProvenance::contains))
            return hold(org.jibro.collection.extraction.OcrProvenance.REVIEW_REQUIRED);
        String checkedAt = draft.checkedAt().atZone(SEOUL).toLocalDate().toString();
        if (!validEvidence(original.evidence())) return hold("OFFICIAL_EVIDENCE_REQUIRED");
        var sourceUrls = new HashSet<String>();
        for (var source : draft.sources()) if (source != null) sourceUrls.add(EvidenceLocationUrl.base(source.url()));
        if (!sourceUrls.contains(EvidenceLocationUrl.base(original.evidence().url())) || original.attachments() == null
                || original.attachments().stream().anyMatch(a -> a == null || !sourceUrls.contains(EvidenceLocationUrl.base(a.url()))))
            issues.add("SOURCE_COVERAGE_INCOMPLETE");
        var attachments = new LinkedHashMap<String, Evidence>();
        for (var source : draft.sources()) {
            if (source == null || !"EXTRACTED".equals(source.status()) || source.sha256() == null
                    || !source.sha256().matches("[a-fA-F0-9]{64}") || source.context() == null || source.context().isEmpty()) {
                issues.add("SOURCE_CONTEXT_INCOMPLETE");
                continue;
            }
            if (!official(source.url())) {
                issues.add("UNTRUSTED_SOURCE");
                continue;
            }
            if (source.label() == null || source.label().isBlank()) {
                issues.add("SOURCE_LOCATION_MISSING");
                continue;
            }
            if (source.context().size() > MAX_CONTEXT_PARTS || source.context().stream()
                    .filter(Objects::nonNull).filter(part -> part.text() != null)
                    .mapToLong(part -> part.text().length()).sum() > MAX_CONTEXT_CHARS) {
                issues.add("SOURCE_LIMIT_EXCEEDED");
                continue;
            }
            boolean statementFound = false;
            for (Part part : source.context()) {
                if (part == null || part.text() == null || part.location() == null || part.location().isBlank()) {
                    issues.add("SOURCE_LOCATION_MISSING");
                    continue;
                }
                // A blank text layer may conceal a scanned page containing conditions or exceptions.
                // Other successfully extracted pages cannot establish completeness for that page.
                if (part.text().replaceAll("[\\s\\p{Zs}]+", "").isEmpty()) {
                    issues.add("SOURCE_CONTEXT_INCOMPLETE");
                    continue;
                }
                for (String raw : part.text().split("[\\r\\n]+")) {
                    var evidence = new Evidence(source.label() + " · " + part.location(), EvidenceLocationUrl.forSource(source, part.location(), raw), checkedAt);
                    if (!validEvidence(evidence)) {
                        issues.add("SOURCE_LOCATION_INVALID");
                        continue;
                    }
                    String line = raw.replaceAll("[\\p{Zs}\\t]+", " ").trim();
                    if (line.isBlank() || HEADINGS.contains(line) || OfficialGuidanceRows.header(line)) continue;
                    if (line.length() > 1200 || line.contains("[발췌 길이 제한]")) {
                        issues.add("SOURCE_TRUNCATED");
                        continue;
                    }
                    var rule = GuidanceRuleGrammar.parse(line);
                    if (rule == null) {
                        issues.add("UNSUPPORTED_CLAUSE");
                        continue;
                    }
                    statementFound = true;
                    String key = rule.kind() + ":" + rule.key();
                    var located = new LocatedRule(rule, evidence, line);
                    allRules.add(located);
                    rules.putIfAbsent(key, located);
                }
            }
            if (!statementFound) issues.add("SOURCE_RULES_MISSING");
            if (!source.url().equals(original.evidence().url())) {
                var evidence = new Evidence(source.label(), source.url(), checkedAt);
                if (validEvidence(evidence)) attachments.put(source.url(), evidence);
                else issues.add("SOURCE_LOCATION_INVALID");
            }
        }
        if (rules.values().stream().noneMatch(r -> r.rule().kind().equals("CONDITION")))
            issues.add("CONDITIONS_MISSING");
        if (rules.values().stream().noneMatch(r -> r.rule().kind().equals("DOCUMENT") || r.rule().kind().equals("DOCUMENT_ITEM")))
            issues.add("DOCUMENTS_MISSING");
        if (!rules.containsKey("SCHEDULE:application")) issues.add("APPLICATION_PERIOD_MISSING");
        if (attachments.size() > 50) issues.add("SOURCE_LIMIT_EXCEEDED");
        if (!issues.isEmpty()) return new Result(false, List.copyOf(issues), null);
        var effective = new ArrayList<>(allRules.stream().filter(rule -> !superseded.contains(rule.statement())).toList());
        effective.addAll(replacements);
        return ScopedRuleGuidance.compile(effective, original, List.copyOf(attachments.values()));
    }

    private static Result hold(String issue) {
        return new Result(false, List.of(issue), null);
    }

    private static boolean validEvidence(Evidence evidence) {
        try {
            EvidenceValidation.validate(evidence);
            return official(evidence.url());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean official(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && HOSTS.contains(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawUserInfo() == null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
