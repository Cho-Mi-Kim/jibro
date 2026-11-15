package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class GuidePaginationTest {
    private final GuideRepository repository = mock(GuideRepository.class);
    private final GuideService service = new GuideService(mock(Environment.class), repository);

    private GuideRepository.Source source(int index) {
        var e = new Evidence("공고 원문", "https://apply.lh.or.kr/" + index, "2030-01-01");
        return new GuideRepository.Source(new UUID(0, index), "공고 " + index, "LH", true,
            new Content("대상 안내", e, List.of(), List.of(new Question("q", "거주 기준 " + index, "지역별 기준", e)),
                List.of(), List.of()));
    }

    @Test void firstPageHydratesOneBoundedBatchAndFollowingPageHasNoGaps() {
        var sources = IntStream.range(0, 32).mapToObj(this::source).toList();
        when(repository.findPublishedSources(0, 32, false, List.of())).thenReturn(sources);
        var first = service.guides("", "", 0);
        assertEquals(20, first.items().size());
        assertTrue(first.hasMore());
        verify(repository).findPublishedSources(0, 32, false, List.of());
        verifyNoMoreInteractions(repository);
        var second = service.guides("", "", 1);
        assertEquals(20, second.items().size());
        var all = java.util.stream.Stream.concat(first.items().stream(), second.items().stream()).toList();
        assertEquals(40, all.stream().map(Guide::id).distinct().count());
        assertEquals(new UUID(0, 6) + "-q-q", second.items().get(0).id());
    }

    @Test void searchFindsMatchesBeyondFirstSourceBatchAndFiltersBeforePaging() {
        when(repository.findPublishedSources(0, 32, true, List.of("거주", "32"))).thenReturn(IntStream.range(0, 32).mapToObj(this::source).toList());
        when(repository.findPublishedSources(32, 32, true, List.of("거주", "32"))).thenReturn(List.of(source(32)));
        var page = service.guides("조건과 용어", "거주 32", 0);
        assertEquals(List.of("거주 기준 32"), page.items().stream().map(Guide::title).toList());
        assertFalse(page.hasMore());
    }

    @Test void emptyResultsAndInvalidRequestsDoNotBecomeUnboundedResponses() {
        when(repository.findPublishedSources(0, 32, false, List.of("없는검색어"))).thenReturn(List.of(source(0)));
        assertTrue(service.guides("", "없는검색어", 0).items().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.guides("없는주제", "", 0));
        assertThrows(IllegalArgumentException.class, () -> service.guides("", "", -1));
        assertThrows(IllegalArgumentException.class, () -> service.guides("", "x".repeat(201), 0));
    }
}
