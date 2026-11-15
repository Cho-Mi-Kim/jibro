package org.jibro.collection.review;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jibro.notice.HousingModels.DocumentTemplate;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.Question;
import org.jibro.notice.HousingModels.ReviewIssue;

/** Maps conflicting explicit submission lists to their documents without choosing a preferred source. */
final class ScopedDocumentConflicts {
    private record Claim(String scope, List<String> names, String statement, Evidence evidence) {}
    record Result(List<ReviewIssue> reviewIssues, List<String> blockers) {}

    private final Map<String, List<Claim>> groups = new LinkedHashMap<>();

    void add(GuidanceRuleGrammar.Rule rule, String statement, Evidence evidence) {
        if (rule == null || !rule.kind().equals("DOCUMENT")) return;
        groups.computeIfAbsent(rule.key(), ignored -> new ArrayList<>())
            .add(new Claim(rule.key(), rule.items(), statement, evidence));
    }

    /** Only real list differences may explain different governing source text. */
    Set<String> conflictingKeys() {
        return groups.entrySet().stream()
            .filter(entry -> entry.getValue().stream().map(claim -> Set.copyOf(claim.names())).distinct().count() > 1)
            .map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    boolean detected() {
        if (!conflictingKeys().isEmpty()) return true;
        var scopesByName = new LinkedHashMap<String, Set<String>>();
        groups.values().forEach(claims -> claims.forEach(claim -> claim.names().forEach(name ->
            scopesByName.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).add(claim.scope()))));
        return scopesByName.values().stream().anyMatch(scopes -> scopes.size() > 1);
    }

    Result bind(List<Question> questions, List<DocumentTemplate> documents) {
        var byName = new LinkedHashMap<String, List<Claim>>();
        groups.values().forEach(claims -> claims.forEach(claim -> claim.names().forEach(name ->
            byName.computeIfAbsent(name, ignored -> new ArrayList<>()).add(claim))));
        List<ReviewIssue> issues = new ArrayList<>();
        Set<String> blockers = new LinkedHashSet<>();
        Set<String> knownQuestions = questions.stream().map(Question::id).collect(Collectors.toSet());
        for (var entry : byName.entrySet()) {
            String name = entry.getKey();
            var scopes = entry.getValue().stream().map(Claim::scope).distinct().toList();
            var related = scopes.stream().flatMap(scope -> groups.get(scope).stream()).distinct().toList();
            boolean conflict = scopes.size() > 1 || related.stream().anyMatch(claim -> !claim.names().contains(name));
            if (!conflict) continue;
            var matching = documents.stream().filter(document -> compact(document.title()).equals(compact(name))).toList();
            // Do not invent a document, substitute a similar title, or leave an unbound issue behind.
            if (matching.size() != 1 || matching.get(0).conditionQuestionId() != null
                && !knownQuestions.contains(matching.get(0).conditionQuestionId())) {
                blockers.add("EVIDENCE_DOCUMENT_CONFLICT_SCOPE_UNMAPPED");
                continue;
            }
            var document = matching.get(0);
            Set<String> questionIds = new LinkedHashSet<>();
            if (document.conditionQuestionId() != null) questionIds.add(document.conditionQuestionId());
            questions.stream().filter(question -> question.id().startsWith("e-section-")
                    && related.stream().anyMatch(claim -> compact(question.reason()).contains(compact(claim.statement()))))
                .map(Question::id).forEach(questionIds::add);
            String detail = "공식 자료의 제출 목록 또는 대상이 서로 다릅니다. 이 서류의 제출 여부는 어느 한쪽으로 확정하지 않으며 기관 확인이 필요합니다.\n"
                + related.stream().map(Claim::statement).distinct().collect(Collectors.joining("\n"));
            var evidence = related.stream().map(Claim::evidence).distinct().toList();
            if (questionIds.size() > 100 || detail.length() > 3000 || evidence.isEmpty() || evidence.size() > 10) {
                blockers.add("EVIDENCE_DOCUMENT_CONFLICT_LIMIT");
                continue;
            }
            issues.add(new ReviewIssue("e-document-conflict-" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),
                "DOCUMENT", name + " 제출 범위 확인 필요", detail, List.copyOf(questionIds), List.of(document.id()), evidence));
        }
        if (issues.size() > 30) blockers.add("EVIDENCE_DOCUMENT_CONFLICT_LIMIT");
        return new Result(List.copyOf(issues), List.copyOf(blockers));
    }

    private static String compact(String text) { return text.replaceAll("[\\s\\p{Zs}]+", ""); }
}
