package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class GuideScopedGuidanceTest {
    @Test
    void generalGuideKeepsApplicabilityAndMultipleSourcesWithoutNoticeQueries() {
        var evidence = new Evidence("LH 제도 안내", "https://apply.lh.or.kr/", "2026-11-21");
        var additional = new Evidence("마이홈 안내", "https://www.myhome.go.kr/", "2026-11-20");
        var entry = new Guide("household", "조건과 용어", "어떤 가족을 함께 심사하나요?",
            "세대원 범위는 공고마다 달라요.", evidence, "선택한 공고의 심사 대상 세대원 기준", List.of(additional));
        var repository = mock(GuideRepository.class);
        var catalog = new GuideCatalog(new GuideCatalog.CatalogFile("2026-11-21", List.of(entry)));
        var page = new GuideService(mock(Environment.class), repository, catalog).guides("", "", 0);
        assertEquals(List.of(entry), page.items());
        assertEquals("선택한 공고의 심사 대상 세대원 기준", page.items().get(0).scope());
        assertEquals(List.of(additional), page.items().get(0).additionalEvidence());
        verifyNoInteractions(repository);
    }
}
