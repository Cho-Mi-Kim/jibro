package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class NoticeSchedulesTest {
    @Test
    void noApplicationReminderAfterTheExactDeadlineButLaterDocumentsRemain() {
        var events = List.of(
            new ScheduleTemplate("closing", "접수 마감 (18:00)", "2026-12-31", null),
            new ScheduleTemplate("docs", "서류 제출 마감", "2026-12-31T20:00:00+09:00", null));
        var value = notice("AVAILABLE", events);
        var before = java.time.Instant.parse("2026-12-31T08:59:59Z");
        var after = java.time.Instant.parse("2026-12-31T09:00:00Z");
        assertEquals(List.of("end", "docs"), NoticeSchedules.reminders(value, before).stream().map(NoticeSchedules.Due::id).toList());
        assertEquals(List.of("docs"), NoticeSchedules.reminders(value, after).stream().map(NoticeSchedules.Due::id).toList());
    }

    private Notice notice(String guide, List<ScheduleTemplate> schedules) {
        return notice(guide, schedules, null);
    }

    private Notice notice(String guide, List<ScheduleTemplate> schedules, NoticeLifecycle lifecycle) {
        return new Notice("notice", "청년 공고", "LH", "전국", "청년 전세임대", "OPEN",
            LocalDate.of(2026, 2, 24), LocalDate.of(2026, 12, 31), LocalDate.of(2026, 2, 24),
            List.of(), "", 3, 3, guide, true, null, List.of(), List.of(), schedules, null, null, null, lifecycle);
    }

    @Test
    void earlyClosureSuppressesApplicationChecksButPreservesDocumentResultAndPublishedEvents() {
        var titles = List.of("신청 접수 시작", "신청 접수 마감", "공식 신청 여부 확인", "신청 완료 확인",
            "서류 제출 마감", "서류 제출 대상자 신청", "신청 접수 마감 결과 발표", "공고 접수 마감 안내 게시");
        var events = java.util.stream.IntStream.range(0, titles.size())
            .mapToObj(index -> new ScheduleTemplate("event-" + index, titles.get(index), "2026-12-31", null)).toList();
        var early = new NoticeLifecycle("EARLY_CLOSED", "공식 조기 마감", null, null, 1);
        assertEquals(titles.subList(4, 8), NoticeSchedules.reminders(notice("AVAILABLE", events, early))
            .stream().map(NoticeSchedules.Due::title).toList());
        assertEquals(List.of(), NoticeSchedules.reminders(notice("AVAILABLE", events,
            new NoticeLifecycle("CANCELLED", "공식 취소", null, null, 2))));
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
