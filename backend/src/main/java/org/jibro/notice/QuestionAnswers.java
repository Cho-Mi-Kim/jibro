package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Validates input shape, never decides qualification or selection priority.
 */
public final class QuestionAnswers {
    private static final BigDecimal MAX_SAFE = new BigDecimal("9007199254740991");
    private static final Set<String> ACKNOWLEDGEMENT_LABELS = Set.of("확인했어요", "안내를 확인했어요", "안내 내용을 확인했어요",
            "안내 내용을 모두 확인했어요", "기준과 예외를 함께 확인했어요", "안내를 읽었어요");
    private static final Set<String> PENDING_ACKNOWLEDGEMENT_LABELS = Set.of("추가 확인이 필요해요", "아직 확인할 내용이 남아 있어요",
            "확인 전", "아직 확인 전이에요", "아직 확인하지 않았어요");

    private QuestionAnswers() {
    }

    /**
     * Classify stored choices without reinterpreting facts or implicit YES/NO answers.
     */
    public static boolean isAcknowledgementQuestion(Question question) {
        var options = question.options();
        if (!question.inputKind().equals("SINGLE") || options == null || options.size() < 2) return false;
        return options.stream().anyMatch(QuestionAnswers::acknowledged)
                && options.stream().anyMatch(option -> option.value().equals("UNKNOWN"))
                && options.stream().allMatch(option -> acknowledged(option) || option.value().equals("UNKNOWN")
                || Set.of("NO", "REVIEW", "PENDING", "NOT_YET").contains(option.value())
                && PENDING_ACKNOWLEDGEMENT_LABELS.contains(option.label().trim()));
    }

    private static boolean acknowledged(AnswerOption option) {
        return Set.of("ACK", "YES", "CHECKED", "CONFIRMED").contains(option.value())
                && ACKNOWLEDGEMENT_LABELS.contains(option.label().trim()) && !option.requiresFollowUp();
    }

    /**
     * Keep answers used by documents and cross-question rules, and all unresolved official criteria.
     */
    public static List<Question> applicantQuestions(List<Question> questions, java.util.Collection<String> documentQuestionIds) {
        var linked = new HashSet<>(documentQuestionIds);
        for (var question : questions) {
            if (question.applicability() != null) linked.add(question.applicability().questionId());
            for (var dependency : question.answerDependencies()) {
                linked.add(question.id());
                linked.add(dependency.questionId());
            }
        }
        return questions.stream().filter(question -> question.reviewRequired() || linked.contains(question.id())
                || !isAcknowledgementQuestion(question)).toList();
    }

    public static void validateQuestion(Question q) {
        if (!Set.of("SINGLE", "MULTIPLE", "NUMBER", "DATE").contains(q.inputKind()))
            throw new IllegalArgumentException();
        var c = q.constraints();
        if (c != null) {
            if (c.unit() != null && (c.unit().isBlank() || c.unit().length() > 20))
                throw new IllegalArgumentException();
            for (BigDecimal n : Arrays.asList(c.min(), c.max(), c.step()))
                if (n != null && !safeNumber(n)) throw new IllegalArgumentException();
            if (c.min() != null && c.max() != null && c.min().compareTo(c.max()) > 0)
                throw new IllegalArgumentException();
            if (c.step() != null && c.step().signum() <= 0) throw new IllegalArgumentException();
            if (c.minDate() != null) date(c.minDate());
            if (c.maxDate() != null) date(c.maxDate());
            if (c.minDate() != null && c.maxDate() != null && c.minDate().compareTo(c.maxDate()) > 0)
                throw new IllegalArgumentException();
            if (c.maxSelections() != null && (c.maxSelections() < 1 || c.maxSelections() > 19))
                throw new IllegalArgumentException();
            if (!q.inputKind().equals("NUMBER") && (c.min() != null || c.max() != null || c.step() != null || c.unit() != null))
                throw new IllegalArgumentException();
            if (!q.inputKind().equals("DATE") && (c.minDate() != null || c.maxDate() != null))
                throw new IllegalArgumentException();
            if (!q.inputKind().equals("MULTIPLE") && c.maxSelections() != null) throw new IllegalArgumentException();
        }
        if (q.inputKind().equals("NUMBER") || q.inputKind().equals("DATE")) {
            if (q.options() != null && !q.options().isEmpty()) throw new IllegalArgumentException();
        } else {
            var values = new HashSet<String>();
            if (q.answerOptions().size() < 2 || q.answerOptions().size() > 20) throw new IllegalArgumentException();
            for (var option : q.answerOptions()) {
                if (option == null || option.value() == null || !option.value().matches("[A-Z][A-Z0-9_]{0,49}")
                        || !values.add(option.value()) || option.label() == null || option.label().isBlank() || option.label().length() > 300)
                    throw new IllegalArgumentException();
            }
            if (!values.contains("UNKNOWN")) throw new IllegalArgumentException();
            if (c != null && c.maxSelections() != null && c.maxSelections() > values.size() - 1)
                throw new IllegalArgumentException();
        }
    }

    public static Object normalize(Question q, Object answer) {
        if (answer == null) throw new IllegalArgumentException();
        if (answer.equals("UNKNOWN")) return "UNKNOWN";
        var c = q.constraints();
        return switch (q.inputKind()) {
            case "SINGLE" -> {
                if (!(answer instanceof String value) || q.answerOptions().stream().noneMatch(o -> o.value().equals(value)))
                    throw new IllegalArgumentException();
                yield value;
            }
            case "MULTIPLE" -> {
                if (!(answer instanceof List<?> selected) || selected.isEmpty() || selected.size() > (c != null && c.maxSelections() != null ? c.maxSelections() : 19))
                    throw new IllegalArgumentException();
                Set<String> choices = new HashSet<>();
                for (Object value : selected) {
                    if (!(value instanceof String text) || text.equals("UNKNOWN") || !choices.add(text)
                            || q.answerOptions().stream().noneMatch(o -> o.value().equals(text)))
                        throw new IllegalArgumentException();
                }
                yield q.answerOptions().stream().map(AnswerOption::value).filter(choices::contains).toList();
            }
            case "NUMBER" -> {
                if (!(answer instanceof Number number)) throw new IllegalArgumentException();
                BigDecimal value;
                try {
                    value = new BigDecimal(number.toString());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException();
                }
                if (!safeNumber(value) || c != null && (c.min() != null && value.compareTo(c.min()) < 0 || c.max() != null && value.compareTo(c.max()) > 0))
                    throw new IllegalArgumentException();
                BigDecimal step = c != null && c.step() != null ? c.step() : BigDecimal.ONE;
                BigDecimal origin = c != null && c.min() != null ? c.min() : BigDecimal.ZERO;
                if (value.subtract(origin).remainder(step).signum() != 0) throw new IllegalArgumentException();
                yield value.stripTrailingZeros();
            }
            case "DATE" -> {
                if (!(answer instanceof String text)) throw new IllegalArgumentException();
                date(text);
                if (c != null && (c.minDate() != null && text.compareTo(c.minDate()) < 0 || c.maxDate() != null && text.compareTo(c.maxDate()) > 0))
                    throw new IllegalArgumentException();
                yield text;
            }
            default -> throw new IllegalArgumentException();
        };
    }

    /**
     * New or changed input only: NONE is a standalone answer, without reinterpreting saved snapshots.
     */
    public static Object normalizeSubmission(Question q, Object answer) {
        if (q.inputKind().equals("MULTIPLE") && answer instanceof List<?> values
                && values.contains("NONE") && values.size() != 1) throw new IllegalArgumentException();
        return normalize(q, answer);
    }

    public static boolean needsFollowUp(Question q, Object answer) {
        if (q.reviewRequired()) return true;
        if (answer == null || answer.equals("UNKNOWN")) return true;
        try {
            normalize(q, answer);
        } catch (IllegalArgumentException ex) {
            return true;
        }
        if (q.inputKind().equals("NUMBER") || q.inputKind().equals("DATE")) return false;
        List<?> values = answer instanceof List<?> selected ? selected : List.of(answer);
        return values.stream().anyMatch(value -> q.answerOptions().stream().anyMatch(o -> o.value().equals(value) && o.requiresFollowUp()));
    }

    public static boolean needsFollowUp(Question q, Object answer, Map<String, ?> answers, List<Question> questions) {
        return needsFollowUp(q, answer) || !AnswerDependencies.conflictMessages(q, answer, answers, questions).isEmpty();
    }

    private static boolean safeNumber(BigDecimal number) {
        return number.abs().compareTo(MAX_SAFE) <= 0 && number.stripTrailingZeros().scale() <= 6;
    }

    private static void date(String text) {
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || text.startsWith("0000"))
            throw new IllegalArgumentException();
        try {
            LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException();
        }
    }
}
