package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class PreparationProgressTest {
    @Test
    void unfinishedQuestionCannotRoundUpToCompleteHouse() {
        Preparation p = new Preparation();
        p.id = "test";
        p.guideState = "AVAILABLE";
        p.questions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            p.questions.add(new Question("q" + i, "확인 항목", "근거", null));
            p.answers.put("q" + i, i == 99 ? Answer.UNKNOWN : Answer.YES);
        }
        Document document = new Document();
        document.necessity = "REQUIRED";
        document.state = DocumentState.READY;
        p.documents.add(document);
        Schedule schedule = new Schedule();
        schedule.recorded = true;
        p.schedules.add(schedule);
        PreparationService.calculate(p);
        assertEquals(99, p.progress);
        assertEquals(4, p.houseStage);
        assertEquals(1, p.warnings.size());
        p.answers.put("q99", Answer.YES);
        PreparationService.calculate(p);
        assertEquals(100, p.progress);
        assertEquals(5, p.houseStage);
    }
}
