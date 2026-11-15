package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
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
    @Autowired NoticeService notices;
    @Autowired GuideService guides;
    @Autowired EntityManager entities;

    @Test void databaseFilteringPreservesAllPagesAndNeverPublishesUnreviewedQuestions() {
        String title = "guide-pages-" + UUID.randomUUID();
        var e = new Evidence("공식 원문", "https://apply.lh.or.kr/test", "2030-01-01");
        var questions = IntStream.range(0, 25).mapToObj(i -> new Question("q" + i, "가구 기준 " + i, "확인된 설명", e)).toList();
        var content = new Content("확인된 대상", e, List.of(), questions, List.of(), List.of());
        UUID reviewed = notices.insert("guide-test-" + UUID.randomUUID(), title, "LH", "서울", "임대",
            null, null, LocalDate.now(), content);
        notices.samplePublish(reviewed, "PASS");
        UUID held = notices.insert("guide-test-" + UUID.randomUUID(), title, "LH", "서울", "임대",
            null, null, LocalDate.now().minusDays(1), content);
        notices.samplePublish(held, "HOLD");
        UUID sample = notices.insert("local-sample-" + UUID.randomUUID(), title, "LH", "서울", "임대",
            null, null, LocalDate.now(), content);
        notices.samplePublish(sample, "PASS");
        entities.flush();
        var first = guides.guides("", title, 0);
        var second = guides.guides("", title, 1);
        assertEquals(20, first.items().size());
        assertTrue(first.hasMore());
        assertEquals(9, second.items().size());
        assertFalse(second.hasMore());
        var all = java.util.stream.Stream.concat(first.items().stream(), second.items().stream()).toList();
        assertEquals(29, all.stream().map(Guide::id).distinct().count());
        assertFalse(all.stream().anyMatch(g -> g.id().startsWith(sample.toString())));
        assertEquals(2, all.stream().filter(g -> g.id().startsWith(held.toString())).count());
        assertEquals(5, guides.guides("조건과 용어", title, 1).items().size());
        assertEquals(1, guides.guides("조건과 용어", title + " 가구 24", 0).items().size());
        assertEquals(2, guides.guides("공식 문의처", title + " 문의 경로", 0).items().size());
        assertTrue(guides.guides("", title + " zzz-no-result", 0).items().isEmpty());
    }
}
