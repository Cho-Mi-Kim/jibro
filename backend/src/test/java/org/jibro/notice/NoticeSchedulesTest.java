package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class NoticeSchedulesTest {
    private Notice notice(String guide, List<ScheduleTemplate> schedules) {
        return new Notice("notice", "청년 공고", "LH", "전국", "청년 전세임대", "OPEN",
                LocalDate.of(2026, 2, 24), LocalDate.of(2026, 12, 31), LocalDate.of(2026, 2, 24),
                List.of(), "", 3, 3, guide, true, null, List.of(), List.of(), schedules);
    }

    @Test
    void oneDeadlineKeepsStableKeyAndOtherEventsOnTheSameDate() {
        var events = List.of(
                new ScheduleTemplate("plain", "신청 접수 마감", "2026-12-31", null),
                new ScheduleTemplate("closing", "신청 접수 마감 (18:00, 조기 중단 가능)", "2026-12-31", null),
                new ScheduleTemplate("docs", "서류 제출 마감", "2026-12-31", null),
                new ScheduleTemplate("result", "당첨자 발표", "2026-12-31", null),
                new ScheduleTemplate("regional", "서울 지역 접수 마감", "2026-12-31T17:00:00", null));
        var due = NoticeSchedules.reminders(notice("AVAILABLE", events));
        assertEquals(List.of("end", "docs", "result", "regional"), due.stream().map(NoticeSchedules.Due::id).toList());
        assertEquals("신청 접수 마감 (18:00, 조기 중단 가능)", due.get(0).title());
        assertEquals(LocalDate.of(2026, 12, 31), due.get(0).date());
    }

    @Test
    void publicSchedulesRemainVisibleDuringGuidanceReviewAndDistinctDatesAreKept() {
        var events = List.of(new ScheduleTemplate("other", "접수 마감", "2026-12-30", null));
        assertEquals(List.of("end", "other"), NoticeSchedules.reminders(notice("AVAILABLE", events))
                .stream().map(NoticeSchedules.Due::id).toList());
        assertEquals(List.of("end", "other"), NoticeSchedules.reminders(notice("CHANGE_REVIEW", events))
                .stream().map(NoticeSchedules.Due::id).toList());
    }

    @Test
    void offsetScheduleUsesSeoulDayAndInvalidDateDoesNotCreateAReminder() {
        var events = List.of(
                new ScheduleTemplate("closing", "접수 마감", "2026-12-30T15:00:00Z", null),
                new ScheduleTemplate("invalid", "결과 발표", "2026-02-30", null));
        var due = NoticeSchedules.reminders(notice("AVAILABLE", events));
        assertEquals(List.of(new NoticeSchedules.Due("end", "접수 마감", LocalDate.of(2026, 12, 31))), due);
    }
}
