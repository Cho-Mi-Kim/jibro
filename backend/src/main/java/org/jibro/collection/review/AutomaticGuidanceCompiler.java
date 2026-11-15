package org.jibro.collection.review;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.EvidenceValidation;
import org.jibro.notice.HousingModels.*;

/** Compiles only fully covered, evidence-bound rules; no keyword or model-confidence publication. */
public final class AutomaticGuidanceCompiler {
    private AutomaticGuidanceCompiler() {}

    public record Result(boolean ready, List<String> issues, Content content) {}
    record LocatedRule(GuidanceRuleGrammar.Rule rule, Evidence evidence, String statement) {}
    private static final Set<String> HEADINGS = Set.of("신청 자격", "신청자격", "제출서류", "제출 서류", "공급 일정", "신청 일정");
    private static final Set<String> HOSTS = Set.of("apply.lh.or.kr", "www.lh.or.kr");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_SOURCES = 51;
    private static final int MAX_CONTEXT_PARTS = 200;
    private static final int MAX_CONTEXT_CHARS = 1_000_000;

    public static Result compile(Draft draft, Content original) {
        return compile(draft, original, false);
    }

    public static Result compileScoped(Draft draft, Content original) {
        return compile(draft, original, true);
    }

    static Result compileScoped(Draft draft, Content original, Set<String> superseded, List<LocatedRule> replacements) {
        return compile(draft, original, true, superseded, replacements);
    }

    private static Result compile(Draft draft, Content original, boolean scoped) {
        return compile(draft, original, scoped, Set.of(), List.of());
    }

    private static Result compile(Draft draft, Content original, boolean scoped, Set<String> superseded, List<LocatedRule> replacements) {
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
        for (var source : draft.sources()) if (source != null) sourceUrls.add(source.url());
        if (!sourceUrls.contains(original.evidence().url()) || original.attachments() == null
                || original.attachments().stream().anyMatch(a -> a == null || !sourceUrls.contains(a.url())))
            issues.add("SOURCE_COVERAGE_INCOMPLETE");
        var attachments = new LinkedHashMap<String, Evidence>();
        for (var source : draft.sources()) {
            if (source == null || !"EXTRACTED".equals(source.status()) || source.sha256() == null
                    || !source.sha256().matches("[a-fA-F0-9]{64}") || source.context() == null || source.context().isEmpty()) {
                issues.add("SOURCE_CONTEXT_INCOMPLETE");
                continue;
            }
            if (!official(source.url())) { issues.add("UNTRUSTED_SOURCE"); continue; }
            if (source.label() == null || source.label().isBlank()) { issues.add("SOURCE_LOCATION_MISSING"); continue; }
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
                    if (!validEvidence(evidence)) { issues.add("SOURCE_LOCATION_INVALID"); continue; }
                    String line = raw.replaceAll("[\\p{Zs}\\t]+", " ").trim();
                    if (line.isBlank() || HEADINGS.contains(line)) continue;
                    if (line.length() > 1200 || line.contains("[발췌 길이 제한]")) { issues.add("SOURCE_TRUNCATED"); continue; }
                    var rule = GuidanceRuleGrammar.parse(line);
                    if (rule == null) { issues.add("UNSUPPORTED_CLAUSE"); continue; }
                    statementFound = true;
                    String key = rule.kind() + ":" + rule.key();
                    var located = new LocatedRule(rule, evidence, line);
                    allRules.add(located);
                    var previous = rules.putIfAbsent(key, located);
                    if (!scoped && previous != null && !previous.rule().value().equals(rule.value())) issues.add("CONFLICTING_RULE");
                }
            }
            if (!statementFound) issues.add("SOURCE_RULES_MISSING");
            if (!source.url().equals(original.evidence().url())) {
                var evidence = new Evidence(source.label(), source.url(), checkedAt);
                if (validEvidence(evidence)) attachments.put(source.url(), evidence);
                else issues.add("SOURCE_LOCATION_INVALID");
            }
        }
        if (rules.values().stream().noneMatch(r -> r.rule().kind().equals("CONDITION"))) issues.add("CONDITIONS_MISSING");
        if (rules.values().stream().noneMatch(r -> r.rule().kind().equals("DOCUMENT"))) issues.add("DOCUMENTS_MISSING");
        if (!rules.containsKey("SCHEDULE:application")) issues.add("APPLICATION_PERIOD_MISSING");
        if (!scoped) validateEventOrder(rules, issues);
        if (attachments.size() > 50) issues.add("SOURCE_LIMIT_EXCEEDED");
        if (!issues.isEmpty()) return new Result(false, List.copyOf(issues), null);
        if (scoped) {
            var effective = new ArrayList<>(allRules.stream().filter(rule -> !superseded.contains(rule.statement())).toList());
            effective.addAll(replacements);
            return ScopedRuleGuidance.compile(effective, original, List.copyOf(attachments.values()));
        }

        var questions = new ArrayList<Question>();
        var documents = new LinkedHashMap<String, DocumentTemplate>();
        var schedules = new ArrayList<ScheduleTemplate>();
        for (LocatedRule located : rules.values()) {
            var rule = located.rule();
            if (rule.kind().equals("CONDITION")) {
                questions.add(new Question("auto-" + rule.key(), rule.title(), located.statement(), located.evidence(), conditionOptions()));
            } else if (rule.kind().equals("DOCUMENT")) {
                String questionId = rule.key().equals("common") ? null : "auto-document-" + rule.key();
                if (questionId != null) questions.add(new Question(questionId, rule.title() + "에 해당하나요?", located.statement(), located.evidence(), identityOptions()));
                for (String name : rule.items()) {
                    String documentId = "auto-doc-" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
                    var document = new DocumentTemplate(documentId, name, questionId,
                            located.statement(), "공식 공고의 발급 안내 확인", null, located.evidence(), questionId == null ? null : List.of("YES"));
                    var previous = documents.putIfAbsent(documentId, document);
                    if (previous != null && !Objects.equals(previous.conditionQuestionId(), questionId)) issues.add("DOCUMENT_APPLICABILITY_CONFLICT");
                }
            } else if (rule.key().equals("application")) {
                schedules.add(new ScheduleTemplate("auto-application-start", "신청 접수 시작", rule.items().get(0), located.evidence()));
                schedules.add(new ScheduleTemplate("auto-application-end", "신청 접수 마감", rule.items().get(1), located.evidence()));
            } else {
                schedules.add(new ScheduleTemplate("auto-" + rule.key(), rule.title(), rule.value(), located.evidence()));
            }
        }
        if (!issues.isEmpty()) return new Result(false, List.copyOf(issues), null);
        var content = new Content(original.summary(), original.evidence(), List.copyOf(attachments.values()),
                List.copyOf(questions), List.copyOf(documents.values()), List.copyOf(schedules));
        return new Result(true, List.of(), content);
    }

    private static void validateEventOrder(Map<String, LocatedRule> rules, Set<String> issues) {
        var application = rules.get("SCHEDULE:application");
        if (application == null) return;
        LocalDate end = LocalDate.parse(application.rule().items().get(1));
        for (String key : List.of("SCHEDULE:announcement", "SCHEDULE:documents-due")) {
            var event = rules.get(key);
            if (event != null && LocalDate.parse(event.rule().value()).isBefore(end)) issues.add("SCHEDULE_ORDER_CONFLICT");
        }
        var announcement = rules.get("SCHEDULE:announcement");
        var documents = rules.get("SCHEDULE:documents-due");
        if (announcement != null && documents != null
                && LocalDate.parse(documents.rule().value()).isBefore(LocalDate.parse(announcement.rule().value())))
            issues.add("SCHEDULE_ORDER_CONFLICT");
    }

    private static List<AnswerOption> conditionOptions() {
        return List.of(new AnswerOption("YES", "해당해요"), new AnswerOption("NO", "해당하지 않아요", true), new AnswerOption("UNKNOWN", "잘 모르겠어요", true));
    }

    private static List<AnswerOption> identityOptions() {
        return List.of(new AnswerOption("YES", "해당해요"), new AnswerOption("NO", "해당하지 않아요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true));
    }

    private static Result hold(String issue) { return new Result(false, List.of(issue), null); }

    private static boolean validEvidence(Evidence evidence) {
        try { EvidenceValidation.validate(evidence); return official(evidence.url()); }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean official(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && HOSTS.contains(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawUserInfo() == null;
        } catch (RuntimeException invalid) { return false; }
    }
}
