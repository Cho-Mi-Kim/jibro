package org.jibro.collection.review;

import java.time.LocalDate;
import java.util.List;

import org.jibro.notice.HousingModels.ApplicationTiming;
import org.jibro.notice.HousingModels.ScheduleTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VisitReceptionTest {
    private AutomaticScheduleCompiler.Result compile(String text, int day) {
        return AutomaticScheduleCompiler.compileIntake(List.of(ApplicationPeriodCompilerTest.source(text)), LocalDate.of(2026, 11, day));
    }

    @Test
    void invalidVisitInstructionsRemainVisibleAsScopedIssuesWithoutLosingTheValidPeriod() {
        String period = "접수기간: 2026.11.23 ~ 2026.11.27\n";
        var result = compile(period + "■ 방문 접수는 2026.11.31까지 사전 연락하세요.\n"
                + "■ 현장 신청 장소 (점심시간 12:75~13:00 제외)", 17);
        assertTrue(result.complete());
        assertEquals(LocalDate.of(2026, 11, 27), result.endDate());
        assertEquals(2, result.reviewIssues().size());
        assertEquals(List.of("방문 접수 사전 연락 기한 확인 필요", "방문 접수 점심시간 확인 필요"),
                result.reviewIssues().stream().map(org.jibro.notice.HousingModels.ReviewIssue::title).toList());
        assertTrue(result.reviewIssues().stream().allMatch(issue -> issue.area().equals("SCHEDULE") && !issue.evidence().isEmpty()));
        assertTrue(result.schedules().stream().noneMatch(s -> s.id().equals("auto-visit-contact") || s.id().equals("auto-visit-break")));
        var published = ApplicationPeriodCompiler.withIntake(new org.jibro.notice.HousingModels.Content("원문", null, List.of(), List.of(), List.of(), List.of()),
                result.schedules(), result.reviewIssues());
        assertEquals(2, published.reviewIssues().size());
        org.jibro.notice.GuidanceIssues.validate(published);
        assertTrue(compile(period, 17).reviewIssues().isEmpty());
    }

    @Test
    void dailyVisitTimesAndBreakStayWithVisitWhileOverallIncludesInternet() {
        var result = compile("""
                인터넷 접수: 2026.11.23 08:00 ~ 2026.11.27 18:00
                방문 접수: 2026.11.23 ~ 2026.11.27
                매일 10:00~17:00
                (점심시간 11:40~13:00 제외)
                """, 17);
        assertTrue(result.complete(), result.issues().toString());
        var visits = result.schedules().stream().filter(s -> s.id().startsWith("auto-visit-")).toList();
        assertEquals(5, visits.size(), result.schedules().toString());
        assertEquals("2026-11-25", visits.get(2).date());
        assertTrue(visits.stream().allMatch(s -> s.title().contains("10:00~17:00") && s.title().contains("11:40~13:00")));
        assertTrue(result.schedules().stream().anyMatch(s -> s.id().equals("auto-application-end") && s.date().endsWith("T18:00")));
    }

    @Test
    void wrappedVisitOnlyPdfTableBindsTheOfficeAndBreakToTheDailyRange() {
        var result = compile("""
                ■ 공급일정 * 현장 접수만 가능합니다.
                신청기간 신청장소 계약안내
                2026.11.23(월)~11.27(금)
                익산시 내 거주지 관할 자격검증 결과 적격자에 한하여
                09:30~17:30
                읍·면·동 행정복지센터 개별통보
                (점심 12:00~13:00 제외)
                """, 17);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(5, result.schedules().stream().filter(s -> s.id().startsWith("auto-visit-")
                && s.title().contains("09:30~17:30") && s.title().contains("12:00~13:00")).count());
    }

    @Test
    void revisedSourceReplacesEarlierNotesAndConflictingContactInstructionsDoNotPublish() {
        String period = "접수기간: 2026.11.23 ~ 2026.11.27\n";
        var old = compile(period + "■ 방문 접수는 사전 예약이 필요합니다.", 17);
        var current = compile(period + "■ 사전 예약 없이 방문 접수가 가능합니다.", 17);
        var content = new org.jibro.notice.HousingModels.Content("", null, List.of(), List.of(), List.of(), old.schedules());
        var updated = ApplicationPeriodCompiler.withIntake(content, current.schedules(), List.of());
        assertEquals(List.of("사전 예약 없이 방문 접수가 가능합니다."), updated.schedules().stream()
                .filter(s -> ApplicationTiming.VISIT_CONTACT.equals(s.title())).map(ScheduleTemplate::timingDescription).toList());
        var disputed = compile(period + "■ 방문 접수는 사전 예약이 필요합니다. 방문 접수는 사전 예약 없이 가능합니다.", 17);
        assertTrue(disputed.complete());
        assertFalse(disputed.schedules().stream().anyMatch(s -> ApplicationTiming.VISIT_CONTACT.equals(s.title())));
    }

    @Test
    void distinctVisitDatesRetainDifferentClocks() {
        var result = compile("방문 접수: 2026.11.23 10:00~17:00\n방문 접수: 2026.11.24 13:00~15:00", 17);
        assertTrue(result.complete(), result.issues().toString());
        assertEquals("2026-11-24T15:00", result.schedules().stream()
                .filter(s -> s.id().equals("auto-application-end")).findFirst().orElseThrow().date());
        assertEquals(4, result.schedules().stream().filter(s -> s.id().startsWith("auto-scoped-")).count());
    }

    @Test
    void advanceCallHasItsOwnDeadlineAndPreservesEligibilityAndPhone() {
        String period = "접수기간: 2026.11.23 ~ 2026.11.27\n";
        String text = "다만, 정보취약계층 등 인터넷 접수가 어려우신 분에 한하여 현장접수를 도와드리며, "
                + "현장접수를 희망하시는 경우 2026.11.20.(금)까지 ☎032-890-5245로 연락주시기 바랍니다.";
        var result = compile(period + text, 17);
        assertEquals(LocalDate.of(2026, 11, 27), result.endDate());
        var note = result.schedules().stream().filter(s -> s.title().equals(ApplicationTiming.VISIT_CONTACT)).findFirst().orElseThrow();
        assertEquals("2026-11-20", note.date());
        assertTrue(note.timingDescription().contains("정보취약계층"));
        assertTrue(note.timingDescription().contains("032-890-5245"));
        assertTrue(ApplicationPeriodCompiler.session(note));
        assertFalse(compile(period + text, 28).schedules().stream().anyMatch(s -> s.id().startsWith("auto-visit-")));
    }

    @Test
    void onlyVisitSpecificLunchAndContactNotesArePublished() {
        String period = "접수기간: 2026.11.23 ~ 2026.11.27\n";
        var result = compile(period + "■ 현장방문 신청 장소 (점심시간 12시~13시 제외)\n■ 방문 접수는 사전 연락 없이 가능합니다.", 17);
        var notes = result.schedules().stream().filter(s -> s.id().startsWith("auto-visit-")).map(ScheduleTemplate::timingDescription).toList();
        assertEquals(2, notes.size());
        assertTrue(notes.stream().anyMatch(s -> s.contains("사전 연락 없이")));
        assertTrue(notes.stream().anyMatch(s -> s.contains("12:00~13:00")));
        assertTrue(compile(period + "■ 서류 제출 장소 (점심시간 12시~13시 제외)\n■ 계약 방문은 사전 연락이 필요합니다.", 17)
                .schedules().stream().noneMatch(s -> s.id().startsWith("auto-visit-")));
    }
}
