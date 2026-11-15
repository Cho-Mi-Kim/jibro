package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class QuestionAnswersTest {
    private final Evidence evidence = new Evidence("입력 계약 테스트", null, "2026-11-06");
    private Question question(String kind, InputConstraints constraints) {
        return new Question("input", "테스트 질문", "입력 계약 검증용", evidence, null, kind, constraints);
    }

    @Test void oldJsonSnapshotsKeepSingleChoiceStringsAndDefaultRules() {
        var json = new ObjectMapper();
        var snapshot = json.readValue("""
            {"preparation":{"questions":[{"id":"input","title":"질문","reason":"이유",
            "evidence":{"label":"근거","checkedAt":"2026-11-06"}}],"answers":{"input":"YES"}},"rules":{"doc":"input"}}
            """, Snapshot.class);
        var q = snapshot.preparation().questions.get(0);
        assertEquals("SINGLE", q.inputKind());
        assertEquals("YES", QuestionAnswers.normalize(q, snapshot.preparation().answers.get("input")));
        assertEquals(List.of("YES"), snapshot.valuesFor("doc"));
        assertFalse(q.needsFollowUp("YES"));
        assertTrue(q.needsFollowUp("UNKNOWN"));
    }

    @Test void numericBoundsStepPrecisionAndZeroAreInputValidationOnly() {
        var q = question("NUMBER", new InputConstraints(BigDecimal.ZERO, BigDecimal.TEN,
                new BigDecimal("0.25"), null, null, null, "개"));
        QuestionAnswers.validateQuestion(q);
        assertEquals(BigDecimal.ZERO, QuestionAnswers.normalize(q, 0));
        assertEquals(new BigDecimal("1.25"), QuestionAnswers.normalize(q, 1.25));
        for (Object value : List.of(-1, 11, 1.1, "1.25", Double.NaN, Double.POSITIVE_INFINITY,
                new BigDecimal("0.0000001"), true, List.of(1)))
            assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.normalize(q, value));
        assertEquals("UNKNOWN", QuestionAnswers.normalize(q, "UNKNOWN"));
        assertFalse(q.needsFollowUp(0));
        assertTrue(q.needsFollowUp("UNKNOWN"));
    }

    @Test void datesRejectImpossibleDatesAndOutOfRangeWithoutCoercion() {
        var q = question("DATE", new InputConstraints(null, null, null, "2024-01-01", "2024-12-31", null, null));
        QuestionAnswers.validateQuestion(q);
        assertEquals("2024-02-29", QuestionAnswers.normalize(q, "2024-02-29"));
        for (Object value : List.of("2023-02-29", "2024-02-30", "2024-1-1", "2025-01-01", "0000-01-01", 20240101))
            assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.normalize(q, value));
        assertEquals("UNKNOWN", QuestionAnswers.normalize(q, "UNKNOWN"));
    }

    @Test void multipleAnswersAreUniqueKnownCodesCanonicalizedInQuestionOrder() {
        var q = new Question("input", "테스트 질문", "이유", evidence,
                List.of(new AnswerOption("A", "첫째"), new AnswerOption("B", "둘째", true),
                        new AnswerOption("C", "셋째"), new AnswerOption("UNKNOWN", "모름")),
                "MULTIPLE", new InputConstraints(null, null, null, null, null, 2, null));
        QuestionAnswers.validateQuestion(q);
        assertEquals(List.of("A", "B"), QuestionAnswers.normalize(q, List.of("B", "A")));
        for (Object value : List.of(List.of(), List.of("A", "A"), List.of("A", "UNKNOWN"), List.of("UNKNOWN"),
                List.of("A", "B", "C"), List.of("OTHER"), "A", List.of(1)))
            assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.normalize(q, value));
        assertEquals("UNKNOWN", QuestionAnswers.normalize(q, "UNKNOWN"));
        assertTrue(q.needsFollowUp(List.of("A", "B")));
        assertFalse(q.needsFollowUp(List.of("A")));
    }

    @Test void invalidQuestionContractsAreRejectedBeforePublication() {
        assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.validateQuestion(question("TEXT", null)));
        assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.validateQuestion(question("NUMBER",
                new InputConstraints(BigDecimal.TEN, BigDecimal.ZERO, null, null, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.validateQuestion(question("NUMBER",
                new InputConstraints(null, null, BigDecimal.ZERO, null, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.validateQuestion(question("DATE",
                new InputConstraints(null, null, null, "2024-02-30", null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.validateQuestion(question("SINGLE",
                new InputConstraints(null, null, null, null, null, 2, null))));
        assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.validateQuestion(new Question("input", "질문", "이유", evidence,
                List.of(new AnswerOption("A", "첫째"), new AnswerOption("B", "둘째")), "MULTIPLE", null)));
    }

    @Test void noneIsExclusiveForNewSubmissionsWithoutReinterpretingSavedAnswers() {
        var q = new Question("input", "해당 항목", "다중 선택", evidence,
            List.of(new AnswerOption("A", "첫째"), new AnswerOption("NONE", "해당 없음"),
                new AnswerOption("UNKNOWN", "모름")), "MULTIPLE", null);
        assertEquals(List.of("NONE"), QuestionAnswers.normalizeSubmission(q, List.of("NONE")));
        assertEquals(List.of("A"), QuestionAnswers.normalizeSubmission(q, List.of("A")));
        assertEquals("UNKNOWN", QuestionAnswers.normalizeSubmission(q, "UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.normalizeSubmission(q, List.of("NONE", "A")));
        assertThrows(IllegalArgumentException.class, () -> QuestionAnswers.normalizeSubmission(q, List.of("A", "NONE")));
        assertEquals(List.of("A", "NONE"), QuestionAnswers.normalize(q, List.of("A", "NONE")));
        assertFalse(q.needsFollowUp(List.of("A", "NONE")));
        var single = new Question("single", "해당 여부", "단일 선택", evidence, q.options(), "SINGLE", null);
        assertEquals("NONE", QuestionAnswers.normalizeSubmission(single, "NONE"));
    }
}
