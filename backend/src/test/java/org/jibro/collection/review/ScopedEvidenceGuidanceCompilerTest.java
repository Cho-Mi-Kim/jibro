package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Draft;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.GuidanceIssues;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.Question;
import org.jibro.notice.QuestionAnswers;
import org.junit.jupiter.api.Test;

class ScopedEvidenceGuidanceCompilerTest {
    private static final String URL = "https://apply.lh.or.kr/notice.pdf";
    private static final String OTHER = "https://apply.lh.or.kr/correction.pdf";
    private static final Instant CHECKED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Content ORIGINAL = new Content("공식 공고", new Evidence("원문", URL, "2026-01-01"),
        List.of(), List.of(), List.of(), List.of());
    private static final String AGE = "신청자는 만 19세 이상 39세 이하이어야 합니다.";
    private static final String INCOME = "신청자의 월평균 소득은 %s원 이하이어야 합니다.";

    @Test
    void isolatesOnlyConflictingIncomeAndKeepsNormalAgeAndDocumentPreparation() {
        var sources = List.of(source(URL, parts("3,000,000", "")), source(OTHER, parts("4,000,000", "")));
        var result = EvidenceGuidanceCompiler.compileScoped(draft(sources), ORIGINAL);
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(result.issues().isEmpty());
        var content = result.content();
        assertEquals(1, content.reviewIssues().size());
        var issue = content.reviewIssues().get(0);
        assertEquals("CONDITION", issue.area());
        assertTrue(issue.detail().contains(INCOME.formatted("3,000,000")));
        assertTrue(issue.detail().contains(INCOME.formatted("4,000,000")));
        assertEquals(List.of(URL, OTHER), issue.evidence().stream().map(Evidence::url).toList());
        assertTrue(issue.evidence().stream().allMatch(e -> e.checkedAt().equals("2026-01-01")));
        assertTrue(content.attachments().stream().anyMatch(e -> e.url().equals(OTHER)));

        var affected = content.questions().stream().filter(Question::reviewRequired).toList();
        assertFalse(affected.isEmpty());
        assertTrue(affected.stream().allMatch(q -> q.reason().contains("월평균 소득")));
        assertTrue(affected.stream().noneMatch(q -> q.reason().contains(AGE)), "same source section must not mark normal age as disputed");
        assertEquals(affected.stream().map(Question::id).sorted().toList(), issue.questionIds().stream().sorted().toList());
        assertTrue(affected.stream().allMatch(q -> q.needsFollowUp("YES")), "a source conflict cannot be cleared by the applicant's confirmation");
        assertTrue(content.questions().stream().anyMatch(q -> !q.reviewRequired() && q.reason().contains(AGE)));
        var document = content.documents().stream().filter(d -> d.title().equals("가족관계증명서")).findFirst().orElseThrow();
        var documentQuestion = content.questions().stream().filter(q -> q.id().equals(document.conditionQuestionId())).findFirst().orElseThrow();
        assertFalse(documentQuestion.reviewRequired());
        assertFalse(documentQuestion.needsFollowUp("YES"));
        assertTrue(issue.documentIds().isEmpty());
        assertNull(document.issueUrl());
        content.questions().forEach(QuestionAnswers::validateQuestion);
        assertDoesNotThrow(() -> GuidanceIssues.validate(content));
    }

    @Test
    void strictCompilerStillHoldsTheSameContradictoryOfficialSources() {
        var result = EvidenceGuidanceCompiler.compile(draft(List.of(source(URL, parts("3,000,000", "")),
            source(OTHER, parts("4,000,000", "")))), ORIGINAL);
        assertFalse(result.ready());
        assertNull(result.content());
        assertTrue(result.issues().contains("EVIDENCE_CONFLICTING_RULE"));
    }

    @Test
    void conflictingDocumentListsKeepSharedDocumentsAndStrictCompilerStillHolds() {
        String first = "4. 신청자격\n" + AGE + "\n8. 제출서류\n공통 제출서류: 가족관계증명서, 재학증명서";
        String second = "4. 신청자격\n" + AGE + "\n8. 제출서류\n공통 제출서류: 가족관계증명서, 졸업증명서";
        var bundle = draft(List.of(source(URL, List.of(new Part("1쪽", first))), source(OTHER, List.of(new Part("1쪽", second)))));
        var result = EvidenceGuidanceCompiler.compileScoped(bundle, ORIGINAL);
        assertTrue(result.ready(), result.issues().toString());
        assertEquals(2, result.content().reviewIssues().size());
        var family = result.content().documents().stream().filter(d -> d.title().equals("가족관계증명서")).findFirst().orElseThrow();
        assertTrue(result.content().reviewIssues().stream().noneMatch(i -> i.documentIds().contains(family.id())));
        assertTrue(result.content().questions().stream().anyMatch(q -> q.id().equals(family.conditionQuestionId()) && !q.reviewRequired()));
        assertDoesNotThrow(() -> GuidanceIssues.validate(result.content()));
        var strict = EvidenceGuidanceCompiler.compile(bundle, ORIGINAL);
        assertFalse(strict.ready());
        assertNull(strict.content());
        assertTrue(strict.issues().contains("EVIDENCE_CONFLICTING_RULE"));
    }

    @Test
    void aDocumentDependingOnConflictingIncomeCannotBePublishedAsIndependent() {
        var sources = List.of(source(URL, parts("3,000,000", "")), source(OTHER, parts("4,000,000", ""))).stream()
            .map(s -> new Source(s.label(),s.url(),s.sha256(),s.status(),s.message(),s.excerpts(),
                s.context().stream().map(p -> new Part(p.location(),p.text().replace("가족관계증명서",
                    "소득 기준에 해당하는 신청자는 소득금액증명과 가족관계증명서를 제출합니다."))).toList())).toList();
        var result = EvidenceGuidanceCompiler.compileScoped(draft(sources),ORIGINAL);
        assertFalse(result.ready());
        assertTrue(result.issues().contains("EVIDENCE_CONFLICT_DOCUMENT_DEPENDENCY_UNMAPPED"));
    }

    @Test
    void unknownProseDifferenceStillHoldsEvenWhenIncomeConflictCanBeLocated() {
        var result = EvidenceGuidanceCompiler.compileScoped(draft(List.of(
            source(URL, parts("3,000,000", "다만 부모와 세대분리한 경우 부모 소득은 제외합니다.")),
            source(OTHER, parts("4,000,000", "다만 부모와 세대분리한 경우 부모 소득은 포함합니다.")))), ORIGINAL);
        assertFalse(result.ready());
        assertNull(result.content());
        assertTrue(result.issues().contains("EVIDENCE_MULTIPLE_GOVERNING_SOURCES_REQUIRE_REVIEW"));
    }

    @Test
    void sourceDifferenceWithoutAnyRecognizedConflictCannotBecomeReady() {
        var result = EvidenceGuidanceCompiler.compileScoped(draft(List.of(
            source(URL, parts("3,000,000", "동점자는 추첨으로 선정합니다.")),
            source(OTHER, parts("3,000,000", "동점자는 거주 기간을 기준으로 선정합니다.")))), ORIGINAL);
        assertFalse(result.ready());
        assertNull(result.content());
        assertTrue(result.issues().contains("EVIDENCE_MULTIPLE_GOVERNING_SOURCES_REQUIRE_REVIEW"));
    }

    @Test
    void unresolvedReferencedPageBlocksPartialPublication() {
        String reference = "예외는 공고문 99쪽의 기준을 함께 적용합니다.";
        var result = EvidenceGuidanceCompiler.compileScoped(draft(List.of(source(URL, parts("3,000,000", reference)),
            source(OTHER, parts("4,000,000", reference)))), ORIGINAL);
        assertFalse(result.ready());
        assertNull(result.content());
        assertTrue(result.issues().contains("EVIDENCE_REFERENCED_PAGE_MISSING"));
    }

    @Test
    void registeredButUnreadAttachmentCannotBeSkippedByPartialCompilation() {
        var original = new Content("공식 공고", ORIGINAL.evidence(),
            List.of(new Evidence("추가 공고문", "https://apply.lh.or.kr/missing.pdf", "2026-01-01")), List.of(), List.of(), List.of());
        var result = EvidenceGuidanceCompiler.compileScoped(draft(List.of(source(URL, parts("3,000,000", "")),
            source(OTHER, parts("4,000,000", "")))), original);
        assertFalse(result.ready());
        assertNull(result.content());
        assertTrue(result.issues().contains("SOURCE_COVERAGE_INCOMPLETE"));
    }

    @Test
    void failedUntrustedOrTruncatedSourceDoesNotPublishTheRemainingCompleteSource() {
        var failed = new Source("정정 공고문", OTHER, null, "FAILED", "다운로드 실패", List.of(), List.of());
        var missing = EvidenceGuidanceCompiler.compileScoped(draft(List.of(source(URL, parts("3,000,000", "")), failed)), ORIGINAL);
        assertFalse(missing.ready());
        assertTrue(missing.issues().contains("EVIDENCE_SOURCE_NOT_COMPLETE"));
        var spoofed = EvidenceGuidanceCompiler.compileScoped(draft(List.of(source(URL, parts("3,000,000", "")),
            source("https://apply.lh.or.kr.example.org/correction.pdf", parts("4,000,000", "")))), ORIGINAL);
        assertFalse(spoofed.ready());
        assertTrue(spoofed.issues().contains("EVIDENCE_SOURCE_NOT_COMPLETE"));
        for (String tail : List.of("[발췌 길이 제한]", "")) {
            var incomplete = source(OTHER, List.of(new Part("1쪽", tail)));
            var result = EvidenceGuidanceCompiler.compileScoped(draft(List.of(source(URL, parts("3,000,000", "")), incomplete)), ORIGINAL);
            assertFalse(result.ready());
            assertNull(result.content());
            assertTrue(result.issues().contains("EVIDENCE_CONTEXT_INCOMPLETE"));
        }
    }

    @Test
    void incompleteThirdSourceCannotCrashReconciliationOfAnOtherwiseLocatedConflict() {
        var incomplete = source("https://apply.lh.or.kr/third.pdf", List.of(new Part("3쪽", null)));
        var result = assertDoesNotThrow(() -> EvidenceGuidanceCompiler.compileScoped(draft(List.of(
            source(URL, parts("3,000,000", "")), source(OTHER, parts("4,000,000", "")), incomplete)), ORIGINAL));
        assertFalse(result.ready());
        assertNull(result.content());
        assertTrue(result.issues().contains("EVIDENCE_CONTEXT_INCOMPLETE"));
    }

    @Test
    void identicalSourcesStayFullyAvailableAndKeepStableQuestions() {
        var bundle = draft(List.of(source(URL, parts("3,000,000", "")), source(OTHER, parts("3,000,000", ""))));
        var first = EvidenceGuidanceCompiler.compileScoped(bundle, ORIGINAL);
        var again = EvidenceGuidanceCompiler.compileScoped(bundle, ORIGINAL);
        assertTrue(first.ready(), first.issues().toString());
        assertTrue(first.content().reviewIssues().isEmpty());
        assertTrue(first.content().questions().stream().noneMatch(Question::reviewRequired));
        assertEquals(first.content().questions().stream().map(Question::id).toList(),
            again.content().questions().stream().map(Question::id).toList());
        assertEquals(first.content().questions().size(), first.content().questions().stream().map(Question::id).distinct().count());
    }

    private static List<Part> parts(String amount, String exception) {
        return List.of(new Part("1쪽", "4. 신청자격\n" + AGE + "\n" + INCOME.formatted(amount) + "\n" + exception),
            new Part("2쪽", "8. 제출서류\n가족관계증명서: 등본에서 관계를 확인할 수 있으면 생략 가능합니다.\n"
                + "주민등록표등본: 제출 대상과 추가 발급 대상은 공식 원문을 확인합니다."));
    }

    private static Draft draft(List<Source> sources) {
        return new Draft("run", "notice", 1, "READY", CHECKED, "완료", sources);
    }

    private static Source source(String url, List<Part> parts) {
        return new Source("공고문", url, "a".repeat(64), "EXTRACTED", "완료", List.of(), parts);
    }
}
