package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Policy calculations without database access or changes to the supplied record. */
public final class PreparationRules {
    private static final int QUESTION_WEIGHT = 30;
    private static final int DOCUMENT_WEIGHT = 50;
    private static final int SCHEDULE_WEIGHT = 20;

    private PreparationRules() {
    }

    public record Assessment(int progress, int houseStage, List<String> warnings, NextAction nextAction) {
    }

    public static String necessity(String questionId, List<String> requiredValues, Map<String, String> answers) {
        if (questionId == null) return "REQUIRED";
        String answer = answers.getOrDefault(questionId, "UNKNOWN");
        return answer.equals("UNKNOWN") ? "UNKNOWN" : requiredValues.contains(answer) ? "CONDITIONAL" : "NOT_APPLICABLE";
    }

    public static Assessment assess(Preparation p) {
        long known = p.questions.stream().filter(q -> !q.needsFollowUp(p.answers.get(q.id()))).count();
        var relevant = p.documents.stream().filter(d -> !d.necessity.equals("NOT_APPLICABLE")).toList();
        long ready = relevant.stream().filter(d -> d.state == DocumentState.READY && !d.necessity.equals("UNKNOWN")).count();
        long recorded = p.schedules.stream().filter(s -> s.recorded).count();
        int progress = (int) Math.round((p.questions.isEmpty() ? 0 : (double) QUESTION_WEIGHT * known / p.questions.size())
            + (relevant.isEmpty() ? 0 : (double) DOCUMENT_WEIGHT * ready / relevant.size())
            + (p.schedules.isEmpty() ? 0 : (double) SCHEDULE_WEIGHT * recorded / p.schedules.size()));
        // Rounding must not turn an unfinished checklist into a completed house.
        if (known < p.questions.size() || ready < relevant.size() || recorded < p.schedules.size())
            progress = Math.min(99, progress);
        int houseStage = progress < 20 ? 1 : progress < 40 ? 2 : progress < 70 ? 3 : progress < 100 ? 4 : 5;
        var warnings = new ArrayList<>(p.questions.stream().filter(q -> q.needsFollowUp(p.answers.get(q.id()))).map(Question::title).toList());
        String prefix = "/preparations/" + p.id;
        var recheck = relevant.stream().filter(d -> d.state == DocumentState.RECHECK).findFirst();
        var unready = relevant.stream().filter(d -> d.state != DocumentState.READY || d.necessity.equals("UNKNOWN")).findFirst();
        NextAction nextAction;
        if (!p.guideState.equals("AVAILABLE")) {
            warnings.add("공고 기준이 변경되었거나 안내를 재검수 중입니다. 기존 기록은 보존됩니다.");
            nextAction = new NextAction("공고 변경 내용 확인하기", "새 기준 확인 전에는 준비 기록을 수정할 수 없어요.", "/notices/" + p.noticeId);
        } else if (recheck.isPresent())
            nextAction = new NextAction("서류 다시 확인하기", "조건 변경으로 재확인이 필요한 서류가 있어요.", prefix + "/documents");
        else if (!warnings.isEmpty())
            nextAction = new NextAction("확인하지 못한 조건 살펴보기", "모름·미입력 항목이 남아 있어요.", prefix + "/conditions/edit");
        else if (unready.isPresent())
            nextAction = new NextAction(unready.get().title + " 준비하기", "아직 준비가 끝나지 않은 서류예요.", prefix + "/documents");
        else nextAction = new NextAction("신청 일정 확인하기", "공식 절차와 일정을 확인하세요.", prefix + "/schedule");
        return new Assessment(progress, houseStage, List.copyOf(warnings), nextAction);
    }
}
