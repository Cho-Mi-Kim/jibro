package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.GuidanceIssues;
import org.jibro.notice.HousingModels.*;
import org.jibro.notice.QuestionAnswers;
import org.junit.jupiter.api.Test;

class AutomaticScopedGuidanceCompilerTest {
    private static final String URL = "https://apply.lh.or.kr/notice.pdf";
    private static final String ATTACHMENT_URL = "https://apply.lh.or.kr/attachment.pdf";
    private static final Instant CHECKED = Instant.parse("2026-01-01T00:00:00Z");
    private static final String VALID = """
            신청 자격
            신청자는 무주택자이어야 합니다.
            신청자는 만 19세 이상 39세 이하이어야 합니다.
            신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.
            공통 제출서류: 주민등록등본, 가족관계증명서
            대학생 제출서류: 재학증명서
            접수기간: 2026.01.01 ~ 2026.01.31
            결과 발표: 2026.02.07
            서류 마감: 2026.02.15
            """;
    private final Content original = new Content("공식 공고", new Evidence("원문", URL, "2026-01-01"),
            List.of(), List.of(), List.of(), List.of());

    @Test
    void consistentOfficialSourcesProduceNormalGuidanceWithoutDuplicateQuestionsOrDocuments() {
        var draft = draft(List.of(source(URL, VALID), source(ATTACHMENT_URL, VALID)));
        var result = AutomaticGuidanceCompiler.compile(draft, original);
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(result.issues().isEmpty());
        var content = result.content();
        assertTrue(content.reviewIssues().isEmpty());
        assertEquals(Set.of("auto-housing", "auto-age", "auto-income", "auto-document-student"),
                content.questions().stream().map(Question::id).collect(Collectors.toSet()));
        assertEquals(4, content.questions().size());
        assertEquals(3, content.documents().size());
        assertTrue(content.questions().stream().noneMatch(Question::reviewRequired));
        assertTrue(content.documents().stream().noneMatch(d -> requiresReview(content, d)));
        content.questions().forEach(QuestionAnswers::validateQuestion);
        assertDoesNotThrow(() -> GuidanceIssues.validate(content));
        assertEquals(original.summary(), content.summary());
        assertEquals(original.evidence(), content.evidence());
        assertEquals(List.of(ATTACHMENT_URL), content.attachments().stream().map(Evidence::url).toList());
        var student = document(content, "재학증명서");
        assertEquals("auto-document-student", student.conditionQuestionId());
        assertEquals(List.of("YES"), student.conditionAnswerValues());
        assertNull(student.issueUrl());
        assertEquals("공고문 · 1쪽", student.evidence().label());
    }

    @Test
    void conflictingIncomeRequiresReviewOnlyForIncomeAndPreservesOtherGuidanceAndBothSources() {
        var draft = draft(List.of(source(URL, VALID),
                source(ATTACHMENT_URL, VALID.replace("3,000,000원", "4,000,000원"))));
        var result = AutomaticGuidanceCompiler.compile(draft, original);
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(result.issues().isEmpty());
        var content = result.content();
        assertEquals(1, content.reviewIssues().size());
        var issue = content.reviewIssues().get(0);
        assertEquals("CONDITION", issue.area());
        assertEquals(List.of("auto-income"), issue.questionIds());
        assertTrue(issue.documentIds().isEmpty());
        assertEquals(Set.of(URL, ATTACHMENT_URL), issue.evidence().stream().map(Evidence::url).collect(Collectors.toSet()));
        assertTrue(issue.detail().contains("3,000,000원"));
        assertTrue(issue.detail().contains("4,000,000원"));
        var income = question(content, "auto-income");
        assertTrue(income.reviewRequired());
        assertEquals("소득 기준 확인 필요", income.title());
        assertTrue(income.needsFollowUp("YES"));
        assertTrue(income.needsFollowUp("NO"));
        assertTrue(income.needsFollowUp("UNKNOWN"));
        assertEquals(Set.of("auto-income"), content.questions().stream().filter(Question::reviewRequired)
                .map(Question::id).collect(Collectors.toSet()));
        var age = question(content, "auto-age");
        assertFalse(age.reviewRequired());
        assertFalse(age.needsFollowUp("YES"));
        assertEquals("만 19세 이상 39세 이하인가요?", age.title());
        assertEquals("신청할 수 있는 나이는 만 19세 이상, 만 39세 이하예요.", age.reason());
        assertEquals(3, content.documents().size());
        assertTrue(content.documents().stream().noneMatch(d -> requiresReview(content, d)));
        assertDoesNotThrow(() -> GuidanceIssues.validate(content));

    }

    @Test
    void differingDocumentListsKeepTheirIntersectionUsableAndMarkOnlyTheirDifferencesForReview() {
        var draft = draft(List.of(source(URL, VALID), source(ATTACHMENT_URL,
                VALID.replace("공통 제출서류: 주민등록등본, 가족관계증명서", "공통 제출서류: 주민등록등본, 주민등록초본"))));
        var result = AutomaticGuidanceCompiler.compile(draft, original);
        assertTrue(result.ready(), result.issues().toString());
        var content = result.content();
        assertEquals(Set.of("주민등록등본", "주민등록초본", "가족관계증명서", "재학증명서"),
                content.documents().stream().map(DocumentTemplate::title).collect(Collectors.toSet()));
        assertEquals(4, content.documents().size());
        assertFalse(requiresReview(content, document(content, "주민등록등본")));
        assertFalse(requiresReview(content, document(content, "재학증명서")));
        assertTrue(requiresReview(content, document(content, "가족관계증명서")));
        assertTrue(requiresReview(content, document(content, "주민등록초본")));
        assertTrue(content.questions().stream().noneMatch(Question::reviewRequired));
        assertEquals(2, content.reviewIssues().size());
        content.reviewIssues().forEach(issue -> {
            assertEquals("DOCUMENT", issue.area());
            assertTrue(issue.questionIds().isEmpty());
            assertEquals(1, issue.documentIds().size());
            assertEquals(Set.of(URL, ATTACHMENT_URL),
                    issue.evidence().stream().map(Evidence::url).collect(Collectors.toSet()));
        });
        assertEquals(Set.of(document(content, "가족관계증명서").id(), document(content, "주민등록초본").id()),
                content.reviewIssues().stream().flatMap(i -> i.documentIds().stream()).collect(Collectors.toSet()));
        assertDoesNotThrow(() -> GuidanceIssues.validate(content));
    }

    @Test
    void conflictingCommonAndConditionalApplicabilityDoesNotPoisonOtherDocuments() {
        var draft = draft(List.of(source(URL, VALID.replace("대학생 제출서류: 재학증명서", "대학생 제출서류: 주민등록등본"))));
        var result = AutomaticGuidanceCompiler.compile(draft, original);
        assertTrue(result.ready(), result.issues().toString());
        var content = result.content();
        assertEquals(2, content.documents().size());
        assertTrue(requiresReview(content, document(content, "주민등록등본")));
        assertFalse(requiresReview(content, document(content, "가족관계증명서")));
        assertEquals(1, content.reviewIssues().size());
        assertEquals(List.of(document(content, "주민등록등본").id()), content.reviewIssues().get(0).documentIds());
        assertTrue(content.questions().stream().noneMatch(Question::reviewRequired));
        assertDoesNotThrow(() -> GuidanceIssues.validate(content));
    }

    @Test
    void unknownExceptionsRemainHeldInsteadOfPublishingOnlyRecognizedRules() {
        var result = AutomaticGuidanceCompiler.compile(draft(List.of(source(URL,
                VALID + "다만, 별표 2의 경우에는 위 기준을 적용하지 않습니다."))), original);
        assertHeld(result, "UNSUPPORTED_CLAUSE");
    }

    @Test
    void failedOrUnreadSourcesRemainHeldEvenWithAnotherFullyReadSource() {
        for (var incomplete : List.of(
                new Source("첨부", ATTACHMENT_URL, null, "FAILED", "추출 실패", List.of()),
                new Source("첨부", ATTACHMENT_URL, "b".repeat(64), "EXTRACTED", "발췌만 확인됨",
                        List.of(new Excerpt("CONDITION", "무주택", "1쪽", ATTACHMENT_URL))),
                new Source("첨부", ATTACHMENT_URL, "b".repeat(64), "EXTRACTED", "완료", List.of(),
                        List.of(new Part("1쪽", VALID), new Part("2쪽", "\n\t \u00a0\u3000"))))) {
            var result = AutomaticGuidanceCompiler.compile(draft(List.of(source(URL, VALID), incomplete)), original);
            assertHeld(result, "SOURCE_CONTEXT_INCOMPLETE");
        }
    }

    @Test
    void registeredButUnfetchedAttachmentPreventsScopedPublication() {
        var content = new Content(original.summary(), original.evidence(),
                List.of(new Evidence("첨부", ATTACHMENT_URL, "2026-01-01")), List.of(), List.of(), List.of());
        var result = AutomaticGuidanceCompiler.compile(draft(List.of(source(URL, VALID))), content);
        assertHeld(result, "SOURCE_COVERAGE_INCOMPLETE");
    }

    @Test
    void conflictEvidenceBeyondSupportedBoundsIsHeldRatherThanTruncated() {
        var sources = IntStream.range(0, 11).mapToObj(index -> source(index == 0 ? URL :
                        "https://apply.lh.or.kr/attachment-" + index + ".pdf",
                VALID.replace("3,000,000원", (index + 1) + ",000,000원"))).toList();
        assertHeld(AutomaticGuidanceCompiler.compile(draft(sources), original), "SCOPED_CONFLICT_LIMIT");
    }

    private static Question question(Content content, String id) {
        return content.questions().stream().filter(q -> q.id().equals(id)).findFirst().orElseThrow();
    }

    private static DocumentTemplate document(Content content, String title) {
        return content.documents().stream().filter(d -> d.title().equals(title)).findFirst().orElseThrow();
    }

    private static boolean requiresReview(Content content, DocumentTemplate document) {
        return GuidanceIssues.documentRequiresReview(content.reviewIssues(), document.id(), document.conditionQuestionId());
    }

    private static void assertHeld(AutomaticGuidanceCompiler.Result result, String issue) {
        assertFalse(result.ready());
        assertTrue(result.issues().contains(issue), result.issues().toString());
        assertNull(result.content());
    }

    private static Draft draft(List<Source> sources) {
        return new Draft("run", "notice", 1, "READY", CHECKED, "완료", sources);
    }

    private static Source source(String url, String text) {
        return new Source("공고문", url, "a".repeat(64), "EXTRACTED", "완료", List.of(), List.of(new Part("1쪽", text)));
    }
}
