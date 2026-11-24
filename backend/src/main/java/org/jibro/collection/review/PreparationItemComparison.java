package org.jibro.collection.review;

import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.Draft;
import org.jibro.notice.FollowupReviewRepository.PriorGuide;
import org.jibro.notice.HousingModels.*;
import org.jibro.notice.source.NoticeSourceInformationModels.Area;
import org.jibro.notice.source.NoticeSourceInformationModels.Section;

/**
 * Independent readings and the disposition of each field in the previous public preparation guide.
 */
public final class PreparationItemComparison {
    public record Check(String noticeId, String id, Area area, String title, String state, Evidence latestEvidence) {
    }

    public record Coverage(int conditions, int documents, int confirmedConditions, int confirmedDocuments,
                           List<Check> comparisons) {
    }

    private final Set<String> conditions = new HashSet<>(), documents = new HashSet<>();
    private final List<Check> checks = new ArrayList<>();
    private final Map<String, Section> confirmed = new LinkedHashMap<>();
    private final List<Section> candidates = new ArrayList<>();
    private final Map<UUID, Set<String>> usedByPrevious = new HashMap<>();
    private final Set<String> disputed = new HashSet<>();
    private final Content current;
    private boolean incomplete;
    private boolean restored;

    public PreparationItemComparison(Draft draft, Content current, List<Section> paired) {
        this.current = current;
        readFields(draft);
        for (var section : paired) {
            register(section.area(), section.title());
            confirmed.put(section.id(), section);
        }
        if (current != null) {
            for (var q : current.questions())
                if (!selector(q))
                    candidate(new Section("current-q-" + q.id(), Area.CONDITION, q.title(), q.reason(), q.evidence()), q.reviewRequired());
            for (var doc : current.documents())
                candidate(new Section("current-d-" + doc.id(), Area.DOCUMENT, doc.title(), documentText(doc, current), doc.evidence()),
                    org.jibro.notice.GuidanceIssues.documentRequiresReview(current.reviewIssues(), doc.id(), doc.conditionQuestionId()));
        }
    }

    private void readFields(Draft draft) {
        if (draft == null || draft.sources() == null || draft.sources().size() > 51) return;
        for (var source : draft.sources()) {
            if (source == null || !"EXTRACTED".equals(source.status()) || source.url() == null
                || !source.url().matches("https://(?:apply|www)\\.lh\\.or\\.kr/[^\\s]+") || source.sha256() == null || !source.sha256().matches("[0-9a-f]{64}")
                || source.context() == null || source.context().size() > 200 || org.jibro.collection.extraction.OcrProvenance.contains(source)
                || source.context().stream().anyMatch(part -> part == null || part.location() == null || part.text() == null || part.text().contains("[발췌 길이 제한]"))
                || source.context().stream().mapToLong(part -> part.text().length()).sum() > 1_000_000) continue;
            var s = OfficialGuidanceRows.read(source);
            for (var part : s.context())
                for (String line : part.text().lines().toList()) {
                    var rule = OfficialGuidanceItem.parse(line.strip());
                    if (rule != null && !OfficialGuidanceItem.needsAgency(rule))
                        register(rule.kind().equals("DOCUMENT_ITEM") ? Area.DOCUMENT : Area.CONDITION, rule.title());
                }
        }
    }

    private void candidate(Section field, boolean unresolved) {
        if (unresolved) {
            disputed.add(field.area() + "/" + normalize(field.title()));
            confirmed.values().removeIf(s -> s.area() == field.area() && normalize(s.title()).equals(normalize(field.title())));
        } else {
            candidates.add(field);
            register(field.area(), field.title());
        }
    }

    private static boolean selector(Question q) {
        return q.id().matches("s\\d+-docs-\\d+|recruit-site");
    }

    private static String documentText(DocumentTemplate doc, Content guide) {
        String condition = "";
        if (doc.conditionQuestionId() != null) {
            var question = guide.questions().stream().filter(q -> q.id().equals(doc.conditionQuestionId())).findFirst();
            if (question.isPresent()) {
                var q = question.get();
                var answers = doc.conditionAnswerValues() == null ? List.<String>of() : doc.conditionAnswerValues();
                String labels = q.answerOptions().stream().filter(o -> answers.contains(o.value())).map(AnswerOption::label).collect(java.util.stream.Collectors.joining(" / "));
                condition = q.title() + " · " + (labels.isBlank() ? q.reason() : labels) + "\n";
            }
        }
        return condition + doc.method() + "\n발급처 · " + doc.issuer();
    }

    public void compare(PriorGuide previous, Optional<VerifiedReissueFiles> files) {
        Content guide = previous.guide();
        for (var section : previous.sections()) {
            if (section.area() == Area.CONDITION || section.area() == Area.DOCUMENT)
                item(previous, section.area(), section.id(), section.title(), section.text(), section.evidence(), files);
        }
        for (var q : guide.questions()) {
            if (selector(q)) continue;
            item(previous, Area.CONDITION, q.id(), q.title(), q.reason(), q.evidence(), q.reviewRequired() ? Optional.empty() : files);
        }
        for (var d : guide.documents()) {
            boolean issue = org.jibro.notice.GuidanceIssues.documentRequiresReview(guide.reviewIssues(), d.id(), d.conditionQuestionId());
            item(previous, Area.DOCUMENT, d.id(), d.title(), documentText(d, guide), d.evidence(), issue ? Optional.empty() : files);
        }
    }

    public void recordRestored(PriorGuide previous, Content latest) {
        for (var q : latest.questions())
            if (!selector(q))
                checks.add(new Check(previous.noticeId().toString(), q.id(), Area.CONDITION, q.title(), "RECONFIRMED", q.evidence()));
        for (var doc : latest.documents())
            checks.add(new Check(previous.noticeId().toString(), doc.id(), Area.DOCUMENT, doc.title(), "RECONFIRMED", doc.evidence()));
    }

    private void item(PriorGuide prior, Area area, String id, String title, String text, Evidence evidence, Optional<VerifiedReissueFiles> files) {
        String key = normalize(title);
        var present = candidates.stream().filter(s -> s.area() == area && normalize(s.title()).equals(key)).toList();
        var paired = confirmed.values().stream().filter(s -> s.id().startsWith("paired-") && s.area() == area && normalize(s.title()).equals(key)).toList();
        String state;
        Evidence latest = null;
        var used = usedByPrevious.computeIfAbsent(prior.noticeId(), ignored -> new HashSet<>());
        var original = area == Area.CONDITION ? prior.guide().questions().stream().filter(q -> normalize(q.title()).equals(key)).count() :
            prior.guide().documents().stream().filter(d -> normalize(d.title()).equals(key)).count();
        original += prior.sections().stream().filter(s -> s.area() == area && normalize(s.title()).equals(key)).count();
        String stableId = (area == Area.CONDITION ? "current-q-" : "current-d-") + id;
        var stable = present.stream().filter(s -> s.id().equals(stableId)).findFirst();
        var match = stable.isPresent() ? stable : original == 1 && present.size() == 1 ? Optional.of(present.get(0)) : Optional.<Section>empty();
        if (disputed.contains(area + "/" + key)) {
            state = "RECHECK";
            incomplete = true;
        } else if (match.isPresent() && used.add(match.get().id())) {
            latest = match.get().evidence();
            state = normalize(match.get().text()).equals(normalize(text)) && sameDefinition(prior.guide(), area, id, match.get()) ? "CURRENT" : "UPDATED";
        } else if (original == 1 && paired.size() == 1) {
            latest = paired.get(0).evidence();
            var rule = area == Area.DOCUMENT ? OfficialGuidanceItem.parse(paired.get(0).title() + " 제출 대상: " + paired.get(0).text()) : null;
            state = rule != null && OfficialGuidanceItem.exempt(rule) ? "NO_LONGER_REQUIRED" : "CONFIRMED_IN_LATEST";
            restored = true;
        } else {
            Evidence fresh = present.isEmpty() && paired.isEmpty() ? files.map(proof -> proof.current(evidence)).orElse(null) : null;
            if (fresh == null) {
                state = "RECHECK";
                incomplete = true;
            } else {
                String itemId = "reissue-" + UUID.nameUUIDFromBytes((prior.noticeId() + "/" + area + "/" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                confirmed.put(itemId, new Section(itemId, area, title, text, fresh));
                register(area, title);
                latest = fresh;
                state = "RECONFIRMED";
                restored = true;
            }
        }
        checks.add(new Check(prior.noticeId().toString(), id, area, title, state, latest));
    }

    private boolean sameDefinition(Content previous, Area area, String id, Section matched) {
        if (area == Area.CONDITION) {
            var old = previous.questions().stream().filter(q -> q.id().equals(id)).findFirst();
            var latest = current.questions().stream().filter(q -> matched.id().equals("current-q-" + q.id())).findFirst();
            return old.isEmpty() || latest.filter(q -> old.get().inputKind().equals(q.inputKind())
                && Objects.equals(old.get().constraints(), q.constraints()) && old.get().answerOptions().equals(q.answerOptions())).isPresent();
        }
        var old = previous.documents().stream().filter(d -> d.id().equals(id)).findFirst();
        var latest = current.documents().stream().filter(d -> matched.id().equals("current-d-" + d.id())).findFirst();
        return old.isEmpty() || latest.filter(d -> Objects.equals(old.get().issueUrl(), d.issueUrl()) && Objects.equals(old.get().issueLabel(), d.issueLabel())
            && Objects.equals(old.get().conditionQuestionId(), d.conditionQuestionId()) && Objects.equals(old.get().conditionAnswerValues(), d.conditionAnswerValues())).isPresent();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Zs}]+", "").strip();
    }

    private void register(Area area, String title) {
        if (area == Area.CONDITION) conditions.add(normalize(title));
        if (area == Area.DOCUMENT) documents.add(normalize(title));
    }

    public Coverage coverage() {
        var verified = java.util.stream.Stream.concat(candidates.stream(), confirmed.values().stream()).toList();
        return new Coverage(conditions.size(), documents.size(), count(verified, Area.CONDITION), count(verified, Area.DOCUMENT), List.copyOf(checks));
    }

    private static int count(List<Section> verified, Area area) {
        return (int) verified.stream().filter(s -> s.area() == area).map(s -> normalize(s.title())).distinct().count();
    }

    public boolean requiresScopedPublication() {
        return incomplete || restored;
    }

    public List<Section> sections(boolean includeCandidate) {
        var result = new ArrayList<>(confirmed.values());
        if (includeCandidate) for (var field : candidates) {
            if (result.stream().noneMatch(s -> s.area() == field.area() && normalize(s.title()).equals(normalize(field.title())) && normalize(s.text()).equals(normalize(field.text()))))
                result.add(field);
        }
        return List.copyOf(result);
    }
}

