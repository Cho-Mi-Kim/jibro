package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Excerpt;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.junit.jupiter.api.Test;

class AutomaticScheduleCompilerTest {
    private static final String URL = "https://apply.lh.or.kr/official.pdf";
    private static final LocalDate CHECKED = LocalDate.of(2026, 11, 7);

    @Test
    void editorMemoDatesCannotReplaceThePublishedApplicationPeriod() {
        String text = "신청 접수 기간: 2026.11.09 ~ 2026.11.13\n"
                + "⟦HWPX:MEMO⟧\n신청 접수 기간: 2026.12.01 ~ 2026.12.31\n⟦HWPX:/MEMO⟧\n";
        var source = new Source("모집공고.hwpx", URL, "hash", "EXTRACTED", "", List.of(),
                List.of(new Part("HWPX Contents/section0.xml (쪽 번호 확인 필요)", text)));
        var result = AutomaticScheduleCompiler.compile(List.of(source), CHECKED);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(LocalDate.of(2026, 11, 9), result.startDate());
        assertEquals(LocalDate.of(2026, 11, 13), result.endDate());
    }

    @Test
    void explicitRangePreservesKoreanTimesAndEvidence() {
        var result = compile("신청 접수 기간: 2026.11.09(월) 오전 9시 ~ 2026.11.13(금) 오후 6시 (조기 중단 가능)",
                "서류 제출 대상자 발표: 2026년 11월 20일", "서류 제출 마감: 2026-11-24 18:00");
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(LocalDate.of(2026, 11, 9), result.startDate());
        assertEquals("2026-11-09T09:00", result.schedules().get(0).date());
        assertEquals("2026-11-13T18:00", result.schedules().get(1).date());
        assertTrue(result.schedules().get(1).title().contains("조기 중단 가능"));
        assertEquals("공고문 2쪽", result.schedules().get(1).evidence().label());
        assertEquals(URL, result.schedules().get(1).evidence().url());
        assertEquals(4, result.schedules().size());
    }

    @Test
    void commaBetweenExplicitAnnouncementDateAndTimePreservesTheOfficialClock() {
        var result = compile("접수기간: 2013.10.28 ~ 2013.10.30",
                "당첨자 발표 : 2014.1.24(금), 16:00 이후");
        assertTrue(result.complete(), result.issues().toString());
        assertEquals("2014-01-24T16:00", result.schedules().get(2).date());
        assertEquals("공고문 2쪽", result.schedules().get(2).evidence().label());
        assertEquals("2026-12-10T18:30", compile("접수기간: 2026.11.16 ~ 2026.11.18",
                "결과 발표: 2026.12.10, 오후 6시 30분").schedules().get(2).date());
    }

    @Test
    void commaDoesNotPermitUnboundMultipleOrInvalidTimes() {
        for (String value : List.of("2026.12.10, 25:00", "2026.12.10, 담당자 문의 16:00",
                "2026.12.10, 16:00 또는 18:00")) {
            assertTrue(compile("접수기간: 2026.11.16 ~ 2026.11.18", "결과 발표: " + value)
                    .issues().contains("SCHEDULE_INVALID_TIME:RESULT"), value);
        }
        assertTrue(compile("접수기간: 2026.11.16 ~ 2026.11.18", "결과 발표: 2026.12.10, 16:00",
                "결과 발표: 2026.12.10 17:00").issues().contains("SCHEDULE_CONFLICT:RESULT"));
    }

    @Test
    void bracketedSingleChannelStillOwnsOnlyItsImmediatelyFollowingRankDay() {
        var result = context("""
                6. 모집일정
                1순위
                [현장접수]
                2023.06.20(화)
                10:00∼16:00
                2,3순위
                [현장접수]
                2023.06.21(수)
                10:00∼16:00
                """);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals("2023-06-20T10:00", result.schedules().get(0).date());
        assertEquals("2023-06-21T16:00", result.schedules().get(1).date());
        assertTrue(result.schedules().stream().anyMatch(s -> s.title().equals("1순위 · 현장 접수 종료")));
    }

    @Test
    void adjacentChannelHeadersCannotAssignTheSameFollowingDateToJustTheLastChannel() {
        for (String channels : List.of("[현장접수]\n[인터넷/모바일]", "현장 접수\n인터넷/모바일")) {
            var result = context("6. 모집일정\n1,2,3순위\n" + channels + "\n2024.08.28.(수)\n10:00~17:00");
            assertFalse(result.complete(), channels);
            assertTrue(result.issues().contains("SCHEDULE_SCOPED_PERIOD_MISSING:1,2,3순위"));
            assertTrue(result.schedules().isEmpty());
        }
    }

    @Test
    void noMissingTimesOrOptionalEventsAreInvented() {
        var result = compile("신청 시작: 2026.11.09", "접수 마감: 2026.11.13");
        assertTrue(result.complete());
        assertEquals(List.of("2026-11-09", "2026-11-13"), result.schedules().stream().map(s -> s.date()).toList());
    }

    @Test
    void apiClosingAndUnlabelledDatesAreNotAnApplicationPeriod() {
        var result = compile("공고 마감일: 2026.11.30", "공고일: 2026.11.07");
        assertFalse(result.complete());
        assertNull(result.endDate());
        assertTrue(result.schedules().isEmpty());
    }

    @Test
    void conflictingAttachmentsClearAllTrustedDates() {
        var result = AutomaticScheduleCompiler.compile(List.of(source("접수 기간: 2026.11.09 ~ 2026.11.13"),
                source("접수 마감: 2026.11.14")), CHECKED);
        assertNull(result.startDate());
        assertTrue(result.issues().contains("SCHEDULE_CONFLICT:END"));
        assertTrue(result.schedules().isEmpty());
    }

    @Test
    void conflictingTimesAreNotResolvedByChoosingOne() {
        var result = compile("접수 기간: 2026.11.09 ~ 2026.11.13 18:00", "접수 마감: 2026.11.13 17:00");
        assertTrue(result.issues().contains("SCHEDULE_CONFLICT:END"));
    }

    @Test
    void invalidDatesAndReversedPeriodsHold() {
        assertFalse(compile("접수 기간: 2026.02.30 ~ 2026.03.01").complete());
        assertTrue(compile("접수 기간: 2026.11.13 ~ 2026.11.09").issues().contains("APPLICATION_PERIOD_REVERSED"));
        assertFalse(compile("접수 기간: 2026.11.09 18:00 ~ 2026.11.09 09:00").complete());
    }

    @Test
    void doesNotGuessYearOrResolveRegionalAndConditionalDates() {
        assertFalse(compile("접수 기간: 11.09 ~ 11.13").complete());
        assertFalse(compile("서울 지역별 접수 기간: 2026.11.09 ~ 2026.11.13").complete());
        assertFalse(compile("접수 마감: 2026.11.13 (변경 가능)", "접수 시작: 2026.11.09").complete());
    }

    @Test
    void multipleDateTokensForSingleEventAndInvalidClockHold() {
        assertFalse(compile("접수 시작: 2026.11.09", "접수 마감: 2026.11.13 또는 2026.11.14").complete());
        assertFalse(compile("접수 기간: 2026.11.09 ~ 2026.11.13 25:00").complete());
        assertFalse(compile("접수 기간: 2026.11.09 ~ 2026.11.13 9:00~18:00").complete());
    }

    @Test
    void distinctLabelsOnOneLineDoNotMixTheirDates() {
        var result = compile("신청 시작: 2026.11.09 신청 마감: 2026.11.13 결과 발표: 2026.11.20");
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(3, result.schedules().size());
    }

    @Test
    void evidenceMustReferToItsFetchedSource() {
        var bad = new Source("공고문", URL, "hash", "EXTRACTED", "", List.of(
                new Excerpt("SCHEDULE", "접수 기간: 2026.11.09 ~ 2026.11.13", "2쪽", "https://example.com")));
        assertTrue(AutomaticScheduleCompiler.compile(List.of(bad), CHECKED).issues().contains("SCHEDULE_EVIDENCE_MISSING"));
    }

    @Test
    void documentReceiptAndObjectionPeriodAreNotApplicationDates() {
        assertFalse(compile("서류 접수 기간: 2026.11.09 ~ 2026.11.13").complete());
        assertFalse(compile("이의 신청 기간: 2026.11.09 ~ 2026.11.13").complete());
        assertFalse(compile("접수 시작: 2026.11.09 이후", "접수 마감: 2026.11.13").complete());
        var value = compile("접수 기간: 2026.11.09 - 2026.11.13", "서류 접수 마감: 2026.11.20");
        assertTrue(value.complete(), value.issues().toString());
        assertEquals("서류 제출 마감", value.schedules().get(2).title());
    }

    @Test
    void detailFieldsSeparateDocumentSelectionFromFinalWinnerAnnouncement() {
        var result = context("""
                접수기간 : 2026.11.16 ~ 2026.11.17
                서류제출대상자 발표일 : 2026.12.07
                서류접수기간 : 2026.12.08 ~ 2026.12.10
                당첨자발표일 : 2027.03.26
                계약기간 : 2027.03.31 ~ 2027.04.30
                """);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(5, result.schedules().size());
        assertEquals("2026-12-07", result.schedules().stream().filter(s -> s.title().equals("서류 제출 대상자 발표")).findFirst().orElseThrow().date());
        assertEquals("2027-03-26", result.schedules().stream().filter(s -> s.title().equals("결과 발표")).findFirst().orElseThrow().date());
    }

    @Test
    void fullContextIncludesRankDatesAfterExcerptLimitAndKeepsExactTimes() {
        var result = context("""
                6. 모집일정
                단지명
                주택형
                신청순위
                신청접수
                인터넷/모바일신청 서류제출
                대상자 발표
                예비입주자
                당첨발표
                연천전곡1
                1,2순위
                2026.11.16(월)
                10:00∼16:00
                인터넷
                모바일
                현장
                2026.12.07(월)
                14:00 이후
                서류제출일:
                2026.12.08.(화)
                ~
                2026.12.10.(목)
                2027.03.26(금)
                14:00 이후
                연천전곡1단지 관리사무소
                3순위
                2026.11.17(화)
                10:00∼16:00
                • 해당순위 마감시 후순위 접수는 받지 않습니다.
                7. 신청방법
                """);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals("2026-11-16T10:00", result.schedules().get(0).date());
        assertEquals("2026-11-17T16:00", result.schedules().get(1).date());
        assertTrue(result.schedules().stream().anyMatch(s -> s.title().equals("1,2순위 접수 종료") && s.date().equals("2026-11-16T16:00")));
        assertTrue(result.schedules().stream().anyMatch(s -> s.title().equals("3순위 접수 시작") && s.evidence().url().equals(URL)));
        assertTrue(result.schedules().stream().noneMatch(s -> s.title().equals("결과 발표")), "unlabelled table columns must not be guessed");
    }

    @Test
    void scopeWindowsCanHaveDifferentApplicationMethodsWithoutAFalseConflict() {
        var result = context("""
                6. 모집일정
                1순위
                인터넷/모바일
                2026.11.25(수)06:00∼16:00
                현장 접수
                2026.11.25(수)10:00∼16:00
                2순위
                인터넷/모바일
                2026.11.26(목)06:00∼16:00
                현장 접수
                2026.11.26(목)10:00∼16:00
                7. 신청방법
                """);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals("2026-11-25T06:00", result.schedules().get(0).date());
        assertEquals("2026-11-26T16:00", result.schedules().get(1).date());
        assertEquals(10, result.schedules().size());
    }

    @Test
    void actualConflictsBetweenAggregateAndRankWindowsRemainHeld() {
        var result = context("""
                접수기간 : 2026.11.16 ~ 2026.11.18
                6. 모집일정
                1,2순위
                2026.11.16(월)10:00∼16:00
                3순위
                2026.11.17(화)10:00∼16:00
                """);
        assertFalse(result.complete());
        assertTrue(result.issues().contains("SCHEDULE_CONFLICT:END"));
    }

    @Test
    void unknownOptionalStagesAndNarrativeHeadersDoNotOverrideExplicitDates() {
        var result = context("""
                접수기간 : 2026.11.16 ~ 2026.11.18
                서류제출 대상자 발표 : 추후 별도 안내
                결과 발표 : 미정
                당첨자 발표일이 빠른 단지를 기준으로 탈락 처리됩니다.
                대상자 발표
                2026.12.07
                """);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(2, result.schedules().size());
    }

    @Test
    void secondRangeYearComesOnlyFromTheSameExplicitRangeAndNeverTheCheckedDate() {
        assertTrue(compile("접수기간 : 2026.11.16(월)10:00 ~ 11.18(수)16:00").complete());
        assertFalse(compile("접수기간 : 11.16(월)10:00 ~ 11.18(수)16:00").complete());
        assertFalse(compile("접수기간 : 2026.12.31 ~ 1.02").complete(), "an unstated year rollover is not inferred");
        assertTrue(compile("접수기간 : 2026-11-16 ~ 2026-11-18").complete());
    }

    @Test
    void abbreviatedSourceYearRequiresAnExplicitMatchingYearInTheSameDocument() {
        var known = context("공고일: 2026.11.05\n접수기간: '26.11.16 ~ '26.11.18");
        assertTrue(known.complete(), known.issues().toString());
        assertFalse(context("공고일: 2025.11.05\n접수기간: '26.11.16 ~ '26.11.18").complete());
        assertFalse(context("접수기간: '26.11.16 ~ '26.11.18").complete());
    }

    @Test
    void methodSpecificWindowsRetainOfficeAndLunchExceptionsWithoutChangingOverallWindow() {
        var result = context("""
                공고일: 2026.11.02
                • 인터넷(PC 또는 모바일) 청약 접수기간은 ‘26.11.16(월) 10:00 ~ ’26.11.18(수) 16:00까지로 접수기간 중에는 24시간 신청 가능합니다.
                ■ 현장방문 신청방법
                • 신청 기간 : 2026.11.17(화) 업무시간(10:00~16:00)내 가능 〔점심시간 : 12:00 ~ 13:00 제외〕
                • 서류제출대상자 발표일 : (2026.11.26(목) 16시 이후)에 선정 여부를 직접 확인하여야 하며, 선정된 분은 반드시 서류제출기간 2026.11.27(금)~2026.12.03(목)에 필요서류를 제출하여야 합니다.
                """);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(LocalDate.of(2026, 11, 16), result.startDate());
        assertEquals(LocalDate.of(2026, 11, 18), result.endDate());
        assertTrue(result.schedules().stream().anyMatch(s -> s.title().equals("현장 (12:00~13:00 제외) 접수 종료") && s.date().equals("2026-11-17T16:00")));
        assertTrue(result.schedules().stream().anyMatch(s -> s.title().equals("서류 제출 대상자 발표") && s.date().equals("2026-11-26T16:00")));
    }

    @Test
    void prosePeriodHasOneWindowAndDoesNotTurnItsEmbeddedStartLabelIntoASecondEvent() {
        var result = context("""
                공고일: 2026.11.04
                ∙ 인터넷(PC) 청약 접수기간은 접수시작일 2026.11.23.(월) 10:00시부터 2026.11.27(금) 17:00시까지로 접수기간 중에는 24시간 신청 가능합니다.
                """);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals("2026-11-27T17:00", result.schedules().get(1).date());
    }

    @Test
    void missingMergedRankCellAndContradictingSameRankSourcesRemainHeld() {
        assertTrue(context("""
                6. 모집일정
                1순위
                2026.11.16(월)10:00∼16:00
                2순위
                """).issues().contains("SCHEDULE_SCOPED_PERIOD_MISSING:2순위"));
        var first = new Source("공고문.pdf", URL, "hash", "EXTRACTED", "", List.of(), List.of(new Part("12쪽", "6. 모집일정\n1순위\n2026.11.16(월)10:00∼16:00")));
        var other = new Source("공고문.hwpx", URL + "?hwpx", "other-hash", "EXTRACTED", "", List.of(), List.of(new Part("표", "6. 모집일정\n1순위\n2026.11.17(화)10:00∼16:00")));
        assertTrue(AutomaticScheduleCompiler.compile(List.of(first, other), CHECKED).issues().contains("SCHEDULE_CONFLICT:START"));
    }

    @Test
    void singleDocumentSubmissionDayIsAnOptionalDeadline() {
        var result = context("접수기간: 2026.11.16 ~ 2026.11.18\n서류제출일: 2026.11.26");
        assertTrue(result.complete(), result.issues().toString());
        assertEquals("2026-11-26", result.schedules().get(2).date());
    }

    @Test
    void announcementBeforeClosingIsHeldWithoutInventingMissingTimes() {
        assertTrue(compile("접수기간: 2026.11.16 ~ 2026.11.18", "결과 발표: 2026.11.15")
                .issues().contains("SCHEDULE_ORDER_CONFLICT"));
        assertTrue(compile("접수기간: 2026.11.16 ~ 2026.11.18 18:00", "대상자 발표: 2026.11.18 17:00")
                .issues().contains("SCHEDULE_ORDER_CONFLICT"));
        assertTrue(compile("접수기간: 2026.11.16 ~ 2026.11.18 18:00", "결과 발표: 2026.11.18").complete(),
                "an unreported announcement time must not be invented");
    }

    @Test
    void earlyAnnouncementAcrossRankWindowsIsUnresolvedRatherThanAssignedToTheLastRank() {
        var result = context("""
                6. 모집일정
                1순위
                2026.11.16(월)10:00∼16:00
                2순위
                2026.11.18(수)10:00∼16:00
                7. 신청방법
                결과 발표: 2026.11.17
                """);
        assertFalse(result.complete());
        assertTrue(result.issues().contains("SCHEDULE_EVENT_SCOPE_UNRESOLVED:RESULT"));
        assertFalse(result.issues().contains("SCHEDULE_ORDER_CONFLICT"));
        assertTrue(result.schedules().isEmpty());
    }

    @Test
    void earlyClosingDoesNotProveAnEarlierAnnouncementIsWrongOrCorrect() {
        var result = compile("접수기간: 2026.11.16 ~ 2026.11.18 (조기 중단 가능)", "결과 발표: 2026.11.17");
        assertTrue(result.issues().contains("SCHEDULE_EVENT_SCOPE_UNRESOLVED:RESULT"));
        assertFalse(result.issues().contains("SCHEDULE_ORDER_CONFLICT"));
    }

    private AutomaticScheduleCompiler.Result context(String text) {
        return AutomaticScheduleCompiler.compile(List.of(new Source("공고문", URL, "hash", "EXTRACTED", "", List.of(),
                List.of(new Part("공고문 12쪽", text)))), CHECKED);
    }

    private AutomaticScheduleCompiler.Result compile(String... text) {
        return AutomaticScheduleCompiler.compile(List.of(source(text)), CHECKED);
    }

    private Source source(String... text) {
        return new Source("공고문", URL, "hash", "EXTRACTED", "", Arrays.stream(text)
                .map(value -> new Excerpt("SCHEDULE", value, "공고문 2쪽", URL)).toList());
    }
}
