package org.jibro.collection.review;

import static org.jibro.notice.HousingModels.*;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;

/**
 * Maps supported official findings to existing guidance fields; it never invents missing documents.
 */
public final class ScopedFindingGuidance {
    private static final String FORM = "FORM_PUBLICATION_DATE_MISMATCH";
    private static final String INCOME = "WAIVED_INCOME_DOCUMENT_SCOPE_UNRESOLVED";
    private static final String SCORING = "FIRST_COME_SCORING_DOCUMENT_SCOPE_UNRESOLVED";
    private static final List<String> INCOME_DOCUMENTS = List.of("임신진단서", "입양관계증명서");
    private static final Pattern BONUS = Pattern.compile("가산점|배점|육아지원세대|사회취약계층");
    private static final Pattern GENERAL = Pattern.compile("일반|공통|기본");

    private ScopedFindingGuidance() {
    }

    public record Result(List<ReviewIssue> reviewIssues, List<String> blockers, List<Question> explanations,
                         List<GuidanceFinding> resolved) {
    }

    private record BoundPart(Source source, Part part) {
    }

    public static Result scope(Draft draft, Content candidate, List<GuidanceFinding> findings) {
        return scope(draft, candidate, findings, true);
    }

    static Result scope(Draft draft, Content candidate, List<GuidanceFinding> findings, boolean primaryConditionsCurrent) {
        var issues = new LinkedHashMap<String, ReviewIssue>();
        var blockers = new LinkedHashSet<String>();
        var explanations = new LinkedHashMap<String, Question>();
        var resolved = new ArrayList<GuidanceFinding>();
        for (GuidanceFinding finding : findings) {
            if (candidate == null || !List.of(FORM, INCOME, SCORING).contains(finding.code())) {
                blockers.add(finding.code());
                continue;
            }
            BoundPart bound = locate(draft, finding.evidence());
            if (bound == null) {
                blockers.add(finding.code());
                continue;
            }
            var explanation = primaryConditionsCurrent
                    ? NationalRentalScopeReview.explainPurpose(draft.sources(), bound.source(), bound.part(), finding) : null;
            if (explanation != null) {
                explanations.putIfAbsent(explanation.id(), explanation);
                resolved.add(finding);
                continue;
            }
            var names = affectedNames(finding.code(), bound.part(), candidate.documents());
            var documents = affectedDocuments(finding.code(), names, candidate.documents());
            // All explicitly implicated document names must be represented; a partial match is not coverage.
            if (names.isEmpty() || names.stream().anyMatch(name -> documents.stream().noneMatch(d -> compact(d.title()).contains(name)))) {
                blockers.add(finding.code());
                continue;
            }
            var questionIds = new LinkedHashSet<String>();
            documents.stream().map(DocumentTemplate::conditionQuestionId).filter(Objects::nonNull).forEach(questionIds::add);
            candidate.questions().stream().filter(question -> samePart(question.evidence(), finding.evidence()))
                    .filter(question -> names.stream().anyMatch(name -> compact(question.title() + " " + question.reason()).contains(name)))
                    .filter(question -> !SCORING.equals(finding.code()) || BONUS.matcher(compact(question.title() + " " + question.reason())).find())
                    .map(Question::id).forEach(questionIds::add);
            var documentIds = new LinkedHashSet<String>();
            documents.stream().map(DocumentTemplate::id).forEach(documentIds::add);
            candidate.documents().stream().filter(document -> questionIds.contains(document.conditionQuestionId()))
                    .map(DocumentTemplate::id).forEach(documentIds::add);
            String id = "auto-scope-" + UUID.nameUUIDFromBytes((finding.code() + "\n" + finding.evidence().url()
                    + "\n" + finding.evidence().label()).getBytes(StandardCharsets.UTF_8));
            String detail = finding.message();
            var previous = issues.get(id);
            if (previous != null && !previous.detail().equals(detail)) detail = previous.detail() + "\n" + detail;
            if (detail == null || detail.isBlank() || detail.length() > 3000) {
                blockers.add(finding.code());
                issues.remove(id);
                continue;
            }
            issues.put(id, new ReviewIssue(id, "DOCUMENT", title(finding.code()), detail,
                    List.copyOf(questionIds), List.copyOf(documentIds), List.of(finding.evidence())));
        }
        return new Result(List.copyOf(issues.values()), List.copyOf(blockers), List.copyOf(explanations.values()), List.copyOf(resolved));
    }

    private static BoundPart locate(Draft draft, Evidence evidence) {
        if (draft == null || draft.checkedAt() == null || draft.sources() == null || evidence == null
                || !draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString().equals(evidence.checkedAt()))
            return null;
        var matches = new ArrayList<BoundPart>();
        for (Source source : draft.sources()) {
            if (!Objects.equals(source.url(), EvidenceLocationUrl.base(evidence.url())) || !"EXTRACTED".equals(source.status())
                    || source.sha256() == null || !source.sha256().matches("[a-f0-9]{64}")) continue;
            for (Part part : source.context()) {
                if (Objects.equals(source.label() + " · " + part.location(), evidence.label())
                        && EvidenceLocationUrl.matchesPart(source, part, evidence.url()))
                    matches.add(new BoundPart(source, part));
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static List<String> affectedNames(String code, Part part, List<DocumentTemplate> documents) {
        String text = compact(part.text());
        if (FORM.equals(code))
            return documents.stream().map(DocumentTemplate::title).map(ScopedFindingGuidance::compact)
                    .filter(name -> name.endsWith("확약서") || name.endsWith("확인서"))
                    .filter(text::contains).distinct().toList();
        if (INCOME.equals(code)) return INCOME_DOCUMENTS.stream().filter(text::contains).toList();
        return text.contains("가족관계증명서") ? List.of("가족관계증명서") : List.of();
    }

    private static List<DocumentTemplate> affectedDocuments(String code, List<String> names, List<DocumentTemplate> documents) {
        var matching = documents.stream().filter(document -> names.stream().anyMatch(name -> compact(document.title()).contains(name))).toList();
        if (!SCORING.equals(code)) return matching;
        var bonus = matching.stream().filter(document -> BONUS.matcher(compact(document.title())).find()).toList();
        // A separate, explicitly labelled general-purpose document is not a scoring document.
        // A generic combined document remains fully unresolved instead of guessing its sub-purpose.
        if (!bonus.isEmpty() && matching.stream().filter(document -> !bonus.contains(document))
                .allMatch(document -> GENERAL.matcher(compact(document.title())).find())) return bonus;
        return matching;
    }

    private static boolean samePart(Evidence first, Evidence second) {
        return first != null && Objects.equals(first.url(), second.url()) && Objects.equals(first.label(), second.label())
                && Objects.equals(first.checkedAt(), second.checkedAt());
    }

    private static String title(String code) {
        return switch (code) {
            case FORM -> "서식의 모집공고일 확인 필요";
            case INCOME -> "소득요건 배제와 제출서류 범위 확인 필요";
            default -> "가산점용 가족관계 서류 확인 필요";
        };
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Zs}]+", "");
    }
}
