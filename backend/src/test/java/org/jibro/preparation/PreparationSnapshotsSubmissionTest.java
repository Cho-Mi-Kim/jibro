package org.jibro.preparation;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

class PreparationSnapshotsSubmissionTest {
    @Test
    void unresolvedApplicantTypeKeepsItsDocumentsButAnExplicitDifferentTypeExcludesThem() {
        var p = new Preparation();
        p.questions = List.of(new Question("category", "신청 상황", "", null,
                List.of(new AnswerOption("STUDENT", "대학생"), new AnswerOption("OTHER", "유형 재확인 필요", true),
                        new AnswerOption("NONE", "확정된 비해당"), new AnswerOption("UNKNOWN", "모름", true))));
        var student = new Document();
        student.id = "student";
        student.note = "이미 준비한 원본 보관";
        var common = new Document();
        common.id = "common";
        common.necessity = "REQUIRED";
        common.state = DocumentState.READY;
        p.documents = List.of(student, common);
        var snap = new Snapshot(p, Map.of("student", "category"), Map.of("student", List.of("STUDENT")));
        for (var scenario : Map.of("OTHER", "UNKNOWN", "NONE", "NOT_APPLICABLE", "STUDENT", "CONDITIONAL", "UNKNOWN", "UNKNOWN").entrySet()) {
            student.necessity = "CONDITIONAL";
            student.state = DocumentState.READY;
            PreparationSnapshots.applyAnswers(snap, Map.of("category", scenario.getKey()));
            assertEquals(scenario.getValue(), student.necessity, scenario.getKey());
            assertEquals(scenario.getValue().equals("CONDITIONAL") ? DocumentState.READY : DocumentState.RECHECK, student.state);
            assertEquals("이미 준비한 원본 보관", student.note);
            assertEquals("REQUIRED", common.necessity);
            assertEquals(DocumentState.READY, common.state);
        }
        p.questions = List.of(new Question("category", "해당하는 상황", "", null,
                p.questions.get(0).options(), "MULTIPLE", null));
        PreparationSnapshots.applyAnswers(snap, Map.of("category", List.of("OTHER")));
        assertEquals("UNKNOWN", student.necessity);
        PreparationSnapshots.applyAnswers(snap, Map.of("category", List.of("STUDENT", "OTHER")));
        assertEquals("CONDITIONAL", student.necessity, "A confirmed required choice remains necessary even alongside an unresolved one");
    }

    private static Snapshot snapshot() {
        var evidence = new Evidence("테스트", null, "2026-11-08");
        var p = new Preparation();
        p.questions = List.of(new Question("mixed", "해당 항목", "이유", evidence,
                        List.of(new AnswerOption("A", "첫째"), new AnswerOption("B", "둘째"),
                                new AnswerOption("NONE", "해당 없음"), new AnswerOption("UNKNOWN", "모름")), "MULTIPLE", null),
                new Question("other", "다른 질문", "이유", evidence));
        return new Snapshot(p, Map.of(), Map.of());
    }

    @Test
    void savedDocumentCriteriaUseTheOriginalSnapshotWithoutDuplicatingStoredRules() {
        var json = new tools.jackson.databind.ObjectMapper();
        var snap = json.readValue("""
                {"preparation":{},"rules":{"form":"family"},"ruleValues":{"form":["ENGAGED"]}}
                """, Snapshot.class);
        var response = json.readTree(json.writeValueAsString(snap.preparation()));
        assertEquals("family", response.at("/documentConditions/form").asString());
        assertEquals("ENGAGED", response.at("/documentConditionValues/form/0").asString());
        var stored = json.readTree(json.writeValueAsString(snap));
        assertFalse(stored.get("preparation").has("documentConditions"));
        assertFalse(stored.get("preparation").has("documentConditionValues"));
        assertEquals("family", stored.at("/rules/form").asString());
        var older = json.readValue("{\"preparation\":{},\"rules\":{\"form\":\"family\"}}", Snapshot.class);
        assertEquals("family", older.preparation().documentConditions.get("form"));
        assertTrue(older.preparation().documentConditionValues.isEmpty());
    }

    @Test
    void rejectsNewConflictingAnswerBeforeChangingAnySavedAnswer() {
        var snap = snapshot();
        snap.preparation().answers.put("mixed", List.of("A"));
        var before = Map.copyOf(snap.preparation().answers);
        assertThrows(IllegalArgumentException.class, () -> PreparationSnapshots.applyAnswers(snap,
                Map.of("mixed", List.of("NONE", "A"), "other", "YES")));
        assertEquals(before, snap.preparation().answers);
    }

    @Test
    void unchangedLegacyAnswerSurvivesEditingAnotherQuestionButCannotBeExpanded() {
        var snap = snapshot();
        snap.preparation().answers.put("mixed", List.of("A", "NONE"));
        PreparationSnapshots.applyAnswers(snap, Map.of("mixed", List.of("A", "NONE"), "other", "YES"));
        assertEquals(List.of("A", "NONE"), snap.preparation().answers.get("mixed"));
        assertEquals("YES", snap.preparation().answers.get("other"));
        assertThrows(IllegalArgumentException.class, () -> PreparationSnapshots.applyAnswers(snap,
                Map.of("mixed", List.of("A", "B", "NONE"), "other", "YES")));
        PreparationSnapshots.applyAnswers(snap, Map.of("mixed", List.of("NONE"), "other", "YES"));
        assertEquals(List.of("NONE"), snap.preparation().answers.get("mixed"));
    }
}
