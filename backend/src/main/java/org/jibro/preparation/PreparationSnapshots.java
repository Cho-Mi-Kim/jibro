package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;

import java.util.*;

import org.jibro.notice.QuestionAnswers;
import org.jibro.notice.GuidanceIssues;
import org.jibro.notice.ReviewedNoticeFiles;
import org.jibro.notice.AnswerDependencies;

/**
 * Pure snapshot construction and answer evaluation shared by saved and one-time checks.
 */
public final class PreparationSnapshots {
    private PreparationSnapshots() {
    }

    public static Snapshot fresh(Notice notice, Content content) {
        Preparation p = new Preparation();
        p.id = UUID.randomUUID().toString();
        p.noticeId = notice.id();
        p.title = notice.title();
        p.version = notice.version();
        p.revision = 1;
        p.guideState = notice.guideState();
        p.questions = content.questions();
        p.reviewIssues = content.reviewIssues();
        Map<String, String> rules = new LinkedHashMap<>();
        Map<String, List<String>> ruleValues = new LinkedHashMap<>();
        for (var template : content.documents()) {
            var d = new Document();
            d.id = template.id();
            d.title = template.title();
            d.method = template.method();
            d.issuer = template.issuer();
            d.issueUrl = template.issueUrl();
            d.evidence = template.evidence();
            p.documents.add(d);
            if (template.conditionQuestionId() != null) rules.put(d.id, template.conditionQuestionId());
            if (template.conditionAnswerValues() != null) ruleValues.put(d.id, template.conditionAnswerValues());
        }
        ReviewedNoticeFiles.applyToPreparation(p, notice);
        for (var template : content.schedules()) {
            var s = new Schedule();
            s.id = template.id();
            s.title = template.title();
            s.date = template.date();
            s.timingDescription = template.timingDescription();
            s.evidence = template.evidence();
            p.schedules.add(s);
        }
        var snapshot = new Snapshot(p, rules, ruleValues);
        recalculateDocuments(snapshot);
        assess(p);
        return snapshot;
    }

    public static void applyAnswers(Snapshot snap, Map<String, ?> answers) {
        Preparation p = snap.preparation();
        Set<String> ids = new HashSet<>(p.questions.stream().map(Question::id).toList());
        if (answers == null || !ids.containsAll(answers.keySet()) || answers.values().stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException();
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (var question : p.questions) {
            if (!answers.containsKey(question.id())) continue;
            Object answer = answers.get(question.id());
            // Full-answer saves may carry an unchanged legacy answer while editing another question.
            normalized.put(question.id(), Objects.equals(answer, p.answers.get(question.id()))
                    ? QuestionAnswers.normalize(question, answer) : QuestionAnswers.normalizeSubmission(question, answer));
        }
        p.answers = normalized;
        recalculateDocuments(snap);
    }

    public static void recalculateDocuments(Snapshot snap) {
        Preparation p = snap.preparation();
        for (var d : p.documents) {
            String question = snap.rules().get(d.id);
            d.reviewRequired = GuidanceIssues.documentRequiresReview(p.reviewIssues, d.id, question);
            String base = PreparationRules.necessity(question, snap.valuesFor(d.id), p.answers, p.questions);
            String next = d.reviewRequired ? "UNKNOWN" : AnswerDependencies.documentNecessity(base, d.id, p.questions, p.answers);
            if (!next.equals(d.necessity)) {
                d.changeState(d.state == DocumentState.READY ? DocumentState.RECHECK : DocumentState.UNCHECKED);
                d.necessity = next;
            }
        }
    }

    public static Preparation assess(Preparation p) {
        var result = PreparationRules.assess(p);
        p.progress = result.progress();
        p.houseStage = result.houseStage();
        p.warnings = result.warnings();
        p.nextAction = result.nextAction();
        return p;
    }
}
