package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.text.Normalizer;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class GuidePaginationTest {
    private final GuideRepository repository = mock(GuideRepository.class);
    private final List<Guide> entries = IntStream.range(0, 45).mapToObj(this::entry).toList();
    private final GuideService service = new GuideService(mock(Environment.class), repository,
        new GuideCatalog(new GuideCatalog.CatalogFile("2026-11-21", entries)));

    private Guide entry(int index) {
        var evidence = new Evidence("LH 공식 설명", "https://apply.lh.or.kr/guide/" + index, "2026-11-21");
        return new Guide("guide-" + index, index % 2 == 0 ? "조건과 용어" : "서류 발급 안내",
            "가구 기준 " + index, "가족 범위는 공고에서 확인해요.", evidence,
            "공공임대 신청자", List.of(new Evidence("마이홈 제도 안내", "https://www.myhome.go.kr/" + index, "2026-11-20")));
    }

    @Test
    void cataloguePaginationHasNoGapsOrDuplicatesAndDoesNotQueryNotices() {
        var first = service.guides("", "", 0);
        var second = service.guides("", "", 1);
        var third = service.guides("", "", 2);
        assertEquals(entries.subList(0, 20), first.items());
        assertEquals(entries.subList(20, 40), second.items());
        assertEquals(entries.subList(40, 45), third.items());
        assertTrue(first.hasMore());
        assertTrue(second.hasMore());
        assertFalse(third.hasMore());
        assertEquals(1, second.page());
        assertEquals(3, first.totalPages());
        assertEquals(3, second.totalPages());
        assertEquals(3, third.totalPages());
        assertTrue(service.guides("", "", 10000).items().isEmpty());
        assertEquals(3, service.guides("", "", 10000).totalPages());
        verifyNoInteractions(repository);
    }

    @Test
    void categoryAndAllSearchTermsApplyBeforePaging() {
        var first = service.guides("조건과 용어", "가구", 0);
        var second = service.guides("조건과 용어", "가구", 1);
        assertEquals(20, first.items().size());
        assertEquals(3, second.items().size());
        assertTrue(first.hasMore());
        assertFalse(second.hasMore());
        assertEquals(2, first.totalPages());
        assertEquals(2, second.totalPages());
        assertEquals(List.of("가구 기준 44"), service.guides("조건과 용어", "가구 44", 0)
            .items().stream().map(Guide::title).toList());
        assertEquals(1, service.guides("조건과 용어", "가구 44", 0).totalPages());
        assertTrue(service.guides("서류 발급 안내", "가구 44", 0).items().isEmpty());
        assertEquals(0, service.guides("서류 발급 안내", "가구 44", 0).totalPages());
    }

    @Test
    void searchIncludesBodyScopeAndAllSourcesWithNormalizedText() {
        assertEquals(20, service.guides("", "LH 가족 공공임대 마이홈", 0).items().size());
        assertEquals(service.guides("", "가구", 0),
            service.guides("", Normalizer.normalize("가구", Normalizer.Form.NFD), 0));
        assertEquals(service.guides("", "LH", 0), service.guides("", "lh", 0));
        assertEquals(service.guides("", "가구 기준", 0), service.guides("", "  가구\t 기준  ", 0));
    }

    @Test
    void emptyResultsAndInvalidRequestsStayBounded() {
        assertTrue(service.guides("", "없는검색어", 0).items().isEmpty());
        assertEquals(0, service.guides("", "없는검색어", 0).totalPages());
        assertThrows(IllegalArgumentException.class, () -> service.guides("없는주제", "", 0));
        assertThrows(IllegalArgumentException.class, () -> service.guides("", "", -1));
        assertThrows(IllegalArgumentException.class, () -> service.guides("", "", 10001));
        assertThrows(IllegalArgumentException.class, () -> service.guides("", "x".repeat(201), 0));
        assertThrows(IllegalArgumentException.class, () -> service.guides(null, "", 0));
        assertThrows(IllegalArgumentException.class, () -> service.guides("", null, 0));
    }
}
