package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OcrProvenance;
import org.jibro.collection.review.local.LocalRuleInterpreter;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class OcrReviewBoundaryTest {
    private static final String URL = "https://apply.lh.or.kr/notice.pdf";
    private static final Instant CHECKED = Instant.parse("2026-01-01T00:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
    private static final String LOCATION = "공고문 1쪽";
    private static final String OCR_LOCATION = LOCATION + " " + OcrProvenance.MARKER;
    private static final String RULES = """
            신청 자격
            신청자는 무주택자이어야 합니다.
            신청자는 만 19세 이상 39세 이하이어야 합니다.
            공통 제출서류: 주민등록등본, 가족관계증명서
            접수기간: 2026.01.01 ~ 2026.01.31
            결과 발표: 2026.02.07
            서류 마감: 2026.02.15
            """;
    private static final String SECTIONS = """
            4. 신청자격
            신청자는 무주택자이어야 합니다.
            5. 제출서류
            주민등록표등본: 신청자와 세대원의 관계를 확인합니다.
            6. 모집일정
            접수기간: 2026.01.01 ~ 2026.01.31
            """;
    private static final Content ORIGINAL = new Content("공식 공고", new Evidence("공고 원문", URL, DAY.toString()),
            List.of(), List.of(), List.of(), List.of());

    @Test
    void nativeExactRulesStillProducePublishableGuidanceAndOfficialSchedules() {
        var draft = draft(source("모집공고.pdf", URL, LOCATION, RULES));
        assertTrue(SourceCoveragePolicy.assessScoped(draft, ORIGINAL).ready());
        var result = AutomaticGuidanceCompiler.compile(draft, ORIGINAL);
        assertTrue(result.ready(), result.issues().toString());
        assertEquals(2, result.content().questions().size());
        assertEquals(2, result.content().documents().size());
        var review = GuidanceReviewCompiler.assess(draft, ORIGINAL);
        assertTrue(review.ready(), review.issues().toString());
        // Scoped guidance receives the independently verified schedules at this final boundary.
        assertEquals(4, review.candidate().schedules().size());
        assertEquals(DAY, review.startDate());
        assertEquals(LocalDate.of(2026, 1, 31), review.endDate());
    }

    @Test
    void evenExactRecognizedOcrRulesCannotBecomeAnApprovedGuide() {
        var draft = draft(source("모집공고.pdf", URL, OCR_LOCATION, RULES));
        assertTrue(SourceCoveragePolicy.assess(draft, ORIGINAL).issues().contains(OcrProvenance.REVIEW_REQUIRED));
        assertTrue(SourceCoveragePolicy.assessScoped(draft, ORIGINAL).issues().contains(OcrProvenance.REVIEW_REQUIRED));
        assertHeld(AutomaticGuidanceCompiler.compile(draft, ORIGINAL));
        var review = GuidanceReviewCompiler.assess(draft, ORIGINAL);
        assertFalse(review.ready());
        assertTrue(review.issues().contains(OcrProvenance.REVIEW_REQUIRED));
    }

    @Test
    void sourceConfirmationFallbackAlsoRejectsOcrInsteadOfConvertingItToAnswerableTasks() {
        var nativeDraft = draft(source("모집공고.pdf", URL, LOCATION, SECTIONS));
        var ocrDraft = draft(source("모집공고.pdf", URL, OCR_LOCATION, SECTIONS));
        var nativeResult = EvidenceGuidanceCompiler.compile(nativeDraft, ORIGINAL);
        assertTrue(nativeResult.ready(), nativeResult.issues().toString());
        assertFalse(nativeResult.content().questions().isEmpty());
        assertFalse(nativeResult.content().documents().isEmpty());
        assertHeld(EvidenceGuidanceCompiler.compile(ocrDraft, ORIGINAL));
    }

    @Test
    void matchingNativeAlternativeDoesNotCertifyTheOcrRepresentation() {
        var nativeSource = source("모집공고.pdf", URL, LOCATION, RULES);
        var ocrSource = source("모집공고.hwpx", "https://apply.lh.or.kr/notice.hwpx", OCR_LOCATION, RULES);
        var draft = draft(nativeSource, ocrSource);
        var coverage = SourceCoveragePolicy.assessScoped(draft, ORIGINAL);
        assertFalse(coverage.ready());
        assertTrue(coverage.issues().contains(OcrProvenance.REVIEW_REQUIRED));
        assertFalse(GuidanceReviewCompiler.assess(draft, ORIGINAL).ready());
    }

    @Test
    void validOcrDatesNeverBecomeOfficialScheduleBoundariesEvenBesideNativeDates() {
        String timetable = "접수기간: 2026.01.01 09:00 ~ 2026.01.31 18:00\n결과 발표: 2026.02.07 17:00";
        var nativeSource = source("모집공고.pdf", URL, LOCATION, timetable);
        var ocrSource = source("일정표.pdf", "https://apply.lh.or.kr/schedule.pdf", OCR_LOCATION, timetable);
        var nativeResult = AutomaticScheduleCompiler.compile(List.of(nativeSource), DAY);
        assertTrue(nativeResult.complete(), nativeResult.issues().toString());
        assertEquals("2026-01-01T09:00", nativeResult.schedules().get(0).date());
        assertTrue(AutomaticScheduleCompiler.compileScoped(List.of(nativeSource), DAY).complete());
        for (var sources : List.of(List.of(ocrSource), List.of(nativeSource, ocrSource))) {
            var strict = AutomaticScheduleCompiler.compile(sources, DAY);
            assertFalse(strict.complete());
            assertNull(strict.startDate());
            assertNull(strict.endDate());
            assertTrue(strict.schedules().isEmpty());
            assertTrue(strict.issues().contains(OcrProvenance.REVIEW_REQUIRED));
            var scoped = AutomaticScheduleCompiler.compileScoped(sources, DAY);
            assertFalse(scoped.complete());
            assertNull(scoped.startDate());
            assertNull(scoped.endDate());
            assertTrue(scoped.schedules().isEmpty());
            assertTrue(scoped.issues().contains(OcrProvenance.REVIEW_REQUIRED));
        }
    }

    @Test
    void localInterpreterDoesNotExtractEvenRecognizedFactsFromAnyPageOfAnOcrSource() {
        String waiver = "소득요건 배제\n";
        var nativeSource = source("모집공고.pdf", URL, LOCATION, waiver);
        var nativeBook = LocalRuleInterpreter.interpret(List.of(nativeSource));
        assertTrue(nativeBook.facts().stream().anyMatch(f -> f.type().equals("INCOME_REQUIREMENT_WAIVED")));
        var mixedSource = new Source("스캔첨부.pdf", "https://apply.lh.or.kr/scan.pdf", "b".repeat(64), "EXTRACTED", "",
                List.of(), List.of(new Part(LOCATION, waiver), new Part("공고문 2쪽 " + OcrProvenance.MARKER, "총자산요건 배제\n")));
        var ocrBook = LocalRuleInterpreter.interpret(List.of(mixedSource));
        assertTrue(ocrBook.facts().isEmpty());
        assertTrue(ocrBook.unresolved().stream().anyMatch(u -> u.code().equals(OcrProvenance.REVIEW_REQUIRED)));
        var combined = LocalRuleInterpreter.interpret(List.of(nativeSource, mixedSource));
        assertEquals(nativeBook.facts(), combined.facts());
        assertTrue(combined.clauses().stream().allMatch(c -> c.sourceUrl().equals(URL)));
        assertTrue(combined.unresolved().stream().anyMatch(u -> u.code().equals(OcrProvenance.REVIEW_REQUIRED)));
    }

    @Test
    void explicitlyAuxiliaryOcrFloorPlanStaysOutsideGuidanceCoverageAndSchedules() {
        var nativeSource = source("모집공고.pdf", URL, LOCATION, RULES);
        var plan = source("평면도.pdf", "https://apply.lh.or.kr/plan.pdf", OCR_LOCATION, "101동 59.9㎡ 거실 주방");
        var original = new Content(ORIGINAL.summary(), ORIGINAL.evidence(),
                List.of(new Evidence(plan.label(), plan.url(), DAY.toString())), List.of(), List.of(), List.of());
        var draft = draft(nativeSource, plan);
        var coverage = SourceCoveragePolicy.assessScoped(draft, original);
        assertTrue(coverage.ready(), coverage.issues().toString());
        assertEquals(List.of(nativeSource), coverage.draft().sources());
        assertEquals(List.of(nativeSource), coverage.scheduleSources());
        assertTrue(coverage.original().attachments().isEmpty());
        var review = GuidanceReviewCompiler.assess(draft, original);
        assertTrue(review.ready(), review.issues().toString());
        assertTrue(review.candidate().attachments().stream().anyMatch(e -> e.url().equals(plan.url())),
                "Excluding a floor plan from rule interpretation must not remove its official attachment link");
    }

    @Test
    void aDrawingWithAnExplicitCorrectionLabelIsNotSilentlyExcludedAsAuxiliary() {
        var nativeSource = source("모집공고.pdf", URL, LOCATION, RULES);
        var correction = source("정정평면도.pdf", "https://apply.lh.or.kr/correction.pdf", OCR_LOCATION, "입주 자격 정정 사항");
        var coverage = SourceCoveragePolicy.assessScoped(draft(nativeSource, correction), ORIGINAL);
        assertFalse(coverage.ready());
        assertTrue(coverage.issues().contains(OcrProvenance.REVIEW_REQUIRED));
    }

    private static Source source(String label, String url, String location, String text) {
        return new Source(label, url, "a".repeat(64), "EXTRACTED", "읽기 결과", List.of(), List.of(new Part(location, text)));
    }

    private static Draft draft(Source... sources) {
        return new Draft("extraction", "notice", 1, "READY", CHECKED, "검토용 원문", List.of(sources));
    }

    private static void assertHeld(AutomaticGuidanceCompiler.Result result) {
        assertFalse(result.ready());
        assertNull(result.content());
        assertTrue(result.issues().contains(OcrProvenance.REVIEW_REQUIRED), result.issues().toString());
    }
}
