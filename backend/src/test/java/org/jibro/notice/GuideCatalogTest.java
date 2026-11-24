package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class GuideCatalogTest {
    private final Evidence evidence = new Evidence("LH 제도 안내", "https://apply.lh.or.kr/", "2026-11-21");

    private Guide guide(Evidence source) {
        return new Guide("guide-household", "조건과 용어", "어떤 가족을 함께 심사하나요?",
            "신청자와 배우자의 범위도 확인해요.", source, "공고에서 정한 심사 대상 가족", List.of());
    }

    private GuideCatalog catalog(List<Guide> items) {
        return new GuideCatalog(new GuideCatalog.CatalogFile("2026-11-21", items));
    }

    @Test
    void reviewedCatalogueIsImmutable() {
        var entries = new ArrayList<>(List.of(guide(evidence)));
        var catalog = catalog(entries);
        entries.clear();
        assertEquals(1, catalog.items().size());
        assertThrows(UnsupportedOperationException.class, () -> catalog.items().clear());
    }

    @Test
    void supportsOfficialWelfareAndLocalHousingCounsellingSources() {
        for (String url : List.of("https://www.mohw.go.kr/", "https://www.seoulhousing.kr/")) {
            assertEquals(1, catalog(List.of(guide(new Evidence("공식 안내", url, "2026-11-21")))).items().size());
        }
    }

    @Test
    void rejectsMissingContentUnknownCategoriesAndDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(guide(evidence), guide(evidence))));
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(new Guide("id", "조건과 용어", "제목", "", evidence, "범위", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(new Guide("id", "없는 분류", "제목", "내용", evidence, "범위", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(new Guide("id", "조건과 용어", "제목", "내용", evidence))));
    }

    @Test
    void rejectsUnofficialOrInsecureLinksAndMissingOrFutureChecks() {
        for (String url : List.of("http://apply.lh.or.kr/", "https://apply.lh.or.kr.evil.example/",
            "https://example.com/", "https://user@apply.lh.or.kr/", "https://apply.lh.or.kr:444/")) {
            assertThrows(IllegalArgumentException.class, () -> catalog(List.of(guide(new Evidence("공식 안내", url, "2026-11-21")))), url);
        }
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(guide(null))));
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(guide(new Evidence("공식 안내", evidence.url(), "")))));
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(guide(new Evidence("공식 안내", evidence.url(), "2026-11-22")))));
        assertThrows(IllegalArgumentException.class, () -> catalog(List.of(new Guide("id", "조건과 용어", "제목", "내용", evidence,
            "범위", List.of(evidence)))));
    }
}
