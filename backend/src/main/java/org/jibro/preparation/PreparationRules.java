package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jibro.notice.GuidanceIssues;
import org.jibro.notice.AnswerDependencies;
import org.jibro.notice.QuestionAnswers;

/**
 * Policy calculations without database access or changes to the supplied record.
 */
public final class PreparationRules {
    private static final int QUESTION_WEIGHT = 30;
    private static final int DOCUMENT_WEIGHT = 50;
    private static final int SCHEDULE_WEIGHT = 20;

    private PreparationRules() {
    }

    public record Assessment(int progress, int houseStage, List<String> warnings, NextAction nextAction) {
    }

    public static String necessity(String questionId, List<String> requiredValues, Map<String, ?> answers, List<Question> questions) {
        if (questionId == null) return "REQUIRED";
        Object answer = answers.get(questionId);
        if (answer == null || answer.equals("UNKNOWN")) return "UNKNOWN";
        boolean matched = answer instanceof List<?> choices ? choices.stream().anyMatch(requiredValues::contains) : requiredValues.contains(answer);
        if (matched) return "CONDITIONAL";
        var question = questions.stream().filter(q -> q.id().equals(questionId)).findFirst();
        return question.isEmpty() || QuestionAnswers.needsFollowUp(question.get(), answer) ? "UNKNOWN" : "NOT_APPLICABLE";
    }

    /**
     * Progress can be calculated from an active-list projection without loading explanatory content.
     */
    public static int progress(Preparation p) {
        var questions = QuestionAnswers.applicantQuestions(PreparationApplicability.visibleQuestions(p), p.documentConditions.values());
        long known = questions.stream().filter(q -> !q.needsFollowUp(p.answers.get(q.id()), p.answers, p.questions)).count();
        var relevant = PreparationApplicability.visibleDocuments(p);
        long ready = relevant.stream().filter(d -> d.state == DocumentState.READY && !d.necessity.equals("UNKNOWN") && !d.reviewRequired).count();
        var schedules = PreparationApplicability.visibleSchedules(p).stream()
                .filter(s -> PreparationSchedules.canRecord(s, p.schedules)).toList();
        long recorded = schedules.stream().filter(s -> s.recorded).count();
        double questionFraction = questions.isEmpty() ? (p.questions.isEmpty() ? 0 : 1) : (double) known / questions.size();
        int progress = (int) Math.round(QUESTION_WEIGHT * questionFraction
                + (relevant.isEmpty() ? 0 : (double) DOCUMENT_WEIGHT * ready / relevant.size())
                + (schedules.isEmpty() ? (p.schedules.isEmpty() ? 0 : SCHEDULE_WEIGHT) : (double) SCHEDULE_WEIGHT * recorded / schedules.size()));
        // Rounding must not turn an unfinished checklist into a completed house.
        if (known < questions.size() || ready < relevant.size() || recorded < schedules.size()
                || !GuidanceIssues.safe(p.reviewIssues).isEmpty())
            progress = Math.min(99, progress);
        return progress;
    }

    public static Assessment assess(Preparation p) {
        int progress = progress(p);
        int houseStage = progress < 20 ? 1 : progress < 40 ? 2 : progress < 70 ? 3 : progress < 100 ? 4 : 5;
        var questions = QuestionAnswers.applicantQuestions(PreparationApplicability.visibleQuestions(p), p.documentConditions.values());
        var relevant = PreparationApplicability.visibleDocuments(p);
        var unanswered = questions.stream().filter(q -> !q.reviewRequired() && q.needsFollowUp(p.answers.get(q.id()), p.answers, p.questions)).toList();
        var warnings = new ArrayList<>(unanswered.stream().map(Question::title).toList());
        var conflicts = AnswerDependencies.conflictMessages(p.questions, p.answers);
        conflicts.stream().filter(message -> !warnings.contains(message)).forEach(warnings::add);
        GuidanceIssues.safe(p.reviewIssues).forEach(issue -> warnings.add("공식 확인 필요 · " + issue.title()));
        String prefix = "/preparations/" + p.id;
        var actionable = relevant.stream().filter(d -> !d.reviewRequired).toList();
        var recheck = actionable.stream().filter(d -> d.state == DocumentState.RECHECK).findFirst();
        var unready = actionable.stream().filter(d -> d.state != DocumentState.READY || d.necessity.equals("UNKNOWN")).findFirst();
        NextAction nextAction;
        if (!p.guideState.equals("AVAILABLE")) {
            warnings.add("공고 기준이 변경되었거나 안내를 재검수 중입니다. 기존 기록은 보존됩니다.");
            nextAction = new NextAction("공고 변경 내용 확인하기", "새 기준 확인 전에는 준비 기록을 수정할 수 없어요.", "/notices/" + p.noticeId);
        } else if (recheck.isPresent())
            nextAction = new NextAction("서류 다시 확인하기", "조건 변경으로 재확인이 필요한 서류가 있어요.", prefix + "/documents");
        else if (!unanswered.isEmpty())
            nextAction = new NextAction("확인하지 못한 조건 살펴보기", conflicts.isEmpty() ? "모름·미입력 항목이 남아 있어요."
                    : "서로 다른 답변을 다시 확인해 주세요.", prefix + "/conditions/edit");
        else if (unready.isPresent())
            nextAction = new NextAction(unready.get().title + " 준비하기", "아직 준비가 끝나지 않은 서류예요.", prefix + "/documents");
        else if (!GuidanceIssues.safe(p.reviewIssues).isEmpty())
            nextAction = new NextAction("공식 확인이 필요한 항목 보기", "기관 자료의 확인 필요 항목은 준비 완료로 계산하지 않아요.", "/notices/" + p.noticeId);
        else if (progress == 100)
            nextAction = new NextAction("준비 기록 완료", "내 답변에 해당하는 조건·서류·일정을 모두 기록했어요. 결과와 후속 안내는 공식 공고에서 확인해 주세요.", prefix + "/schedule", true);
        else nextAction = new NextAction("신청 일정 확인하기", "신청·제출을 마친 일정은 일정 탭에서 완료로 표시해 주세요.", prefix + "/schedule");
        return new Assessment(progress, houseStage, List.copyOf(warnings), nextAction);
    }
}
