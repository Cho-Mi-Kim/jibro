package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.jibro.notice.HousingModels.*;

import java.time.*;
import java.util.*;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DefaultNoticeScopeIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    NoticeQueryRepository queries;
    @Autowired
    EntityManager em;
    final String group = "default-window-" + UUID.randomUUID();
    final LocalDate day = LocalDate.of(2026, 11, 11);
    final Instant now = day.atTime(12, 0).atZone(ApplicationPeriod.SEOUL).toInstant();

    UUID notice(String suffix, LocalDate start, LocalDate end) {
        var evidence = new Evidence("공식 자료", "https://apply.lh.or.kr/", "2026-11-11");
        var content = new Content("범위 검사", evidence, List.of(), List.of(), List.of(), List.of());
        var id = notices.insert("scope-" + UUID.randomUUID(), group + suffix, "LH", "서울", "국민임대", start, end, day.minusYears(1), content);
        notices.samplePublish(id, "HOLD");
        return id;
    }

    @Test
    void officialClosureWithoutDatesIsNotUnknownAndSourceReopeningClearsIt() {
        String url = "https://apply.lh.or.kr/example?scope=" + UUID.randomUUID();
        String key = org.jibro.collection.LhNoticeClient.sourceKey(url);
        var closedItem = new org.jibro.collection.LhNoticeClient.Item(key, group + "closed-source", "서울", "국민임대", url, day, "a".repeat(64), true);
        UUID id = notices.importSource(closedItem);
        notices.samplePublish(id, "HOLD");
        em.flush();
        assertTrue(queries.search(List.of(), List.of(), List.of(), group, "active", 0, 20, now).items().isEmpty());
        assertTrue(queries.search(List.of(), List.of(), List.of("UNKNOWN"), group, "active", 0, 20, now).items().isEmpty());
        assertEquals(List.of(id), queries.search(List.of(), List.of(), List.of("CLOSED"), group, "active", 0, 20, now).items().stream().map(NoticeListSource::id).toList());
        assertEquals("CLOSED", notices.view(notices.entity(id), false, now).status());
        assertTrue(AutomaticReviewEligibility.closed(notices.entity(id), notices.content(id), now));
        // A source status alone supplies no invented application start/end date.
        assertNull(notices.entity(id).endDate);
        notices.entity(id).endDate = day.minusDays(28);
        em.flush();
        assertEquals(1, queries.search(List.of(), List.of(), List.of(), group, "active", 0, 20, now).totalElements());
        notices.entity(id).endDate = day.minusDays(29);
        em.flush();
        assertEquals(0, queries.search(List.of(), List.of(), List.of(), group, "active", 0, 20, now).totalElements());
        notices.entity(id).endDate = null;
        notices.importSource(new org.jibro.collection.LhNoticeClient.Item(key, closedItem.title(), "서울", "국민임대", url, day, "b".repeat(64), false));
        notices.samplePublish(id, "HOLD");
        em.flush();
        assertFalse(notices.entity(id).sourceClosed);
        assertEquals("UNKNOWN", notices.view(notices.entity(id), false, now).status());
        assertEquals(1, queries.search(List.of(), List.of(), List.of(), group, "active", 0, 20, now).totalElements());
    }

    @Test
    void appliesCalendarDayBoundaryBeforeCountPaginationKeywordAndEverySort() {
        UUID open = notice("open", day.minusDays(2), day.plusDays(2));
        UUID future = notice("future", day.plusDays(2), day.plusDays(4));
        UUID unknown = notice("unknown", null, null);
        UUID boundary = notice("28", day.minusDays(40), day.minusDays(28));
        UUID old = notice("29", day.minusDays(40), day.minusDays(29));
        UUID today = notice("timed", day.minusDays(3), day);
        notices.entity(today).applicationEndAt = now;
        em.flush();
        var expected = Set.of(open, future, unknown, boundary, today);
        for (String sort : List.of("active", "newest", "deadline")) {
            var found = new HashSet<UUID>();
            for (int page = 0; page < 3; page++) {
                var result = queries.search(List.of("서울"), List.of("국민임대"), List.of(), group, sort, page, 2, now);
                assertEquals(5, result.totalElements());
                assertEquals(3, result.totalPages());
                found.addAll(result.items().stream().map(NoticeListSource::id).toList());
            }
            assertEquals(expected, found);
        }
        assertTrue(queries.search(List.of(), List.of(), List.of(), group + "29", "active", 0, 20, now).items().isEmpty());
        var explicit = queries.search(List.of(), List.of(), List.of("CLOSED"), group, "active", 0, 20, now);
        assertEquals(Set.of(old, boundary, today), new HashSet<>(explicit.items().stream().map(NoticeListSource::id).toList()));
        assertDoesNotThrow(() -> notices.notice(old));
        assertFalse(queries.search(List.of(), List.of(), List.of(), group, "active", 0, 20,
            day.plusDays(1).atStartOfDay(ApplicationPeriod.SEOUL).toInstant()).items().stream().anyMatch(n -> n.id().equals(boundary)));
    }
}
