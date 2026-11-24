package org.jibro.collection.review;

import java.time.LocalDate;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntakeChannelDatesTest {
    private static final String FILE = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1";
    private static final LocalDate CHECK = LocalDate.parse("2026-11-16");
    private Source source(String name, String url, String text) {
        return new Source(name, url, "e".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", text)));
    }

    private ApplicationPeriodCompiler.Result assess(List<IntakeChannelDates.Window> windows) {
        var paper = source("모집공고.pdf", FILE, "인터넷 접수 기간: 2026.12.01 10:00 ~ 2026.12.02 16:00\n"
            + "인터넷 접수 기간: 2026.12.01 10:00 ~ 2026.12.03 16:00\n"
            + "현장 접수 기간: 2026.12.01 09:00 ~ 2026.12.02 15:00");
        return AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, windows, List.of());
    }

    @Test
    void oneMethodCanResolveTheEndWithoutChangingTheOther() {
        var result = assess(List.of(window("온라인", "", "2026-12-03")));
        assertEquals(LocalDate.parse("2026-12-03"), result.end());
        assertTrue(result.schedules().stream().anyMatch(s -> s.title().contains("현장") && s.date().equals("2026-12-02T15:00")));
    }

    @Test
    void otherRanksAndGenericOrUncorroboratedDatesLeaveOnlyOneEndUndated() {
        for (var w : List.of(window("", "", "2026-12-02"), window("온라인", "2순위", "2026-12-02"),
            window("온라인", "", "2026-12-04"))) {
            var result = assess(List.of(w));
            assertNull(result.end());
            assertEquals(LocalDate.parse("2026-12-01"), result.start());
            var missing = result.schedules().stream().filter(s -> s.date().isEmpty()).toList();
            assertEquals(1, missing.size());
            assertTrue(missing.get(0).timingDescription().contains("마감 16:00"));
        }
    }

    @Test
    void localPostConfirmsOnlyTheDisputedClockOfItsOwnChannel() {
        var paper = source("공고문.pdf", FILE, "현장 접수 기간: 2026.12.01 13:30 ~ 2026.12.02 16:00\n"
            + "현장 접수 기간: 2026.12.01 14:00 ~ 2026.12.02 16:00");
        var start = LocalDate.parse("2026-12-01");
        var post = new IntakeChannelDates.Window("현장", "", start, null, "https://www.gimje.go.kr/post",
            java.time.LocalTime.of(14, 0), null, CHECK);
        var result = AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, List.of(post), List.of());
        assertTrue(result.reviewIssues().isEmpty());
        assertTrue(result.schedules().stream().anyMatch(s -> s.date().equals("2026-12-01T14:00") && s.evidence().url().equals(post.url())));
        var unresolved = AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, List.of(), List.of());
        assertEquals(start, unresolved.start());
        assertEquals(1, unresolved.reviewIssues().size());
        assertTrue(unresolved.reviewIssues().get(0).title().contains("시각"));
    }

    @Test
    void aClockOnlyConfirmationNeedsOneKnownDayAndCannotPickBetweenCompetingNotices() {
        var paper = source("모집공고.pdf", FILE, "현장 접수 기간: 2026.12.01 13:30 ~ 2026.12.02 16:00\n"
            + "현장 접수 기간: 2026.12.01 14:00 ~ 2026.12.02 16:00");
        var first = new IntakeChannelDates.Window("현장", "", null, null, "https://www.gimje.go.kr/a",
            java.time.LocalTime.of(14, 0), null, CHECK);
        var second = new IntakeChannelDates.Window("현장", "", null, null, "https://www.kohom.or.kr/b",
            java.time.LocalTime.of(13, 30), null, CHECK);
        var verified = AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, List.of(first), List.of());
        assertTrue(verified.reviewIssues().isEmpty());
        assertTrue(verified.schedules().stream().anyMatch(s -> s.date().equals("2026-12-01T14:00")));
        var disputed = AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, List.of(first, second), List.of());
        assertEquals(1, disputed.reviewIssues().size());
        assertEquals(LocalDate.parse("2026-12-02"), disputed.end());
        assertTrue(disputed.schedules().stream().noneMatch(s -> s.date().contains("T14:00")));
    }

    @Test void explicitMunicipalCorrectionCanReplaceACorroboratedOldBoundary() {
        var post = new IntakeChannelDates.Window("온라인", "", null, LocalDate.parse("2026-12-04"),
            "https://www.gimje.go.kr/post", null, java.time.LocalTime.of(17, 0), CHECK,
            new IntakeChannelDates.Previous(LocalDate.parse("2026-12-02"), java.time.LocalTime.of(16,0)));
        var result = assess(List.of(post, window("온라인", "", "2026-12-02")));
        assertEquals(post.end(), result.end());
        assertTrue(result.schedules().stream().anyMatch(s -> s.date().equals("2026-12-04T17:00")));
    }

    @Test void attachmentAddsMissingHoursAndConflictingClosesLeaveStartIntact() {
        var original = source("모집공고.pdf", FILE, "온라인 접수 기간: 2026.12.01 ~ 2026.12.02");
        var file = new IntakeChannelDates.Attachment("변경 일정.pdf", "공고문 2쪽", "abc");
        var claim = new IntakeChannelDates.Window("온라인", "", LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-02"),
            "https://www.gimje.go.kr/post", java.time.LocalTime.of(10,0), java.time.LocalTime.of(17,0), CHECK).from(file);
        var proven = AutomaticScheduleCompiler.reconcile(List.of(original), CHECK, List.of(claim), List.of());
        assertNotNull(proven);
        assertTrue(proven.reviewIssues().isEmpty());
        assertTrue(proven.schedules().stream().anyMatch(s -> s.date().endsWith("T17:00") && s.evidence().label().contains("2쪽")));
        var other = new IntakeChannelDates.Window("온라인", "", null, null, claim.url(), null, java.time.LocalTime.of(16,0), CHECK);
        var disputed = AutomaticScheduleCompiler.reconcile(List.of(original), CHECK, List.of(claim, other), List.of());
        assertEquals(1, disputed.reviewIssues().size());
        assertTrue(disputed.schedules().stream().anyMatch(s -> s.date().equals("2026-12-01T10:00")));
        assertTrue(disputed.schedules().stream().noneMatch(s -> s.date().endsWith("T17:00")));
    }

    @Test void attachmentCanFillAnOmittedBoundaryWithoutGuessingTheOtherClock() {
        var source = source("모집공고.pdf", FILE, "접수 시작: 2026.12.01 09:00");
        var closes = new IntakeChannelDates.Window("", "", null, LocalDate.parse("2026-12-05"),
            "https://www.gimje.go.kr/post", null, null, CHECK)
            .from(new IntakeChannelDates.Attachment("붙임.pdf", "공고문 1쪽", "abc"));
        var result = AutomaticScheduleCompiler.reconcile(List.of(source), CHECK, List.of(closes), List.of());
        assertNotNull(result);
        assertEquals(LocalDate.parse("2026-12-05"), result.end());
        assertTrue(result.schedules().stream().anyMatch(s -> s.date().equals("2026-12-05")));
        assertTrue(result.schedules().stream().anyMatch(s -> s.date().endsWith("T09:00")));
    }

    private IntakeChannelDates.Window management(String method, String rank, String end) {
        return new IntakeChannelDates.Window(method, rank, LocalDate.parse("2026-12-01"), LocalDate.parse(end),
            "https://www.kohom.or.kr/web/mainComm/HM001002002.do?mode=view&p_idx=10",
            java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), CHECK, null, null,
            IntakeChannelDates.AgencyField.KOHOM_INTAKE);
    }

    @Test void managementClockNeedsBothAttachmentDatesAndNeverUsesOnlyPortalDates() {
        var field = management("", "", "2026-12-03");
        String dates = "현장 접수 기간: 2026.12.01 ~ 2026.12.03";
        var paper = source("모집공고.pdf", FILE, dates);
        var result = AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, List.of(field), List.of());
        assertNotNull(result);
        assertTrue(result.schedules().stream().anyMatch(s -> s.date().equals("2026-12-03T18:00") && s.evidence().label().contains("신청접수일")));
        assertNull(AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, List.of(management("", "", "2026-12-04")), List.of()));
        assertNull(AutomaticScheduleCompiler.reconcile(List.of(source("공식 상세 본문", "https://apply.lh.or.kr/notice", dates)),
            CHECK, List.of(field), List.of()));
        var timed = source("모집공고.pdf", FILE, dates.replace("12.01", "12.01 10:00"));
        var partial = AutomaticScheduleCompiler.reconcile(List.of(timed), CHECK, List.of(field), List.of());
        assertTrue(partial.schedules().stream().anyMatch(s -> s.date().equals("2026-12-01T10:00")));
        assertTrue(partial.schedules().stream().anyMatch(s -> s.date().equals("2026-12-03T18:00")));
    }

    @Test void aVerifiedManagementIntervalPublishesBothAggregateAndSessionClocks() {
        var field = management("", "", "2026-12-03");
        for (String label : List.of("접수 기간", "현장 접수 기간")) {
            var paper = source("모집공고.pdf", FILE, label + ": 2026.12.01 ~ 2026.12.03");
            var result = AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, List.of(field), List.of());
            assertNotNull(result);
            for (var event : List.of("auto-scoped-1-start", "auto-application-start", "auto-scoped-1-end", "auto-application-end")) {
                var saved = result.schedules().stream().filter(s -> s.id().equals(event)).findFirst().orElseThrow();
                assertEquals(event.endsWith("start") ? "2026-12-01T09:00" : "2026-12-03T18:00", saved.date());
                assertEquals(field.url(), saved.evidence().url());
            }
        }
    }

    @Test void aSingleDateOnlyRoundAlsoGetsTheOfficialCommonClocksAtInitialCollection() {
        var source = source("모집공고.pdf", FILE, "접수 기간: 2026.12.01 09:00 ~ 2026.12.03 18:00\n현장 접수 기간: 2026.12.01 ~ 2026.12.03");
        var result = AutomaticScheduleCompiler.compileIntake(List.of(source), CHECK);
        assertTrue(result.issues().isEmpty());
        assertEquals(2, result.schedules().stream().filter(s -> s.date().equals("2026-12-01T09:00")).count());
        assertEquals(2, result.schedules().stream().filter(s -> s.date().equals("2026-12-03T18:00")).count());
    }

    @Test void managementClockCannotCrossBetweenMethodsWithTheSameDates() {
        var paper = source("모집공고.pdf", FILE, "온라인 접수 기간: 2026.12.01 ~ 2026.12.03\n현장 접수 기간: 2026.12.01 ~ 2026.12.03");
        assertNull(AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, List.of(management("", "", "2026-12-03")), List.of()));
        var result = AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, List.of(management("현장", "", "2026-12-03")), List.of());
        assertNotNull(result);
        assertTrue(result.schedules().stream().anyMatch(s -> s.title().contains("현장") && s.date().endsWith("T18:00")));
        assertTrue(result.schedules().stream().noneMatch(s -> s.title().contains("온라인") && s.date().contains("T")));
    }

    @Test void competingManagementRowsLeaveOnlyTheirDisputedClockUnconfirmed() {
        var first = management("", "", "2026-12-03");
        var second = new IntakeChannelDates.Window("", "", first.start(), first.end(), first.url() + "1",
            first.opens(), java.time.LocalTime.of(17,0), CHECK, null, null, IntakeChannelDates.AgencyField.KOHOM_INTAKE);
        var paper = source("모집공고.pdf", FILE, "현장 접수 기간: 2026.12.01 ~ 2026.12.03");
        var result = AutomaticScheduleCompiler.reconcile(List.of(paper), CHECK, List.of(first, second), List.of());
        assertEquals(1, result.reviewIssues().size());
        assertEquals(first.end(), result.end());
        assertTrue(result.schedules().stream().anyMatch(s -> s.date().equals("2026-12-01T09:00")));
        assertTrue(result.schedules().stream().noneMatch(s -> s.date().startsWith("2026-12-03T")));
    }

    private IntakeChannelDates.Window window(String channel, String qualifier, String end) {
        return new IntakeChannelDates.Window(channel, qualifier, LocalDate.parse("2026-12-01"), LocalDate.parse(end), "https://www.myhome.go.kr/");
    }
}
