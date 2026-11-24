package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;

import java.util.*;
import java.util.regex.Pattern;

import tools.jackson.databind.ObjectMapper;

import org.jibro.notice.AnswerDependencies;
import org.jibro.notice.GuidanceIssues;
import org.jibro.notice.QuestionAnswers;

/**
 * Select only certainly applicable or unresolved items; never rewrite a saved answer or record.
 */
public final class PreparationApplicability {
    private PreparationApplicability() {
    }

    private record DocumentGuidance(String title, List<String> documents) {
    }

    private record TypeOptionRule(String type, List<String> values, String label) {
    }

    private record Vocabulary(Map<String, List<String>> types, Map<String, List<String>> channels,
                              List<TypeOptionRule> typeOptionRules, Map<String, List<String>> typeOverlaps,
                              Map<String, String> channelValues, List<DocumentGuidance> documentGuidance) {
    }

    private static final Vocabulary VOCABULARY = vocabulary();
    private static final Map<String, List<String>> TYPES = VOCABULARY.types();
    private static final Map<String, List<String>> CHANNELS = VOCABULARY.channels();
    private static final Map<String, String> CHANNEL_VALUES = VOCABULARY.channelValues();

    private static Vocabulary vocabulary() {
        try (var stream = PreparationApplicability.class.getResourceAsStream("/preparation/preparation-audience-vocabulary.json")) {
            if (stream == null) throw new IllegalStateException("Missing preparation audience vocabulary");
            return new ObjectMapper().readValue(stream, Vocabulary.class);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read preparation audience vocabulary", ex);
        }
    }

    public static List<Question> visibleQuestions(Preparation p) {
        if (!available(p)) return p.questions;
        var uncertain = uncertainQuestions(p);
        var types = selectedTypes(p, uncertain);
        return p.questions.stream().filter(q -> {
            if (uncertain.contains(q.id())) return true;
            if (!matchesQuestionScope(q, p, uncertain, types)) return false;
            Object answer = p.answers.get(q.id());
            if (explicitUnknownOrInvalid(q, answer)
                    || answer != null && q.answerOptions().stream().anyMatch(o -> o.requiresFollowUp()
                    && (answer instanceof List<?> values ? values.contains(o.value()) : answer.equals(o.value())))
                    || "DOES_NOT_MEET".equals(answer) || "NO".equals(answer) && !documentQuestion(q)) return true;
            if (explicitlyNotApplicable(q, answer)) return false;
            if (QuestionAnswers.isAcknowledgementQuestion(q)) {
                var guidance = VOCABULARY.documentGuidance().stream().filter(g -> g.title().equals(q.title())).findFirst();
                if (guidance.isPresent()) {
                    var titles = guidance.get().documents();
                    var linked = p.documents.stream().filter(d -> titles.contains(d.title)).toList();
                    if (linked.stream().map(d -> d.title).distinct().count() == titles.size()
                            && linked.stream().allMatch(d -> excludedDocument(d, p, uncertain, types)))
                        return false;
                }
            }
            if (documentQuestion(q) && answer != null) {
                var linked = p.documents.stream().filter(d -> q.id().equals(p.documentConditions.get(d.id))).toList();
                if (!linked.isEmpty() && linked.stream().allMatch(d -> excludedDocument(d, p, uncertain, types)))
                    return false;
            }
            return true;
        }).toList();
    }

    public static List<Document> visibleDocuments(Preparation p) {
        var uncertain = uncertainQuestions(p);
        var types = selectedTypes(p, uncertain);
        return p.documents.stream().filter(d -> !excludedDocument(d, p, uncertain, types)).toList();
    }

    private static boolean excludedDocument(Document document, Preparation p, Set<String> uncertain, TypeSelection types) {
        String questionId = p.documentConditions.get(document.id);
        var question = p.questions.stream().filter(q -> q.id().equals(questionId)).findFirst();
        if (document.reviewRequired || GuidanceIssues.documentRequiresReview(p.reviewIssues, document.id, questionId)
                || GuidanceIssues.safe(p.reviewIssues).stream().anyMatch(i -> "DOCUMENT".equals(i.area())
                && (i.questionIds() == null || i.questionIds().isEmpty()) && (i.documentIds() == null || i.documentIds().isEmpty()))
                || question.isPresent() && uncertain.contains(questionId)
                || !"NOT_APPLICABLE".equals(AnswerDependencies.documentNecessity("NOT_APPLICABLE", document.id, p.questions, p.answers)))
            return false;
        return "NOT_APPLICABLE".equals(document.necessity)
                || available(p) && question.isPresent() && !matchesQuestionScope(question.get(), p, uncertain, types);
    }

    private static boolean matchesQuestionScope(Question question, Preparation p, Set<String> uncertain, TypeSelection types) {
        var applicability = question.applicability();
        if (applicability != null) {
            var parent = p.questions.stream().filter(q -> q.id().equals(applicability.questionId())).findFirst();
            if (parent.isEmpty()) return true;
            var values = selectedValues(parent.get(), p.answers.get(parent.get().id()), uncertain);
            return values == null || !Collections.disjoint(applicability.answerValues(), values);
        }
        if (types == null || question.id().equals(types.question().id())) return true;
        var scope = new HashSet<>(exclusiveNamedTypes(question.title()));
        if (scope.isEmpty()) return true;
        // Dependencies can widen a named audience, but cannot establish an exclusive audience on their own.
        for (var dependency : question.answerDependencies()) {
            if (!dependency.questionId().equals(types.question().id()) || !dependency.conflictingValues().contains("NOT_APPLICABLE"))
                continue;
            for (var value : dependency.answerValues()) {
                var mapped = types.choices().get(value);
                if (mapped == null || mapped.isEmpty()) return true;
                scope.addAll(mapped);
            }
        }
        var choices = new HashSet<String>();
        types.choices().values().forEach(choices::addAll);
        if (!choices.containsAll(scope)) return true;
        for (var type : new HashSet<>(scope)) scope.addAll(VOCABULARY.typeOverlaps().getOrDefault(type, List.of()));
        return !Collections.disjoint(scope, types.values());
    }

    public static List<Schedule> visibleSchedules(Preparation p) {
        if (!available(p) || GuidanceIssues.safe(p.reviewIssues).stream().anyMatch(i -> "SCHEDULE".equals(i.area())))
            return p.schedules;
        var channels = selectedChannels(p, uncertainQuestions(p));
        if (channels == null) return p.schedules;
        return p.schedules.stream().filter(s -> {
            String title = s.title == null ? "" : s.title;
            if (!title.startsWith("접수 회차 · ") || Pattern.compile("동.?호|계약|서류|증빙|제출|불가|제외|않|아니|불허").matcher(title).find())
                return true;
            long mentionedChannels = CHANNELS.values().stream().filter(aliases -> aliases.stream().anyMatch(alias ->
                    Pattern.compile("(?:^|[^가-힣A-Za-z0-9])" + alias + "(?=$|[^가-힣A-Za-z0-9]|접수|신청|청약)").matcher(title).find())).count();
            if (mentionedChannels > 1) return true;
            var scope = new HashSet<String>();
            CHANNELS.forEach((channel, aliases) -> {
                for (var alias : aliases)
                    if (Pattern.compile("(?:^|[^가-힣A-Za-z0-9])" + alias + "\\s*(?:접수|신청|청약)").matcher(title).find())
                        scope.add(channel);
            });
            return scope.isEmpty() || !Collections.disjoint(scope, channels);
        }).toList();
    }

    private static boolean available(Preparation p) {
        return p.guideState == null || p.guideState.equals("AVAILABLE");
    }

    private static Set<String> uncertainQuestions(Preparation p) {
        var result = new HashSet<>(GuidanceIssues.questionIds(p.reviewIssues));
        if (GuidanceIssues.safe(p.reviewIssues).stream().anyMatch(i -> "CONDITION".equals(i.area())
                && (i.questionIds() == null || i.questionIds().isEmpty())))
            p.questions.forEach(q -> result.add(q.id()));
        for (var question : p.questions) {
            if (question.reviewRequired()) result.add(question.id());
            if (!AnswerDependencies.conflictMessages(question, p.answers.get(question.id()), p.answers, p.questions).isEmpty()) {
                result.add(question.id());
                question.answerDependencies().forEach(dependency -> result.add(dependency.questionId()));
            }
        }
        for (var question : p.questions)
            if (question.applicability() != null && result.contains(question.applicability().questionId()))
                result.add(question.id());
        return result;
    }

    private static boolean explicitUnknownOrInvalid(Question q, Object answer) {
        if (answer == null) return false;
        if (answer.equals("UNKNOWN")) return true;
        try {
            QuestionAnswers.normalize(q, answer);
            return false;
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    private static boolean explicitlyNotApplicable(Question q, Object answer) {
        if (!(answer instanceof String value) || !Set.of("NOT_MY_TYPE", "NOT_APPLICABLE", "NOT_REQUIRED").contains(value))
            return false;
        return q.answerOptions().stream().anyMatch(option -> option.value().equals(value)
                && Pattern.compile("다른.?유형|해당.?없|해당하지.?않|제출.?대상.{0,5}아니").matcher(option.label()).find());
    }

    private static boolean documentQuestion(Question q) {
        String title = q.title() == null ? "" : q.title();
        return Pattern.compile("제출.?대상|(?:서류|증명서|동의서|확약서).*(?:누가|내나요|준비|확인)").matcher(title).find();
    }

    private static Set<String> namedTypes(String title) {
        var result = new HashSet<String>();
        String plain = title == null ? "" : title;
        TYPES.forEach((value, aliases) -> {
            for (var alias : aliases)
                if (Pattern.compile("(?:^|[^가-힣A-Za-z0-9])" + alias.replace(" ", "\\s*")
                        + "(?=$|[^가-힣A-Za-z0-9]|은|는|이|가|의|으로|로|에게|만|과|와)").matcher(plain).find())
                    result.add(value);
        });
        return result;
    }

    private static Set<String> exclusiveNamedTypes(String title) {
        if (title == null || Pattern.compile("아니|않|제외|달라|비교|차이|구분").matcher(title).find()) return Set.of();
        String aliases = TYPES.values().stream().flatMap(List::stream).sorted(Comparator.comparingInt(String::length).reversed())
                .map(value -> Pattern.quote(value).replace(" ", "\\E\\s*\\Q")).reduce((a, b) -> a + "|" + b).orElseThrow();
        var prefix = Pattern.compile("^(?:" + aliases + ")(?:(?:[·ㆍ,/]|\\s*(?:및|또는|과|와)\\s*)(?:" + aliases
                + "))*(?:은|는|이|가|의|으로|로)?\\s+").matcher(title);
        return prefix.find() ? namedTypes(prefix.group()) : Set.of();
    }

    private static Set<String> optionTypes(AnswerOption option) {
        if (option.requiresFollowUp() || Set.of("UNKNOWN", "NONE", "OTHER", "NOT_APPLICABLE").contains(option.value()))
            return Set.of();
        var named = namedTypes(option.label());
        if (!named.isEmpty()) return named;
        return VOCABULARY.typeOptionRules().stream()
                .filter(rule -> rule.values().contains(option.value()) && Pattern.compile(rule.label()).matcher(option.label()).find())
                .findFirst().map(rule -> Set.of(rule.type())).orElse(Set.of());
    }

    private static boolean typeSelector(Question q) {
        if (q.options() == null || q.title() == null
                || !Pattern.compile("(?:신청|공급).*(?:유형|계층|경로)|(?:유형|계층|경로).*(?:신청|공급)|어떤\\s*유형|일반공급.*주거약자").matcher(q.title()).find()
                || Pattern.compile("가점|순위|인정\\s*유형").matcher(q.title()).find()) return false;
        return q.options().stream().flatMap(option -> optionTypes(option).stream()).distinct().count() >= 2;
    }

    private record TypeSelection(Question question, Map<String, Set<String>> choices, Set<String> values) {
    }

    private static TypeSelection selectedTypes(Preparation p, Set<String> uncertain) {
        var parents = p.questions.stream().filter(PreparationApplicability::typeSelector).toList();
        if (parents.size() != 1) return null;
        var question = parents.get(0);
        var answers = selectedValues(question, p.answers.get(question.id()), uncertain);
        if (answers == null) return null;
        var choices = new HashMap<String, Set<String>>();
        question.options().forEach(option -> choices.put(option.value(), optionTypes(option)));
        if (answers.stream().anyMatch(value -> choices.getOrDefault(value, Set.of()).isEmpty())) return null;
        var values = new HashSet<String>();
        answers.forEach(value -> values.addAll(choices.get(value)));
        return new TypeSelection(question, choices, values);
    }

    private static Set<String> selectedChannels(Preparation p, Set<String> uncertain) {
        var parents = p.questions.stream().filter(q -> q.options() != null && q.title() != null
                && Pattern.compile("신청|접수|청약").matcher(q.title()).find()
                && Pattern.compile("방법|방식").matcher(q.title()).find()
                && q.options().stream().anyMatch(o -> CHANNEL_VALUES.containsKey(o.value()))).toList();
        if (parents.size() != 1) return null;
        var q = parents.get(0);
        var values = selectedValues(q, p.answers.get(q.id()), uncertain);
        if (values == null || !CHANNEL_VALUES.keySet().containsAll(values)) return null;
        var result = new HashSet<String>();
        for (var option : q.options()) {
            if (!values.contains(option.value())) continue;
            var channel = CHANNEL_VALUES.get(option.value());
            if (Pattern.compile("불가|제외|않|아니|불허").matcher(option.label()).find()
                    || CHANNELS.get(channel).stream().noneMatch(alias -> option.label().contains(alias))
                    || CHANNELS.values().stream().filter(aliases -> aliases.stream().anyMatch(alias -> option.label().contains(alias))).count() != 1)
                return null;
            result.add(channel);
        }
        return result;
    }

    private static Set<String> selectedValues(Question q, Object answer, Set<String> uncertain) {
        if (answer == null || uncertain.contains(q.id()) || explicitUnknownOrInvalid(q, answer)) return null;
        var result = new HashSet<String>();
        if (answer instanceof String value) result.add(value);
        else if (answer instanceof List<?> values && values.stream().allMatch(String.class::isInstance))
            values.forEach(value -> result.add((String) value));
        if (result.isEmpty()) return null;
        if (q.answerOptions().stream().anyMatch(o -> result.contains(o.value()) && o.requiresFollowUp())) return null;
        return result;
    }
}
