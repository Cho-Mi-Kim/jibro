package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jibro.notice.GuidanceIssues;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class ScopedDocumentConflictsTest {
    private static final String FIRST = "공통 제출서류: 가족관계증명서, 재학증명서";
    private static final String SECOND = "공통 제출서류: 가족관계증명서, 졸업증명서";
    private static final Evidence ORIGINAL = evidence("notice");

    @Test
    void sharedDocumentStaysNormalAndOnlyDifferentNamesReceiveAllOriginalClaims() {
        var conflicts = conflicts(FIRST, SECOND);
        var documents = documents("가족관계증명서", "재학증명서", "졸업증명서");
        var questions = questions(documents, FIRST, SECOND);
        var result = conflicts.bind(questions, documents);
        assertTrue(result.blockers().isEmpty());
        assertTrue(conflicts.detected());
        assertEquals(Set.of("common"), conflicts.conflictingKeys());
        assertEquals(2, result.reviewIssues().size());
        var family = documents.get(0);
        assertTrue(result.reviewIssues().stream().noneMatch(i -> i.documentIds().contains(family.id())));
        assertTrue(result.reviewIssues().stream().noneMatch(i -> i.questionIds().contains(family.conditionQuestionId())));
        for (var issue : result.reviewIssues()) {
            assertEquals("DOCUMENT", issue.area());
            assertEquals(1, issue.documentIds().size());
            var affected = documents.stream().filter(d -> issue.documentIds().contains(d.id())).findFirst().orElseThrow();
            assertTrue(issue.questionIds().contains(affected.conditionQuestionId()));
            assertTrue(issue.questionIds().containsAll(List.of("e-section-1", "e-section-2")));
            assertFalse(issue.questionIds().contains("e-section-normal"));
            assertTrue(issue.detail().contains(FIRST));
            assertTrue(issue.detail().contains(SECOND));
            assertEquals(List.of(evidence("first"), evidence("second")), issue.evidence());
        }
        var content = new Content("안내", ORIGINAL, List.of(), questions, documents, List.of(), result.reviewIssues());
        assertDoesNotThrow(() -> GuidanceIssues.validate(content));
        var normal = content.questions().stream().filter(q -> q.id().equals(family.conditionQuestionId())).findFirst().orElseThrow();
        assertFalse(normal.reviewRequired());
        assertFalse(normal.needsFollowUp("YES"));
        for (var document : documents.subList(1, documents.size())) {
            var affected = content.questions().stream().filter(q -> q.id().equals(document.conditionQuestionId())).findFirst().orElseThrow();
            assertTrue(affected.reviewRequired());
            assertTrue(affected.needsFollowUp("YES"));
        }
    }

    @Test
    void equivalentListOrderAndRepeatedSourcesDoNotCreateAConflict() {
        var conflicts = conflicts(FIRST, "공통 제출서류: 재학증명서, 가족관계증명서");
        conflicts.add(GuidanceRuleGrammar.parse(FIRST), FIRST, evidence("copy"));
        var documents = documents("가족관계증명서", "재학증명서");
        var result = conflicts.bind(questions(documents, FIRST), documents);
        assertFalse(conflicts.detected());
        assertTrue(conflicts.conflictingKeys().isEmpty());
        assertTrue(result.reviewIssues().isEmpty());
        assertTrue(result.blockers().isEmpty());
    }

    @Test
    void missingDocumentCandidateBlocksInsteadOfDiscardingAnUnmappedDifference() {
        var documents = documents("가족관계증명서", "재학증명서");
        var result = conflicts(FIRST, SECOND).bind(questions(documents, FIRST, SECOND), documents);
        assertEquals(List.of("EVIDENCE_DOCUMENT_CONFLICT_SCOPE_UNMAPPED"), result.blockers());
        assertEquals(1, result.reviewIssues().size());
        assertEquals(List.of(documents.get(1).id()), result.reviewIssues().get(0).documentIds());
    }

    @Test
    void absentApplicabilityQuestionOrAmbiguousCandidateTitleCannotBeBound() {
        var documents = documents("가족관계증명서", "재학증명서", "졸업증명서");
        var missingQuestion = questions(documents, FIRST, SECOND).stream()
                .filter(q -> !q.id().equals(documents.get(1).conditionQuestionId())).toList();
        assertTrue(conflicts(FIRST, SECOND).bind(missingQuestion, documents).blockers()
                .contains("EVIDENCE_DOCUMENT_CONFLICT_SCOPE_UNMAPPED"));
        var ambiguous = new ArrayList<>(documents);
        var student = documents.get(1);
        ambiguous.add(new DocumentTemplate("duplicate-student", student.title(), student.conditionQuestionId(), student.method(),
                student.issuer(), student.issueUrl(), student.evidence(), student.conditionAnswerValues()));
        assertTrue(conflicts(FIRST, SECOND).bind(questions(documents, FIRST, SECOND), ambiguous).blockers()
                .contains("EVIDENCE_DOCUMENT_CONFLICT_SCOPE_UNMAPPED"));
    }

    @Test
    void multipleApplicabilityScopesDoNotSelectOneScopeAsTheAnswer() {
        String common = "공통 제출서류: 가족관계증명서";
        String student = "대학생 제출서류: 가족관계증명서";
        var conflicts = conflicts(common, student);
        var documents = documents("가족관계증명서");
        var result = conflicts.bind(questions(documents, common, student), documents);
        assertTrue(conflicts.detected());
        assertTrue(conflicts.conflictingKeys().isEmpty(), "different scopes alone do not prove unrelated source differences were reconciled");
        assertTrue(result.blockers().isEmpty());
        assertEquals(1, result.reviewIssues().size());
        assertTrue(result.reviewIssues().get(0).detail().contains(common));
        assertTrue(result.reviewIssues().get(0).detail().contains(student));
    }

    @Test
    void issueIncludesAbsenceClaimsFromEveryRelatedScope() {
        var conflicts = conflicts("공통 제출서류: 가족관계증명서, 재학증명서", "공통 제출서류: 가족관계증명서");
        String student = "대학생 제출서류: 재학증명서";
        conflicts.add(GuidanceRuleGrammar.parse(student), student, evidence("student"));
        var documents = documents("가족관계증명서", "재학증명서");
        var result = conflicts.bind(questions(documents, FIRST, "공통 제출서류: 가족관계증명서", student), documents);
        assertTrue(result.blockers().isEmpty());
        assertEquals(1, result.reviewIssues().size());
        var issue = result.reviewIssues().get(0);
        assertEquals(3, issue.evidence().size());
        assertTrue(issue.detail().contains("공통 제출서류: 가족관계증명서\n"));
        assertTrue(issue.detail().contains(student));
        assertFalse(issue.documentIds().contains(documents.get(0).id()));
    }

    @Test
    void evidenceLimitDoesNotSilentlyDropAnyOfficialSource() {
        var conflicts = new ScopedDocumentConflicts();
        for (int i = 0; i < 11; i++) {
            String statement = i % 2 == 0 ? FIRST : "공통 제출서류: 가족관계증명서";
            conflicts.add(GuidanceRuleGrammar.parse(statement), statement, evidence("source-" + i));
        }
        var documents = documents("가족관계증명서", "재학증명서");
        var result = conflicts.bind(questions(documents, FIRST), documents);
        assertEquals(List.of("EVIDENCE_DOCUMENT_CONFLICT_LIMIT"), result.blockers());
        assertTrue(result.reviewIssues().isEmpty());
    }

    @Test
    void documentIssueIdentityDoesNotDependOnSourceOrCandidateOrder() {
        var documents = documents("가족관계증명서", "재학증명서", "졸업증명서");
        var first = conflicts(FIRST, SECOND).bind(questions(documents, FIRST, SECOND), documents);
        var reversed = new ArrayList<>(documents);
        java.util.Collections.reverse(reversed);
        var reverse = conflicts(SECOND, FIRST).bind(questions(documents, SECOND, FIRST), reversed);
        assertEquals(first.reviewIssues().stream().map(ReviewIssue::id).sorted().toList(),
                reverse.reviewIssues().stream().map(ReviewIssue::id).sorted().toList());
    }

    private static ScopedDocumentConflicts conflicts(String first, String second) {
        var result = new ScopedDocumentConflicts();
        result.add(GuidanceRuleGrammar.parse(first), first, evidence("first"));
        result.add(GuidanceRuleGrammar.parse(second), second, evidence("second"));
        return result;
    }

    private static List<DocumentTemplate> documents(String... names) {
        return java.util.Arrays.stream(names).map(name -> {
            String suffix = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
            return new DocumentTemplate("doc-" + suffix, name, "applies-" + suffix, "공식 기준 확인", "공식 발급처", null,
                    ORIGINAL, List.of("YES"));
        }).toList();
    }

    private static List<Question> questions(List<DocumentTemplate> documents, String... statements) {
        var questions = new ArrayList<Question>();
        for (var document : documents)
            questions.add(new Question(document.conditionQuestionId(), document.title() + " 제출 대상인가요?",
                    "서류 제출 대상을 확인해 주세요.", ORIGINAL, List.of(new AnswerOption("YES", "해당해요"), new AnswerOption("NO", "해당하지 않아요"))));
        for (int i = 0; i < statements.length; i++)
            questions.add(new Question("e-section-" + (i + 1), "서류 원문 확인", statements[i], ORIGINAL));
        questions.add(new Question("e-section-normal", "발급일 확인", "발급 시점을 확인해 주세요.", ORIGINAL));
        return List.copyOf(questions);
    }

    private static Evidence evidence(String name) {
        return new Evidence(name + " · 공고문 2쪽", "https://apply.lh.or.kr/" + name + ".pdf", "2026-01-01");
    }
}
