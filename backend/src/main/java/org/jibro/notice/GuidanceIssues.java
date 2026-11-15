package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import java.util.*;

/** Scope of unresolved official material, separate from the applicant's own answers. */
public final class GuidanceIssues {
    private GuidanceIssues() {}

    public static List<ReviewIssue> safe(List<ReviewIssue> issues) {
        return issues == null ? List.of() : issues;
    }

    public static Set<String> questionIds(List<ReviewIssue> issues) {
        var result = new HashSet<String>();
        safe(issues).stream().filter(Objects::nonNull).filter(i -> i.questionIds() != null)
            .forEach(i -> result.addAll(i.questionIds()));
        return result;
    }

    public static boolean documentRequiresReview(List<ReviewIssue> issues, String documentId, String questionId) {
        return questionId != null && questionIds(issues).contains(questionId)
            || safe(issues).stream().filter(Objects::nonNull)
                .anyMatch(i -> i.documentIds() != null && i.documentIds().contains(documentId));
    }

    static List<ReviewIssue> withDependentDocuments(List<ReviewIssue> issues, List<DocumentTemplate> documents) {
        return issues.stream().map(issue -> {
            if (issue == null || issue.questionIds() == null || issue.documentIds() == null) return issue;
            var ids = new ArrayList<>(issue.documentIds());
            documents.stream().filter(Objects::nonNull)
                .filter(d -> d.conditionQuestionId() != null && issue.questionIds().contains(d.conditionQuestionId()))
                .forEach(d -> { if (!ids.contains(d.id())) ids.add(d.id()); });
            return new ReviewIssue(issue.id(), issue.area(), issue.title(), issue.detail(), issue.questionIds(),
                Collections.unmodifiableList(new ArrayList<>(ids)), issue.evidence());
        }).toList();
    }

    public static void validate(Content content) {
        var issues = content.reviewIssues();
        if (issues.size() > 30) throw invalid();
        var ids = new HashSet<String>();
        var questions = new HashSet<>(content.questions().stream().map(Question::id).toList());
        var documents = new HashSet<>(content.documents().stream().map(DocumentTemplate::id).toList());
        for (var issue : issues) {
            if (issue == null || !id(issue.id()) || !ids.add(issue.id())
                || issue.area() == null || !Set.of("CONDITION", "DOCUMENT", "SCHEDULE").contains(issue.area())
                || !text(issue.title(), 300) || !text(issue.detail(), 3000)
                || !references(issue.questionIds(), questions) || !references(issue.documentIds(), documents)
                || issue.area().equals("CONDITION") && issue.questionIds().isEmpty()
                || issue.area().equals("DOCUMENT") && issue.documentIds().isEmpty()
                || issue.evidence() == null || issue.evidence().isEmpty() || issue.evidence().size() > 10)
                throw invalid();
            issue.evidence().forEach(EvidenceValidation::validate);
        }
        var affected = questionIds(issues);
        if (content.questions().stream().anyMatch(q -> q.reviewRequired() != affected.contains(q.id())))
            throw invalid();
    }

    private static boolean references(List<String> ids, Set<String> known) {
        return ids != null && ids.size() <= 100 && new HashSet<>(ids).size() == ids.size()
            && ids.stream().allMatch(GuidanceIssues::id) && known.containsAll(ids);
    }

    private static boolean id(String value) {
        return value != null && value.matches("[a-zA-Z0-9_-]{1,64}");
    }

    private static boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_SCOPED_GUIDANCE_ISSUE");
    }
}
