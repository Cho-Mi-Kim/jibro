package org.jibro.notice;

import java.time.*;
import java.util.*;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NoticeSortIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    NoticeQueryRepository queries;
    @Autowired
    EntityManager entities;
    @Autowired
    ObjectMapper json;
    private final LocalDate day = LocalDate.of(2026, 11, 8);
    private final Instant now = Instant.parse("2026-11-08T03:00:00Z");
    private final String group = "notice-sort-" + UUID.randomUUID();

    @Test
    void statusPriorityIsAppliedBeforePaginationWithUnknownAfterOfficialClosures() {
        UUID closed = create("closed", day.minusDays(3), day.minusDays(1));
        UUID open = create("open", day.minusDays(1), day.plusDays(30));
        UUID soon = create("soon", day.minusDays(1), day.plusDays(1));
        UUID upcoming = create("upcoming", day.plusDays(1), day.plusDays(2));
        UUID unknown = create("unknown", null, null);
        UUID early = create("early", day.minusDays(1), day.plusDays(5));
        UUID cancelled = create("cancelled", day.minusDays(1), day.plusDays(6));
        notices.entity(early).lifecycleState = "EARLY_CLOSED";
        notices.entity(cancelled).lifecycleState = "CANCELLED";
        entities.flush();

        var all = new ArrayList<UUID>();
        for (int page = 0; page < 3; page++) {
            var result = search("active", page, 2, now);
            assertEquals(5, result.totalElements());
            all.addAll(result.items().stream().map(NoticeListSource::id).toList());
        }
        assertEquals(List.of(soon, open, upcoming, closed, unknown), all);
        assertEquals("UNKNOWN", notices.view(notices.entity(unknown), false, now).status());
    }

    @Test
    void explicitDeadlineAndNewestModesKeepTheirMeaning() {
        UUID closed = create("closed", day.minusDays(3), day.minusDays(1));
        UUID open = create("open", day.minusDays(1), day.plusDays(30));
        notices.entity(closed).publishedDate = day;
        notices.entity(open).publishedDate = day.minusDays(5);
        entities.flush();
        assertEquals(closed, search("deadline", 0, 10, now).items().get(0).id());
        assertEquals(closed, search("newest", 0, 10, now).items().get(0).id());
        assertEquals(open, search("active", 0, 10, now).items().get(0).id());
        assertDoesNotThrow(() -> notices.search(List.of(), List.of(), List.of(), group, "active", 0, 10));
    }

    @Test
    void exactClosingInstantMovesOnlyTheTimedNoticeBelowStillOpenDateOnlyNotice() {
        UUID timed = create("timed", day.minusDays(1), day);
        UUID untimed = create("untimed", day.minusDays(1), day);
        notices.entity(timed).applicationEndAt = now;
        entities.flush();
        assertEquals(List.of(untimed, timed), search("active", 0, 10, now).items().stream().map(NoticeListSource::id).toList());
        assertEquals("CLOSED", notices.view(notices.entity(timed), false, now).status());
        assertEquals("OPEN", notices.view(notices.entity(untimed), false, now).status());
    }

    @Test
    void observedOfficialClosureIsFilteredAndSortedBeforePaginationWithoutInventingDeadline() {
        UUID open = create("open", day.minusDays(2), day.plusDays(20));
        UUID upcoming = create("upcoming", day.plusDays(1), day.plusDays(20));
        UUID closed = create("observed-closed", day.minusDays(20), null);
        UUID unknown = create("unknown", day.minusDays(20), null);
        UUID closureWithFutureDate = create("observed-future", day.minusDays(2), day.plusDays(10));
        for (UUID id : List.of(closed, closureWithFutureDate)) {
            var c = notices.content(id);
            String reason = "공식 상세의 공고상태가 접수마감입니다.";
            notices.entity(id).content = json.writeValueAsString(new Content(reason, c.evidence(), c.attachments(),
                c.questions(), c.documents(), c.schedules(), c.reviewIssues(), new OfficialApplicationClosure(reason, c.evidence())));
        }
        entities.flush();
        entities.clear();
        var pages = new ArrayList<UUID>();
        for (int page = 0; page < 3; page++) pages.add(search("active", page, 1, now).items().get(0).id());
        assertEquals(List.of(open, upcoming, unknown), pages);
        for (String status : List.of("OPEN", "UPCOMING", "UNKNOWN", "CLOSED")) {
            var result = queries.search(List.of(), List.of(), List.of(status), group, "active", 0, 10, now);
            var expected = switch (status) {
                case "OPEN" -> List.of(open);
                case "UPCOMING" -> List.of(upcoming);
                case "UNKNOWN" -> List.of(unknown);
                default -> List.of(closureWithFutureDate, closed);
            };
            assertEquals(expected, result.items().stream().map(NoticeListSource::id).toList());
        }
        var observed = notices.view(notices.entity(closed), false, now);
        assertEquals("CLOSED", observed.status());
        assertFalse(observed.canStart());
        assertNull(observed.endDate());
        assertNull(observed.applicationTiming().endsAt());
        assertNull(observed.applicationTiming().nextChangeAt());
        assertEquals("UNKNOWN", notices.view(notices.entity(unknown), false, now).status());
        assertTrue(notices.view(notices.entity(unknown), false, now).canStart());
    }

    @Test
    void guidancePriorityIsAppliedWithinEveryStatusBeforePagination() {
        var expected = new ArrayList<UUID>();
        for (int state = 0; state < 3; state++) {
            for (int guidance = 0; guidance < 4; guidance++) {
                LocalDate start = state == 1 ? day.plusDays(1) : day.minusDays(20);
                // Earlier deadlines deliberately belong to lower-priority guidance.
                LocalDate end = state == 2 ? day.minusDays(guidance + 1) : day.plusDays(20 - guidance);
                UUID id = create("matrix-" + state + "-" + guidance, start, end);
                guidance(id, guidance);
                expected.add(id);
            }
        }
        entities.flush();
        var actual = new ArrayList<UUID>();
        for (int page = 0; page < 4; page++) {
            var result = search("active", page, 3, now);
            assertEquals(12, result.totalElements());
            actual.addAll(result.items().stream().map(NoticeListSource::id).toList());
        }
        assertEquals(expected, actual);
    }

    @Test
    void legacyMissingIssuesAndNullIssuesAreFullyAvailableWhileChangedGuidanceIsReviewing() {
        UUID absent = create("absent-issues", day.minusDays(1), day.plusDays(5));
        UUID nullIssues = create("null-issues", day.minusDays(1), day.plusDays(6));
        UUID empty = create("empty-issues", day.minusDays(1), day.plusDays(7));
        UUID partial = create("partial-issues", day.minusDays(1), day.plusDays(2));
        UUID changed = create("changed-guide", day.minusDays(1), day.plusDays(3));
        UUID unsupported = create("unsupported", day.minusDays(1), day.plusDays(1));
        guidance(partial, 1);
        guidance(changed, 1);
        notices.entity(changed).changeReview = true;
        guidance(unsupported, 3);
        notices.entity(absent).content = notices.entity(absent).content.replace(",\"reviewIssues\":[]", "");
        notices.entity(nullIssues).content = notices.entity(nullIssues).content.replace("\"reviewIssues\":[]", "\"reviewIssues\":null");
        assertFalse(notices.entity(absent).content.contains("reviewIssues"));
        assertTrue(notices.entity(nullIssues).content.contains("\"reviewIssues\":null"));
        entities.flush();
        assertEquals(List.of(absent, nullIssues, empty, partial, changed, unsupported),
            search("active", 0, 20, now).items().stream().map(NoticeListSource::id).toList());
        assertEquals("CHANGE_REVIEW", notices.view(notices.entity(changed), false, now).guideState());
    }

    private void guidance(UUID id, int priority) {
        var n = notices.entity(id);
        n.guideReview = priority == 2 ? "HOLD" : priority == 3 ? "UNSUPPORTED" : "PASS";
        if (priority == 1) {
            var c = notices.content(id);
            var issue = new ReviewIssue("schedule-source", "SCHEDULE", "발표일 확인 필요", "공식 자료의 발표일이 다릅니다.",
                List.of(), List.of(), List.of(c.evidence()));
            n.content = json.writeValueAsString(new Content(c.summary(), c.evidence(), c.attachments(), c.questions(),
                c.documents(), c.schedules(), List.of(issue)));
        }
    }

    private Page<NoticeListSource> search(String sort, int page, int size, Instant clock) {
        return queries.search(List.of(), List.of(), List.of(), group, sort, page, size, clock);
    }

    private UUID create(String suffix, LocalDate start, LocalDate end) {
        var evidence = new Evidence("검사 근거", "https://apply.lh.or.kr/", "2026-11-01");
        var content = new Content("정렬 검증", evidence, List.of(), List.of(), List.of(), List.of());
        UUID id = notices.insert(UUID.randomUUID().toString(), group + suffix, "LH", "서울", "임대", start, end, day.minusDays(10), content);
        notices.samplePublish(id, "PASS");
        return id;
    }
}
