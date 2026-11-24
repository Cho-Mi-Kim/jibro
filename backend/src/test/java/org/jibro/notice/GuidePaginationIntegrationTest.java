package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

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
class GuidePaginationIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    GuideService guides;
    @Autowired
    GuideCatalog catalog;
    @Autowired
    EntityManager entities;

    @Test
    void noticePublicationCannotReplaceGeneralFaqWithLocalOrUnreviewedCriteria() {
        var before = guides.guides("", "", 0);
        String title = "guide-pages-" + UUID.randomUUID();
        var evidence = new Evidence("개별 공고 원문", "https://apply.lh.or.kr/test", "2020-01-01");
        var content = new Content("특정 지역의 과거 소득 기준", evidence, List.of(),
            List.of(new Question("q", "공고 한정 기준", "미확정 또는 과거 기준", evidence)),
            List.of(), List.of());
        for (String review : List.of("PASS", "HOLD")) {
            UUID id = notices.insert("guide-test-" + UUID.randomUUID(), title, "LH", "서울", "임대",
                null, null, LocalDate.of(2020, 1, 1), content);
            notices.samplePublish(id, review);
        }
        UUID sample = notices.insert("local-sample-" + UUID.randomUUID(), title, "LH", "서울", "임대",
            null, null, LocalDate.now(), content);
        notices.samplePublish(sample, "PASS");
        entities.flush();
        assertEquals(before, guides.guides("", "", 0));
        assertTrue(guides.guides("", title, 0).items().isEmpty());
        assertTrue(guides.guides("", "미확정 또는 과거 기준", 0).items().isEmpty());
    }

    @Test
    void packagedCatalogueIsCompleteAcrossPagesAndEveryTopicHasReviewedSources() {
        var all = new java.util.ArrayList<Guide>();
        for (int index = 0; ; index++) {
            var page = guides.guides("", "", index);
            assertTrue(page.items().size() <= 20);
            assertEquals((catalog.items().size() + 19) / 20, page.totalPages());
            all.addAll(page.items());
            if (!page.hasMore()) break;
        }
        assertEquals(catalog.items(), all);
        assertEquals(all.size(), all.stream().map(Guide::id).distinct().count());
        for (String category : GuideCatalog.CATEGORIES) {
            var page = guides.guides(category, "", 0);
            assertFalse(page.items().isEmpty(), category);
            long matching = catalog.items().stream().filter(guide -> guide.category().equals(category)).count();
            assertEquals((matching + 19) / 20, page.totalPages());
            assertTrue(page.items().stream().allMatch(guide -> guide.category().equals(category)
                && guide.scope() != null && !guide.scope().isBlank()
                && guide.evidence() != null && guide.evidence().checkedAt() != null));
        }
        var empty = guides.guides("", "없는검색어987654321", 0);
        assertTrue(empty.items().isEmpty());
        assertEquals(0, empty.totalPages());
        assertFalse(empty.hasMore());
    }
}
