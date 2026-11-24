package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class PreparationProgressTest {
    @Test
    void unresolvedOfficialSchedulePreventsCompleteEvenWhenAllPersonalTasksAreDone() {
        var p = new Preparation();
        p.id = "preparation";
        p.noticeId = "notice";
        p.guideState = "AVAILABLE";
        p.questions.add(new Question("known", "조건", "근거", null));
        p.answers.put("known", "YES");
        var document = new Document();
        document.id = "document";
        document.necessity = "REQUIRED";
        document.state = DocumentState.READY;
        p.documents.add(document);
        var schedule = new Schedule();
        schedule.recorded = true;
        p.schedules.add(schedule);
        p.reviewIssues.add(new ReviewIssue("date", "SCHEDULE", "결과 발표일 확인 필요", "공식 자료 간 날짜가 다릅니다.",
                List.of(), List.of(), List.of(new Evidence("일정 원문", null, "2026-11-01"))));

        var result = PreparationRules.assess(p);
        assertEquals(99, result.progress());
        assertEquals(4, result.houseStage());
        assertEquals("/notices/notice", result.nextAction().path());
        assertEquals(List.of("공식 확인 필요 · 결과 발표일 확인 필요"), result.warnings());
    }

    @Test
    void unfinishedQuestionCannotRoundUpToCompleteHouse() {
        Preparation p = new Preparation();
        p.id = "test";
        p.guideState = "AVAILABLE";
        p.questions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            p.questions.add(new Question("q" + i, "확인 항목", "근거", null));
            p.answers.put("q" + i, i == 99 ? "UNKNOWN" : "YES");
        }
        Document document = new Document();
        document.id = "document";
        document.necessity = "REQUIRED";
        document.state = DocumentState.READY;
        p.documents.add(document);
        Schedule schedule = new Schedule();
        schedule.recorded = true;
        p.schedules.add(schedule);
        var unfinished = PreparationRules.assess(p);
        assertEquals(99, unfinished.progress());
        assertEquals(4, unfinished.houseStage());
        assertEquals(1, unfinished.warnings().size());
        p.answers.put("q99", "YES");
        var complete = PreparationRules.assess(p);
        assertEquals(100, complete.progress());
        assertEquals(5, complete.houseStage());
    }
}
