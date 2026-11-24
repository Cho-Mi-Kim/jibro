package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import org.jibro.preparation.PreparationRules;
import org.jibro.preparation.PreparationSnapshots;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.ObjectMapper;

class AnswerDependenciesTest {
    private final ObjectMapper json = new ObjectMapper();

    private Content fixture() throws Exception {
        var node = json.readTree(Files.readString(Path.of("../data/contracts/answer-dependencies.json")));
        var questions = Arrays.asList(json.treeToValue(node.get("questions"), Question[].class));
        var documents = Arrays.asList(json.treeToValue(node.get("documents"), DocumentTemplate[].class));
        return new Content("검수된 조건", questions.get(0).evidence(), List.of(), questions, documents, List.of());
    }

    private Snapshot snapshot(Content content) {
        var p = new Preparation();
        p.id = "p";
        p.guideState = "AVAILABLE";
        p.questions = content.questions();
        p.reviewIssues = content.reviewIssues();
        var d = new Document();
        d.id = "family";
        d.title = "가족관계증명서";
        d.necessity = "UNKNOWN";
        p.documents.add(d);
        var template = content.documents().get(0);
        return new Snapshot(p, Map.of(d.id, template.conditionQuestionId()), Map.of(d.id, template.conditionAnswerValues()));
    }

    @TestFactory
    Stream<DynamicTest> sameLiteralDependencyCasesAsStaticPreview() throws Exception {
        Content content = fixture();
        AnswerDependencies.validate(content);
        var node = json.readTree(Files.readString(Path.of("../data/contracts/answer-dependencies.json")));
        var tests = new ArrayList<DynamicTest>();
        for (var scenario : node.get("cases")) {
            tests.add(DynamicTest.dynamicTest(scenario.get("name").asString(), () -> {
                var snap = snapshot(content);
                @SuppressWarnings("unchecked") Map<String, Object> answers = json.treeToValue(scenario.get("answers"), LinkedHashMap.class);
                PreparationSnapshots.applyAnswers(snap, answers);
                var p = snap.preparation();
                assertEquals(scenario.get("answers"), json.valueToTree(p.answers), "Never silently replace a supplied answer");
                assertEquals(scenario.get("followUpQuestionIds"), json.valueToTree(p.questions.stream()
                        .filter(q -> q.needsFollowUp(p.answers.get(q.id()), p.answers, p.questions)).map(Question::id).toList()));
                assertEquals(scenario.get("conflictQuestionIds"), json.valueToTree(p.questions.stream()
                        .filter(q -> !AnswerDependencies.conflictMessages(q, p.answers.get(q.id()), p.answers, p.questions).isEmpty())
                        .map(Question::id).toList()));
                assertEquals(scenario.get("necessity").get("family").asString(), p.documents.get(0).necessity);
            }));
        }
        return tests.stream();
    }

    @Test
    void aConflictCountsAsUnfinishedAndItsMessageDisappearsAfterCorrection() throws Exception {
        var snap = snapshot(fixture());
        PreparationSnapshots.applyAnswers(snap, Map.of("category", "YOUTH", "applies-family", "NONE"));
        var p = snap.preparation();
        p.documents.get(0).state = DocumentState.READY;
        var result = PreparationRules.assess(p);
        assertEquals(65, result.progress());
        assertEquals(List.of(p.questions.get(1).title(), p.questions.get(1).answerDependencies().get(0).message()), result.warnings());
        assertEquals("서로 다른 답변을 다시 확인해 주세요.", result.nextAction().reason());
        assertEquals("/preparations/p/conditions/edit", result.nextAction().path());
        PreparationSnapshots.applyAnswers(snap, Map.of("category", "YOUTH", "applies-family", "REQUIRED_TYPE"));
        assertTrue(PreparationRules.assess(p).warnings().isEmpty());
        assertEquals(DocumentState.READY, p.documents.get(0).state);
    }

    @Test
    void changingAFormerlyExcludedDocumentToRequiredPreservesTheNoteAndRechecksCompletion() throws Exception {
        var snap = snapshot(fixture());
        PreparationSnapshots.applyAnswers(snap, Map.of("category", "ELDER", "applies-family", "NONE"));
        var d = snap.preparation().documents.get(0);
        d.note = "남겨 둔 메모";
        d.state = DocumentState.READY;
        PreparationSnapshots.applyAnswers(snap, Map.of("category", "YOUTH", "applies-family", "NONE"));
        assertEquals("CONDITIONAL", d.necessity);
        assertEquals(DocumentState.RECHECK, d.state);
        assertEquals("남겨 둔 메모", d.note);
        assertEquals("서류 다시 확인하기", PreparationRules.assess(snap.preparation()).nextAction().label());
    }

    @Test
    void requiredDocumentsRemainRequiredAndUnknownTriggersDoNotEraseKnownRequirements() throws Exception {
        var questions = fixture().questions();
        assertEquals("REQUIRED", AnswerDependencies.documentNecessity("REQUIRED", "family", questions, Map.of()));
        assertEquals("CONDITIONAL", AnswerDependencies.documentNecessity("CONDITIONAL", "family", questions, Map.of()));
        assertEquals("UNKNOWN", AnswerDependencies.documentNecessity("NOT_APPLICABLE", "family", questions, Map.of()));
    }

    @Test
    void officialUncertaintyPropagatesToTheDependentQuestionAndDocumentWithoutInventingAnAnswerConflict() throws Exception {
        var content = fixture();
        var issue = new ReviewIssue("source", "CONDITION", "신청 유형 확인", "공식 자료가 달라요.",
                List.of("category"), List.of(), List.of(content.evidence()));
        var reviewed = new Content(content.summary(), content.evidence(), content.attachments(), content.questions(),
                content.documents(), content.schedules(), List.of(issue));
        assertTrue(reviewed.questions().get(1).reviewRequired());
        assertTrue(reviewed.reviewIssues().get(0).documentIds().contains("family"));
        assertEquals(content.questions().get(1).answerDependencies(), reviewed.questions().get(1).answerDependencies());
        GuidanceIssues.validate(reviewed);
        var snap = snapshot(reviewed);
        PreparationSnapshots.applyAnswers(snap, Map.of("category", "YOUTH", "applies-family", "NONE"));
        assertEquals("UNKNOWN", snap.preparation().documents.get(0).necessity);
        assertTrue(snap.preparation().documents.get(0).reviewRequired);
        assertTrue(AnswerDependencies.conflictMessages(snap.preparation().questions, snap.preparation().answers).isEmpty());
        assertEquals("UNKNOWN", AnswerDependencies.documentNecessity("REQUIRED", "family", reviewed.questions(), Map.of("category", "YOUTH")));
    }

    @Test
    void multipleChoiceRelationshipsUseSelectedValuesWithoutChangingThem() throws Exception {
        var c = fixture();
        var parent = c.questions().get(0);
        var child = c.questions().get(1);
        var questions = List.of(new Question(parent.id(), parent.title(), parent.reason(), parent.evidence(), parent.options(), "MULTIPLE", null),
                new Question(child.id(), child.title(), child.reason(), child.evidence(), child.options(), "MULTIPLE", null, false, child.answerDependencies()));
        var answers = Map.<String, Object>of("category", List.of("YOUTH", "ELDER"), "applies-family", List.of("NONE"));
        assertEquals("CONDITIONAL", AnswerDependencies.documentNecessity("NOT_APPLICABLE", "family", questions, answers));
        assertTrue(questions.get(1).needsFollowUp(answers.get("applies-family"), answers, questions));
    }

    @Test
    void olderQuestionsDoNotSerializeAnEmptyNewFieldOrReinterpretTheirAnswers() throws Exception {
        var c = fixture();
        var child = c.questions().get(1);
        var old = new Question(child.id(), child.title(), child.reason(), child.evidence(), child.options(), child.inputKind(), child.constraints(), false);
        assertFalse(json.writeValueAsString(old).contains("answerDependencies"));
        assertEquals(List.of(), json.readValue(json.writeValueAsString(old), Question.class).answerDependencies());
        assertFalse(old.needsFollowUp("NONE", Map.of("category", "YOUTH"), List.of(c.questions().get(0), old)));
        assertEquals("NOT_APPLICABLE", AnswerDependencies.documentNecessity("NOT_APPLICABLE", "family", List.of(c.questions().get(0), old), Map.of("category", "YOUTH")));
    }

    @Test
    void rejectsMissingReferencesUnknownValuesSelfReferencesNoOpAndOversizedRules() throws Exception {
        var c = fixture();
        var original = c.questions().get(1).answerDependencies().get(0);
        var bad = Arrays.asList(
                new AnswerDependency("missing", original.answerValues(), original.conflictingValues(), original.requiredDocumentIds(), original.message()),
                new AnswerDependency("applies-family", List.of("NONE"), original.conflictingValues(), original.requiredDocumentIds(), original.message()),
                new AnswerDependency("category", List.of("MISSING"), original.conflictingValues(), original.requiredDocumentIds(), original.message()),
                new AnswerDependency("category", List.of("UNKNOWN"), original.conflictingValues(), original.requiredDocumentIds(), original.message()),
                new AnswerDependency("category", original.answerValues(), List.of("UNKNOWN"), original.requiredDocumentIds(), original.message()),
                new AnswerDependency("category", original.answerValues(), List.of("MISSING"), original.requiredDocumentIds(), original.message()),
                new AnswerDependency("category", original.answerValues(), original.conflictingValues(), List.of("missing"), original.message()),
                new AnswerDependency("category", original.answerValues(), List.of(), List.of(), original.message()),
                new AnswerDependency("category", original.answerValues(), original.conflictingValues(), original.requiredDocumentIds(), " "),
                new AnswerDependency("category", original.answerValues(), original.conflictingValues(), original.requiredDocumentIds(), "x".repeat(501)),
                new AnswerDependency("category", List.of("YOUTH", "YOUTH"), original.conflictingValues(), original.requiredDocumentIds(), original.message()),
                new AnswerDependency("category", original.answerValues(), null, original.requiredDocumentIds(), original.message()));
        for (var rule : bad)
            assertThrows(IllegalArgumentException.class, () -> AnswerDependencies.validate(withRules(c, List.of(rule))));
        assertThrows(IllegalArgumentException.class, () -> AnswerDependencies.validate(withRules(c, Collections.nCopies(11, original))));
        var numeric = new Question("category", "숫자", "입력", c.evidence(), null, "NUMBER", null);
        var numericContent = new Content(c.summary(), c.evidence(), List.of(), List.of(numeric, c.questions().get(1)), c.documents(), List.of());
        assertThrows(IllegalArgumentException.class, () -> AnswerDependencies.validate(numericContent));
    }

    @Test
    void explicitApplicabilitySurvivesJsonAndOfficialReviewAndRejectsInvalidParents() throws Exception {
        var c = fixture();
        var parent = c.questions().get(0);
        var q = c.questions().get(1);
        var applicability = new QuestionApplicability(parent.id(), List.of("YOUTH"));
        var scoped = new Question(q.id(), q.title(), q.reason(), q.evidence(), q.options(), q.inputKind(), q.constraints(),
                false, List.of(), applicability);
        var content = new Content(c.summary(), c.evidence(), List.of(), List.of(parent, scoped), c.documents(), List.of());
        AnswerDependencies.validate(content);
        assertEquals(scoped, json.readValue(json.writeValueAsString(scoped), Question.class));
        assertFalse(json.writeValueAsString(parent).contains("applicability"));
        var issue = new ReviewIssue("scope", "CONDITION", "유형 확인", "공식 자료 확인", List.of(parent.id()), List.of(), List.of(c.evidence()));
        var reviewed = new Content(c.summary(), c.evidence(), List.of(), content.questions(), c.documents(), List.of(), List.of(issue));
        assertEquals(applicability, reviewed.questions().get(1).applicability());
        assertTrue(reviewed.questions().get(1).reviewRequired());
        assertTrue(reviewed.reviewIssues().get(0).documentIds().contains("family"));
        for (var invalid : List.of(
                new QuestionApplicability("missing", List.of("YOUTH")),
                new QuestionApplicability(q.id(), List.of("NONE")),
                new QuestionApplicability(parent.id(), List.of()),
                new QuestionApplicability(parent.id(), List.of("UNKNOWN")),
                new QuestionApplicability(parent.id(), List.of("MISSING")),
                new QuestionApplicability(parent.id(), List.of("YOUTH", "YOUTH")))) {
            var bad = new Question(q.id(), q.title(), q.reason(), q.evidence(), q.options(), q.inputKind(), null, false, List.of(), invalid);
            assertEquals("INVALID_QUESTION_APPLICABILITY", assertThrows(IllegalArgumentException.class, () ->
                    AnswerDependencies.validate(new Content(c.summary(), c.evidence(), List.of(), List.of(parent, bad), c.documents(), List.of()))).getMessage());
        }
        var scopedParent = new Question(parent.id(), parent.title(), parent.reason(), parent.evidence(), parent.options(),
                "SINGLE", null, false, List.of(), new QuestionApplicability(scoped.id(), List.of("NONE")));
        assertThrows(IllegalArgumentException.class, () -> AnswerDependencies.validate(
                new Content(c.summary(), c.evidence(), List.of(), List.of(scopedParent, scoped), c.documents(), List.of())));
    }

    private Content withRules(Content c, List<AnswerDependency> rules) {
        var q = c.questions().get(1);
        var child = new Question(q.id(), q.title(), q.reason(), q.evidence(), q.options(), q.inputKind(), q.constraints(), q.reviewRequired(), rules);
        return new Content(c.summary(), c.evidence(), c.attachments(), List.of(c.questions().get(0), child), c.documents(), c.schedules());
    }
}
