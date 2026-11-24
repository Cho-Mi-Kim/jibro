package org.jibro.collection.review;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.jibro.notice.HousingModels.*;
import static org.jibro.collection.review.AutomaticGuidanceCompiler.*;

/**
 * Exact grammar claims keep their scope and provenance; conflicting values never become an answerable rule.
 */
final class ScopedRuleGuidance {
    private ScopedRuleGuidance() {
    }

    static Result compile(List<LocatedRule> rules, Content original, List<Evidence> attachments) {
        Map<String, List<LocatedRule>> groups = rules.stream().collect(Collectors.groupingBy(
                r -> r.rule().kind() + ":" + r.rule().key(), LinkedHashMap::new, Collectors.toList()));
        var questions = new LinkedHashMap<String, Question>();
        var documents = new ArrayList<DocumentTemplate>();
        var reviewIssues = new ArrayList<ReviewIssue>();
        var documentGroups = new LinkedHashMap<String, List<LocatedRule>>();
        for (var group : groups.values()) {
            var first = group.get(0);
            var rule = first.rule();
            if (rule.kind().equals("CONDITION")) {
                String id = "auto-" + rule.key();
                boolean conflict = group.stream().map(r -> r.rule().value()).distinct().count() > 1;
                String title = conflict ? (rule.key().startsWith("field-") ? rule.title() : conditionName(rule.key())) + " 확인 필요" : rule.title();
                questions.put(id, new Question(id, title, conflict ? "공식 자료의 기준이 서로 달라 기관 확인이 필요합니다." : rule.conditionSummary(first.statement()),
                        first.evidence(), List.of(new AnswerOption("YES", "해당해요"), new AnswerOption("NO", "해당하지 않아요", true), new AnswerOption("UNKNOWN", "잘 모르겠어요", true))));
                if (!conflict && OfficialGuidanceItem.needsAgency(rule))
                    reviewIssues.add(agency(first, List.of(id), List.of()));
                if (conflict)
                    reviewIssues.add(issue("auto-conflict-" + rule.key(), "CONDITION", title, List.of(id), List.of(), group));
            } else if (rule.kind().equals("DOCUMENT_ITEM")) {
                String id = "doc-" + rule.key();
                String qid = "applies-" + rule.key();
                boolean conflict = group.stream().map(r -> r.rule().value()).distinct().count() > 1;
                var related = new ArrayList<>(group);
                var listed = rules.stream().filter(r -> r.rule().kind().equals("DOCUMENT") && r.rule().items().stream()
                        .anyMatch(name -> name.replace(" ", "").equals(rule.title().replace(" ", "")))).toList();
                if (!listed.isEmpty()) {
                    boolean sameAudience = listed.stream().allMatch(r -> r.rule().key().equals("common"))
                            && group.stream().allMatch(r -> OfficialGuidanceItem.commonRecipient(r.rule()));
                    if (group.stream().anyMatch(r -> OfficialGuidanceItem.exempt(r.rule()))) {
                        conflict = true;
                        related.addAll(listed);
                    } else if (!sameAudience) return new Result(false, List.of("DOCUMENT_ITEM_SCOPE_UNVERIFIED"), null);
                }
                if (!conflict && OfficialGuidanceItem.exempt(rule)) {
                    questions.put(qid, new Question(qid, rule.title() + " 제출 면제", first.statement(), first.evidence()));
                    continue;
                }
                String detail = conflict ? "공식 자료의 제출 대상이 달라 기관 확인이 필요합니다." : rule.value();
                boolean common = !conflict && OfficialGuidanceItem.commonRecipient(rule);
                if (!common)
                    questions.put(qid, new Question(qid, rule.title() + " 제출 대상에 해당하나요?", detail, first.evidence(),
                            List.of(new AnswerOption("YES", "제출 대상이에요"), new AnswerOption("NO", "해당하지 않아요"),
                                    new AnswerOption("UNKNOWN", "잘 모르겠어요", true))));
                String issuer = "공식 공고의 발급 안내 확인";
                int issuerField = detail.lastIndexOf(" · 발급처: ");
                if (issuerField >= 0) {
                    String value = detail.substring(issuerField + " · 발급처: ".length()).strip();
                    if (!value.isEmpty() && value.length() <= 100) issuer = value;
                }
                documents.add(new DocumentTemplate(id, rule.title(), common ? null : qid, detail, issuer,
                        null, first.evidence(), common ? null : List.of("YES")));
                if (!conflict && OfficialGuidanceItem.needsAgency(rule))
                    reviewIssues.add(agency(first, List.of(qid), List.of(id)));
                if (conflict)
                    reviewIssues.add(issue("conflict-" + rule.key(), "DOCUMENT", rule.title() + " 제출 대상 확인 필요",
                            List.of(qid), List.of(id), related));
            } else if (rule.kind().equals("DOCUMENT")) {
                if (!rule.key().equals("common")) {
                    String id = "auto-document-" + rule.key();
                    questions.put(id, new Question(id, rule.title() + "에 해당하나요?", "신분에 따른 제출 대상은 공식 서류 안내와 함께 확인합니다.",
                            first.evidence(), List.of(new AnswerOption("YES", "해당해요"), new AnswerOption("NO", "해당하지 않아요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true))));
                }
                for (var located : group)
                    for (String name : located.rule().items())
                        documentGroups.computeIfAbsent(name, ignored -> new ArrayList<>()).add(located);
            }
        }
        for (var entry : documentGroups.entrySet()) {
            String name = entry.getKey();
            var item = rules.stream().filter(r -> r.rule().kind().equals("DOCUMENT_ITEM")
                    && r.rule().title().replace(" ", "").equals(name.replace(" ", ""))).findFirst();
            if (item.isPresent()) continue;
            var claims = entry.getValue();
            var first = claims.get(0);
            String id = "auto-doc-" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            var scopes = claims.stream().map(c -> c.rule().key()).distinct().toList();
            var related = new ArrayList<LocatedRule>();
            boolean conflict = scopes.size() > 1;
            for (String scope : scopes) {
                var variants = groups.get("DOCUMENT:" + scope);
                related.addAll(variants);
                if (variants.stream().anyMatch(c -> !c.rule().items().contains(name))) conflict = true;
            }
            String qid = first.rule().key().equals("common") ? null : "auto-document-" + first.rule().key();
            documents.add(new DocumentTemplate(id, name, qid, conflict ? "공식 자료의 제출 목록·대상이 달라 기관 확인이 필요합니다." : first.statement(),
                    "공식 공고의 발급 안내 확인", null, first.evidence(), qid == null ? null : List.of("YES")));
            if (conflict)
                reviewIssues.add(issue("auto-doc-conflict-" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),
                        "DOCUMENT", name + " 제출 범위 확인 필요", List.of(), List.of(id), related));
        }
        if (reviewIssues.size() > 30 || reviewIssues.stream().anyMatch(i -> i.detail().length() > 3000 || i.evidence().size() > 10))
            return new Result(false, List.of("SCOPED_CONFLICT_LIMIT"), null);
        // The independent schedule compiler owns every date and time conflict, including application boundaries.
        return new Result(true, List.of(), new Content(original.summary(), original.evidence(), attachments,
                List.copyOf(questions.values()), documents, List.of(), reviewIssues));
    }

    private static ReviewIssue agency(LocatedRule claim, List<String> questions, List<String> documents) {
        var rule = claim.rule();
        return new ReviewIssue("agency-" + rule.key(), rule.kind().equals("CONDITION") ? "CONDITION" : "DOCUMENT",
                rule.title() + " 기관 확인 필요", claim.statement(), questions, documents, List.of(claim.evidence()));
    }

    private static ReviewIssue issue(String id, String area, String title, List<String> qids, List<String> dids, List<LocatedRule> claims) {
        String detail = "공식 자료의 아래 안내가 서로 다릅니다. 어느 한쪽을 확정하지 않으며 기관 확인 전에는 이 항목을 준비 완료로 계산하지 않습니다.\n"
                + claims.stream().map(LocatedRule::statement).distinct().collect(Collectors.joining("\n"));
        return new ReviewIssue(id, area, title, detail, qids, dids, claims.stream().map(LocatedRule::evidence).distinct().toList());
    }

    private static String conditionName(String key) {
        return switch (key) {
            case "age" -> "연령 기준";
            case "income" -> "소득 기준";
            case "assets" -> "자산 기준";
            case "vehicle" -> "자동차 기준";
            case "housing" -> "무주택 기준";
            case "marriage" -> "혼인 기준";
            default -> "신청 기준";
        };
    }
}
