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
            p.answers.put("q" + i, i == 99 ? "UNKNOWN" : "YES");
        }
        Document document = new Document();
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
