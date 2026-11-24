package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;

import java.util.*;

/**
 * Versioned answer relationships, without changing the applicant's supplied answers.
 */
public final class AnswerDependencies {
    private enum Match {YES, NO, UNKNOWN}

    private AnswerDependencies() {
    }

    public static List<String> conflictMessages(Question question, Object answer, Map<String, ?> answers,
                                                List<Question> questions) {
        if (question.reviewRequired() || unknown(answer) || !validAnswer(question, answer)) return List.of();
        var messages = new LinkedHashSet<String>();
        for (var dependency : question.answerDependencies()) {
            if (match(dependency, questions, answers) == Match.YES && selected(answer, dependency.conflictingValues()))
                messages.add(dependency.message());
        }
        return List.copyOf(messages);
    }

    public static List<String> conflictMessages(List<Question> questions, Map<String, ?> answers) {
        var messages = new LinkedHashSet<String>();
        for (var question : questions)
            messages.addAll(conflictMessages(question, answers.get(question.id()), answers, questions));
        return List.copyOf(messages);
    }

    /**
     * A known alternative requirement cannot be cancelled by an unrelated negative answer.
     */
    public static String documentNecessity(String base, String documentId, List<Question> questions, Map<String, ?> answers) {
        var dependencies = new ArrayList<AnswerDependency>();
        for (var question : questions) {
            for (var dependency : question.answerDependencies()) {
                if (!dependency.requiredDocumentIds().contains(documentId)) continue;
                var parent = find(questions, dependency.questionId());
                if (question.reviewRequired() || parent != null && parent.reviewRequired()) return "UNKNOWN";
                dependencies.add(dependency);
            }
        }
        if (base.equals("REQUIRED")) return base;
        boolean uncertain = false;
        for (var dependency : dependencies) {
            Match match = match(dependency, questions, answers);
            if (match == Match.YES) return "CONDITIONAL";
            uncertain |= match == Match.UNKNOWN;
        }
        return base.equals("NOT_APPLICABLE") && uncertain ? "UNKNOWN" : base;
    }

    public static void validate(Content content) {
        var questions = content.questions();
        var documentIds = new HashSet<>(content.documents().stream().map(DocumentTemplate::id).toList());
        for (var question : questions) {
            var applicability = question.applicability();
            if (applicability != null) {
                var parent = find(questions, applicability.questionId());
                if (parent == null || parent.id().equals(question.id()) || parent.applicability() != null
                        || !choiceQuestion(parent) || !choices(applicability.answerValues(), parent, false))
                    throw new IllegalArgumentException("INVALID_QUESTION_APPLICABILITY");
            }
            if (question.answerDependencies().size() > 10) throw invalid();
            var distinct = new HashSet<AnswerDependency>();
            for (var dependency : question.answerDependencies()) {
                if (dependency == null || !distinct.add(dependency) || dependency.questionId() == null
                        || dependency.questionId().equals(question.id()) || !choiceQuestion(question)
                        || dependency.message() == null || dependency.message().isBlank() || dependency.message().length() > 500)
                    throw invalid();
                var parent = find(questions, dependency.questionId());
                if (parent == null || !choiceQuestion(parent)
                        || !choices(dependency.answerValues(), parent, false)
                        || !choices(dependency.conflictingValues(), question, true)
                        || dependency.requiredDocumentIds() == null || dependency.requiredDocumentIds().size() > 100
                        || new HashSet<>(dependency.requiredDocumentIds()).size() != dependency.requiredDocumentIds().size()
                        || !documentIds.containsAll(dependency.requiredDocumentIds())
                        || dependency.requiredDocumentIds().isEmpty() && dependency.conflictingValues().isEmpty())
                    throw invalid();
            }
        }
    }

    private static boolean choices(List<String> values, Question question, boolean emptyAllowed) {
        if (values == null || !emptyAllowed && values.isEmpty() || values.size() > 20
                || new HashSet<>(values).size() != values.size() || values.contains("UNKNOWN")) return false;
        return question.answerOptions().stream().map(AnswerOption::value).toList().containsAll(values);
    }

    private static boolean choiceQuestion(Question question) {
        return Set.of("SINGLE", "MULTIPLE").contains(question.inputKind());
    }

    private static Match match(AnswerDependency dependency, List<Question> questions, Map<String, ?> answers) {
        var parent = find(questions, dependency.questionId());
        Object answer = answers.get(dependency.questionId());
        if (parent == null || parent.reviewRequired() || unknown(answer) || !validAnswer(parent, answer))
            return Match.UNKNOWN;
        return selected(answer, dependency.answerValues()) ? Match.YES : Match.NO;
    }

    private static Question find(List<Question> questions, String id) {
        return questions.stream().filter(question -> question.id().equals(id)).findFirst().orElse(null);
    }

    private static boolean unknown(Object answer) {
        return answer == null || answer.equals("UNKNOWN");
    }

    private static boolean selected(Object answer, List<String> values) {
        return answer instanceof List<?> selected ? selected.stream().anyMatch(values::contains) : values.contains(answer);
    }

    private static boolean validAnswer(Question question, Object answer) {
        try {
            QuestionAnswers.normalize(question, answer);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_ANSWER_DEPENDENCY");
    }
}
