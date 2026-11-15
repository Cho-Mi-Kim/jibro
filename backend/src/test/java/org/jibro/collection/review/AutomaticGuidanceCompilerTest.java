package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import org.jibro.notice.QuestionAnswers;
import org.jibro.preparation.PreparationRules;
import org.junit.jupiter.api.Test;

class AutomaticGuidanceCompilerTest {
    private static final String URL = "https://apply.lh.or.kr/notice.pdf";
    private static final Instant CHECKED = Instant.parse("2026-01-01T00:00:00Z");
    private static final String VALID = """
            신청 자격
            신청자는 무주택자이어야 합니다.
            신청자는 미혼이어야 합니다.
            신청자는 만 19세 이상 39세 이하이어야 합니다.
            신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.
            신청자의 총자산은 200,000,000원 이하이어야 합니다.
            신청자의 자동차 가액은 30,000,000원 이하이어야 합니다.
            공통 제출서류: 주민등록등본, 가족관계증명서
            대학생 제출서류: 재학증명서
            접수기간: 2026.01.01 ~ 2026.01.31
            결과 발표: 2026.02.07
            서류 마감: 2026.02.15
            """;
    private final Content original = new Content("공식 공고", new Evidence("원문", URL, "2026-01-01"), List.of(), List.of(), List.of(), List.of());

    @Test void compilesExplicitCompleteRulesWithSourceLocationsAndConditionalDocuments() {
        var result = compile(VALID);
        assertTrue(result.ready(), result.issues().toString());
        var content = result.content();
        assertEquals(7, content.questions().size());
        assertEquals(3, content.documents().size());
        assertEquals(4, content.schedules().size());
        content.questions().forEach(QuestionAnswers::validateQuestion);
        assertEquals("공고문 · 1쪽", content.questions().get(0).evidence().label());
        assertEquals("2026-01-31", content.schedules().get(1).date());
        var student = content.documents().stream().filter(d -> d.title().equals("재학증명서")).findFirst().orElseThrow();
        assertEquals("auto-document-student", student.conditionQuestionId());
        assertEquals("CONDITIONAL", PreparationRules.necessity(student.conditionQuestionId(), student.conditionAnswerValues(), java.util.Map.of(student.conditionQuestionId(), "YES")));
        assertEquals("NOT_APPLICABLE", PreparationRules.necessity(student.conditionQuestionId(), student.conditionAnswerValues(), java.util.Map.of(student.conditionQuestionId(), "NO")));
    }

    @Test void incomeThresholdIsAnEvidenceBoundSelfCheckRatherThanAnInputMaximum() {
        var income = compile(VALID).content().questions().stream().filter(q -> q.id().equals("auto-income")).findFirst().orElseThrow();
        assertEquals("SINGLE", income.inputKind());
        assertNull(income.constraints());
        assertTrue(income.needsFollowUp("NO"));
        assertTrue(income.needsFollowUp("UNKNOWN"));
        assertFalse(income.needsFollowUp("YES"));
    }

    @Test void rejectsExceptionEvenWhenItHasNoClassifierKeyword() {
        var result = compile(VALID + "다만, 별표 2의 경우에는 위 기준을 적용하지 않습니다.");
        assertFalse(result.ready());
        assertTrue(result.issues().contains("UNSUPPORTED_CLAUSE"));
        assertNull(result.content());
    }

    @Test void rejectsMixedHouseholdScopeRatherThanApplyingPersonalThreshold() {
        assertFalse(compile(VALID.replace("신청자의 월평균 소득", "신청자와 부모의 월평균 소득")).ready());
    }

    @Test void rejectsAmbiguousTableAndDisconnectedNumbers() {
        assertFalse(compile(VALID.replace("신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.", "소득 기준\n1인 3,000,000\n2인 4,000,000")).ready());
    }

    @Test void rejectsUnknownDocumentAndDoesNotInventIssuerUrl() {
        assertFalse(compile(VALID.replace("재학증명서", "별지 제3호 해당 서류")).ready());
        assertTrue(compile(VALID).content().documents().stream().allMatch(d -> d.issueUrl() == null));
    }

    @Test void rejectsDifferentRulesAcrossOfficialSources() {
        var first = source(URL, VALID);
        var second = source("https://apply.lh.or.kr/attachment.pdf", VALID.replace("3,000,000원", "4,000,000원"));
        var result = AutomaticGuidanceCompiler.compile(draft(List.of(first, second)), original);
        assertFalse(result.ready());
        assertTrue(result.issues().contains("CONFLICTING_RULE"));
    }

    @Test void identicalRulesAcrossSourcesAreDeduplicated() {
        var result = AutomaticGuidanceCompiler.compile(draft(List.of(source(URL, VALID), source("https://apply.lh.or.kr/attachment.pdf", VALID))), original);
        assertTrue(result.ready(), result.issues().toString());
        assertEquals(7, result.content().questions().size());
        assertEquals(1, result.content().attachments().size());
    }

    @Test void rejectsIncompleteExtractionEvenWithSuccessfulExcerpt() {
        var incomplete = new Source("공고문", URL, "a".repeat(64), "EXTRACTED", "발췌", List.of(new Excerpt("CONDITION", "무주택", "1쪽", URL)));
        var result = AutomaticGuidanceCompiler.compile(draft(List.of(incomplete)), original);
        assertFalse(result.ready());
        assertTrue(result.issues().contains("SOURCE_CONTEXT_INCOMPLETE"));
    }

    @Test void rejectsTruncationAndPartialSourceWithoutPublishingSubset() {
        assertTrue(compile(VALID + "[발췌 길이 제한]").issues().contains("SOURCE_TRUNCATED"));
        var failed = new Source("첨부", URL, null, "FAILED", "추출 실패", List.of());
        assertFalse(AutomaticGuidanceCompiler.compile(draft(List.of(source(URL, VALID), failed)), original).ready());
    }

    @Test void rejectsInvalidOrBackwardDates() {
        assertFalse(compile(VALID.replace("2026.01.31", "2026.02.30")).ready());
        assertFalse(compile(VALID.replace("2026.01.01 ~ 2026.01.31", "2026.01.31 ~ 2026.01.01")).ready());
        assertTrue(compile(VALID.replace("2026.02.07", "2026.01.15")).issues().contains("SCHEDULE_ORDER_CONFLICT"));
    }

    @Test void rejectsDocumentListedAsBothUnconditionalAndConditional() {
        var result = compile(VALID.replace("대학생 제출서류: 재학증명서", "대학생 제출서류: 주민등록등본"));
        assertFalse(result.ready());
        assertTrue(result.issues().contains("DOCUMENT_APPLICABILITY_CONFLICT"));
    }

    @Test void rejectsMissingQuestionsDocumentsAndApplicationPeriod() {
        assertFalse(compile("신청자는 무주택자이어야 합니다.").ready());
        assertFalse(compile(VALID.replace("공통 제출서류: 주민등록등본, 가족관계증명서", "").replace("대학생 제출서류: 재학증명서", "")).ready());
        assertFalse(compile(VALID.replace("접수기간: 2026.01.01 ~ 2026.01.31", "")).ready());
    }

    @Test void rejectsSpoofedOfficialHostOrMissingHashAndSourceLocation() {
        assertFalse(AutomaticGuidanceCompiler.compile(draft(List.of(source("https://apply.lh.or.kr.evil.example/doc", VALID))), original).ready());
        var missing = new Source("공고문", URL, "", "EXTRACTED", "완료", List.of(), List.of(new Part("1쪽", VALID)));
        assertFalse(AutomaticGuidanceCompiler.compile(draft(List.of(missing)), original).ready());
        var location = new Source("공고문", URL, "a".repeat(64), "EXTRACTED", "완료", List.of(), List.of(new Part("", VALID)));
        assertFalse(AutomaticGuidanceCompiler.compile(draft(List.of(location)), original).ready());
    }

    @Test void refusesToPublishWhenARegisteredAttachmentHasNotBeenRead() {
        var content = new Content(original.summary(), original.evidence(), List.of(new Evidence("추가 공고문", "https://apply.lh.or.kr/missing.pdf", "2026-01-01")), List.of(), List.of(), List.of());
        var result = AutomaticGuidanceCompiler.compile(draft(List.of(source(URL, VALID))), content);
        assertFalse(result.ready());
        assertTrue(result.issues().contains("SOURCE_COVERAGE_INCOMPLETE"));
    }

    @Test void rejectsHybridDocumentWithBlankTextPageEvenWhenOtherPagesAreFullySupported() {
        for (String blank : List.of("", "\n\t \u00a0\u3000")) {
            var hybrid = new Source("공고문", URL, "a".repeat(64), "EXTRACTED", "완료", List.of(),
                    List.of(new Part("1쪽", VALID), new Part("2쪽", blank)));
            var result = AutomaticGuidanceCompiler.compile(draft(List.of(hybrid)), original);
            assertFalse(result.ready());
            assertTrue(result.issues().contains("SOURCE_CONTEXT_INCOMPLETE"));
            assertNull(result.content());
        }
    }

    @Test void rejectsContextBeyondParserBoundsRatherThanTreatingItAsComplete() {
        var tooManyParts = new Source("공고문", URL, "a".repeat(64), "EXTRACTED", "완료", List.of(),
                java.util.stream.IntStream.range(0, 201).mapToObj(page -> new Part((page + 1) + "쪽", VALID)).toList());
        assertTrue(AutomaticGuidanceCompiler.compile(draft(List.of(tooManyParts)), original).issues().contains("SOURCE_LIMIT_EXCEEDED"));
        var tooManyChars = new Source("공고문", URL, "a".repeat(64), "EXTRACTED", "완료", List.of(),
                List.of(new Part("1쪽", "신청 자격\n".repeat(166_667))));
        assertTrue(AutomaticGuidanceCompiler.compile(draft(List.of(tooManyChars)), original).issues().contains("SOURCE_LIMIT_EXCEEDED"));
        assertTrue(AutomaticGuidanceCompiler.compile(draft(java.util.Collections.nCopies(52, source(URL, VALID))), original)
                .issues().contains("SOURCE_LIMIT_EXCEEDED"));
    }

    @Test void fullContextCanSupportReviewEvenWhenExcerptPreviewIsCapped() {
        var preview = java.util.Collections.nCopies(90, new Excerpt("CONDITION", "미리 보기", "1쪽", URL));
        var source = new Source("공고문", URL, "a".repeat(64), "EXTRACTED", "완료", preview, List.of(new Part("1쪽", VALID)));
        assertTrue(AutomaticGuidanceCompiler.compile(draft(List.of(source)), original).ready());
    }

    private AutomaticGuidanceCompiler.Result compile(String text) {
        return AutomaticGuidanceCompiler.compile(draft(List.of(source(URL, text))), original);
    }

    private Draft draft(List<Source> sources) {
        return new Draft("run", "notice", 1, "READY", CHECKED, "완료", sources);
    }

    private Source source(String url, String text) {
        return new Source("공고문", url, "a".repeat(64), "EXTRACTED", "완료", List.of(), List.of(new Part("1쪽", text)));
    }
}
