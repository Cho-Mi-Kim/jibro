package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.jibro.notice.HousingModels.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApplicationTimingIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    NoticeQueryRepository queries;
    @Autowired
    EntityManager entities;

    @Test
    void searchCountsAndDetailAgreeAtTheDeadlineAndKeepDateOnlyNotices() {
        var day = LocalDate.of(2026, 12, 31);
        String group = "time-boundary-" + UUID.randomUUID();
        UUID timed = create(group + "-timed", day, "2026-12-31T18:00:00+09:00");
        UUID untimed = create(group + "-untimed", day, "2026-12-31");
        entities.flush();
        entities.clear();
        Instant closes = Instant.parse("2026-12-31T09:00:00Z");
        assertEquals(2, search(group, "OPEN", closes.minusMillis(1)).totalElements());
        var open = search(group, "OPEN", closes);
        assertEquals(1, open.totalElements());
        assertEquals(untimed, open.items().get(0).id());
        var closed = search(group, "CLOSED", closes);
        assertEquals(1, closed.totalElements());
        assertEquals(timed, closed.items().get(0).id());
        var view = notices.view(notices.entity(closed.items().get(0).id()), false, closes);
        assertEquals("CLOSED", view.status());
        assertFalse(view.canStart());
        assertEquals(closes, view.applicationTiming().endsAt());
        assertNull(view.applicationTiming().nextChangeAt());
        assertNull(notices.view(notices.entity(untimed), false, closes).applicationTiming().endsAt());
    }

    @Test
    void editingSchedulesOrBasicDatesRecomputesTheSearchProjection() {
        var day = LocalDate.of(2026, 12, 31);
        UUID id = create("timing-update-" + UUID.randomUUID(), day, "2026-12-31");
        var entity = notices.entity(id);
        notices.updateContent(id, entity.revision, content("2026-12-31T18:00:00"));
        assertEquals(Instant.parse("2026-12-31T09:00:00Z"), entity.applicationEndAt);
        var draft = notices.draft(id);
        notices.updateDraft(id, entity.revision, new NoticeService.Draft(draft.title(), draft.agency(), draft.region(), draft.type(),
                draft.startDate(), day.plusDays(1), draft.publishedDate(), draft.content()));
        assertNull(entity.applicationEndAt, "old schedule must not supply a time for the new basic deadline");
        assertEquals("HOLD", entity.basicReview);
    }

    private Page<NoticeListSource> search(String keyword, String state, Instant now) {
        return queries.search(List.of(), List.of(), List.of(state), keyword, "deadline", 0, 20, now);
    }

    private UUID create(String title, LocalDate day, String closingDate) {
        UUID id = notices.insert(UUID.randomUUID().toString(), title, "LH", "전국", "청년 전세임대",
                day.minusDays(1), day, day.minusDays(2), content(closingDate));
        notices.samplePublish(id, "PASS");
        return id;
    }

    private Content content(String closingDate) {
        var evidence = new Evidence("검증용 원문", "https://apply.lh.or.kr/", "2026-01-01");
        return new Content("마감 시각 검증", evidence, List.of(), List.of(), List.of(),
                List.of(new ScheduleTemplate("close", "신청 접수 마감", closingDate, evidence)));
    }
}
