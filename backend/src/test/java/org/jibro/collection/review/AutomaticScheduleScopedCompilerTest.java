package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Excerpt;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.GuidanceIssues;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.HousingModels.Evidence;
import org.junit.jupiter.api.Test;

class AutomaticScheduleScopedCompilerTest {
    private static final String URL = "https://apply.lh.or.kr/notice.pdf";
    private static final LocalDate CHECKED = LocalDate.of(2026, 11, 7);
    private static final String PERIOD = "접수기간: 2026.11.16 ~ 2026.11.18 18:00";

    @Test
    void conflictingResultDatesKeepNormalPeriodAndDocumentsWithBothOfficialSources() {
        var sources = List.of(source(URL, PERIOD, "서류제출 대상자 발표: 2026.12.07", "서류 제출 마감: 2026.12.10", "결과 발표: 2027.03.26"),
            source(URL + "?attachment=correction", "결과 발표: 2026.03.26"));
        var result = AutomaticScheduleCompiler.compileScoped(sources, CHECKED);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(LocalDate.of(2026, 11, 16), result.startDate());
        assertEquals(LocalDate.of(2026, 11, 18), result.endDate());
        assertEquals(List.of("auto-application-start", "auto-application-end", "auto-document-selection", "auto-documents-end"),
            result.schedules().stream().map(s -> s.id()).toList());
        assertEquals(1, result.reviewIssues().size());
        var issue = result.reviewIssues().get(0);
        assertEquals("SCHEDULE", issue.area());
        assertTrue(issue.questionIds().isEmpty());
        assertTrue(issue.documentIds().isEmpty());
        assertTrue(issue.detail().contains("2027-03-26"));
        assertTrue(issue.detail().contains("2026-03-26"));
        assertEquals(List.of(URL, URL + "?attachment=correction"), issue.evidence().stream().map(Evidence::url).toList());
        assertTrue(issue.evidence().stream().allMatch(e -> e.checkedAt().equals(CHECKED.toString())));
        assertDoesNotThrow(() -> GuidanceIssues.validate(new Content("안내", issue.evidence().get(0), List.of(),
            List.of(), List.of(), result.schedules(), result.reviewIssues())));
        var strict = AutomaticScheduleCompiler.compile(sources, CHECKED);
        assertFalse(strict.complete(), "legacy callers must retain the strict contract");
        assertTrue(strict.schedules().isEmpty());
    }

    @Test
    void contradictoryDocumentDeadlineTimesNeverChooseOneAndKeepOtherEvents() {
        var result = compile(PERIOD, "서류제출 대상자 발표: 2026.12.07", "결과 발표: 2027.03.26",
            "서류 제출 마감: 2026.12.10 17:00", "서류 제출 마감: 2026.12.10 18:00");
        assertTrue(result.complete(), result.issues().toString());
        assertTrue(result.schedules().stream().noneMatch(s -> s.id().equals("auto-documents-end")));
        assertTrue(result.schedules().stream().anyMatch(s -> s.id().equals("auto-result")));
        var issue = result.reviewIssues().get(0);
        assertTrue(issue.title().startsWith("서류 제출 마감"));
        assertTrue(issue.detail().contains("17:00"));
        assertTrue(issue.detail().contains("18:00"));
    }

    @Test
    void earlierAnnouncementsAreExcludedWithoutRemovingNormalDocumentDeadline() {
        var result = compile(PERIOD, "대상자 발표: 2026.11.15", "결과 발표: 2026.11.18 17:00", "서류 마감: 2026.11.20");
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(2, result.reviewIssues().size());
        assertEquals(List.of("auto-application-start", "auto-application-end", "auto-documents-end"),
            result.schedules().stream().map(s -> s.id()).toList());
        assertTrue(result.reviewIssues().stream().allMatch(i -> i.detail().contains("접수 마감보다 이르게")));
        assertTrue(result.reviewIssues().stream().allMatch(i -> i.detail().contains("2026-11-18 18:00")));
    }

    @Test
    void uncertainAnnouncementScopeDoesNotDiscardVerifiedRankWindows() {
        var source = context("""
            6. 모집일정
            1순위
            2026.11.16(월)10:00∼16:00
            2순위
            2026.11.18(수)10:00∼16:00
            7. 신청방법
            결과 발표: 2026.11.17
            """);
        var result = AutomaticScheduleCompiler.compileScoped(List.of(source), CHECKED);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(6, result.schedules().size());
        assertTrue(result.schedules().stream().noneMatch(s -> s.id().equals("auto-result")));
        assertTrue(result.reviewIssues().get(0).detail().contains("적용 순위·접수 방식"));
        assertTrue(result.reviewIssues().get(0).evidence().stream().anyMatch(e -> e.label().contains("2순위")));
    }

    @Test
    void explicitlyUnannouncedOrProvisionalOptionalEventsRemainVisibleAsReviewIssues() {
        var result = compile(PERIOD, "결과 발표: 미정", "서류 마감: 2026.12.10 (변경 가능)");
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(2, result.schedules().size());
        assertEquals(2, result.reviewIssues().size());
        assertTrue(result.reviewIssues().stream().anyMatch(i -> i.detail().contains("미정")));
        assertTrue(result.reviewIssues().stream().anyMatch(i -> i.detail().contains("2026.12.10 (변경 가능)")));
        assertTrue(AutomaticScheduleCompiler.compile(List.of(source(URL, PERIOD, "결과 발표: 미정")), CHECKED).complete());
    }

    @Test
    void anUnknownStageInAnotherSourceDoesNotSilentlyValidateAnOtherwisePreciseDate() {
        var result = AutomaticScheduleCompiler.compileScoped(List.of(source(URL, PERIOD, "결과 발표: 2027.03.26"),
            source(URL + "?attachment=latest", "결과 발표: 추후 별도 안내")), CHECKED);
        assertTrue(result.complete());
        assertEquals(2, result.schedules().size());
        assertEquals(2, result.reviewIssues().get(0).evidence().size());
        assertTrue(result.reviewIssues().get(0).detail().contains("2027-03-26"));
        assertTrue(result.reviewIssues().get(0).detail().contains("추후 별도 안내"));
    }

    @Test
    void applicationBoundaryConflictsCannotBeConvertedIntoOptionalReviewIssues() {
        for (String conflict : List.of("접수 시작: 2026.11.17", "접수 마감: 2026.11.19", "접수 마감: 2026.11.18 17:00")) {
            var result = compile(PERIOD, conflict, "결과 발표: 2027.03.26", "결과 발표: 2026.03.26");
            assertFalse(result.complete(), conflict);
            assertNull(result.startDate());
            assertNull(result.endDate());
            assertTrue(result.schedules().isEmpty());
            assertTrue(result.issues().stream().anyMatch(i -> i.equals("SCHEDULE_CONFLICT:START") || i.equals("SCHEDULE_CONFLICT:END")));
        }
    }

    @Test
    void missingOrConflictingRankPeriodsCannotUseAnotherRanksBoundary() {
        var missing = AutomaticScheduleCompiler.compileScoped(List.of(context("""
            접수기간: 2026.11.16 ~ 2026.11.18
            6. 모집일정
            1순위
            2026.11.16(월)10:00∼16:00
            2순위
            """)), CHECKED);
        assertFalse(missing.complete());
        assertTrue(missing.issues().contains("SCHEDULE_SCOPED_PERIOD_MISSING:2순위"));
        assertTrue(missing.schedules().isEmpty());
        var conflicting = AutomaticScheduleCompiler.compileScoped(List.of(context("""
            6. 모집일정
            1순위
            2026.11.16(월)10:00∼16:00
            1순위
            2026.11.17(화)10:00∼16:00
            2순위
            2026.11.18(수)10:00∼16:00
            """)), CHECKED);
        assertFalse(conflicting.complete());
        assertNull(conflicting.endDate());
        assertTrue(conflicting.issues().contains("SCHEDULE_CONFLICT:START"));
    }

    @Test
    void malformedOrAmbiguousOptionalNumbersAreStillBlockers() {
        for (String text : List.of("결과 발표: 2026.02.30", "결과 발표: 2026.12.10 25:00",
            "결과 발표: 2026.12.10 또는 2026.12.11", "결과 발표: 12.10")) {
            var result = compile(PERIOD, text);
            assertFalse(result.complete(), text);
            assertFalse(result.issues().isEmpty());
            assertTrue(result.reviewIssues().isEmpty());
            assertTrue(result.schedules().isEmpty());
        }
    }

    @Test
    void missingSourceOrDetachedEvidenceNeverBecomeAnItemLevelException() {
        var unavailable = new Source("정정 공고문", URL + "?unavailable", null, "FAILED", "다운로드 실패", List.of());
        var result = AutomaticScheduleCompiler.compileScoped(List.of(source(URL, PERIOD), unavailable), CHECKED);
        assertFalse(result.complete());
        assertTrue(result.issues().contains("SCHEDULE_SOURCE_UNAVAILABLE"));
        var detached = new Source("정정 공고문", URL + "?correction", "hash", "EXTRACTED", "", List.of(
            new Excerpt("SCHEDULE", "결과 발표: 2026.12.10", "공고문 2쪽", "https://example.com")));
        var invalid = AutomaticScheduleCompiler.compileScoped(List.of(source(URL, PERIOD), detached), CHECKED);
        assertFalse(invalid.complete());
        assertTrue(invalid.issues().contains("SCHEDULE_EVIDENCE_MISSING"));
        assertTrue(invalid.reviewIssues().isEmpty());
    }

    @Test
    void completeListingFieldsAreComparedEvenWhenNoKeywordExcerptWasExtracted() {
        var listing = new Source("공식 상세 본문", URL + "?listing", "fetched-hash", "EMPTY", "본문 발췌 없음", List.of(),
            List.of(new Part("공식 상세 일정", "당첨자발표일 : 2026.12.11")));
        var result = AutomaticScheduleCompiler.compileScoped(List.of(source(URL, PERIOD, "결과 발표: 2026.12.10"), listing), CHECKED);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(2, result.schedules().size());
        assertEquals(2, result.reviewIssues().get(0).evidence().size());
        assertTrue(result.reviewIssues().get(0).detail().contains("2026-12-11"));
        var boundary = new Source("공식 상세 본문", URL + "?listing", "fetched-hash", "EMPTY", "본문 발췌 없음", List.of(),
            List.of(new Part("공식 상세 일정", "접수기간 : 2026.11.16 ~ 2026.11.19")));
        var invalid = AutomaticScheduleCompiler.compileScoped(List.of(source(URL, PERIOD), boundary), CHECKED);
        assertFalse(invalid.complete());
        assertTrue(invalid.issues().contains("SCHEDULE_CONFLICT:END"));
    }

    @Test
    void tooManyConflictingEvidenceLocationsBlockInsteadOfDroppingOriginalSources() {
        var sources = new ArrayList<Source>();
        sources.add(source(URL, PERIOD));
        for (int day = 1; day <= 11; day++) sources.add(source(URL + "?source=" + day, "결과 발표: 2026.12." + day));
        var result = AutomaticScheduleCompiler.compileScoped(sources, CHECKED);
        assertFalse(result.complete());
        assertTrue(result.issues().contains("SCHEDULE_CONFLICT:RESULT"));
        assertTrue(result.issues().contains("SCHEDULE_REVIEW_SCOPE_UNVERIFIED:RESULT"));
        assertTrue(result.reviewIssues().isEmpty());
    }

    @Test
    void noMissingTimeOrOptionalDateIsInventedByScopedReview() {
        var result = compile(PERIOD, "결과 발표: 2026.11.18");
        assertTrue(result.complete());
        assertTrue(result.reviewIssues().isEmpty());
        assertEquals("2026-11-18", result.schedules().get(2).date());
        assertTrue(compile(PERIOD).reviewIssues().isEmpty());
        assertFalse(compile("접수 기간: 11.16 ~ 11.18").complete());
    }

    private AutomaticScheduleCompiler.ScopedResult compile(String... fields) {
        return AutomaticScheduleCompiler.compileScoped(List.of(source(URL, fields)), CHECKED);
    }

    private Source source(String url, String... fields) {
        return new Source("공고문", url, "fetched-hash", "EXTRACTED", "", Arrays.stream(fields)
            .map(value -> new Excerpt("SCHEDULE", value, "공고문 2쪽", url)).toList());
    }

    private Source context(String text) {
        return new Source("공고문", URL, "fetched-hash", "EXTRACTED", "", List.of(), List.of(new Part("공고문 12쪽", text)));
    }
}
