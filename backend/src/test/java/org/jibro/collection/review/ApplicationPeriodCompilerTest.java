package org.jibro.collection.review;

import java.time.*;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationPeriodCompilerTest {
    static final String URL = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";

    @Test
    void legalPurchaseRightDefinitionAndOfficeHoursAreNotOpenEndedApplications() {
        assertTrue(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02\n⑧ 선착순의 방법으로 공급받은 분양권등을 소유한 경우\n■ 운영기간 : 2015.1.1 ~ 공급종료 시 까지")).ready());
    }

    static Source source(String text) {
        return new Source("공고문.pdf", URL, "a".repeat(64), "EXTRACTED", null, List.of(), List.of(new Part("PDF 2쪽", text)));
    }

    ApplicationPeriodCompiler.Result assess(Source... sources) {
        var content = new Content("검수 중", new Evidence("공고", URL, "2026-11-11"), List.of(), List.of(), List.of(), List.of());
        return ApplicationPeriodCompiler.assess(new Draft("id", "notice", 2, "READY", Instant.parse("2026-11-11T00:00:00Z"), "", List.of(sources)), content);
    }

    @Test
    void expiredPeriodIsIndependentOfUnsupportedQualifications() {
        var result = assess(source("자격은 복잡한 예외표 참조\n신청 기간: 2015-04-01 10:00 ~ 2015-04-02 16:00"));
        assertTrue(result.ready());
        assertEquals(LocalDate.of(2015, 4, 2), result.end());
        assertEquals(2, result.schedules().size());
    }

    @Test
    void dailyHoursBesideTheIntakeRangeOnlyApplyToThatRange() {
        var result = assess(source("접수기간: 2026.11.23 ~ 2026.11.27\n(매일 10:00~16:00)\n문의 시간: 09:00~18:00"));
        assertTrue(result.ready(), result.issues().toString());
        assertEquals(List.of("2026-11-23T10:00", "2026-11-27T16:00"),
                result.schedules().stream().map(ScheduleTemplate::date).toList());
    }

    @Test
    void oneConfirmedTimeDoesNotInventTheOtherBoundary() {
        var start = assess(source("신청기간: 2026.11.24(화) 10:00 ~ 모집 완료 시까지"));
        assertTrue(start.ready(), start.issues().toString());
        assertNull(start.end());
        assertEquals("2026-11-24T10:00", start.schedules().get(0).date());
        assertEquals(ApplicationTiming.UNTIL_FILLED_SESSION, start.schedules().get(1).title());
        assertFalse(assess(source("문의시간: 2026.11.24 10:00~16:00")).ready());
    }

    @Test
    void untilFilledNeedsAnIntakeClauseAndNeverBecomesACalendarDeadline() {
        for (var text : List.of("신청 기간: 모집 완료 시까지", "접수기간:\n모집완료시까지", "모집 기간\n모집 완료 시까지",
                "모집 완료시까지 신청접수합니다. (점심시간 및 토요일·일요일·법정공휴일에는 신청접수 받지 않습니다.)",
                "신청기간                  신청장소       계약안내\n모집 완료시까지 09:30~17:30     주민센터",
                "신청기간 신청장소 계약안내\n모집 완료시까지 09:30~17:30 김제시 내 거주지 관할",
                "신청기간\n신청장소\n계약안내\n모집 완료시까지 09:30~17:30")) {
            var result = assess(source(text));
            assertTrue(result.ready(), text + result.issues());
            var event = result.schedules().get(0);
            assertEquals(ApplicationTiming.UNTIL_FILLED_SESSION, event.title());
            assertEquals("", event.date());
            assertEquals(URL, event.evidence().url());
            assertEquals("PDF 2쪽", event.evidence().label());
            assertNull(result.end());
        }
        for (var text : List.of("상시 모집", "서류 접수기간: 모집 완료 시까지", "1순위 접수기간: 모집 완료 시까지",
                "계약기간: 모집 완료 시까지", "접수기간: 모집 완료 시까지가 아닙니다",
                "신청기간: 모집 완료 시까지\n접수기간: 2026.12.01~2026.12.30"))
            assertFalse(assess(source(text)).ready(), text);
    }

    @Test
    void rollingAttachmentTakesPrecedenceOverPortalDisplayDatesButNotAFixedAttachmentDeadline() {
        var listing = new Source("공식 상세 본문", URL + "9", "b".repeat(64), "EXTRACTED", "", List.of(),
                List.of(new Part("상세 본문", "접수기간: 2026.01.01 ~ 2026.12.31")));
        var result = assess(source("신청 기간: 모집완료시까지"), listing);
        assertTrue(result.ready(), result.issues().toString());
        assertNull(result.start());
        assertNull(result.end());
        assertEquals(1, result.schedules().size());
        assertFalse(assess(source("접수기간: 모집 완료 시까지\n접수 마감: 2026.12.31 16:00"), listing).ready());
    }

    @Test
    void dateDisagreementsOnlyPublishClocksWithoutAnOpposingClock() {
        var dates = "신청기간: 2026.12.01 10:00 ~ 2026.12.02 16:00\n신청기간: 2026.11.30 10:00 ~ 2026.12.01 ";
        var both = assess(source(dates + "16:00"));
        assertTrue(both.ready(), both.issues().toString());
        assertNull(both.start());
        assertNull(both.end());
        assertEquals(2, both.schedules().size());
        assertTrue(both.schedules().stream().allMatch(s -> s.date().isEmpty() && s.title().equals(ApplicationTiming.UNDATED_HOURS_SESSION)));
        var one = assess(source(dates + "17:00"));
        assertEquals(1, one.schedules().size());
        assertTrue(one.schedules().get(0).timingDescription().contains("시작 10:00"));
        assertFalse(assess(source(dates.replace("2026.", "2025.") + "16:00")).ready());
    }

    @Test
    void dateOnlyListingAndOnlineInstructionsExposeHoursWithoutBorrowingDatesOrConsultationHours() {
        var listing = new Source("공식 상세 본문", URL + "9", "b".repeat(64), "EXTRACTED", "", List.of(),
                List.of(new Part("공식 상세 본문 · 항목 1", "접수기간: 2026.12.01 ~ 2026.12.02")));
        var notice = source("인터넷(PC) 청약 접수기간은 접수시작일 2026.11.30.(월) 10시부터 2026.12.01.(화) 16시까지로 접수기간 중에는 24시간 신청 가능합니다.\n문의시간 09:00~18:00");
        var result = assess(notice, listing);
        assertTrue(result.ready(), result.issues().toString());
        assertNull(result.end());
        assertEquals(2, result.schedules().size());
        assertTrue(result.schedules().stream().allMatch(s -> s.timingDescription().contains("인터넷") && s.evidence().url().equals(URL)));
        assertTrue(result.schedules().get(1).timingDescription().contains("16:00"));
    }

    @Test
    void adjacentStructuredHeaderBindsOnlyTheIntakeColumnToEachComplex() {
        String table = "⟦HWPX:TABLE id=1 rows=1 cols=3⟧\n"
                + cell(1, 1, 1, "단지명") + cell(1, 1, 2, "신청접수") + cell(1, 1, 3, "서류제출")
                + "⟦HWPX:/TABLE⟧\n⟦HWPX:TABLE id=2 rows=2 cols=3⟧\n"
                + cell(2, 1, 1, "가단지") + cell(2, 1, 2, "2026.12.01 09:00~14:00") + cell(2, 1, 3, "2026.12.20 18:00")
                + cell(2, 2, 1, "나단지") + cell(2, 2, 2, "[현장접수] 2026.12.02 10:00~15:00 * 점심시간 12시~13시 제외") + cell(2, 2, 3, "2026.12.21 16:00")
                + "⟦HWPX:/TABLE⟧";
        for (String format : List.of("HWPX", "PDF")) {
            var result = assess(new Source("공고." + format.toLowerCase(java.util.Locale.ROOT), URL, "a".repeat(64), "EXTRACTED", "", List.of(),
                    List.of(new Part("본문", table.replace("HWPX", format)))));
            assertTrue(result.ready(), result.issues().toString());
            assertEquals(LocalDate.of(2026, 12, 2), result.end());
            assertTrue(result.schedules().stream().anyMatch(s -> s.title().contains("나단지 · 현장 (점심 12:00~13:00 제외)")
                    && s.date().equals("2026-12-02T15:00") && s.evidence().label().contains("표 2 행 2 열 2")));
        }
    }

    @Test
    void rankedIntakeCellsKeepVisitWindowsSeparateFromViewingAndResults() {
        String table = "공고일 2026.11.11\n⟦HWPX:TABLE id=1 rows=3 cols=4⟧\n"
                + cell(1, 1, 1, "신청\n자격") + cell(1, 1, 2, "주택열람")
                + cell(1, 1, 3, "신청접수") + cell(1, 1, 4, "입주자 선정결과 발표")
                + cell(1, 2, 1, "1순위\n(우선)") + cell(1, 2, 2, "‘26.11.21~11.24 (10:00~16:00)")
                + cell(1, 2, 3, "‘26.11.23(월)\n~11.24(화)\n[방문접수]\n(10:00~16:00)")
                + cell(1, 2, 4, "‘26.12.22 (17:00 이후)")
                + cell(1, 3, 1, "1순위(일반)·2순위") + cell(1, 3, 2, "‘26.12.12~12.15 (10:00~16:00)")
                + cell(1, 3, 3, "‘26.12.14(월)~12.15(화) [방문접수] (10:00~16:00)")
                + cell(1, 3, 4, "‘27.1.27 (17:00 이후)") + "⟦HWPX:/TABLE⟧";
        for (String format : List.of("HWPX", "PDF")) {
            var result = assess(new Source("공고." + format.toLowerCase(Locale.ROOT), URL, "a".repeat(64), "EXTRACTED", "", List.of(),
                    List.of(new Part("본문", table.replace("HWPX", format)))));
            assertTrue(result.ready(), result.issues().toString());
            assertEquals(LocalDate.of(2026, 11, 23), result.start());
            assertEquals(LocalDate.of(2026, 12, 15), result.end());
            var rounds = result.schedules().stream().filter(ApplicationPeriodCompiler::session).toList();
            assertEquals(List.of("2026-11-23", "2026-11-24", "2026-12-14", "2026-12-15"),
                    rounds.stream().map(ScheduleTemplate::date).toList());
            assertTrue(rounds.stream().allMatch(s -> s.title().endsWith("10:00~16:00")));
            assertEquals(List.of("2026-11-23T10:00", "2026-12-15T16:00"),
                    result.schedules().stream().filter(s -> !ApplicationPeriodCompiler.session(s)).map(ScheduleTemplate::date).toList());
            assertTrue(rounds.get(0).title().contains("1순위 (우선) · 방문"));
            assertTrue(rounds.get(2).title().contains("1순위(일반)·2순위 · 방문"));
        }
        assertFalse(assess(source(table.replace("[방문접수]", "[방문접수 불가]"))).ready());
    }

    @Test
    void physicallyAlignedRankBoxesBindOnlyTheirOwnIntakeRows() {
        String tables = "공고일 2026.11.11\n⟦PDF:TABLE id=1 rows=2 cols=1 rowgroup=1⟧\n"
                + cell(1, 1, 1, "신청\n자격") + cell(1, 2, 1, "1순위 (우선)") + "⟦PDF:/TABLE⟧\n"
                + "⟦PDF:TABLE id=2 rows=2 cols=3 rowgroup=1⟧\n"
                + cell(2, 1, 1, "주택열람") + cell(2, 1, 2, "신청접수") + cell(2, 1, 3, "선정결과 발표")
                + cell(2, 2, 1, "2026.11.21~11.24 (09:00~18:00)")
                + cell(2, 2, 2, "2026.11.23~11.24 [방문접수] (10:00~16:00)")
                + cell(2, 2, 3, "2026.12.22 (17:00 이후)") + "⟦PDF:/TABLE⟧\n";
        var part = new Part("공고문 6쪽", tables.replace("HWPX", "PDF"));
        var source = new Source("공고.pdf", URL, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(part));
        var result = assess(source);
        assertTrue(result.ready(), result.issues().toString());
        assertEquals(LocalDate.of(2026, 11, 23), result.start());
        assertEquals(LocalDate.of(2026, 11, 24), result.end());
        assertEquals(List.of("2026-11-23", "2026-11-24"), result.schedules().stream()
                .filter(ApplicationPeriodCompiler::session).map(ScheduleTemplate::date).toList());
        assertTrue(result.schedules().stream().filter(ApplicationPeriodCompiler::session)
                .allMatch(s -> s.title().contains("1순위 (우선)") && s.title().endsWith("10:00~16:00")
                        && s.evidence().label().contains("신청순위 표 1 행 2")));
        for (String invalid : List.of(
                part.text().replace("rowgroup=1", "rowgroup=0"),
                part.text().replace("cols=3 rowgroup=1", "cols=3 rowgroup=2"),
                part.text().replace("⟦PDF:/TABLE⟧\n⟦PDF:TABLE id=2", "⟦PDF:/TABLE⟧\n별도 모집\n⟦PDF:TABLE id=2"),
                part.text().replace("신청\n자격", "주택열람 안내")))
            assertTrue(ApplicationTableReader.read(source, new Part("공고문 6쪽", invalid)).isEmpty(), invalid);
        var unlinked = ApplicationTableReader.read(source, new Part("공고문 6쪽", part.text().replace(" rowgroup=1", "")));
        assertTrue(unlinked.stream().noneMatch(e -> e.scope().contains("우선")));
    }

    private static String cell(int table, int row, int column, String text) {
        return "⟦HWPX:CELL table=" + table + " row=" + row + " col=" + column + " rowspan=1 colspan=1⟧\n"
                + text + "\n⟦HWPX:/CELL⟧\n";
    }

    @Test
    void siteAndChannelRowsRetainTheirTimesAndPhysicalPages() {
        var result = assess(source("""
                6. 모집일정
                가단지 온라인 접수: 2026.11.24(화) 10:00~24:00
                나단지 방문 접수: 2026.11.25(수) 09:00~15:00
                """));
        assertTrue(result.ready(), result.issues().toString());
        var midnight = result.schedules().stream().filter(s -> s.title().contains("가단지 온라인 접수 종료")).findFirst().orElseThrow();
        assertEquals(Instant.parse("2026-11-24T15:00:00Z"), org.jibro.notice.ApplicationPeriod.instant(midnight));
        assertEquals(4, result.schedules().stream().filter(ApplicationPeriodCompiler::session).count());
        assertTrue(result.schedules().stream().allMatch(s -> s.evidence().label().equals("PDF 2쪽")));
    }

    @Test
    void unknownAndRollingPeriodsNeverClose() {
        assertFalse(assess(source("서류 제출 기간: 2015-04-01 ~ 2015-04-02")).ready());
        assertFalse(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02\n이후 상시 모집")).ready());
        assertFalse(assess(source("신청 기간: 4.1 ~ 4.2")).ready());
    }

    @Test
    void contradictoryDatesAndUnreadAttachmentsBlock() {
        assertFalse(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02"), source("신청 기간: 2015-04-01 ~ 2015-04-03")).ready());
        assertFalse(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02"),
                new Source("정정공고.hwp", URL + "4", null, "FAILED", "", List.of(), List.of())).ready());
    }

    @Test
    void legacyDateMethodPlaceTablePreservesDailyClosingTime() {
        var result = assess(source("신청일시\n신청방법\n신청장소\n2015.10.13(화)∼14(수)\n(10:00 ~ 17:00)\n인터넷신청"));
        assertTrue(result.ready());
        assertEquals(LocalDate.of(2015, 10, 14), result.end());
        assertTrue(result.schedules().get(1).date().contains("17:00"));
    }

    @Test
    void tableWithAnnouncementColumnIsNotAnApplicationRange() {
        assertFalse(assess(source("신청일시\n결과발표\n신청장소\n2015.10.13(화)\n2015.10.20(화)")).ready());
    }

    @Test
    void singleDayAndCrossMonthAreExplicitlyBounded() {
        assertEquals(LocalDate.of(2015, 4, 28), assess(source("신청일자\n신청방법\n신청장소\n2015.4.28(화)\n(10:00~16:00)\n방문신청")).end());
        assertEquals(LocalDate.of(2015, 11, 2), assess(source("신청일시\n신청방법\n신청장소\n2015.10.31~11.2\n인터넷신청")).end());
        assertFalse(assess(source("신청일시\n신청방법\n신청장소\n2015.12.31~1.2\n인터넷신청")).ready());
    }

    @Test
    void inlineHoursAndRankRowsPreserveTheLastApplicationDay() {
        assertEquals(LocalDate.of(2014, 7, 18), assess(source("신청일자\n신청방법\n신청장소\n2014.7.17(목)～18(금) (10:00~17:00)\n인터넷 신청")).end());
        assertEquals(LocalDate.of(2015, 9, 11), assess(source("5. 모집일정\n1순위\n2015.09.10(목)\n(10:00~16:00)\n2순위\n2015.09.11(금)\n(10:00~16:00)")).end());
    }

    @Test
    void missingMergedRankAndLaterOpenEndedContractsRemainUnresolved() {
        assertFalse(assess(source("5. 모집일정\n1순위\n2015.09.10(목)\n(10:00~16:00)\n2순위\n6. 제출 서류")).ready());
        assertFalse(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02\n이후 선착순 동호지정 계약 가능")).ready());
        assertFalse(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02\n계약일시 2015-04-03 ~ 공급종료시")).ready());
    }
}
