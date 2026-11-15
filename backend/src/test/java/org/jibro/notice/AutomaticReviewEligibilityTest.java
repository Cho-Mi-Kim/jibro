package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class AutomaticReviewEligibilityTest {
    @Test void dateOnlyDeadlineStaysOpenUntilKoreanMidnightAndPreciseTimeClosesAtBoundary() {
        LocalDate day = LocalDate.of(2026, 11, 13);
        Instant evening = day.atTime(18, 0).atZone(ApplicationPeriod.SEOUL).toInstant();
        assertFalse(AutomaticReviewEligibility.closed(day.minusDays(3), day, List.of(), evening));
        assertTrue(AutomaticReviewEligibility.closed(day.minusDays(3), day, List.of(), day.plusDays(1).atStartOfDay(ApplicationPeriod.SEOUL).toInstant()));
        var event = new ScheduleTemplate("end", "신청 접수 마감", "2026-11-13T18:00", new Evidence("공고문", "https://apply.lh.or.kr/a.pdf", "2026-11-10"));
        assertFalse(AutomaticReviewEligibility.closed(day, day, List.of(event), evening.minusSeconds(1)));
        assertTrue(AutomaticReviewEligibility.closed(day, day, List.of(event), evening));
        assertFalse(AutomaticReviewEligibility.closed(null, null, List.of(), evening));
    }
}
