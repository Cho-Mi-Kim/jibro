package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.jibro.notice.HousingModels.ScheduleTemplate;
import org.junit.jupiter.api.Test;

class ApplicationPeriodTest {
    private final LocalDate day = LocalDate.of(2026, 12, 31);

    private ScheduleTemplate event(String title, String date) {
        return new ScheduleTemplate("end", title, date, null);
    }

    @Test
    void closesAtTheExactReviewedTimeInSeoul() {
        var window = ApplicationPeriod.resolve(day.minusDays(1), day,
            List.of(event("신청 접수 마감 (18:00, 조기 중단 가능)", day.toString())));
        assertEquals(Instant.parse("2026-12-31T09:00:00Z"), window.endsAt());
        assertEquals("OPEN", ApplicationPeriod.status(day.minusDays(1), day, window, window.endsAt().minusMillis(1)));
        assertEquals("CLOSED", ApplicationPeriod.status(day.minusDays(1), day, window, window.endsAt()));
        assertEquals("CLOSED", ApplicationPeriod.status(day.minusDays(1), day, window, window.endsAt().plusSeconds(1)));
    }

    @Test
    void unknownTimeStaysDateOnlyAndExpiresAtTheNextSeoulDay() {
        var window = ApplicationPeriod.resolve(day.minusDays(1), day, List.of(event("접수 마감", day.toString())));
        assertNull(window.endsAt());
        assertEquals("OPEN", ApplicationPeriod.status(day.minusDays(1), day, window, Instant.parse("2026-12-31T14:59:59Z")));
        assertEquals("CLOSED", ApplicationPeriod.status(day.minusDays(1), day, window, Instant.parse("2026-12-31T15:00:00Z")));
        assertNull(ApplicationPeriod.nextChange(day.minusDays(1), day, window, Instant.parse("2026-12-31T15:00:00Z")));
    }

    @Test
    void openingTimeAndExplicitOffsetsUseTheSameBoundary() {
        var window = ApplicationPeriod.resolve(day, day, List.of(
            event("접수 시작", "2026-12-31T00:00:00Z"), event("접수 마감", "2026-12-31T18:00:00+09:00")));
        assertEquals("UPCOMING", ApplicationPeriod.status(day, day, window, Instant.parse("2026-12-30T23:59:59Z")));
        assertEquals("OPEN", ApplicationPeriod.status(day, day, window, Instant.parse("2026-12-31T00:00:00Z")));
        assertEquals(window.startsAt(), ApplicationPeriod.nextChange(day, day, window, Instant.parse("2026-12-30T23:59:59Z")));
    }

    @Test
    void unrelatedRegionalDocumentAndDifferentDayTimesDoNotBecomeTheApplicationDeadline() {
        var window = ApplicationPeriod.resolve(day.minusDays(1), day, List.of(
            event("서류 제출 마감", "2026-12-31T10:00:00"),
            event("서울 지역 접수 마감", "2026-12-31T11:00:00"),
            event("신청 접수 마감 결과 발표", "2026-12-31T12:00:00"),
            event("접수 마감", "2026-12-30T18:00:00")));
        assertNull(window.endsAt());
    }

    @Test
    void conflictingTimesAreNotGuessedAndInvalidDatesAreRejected() {
        assertNull(ApplicationPeriod.resolve(day, day, List.of(event("접수 마감", "2026-12-31T17:00:00"),
            event("접수 마감", "2026-12-31T18:00:00"))).endsAt());
        assertNull(ApplicationPeriod.day("2026-02-30"));
        assertNull(ApplicationPeriod.day("2026-12-31T25:00:00"));
        assertNull(ApplicationPeriod.instant(event("접수 마감 (09:00~18:00)", day.toString())));
        assertEquals("UNKNOWN", ApplicationPeriod.status(null, day, new ApplicationPeriod.Window(null, null), Instant.now()));
    }

    @Test
    void koreanClockLabelAndOffsetDayAreRecognized() {
        assertEquals(Instant.parse("2026-12-31T09:00:00Z"),
            ApplicationPeriod.resolve(day, day, List.of(event("접수 마감 (18시)", day.toString()))).endsAt());
        assertEquals(day, ApplicationPeriod.day("2026-12-30T15:00:00Z"));
        assertEquals(Instant.parse("2026-12-31T09:00:00Z"),
            ApplicationPeriod.instant(event("접수 마감 (오후 6시)", day.toString())));
        assertEquals(Instant.parse("2026-12-30T15:00:00Z"),
            ApplicationPeriod.instant(event("접수 마감 (오전 12시)", day.toString())));
        assertNull(ApplicationPeriod.instant(event("접수 마감 (오후 18시)", day.toString())));
    }
}
