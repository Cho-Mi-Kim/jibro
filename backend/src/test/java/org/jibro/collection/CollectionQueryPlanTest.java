package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class CollectionQueryPlanTest {
    @Test
    void validatesSupportedCategoriesAndKeepsHistoricalSearchExplicit() {
        var plan = new CollectionQueryPlan("06, 13", LocalDate.of(2000, 1, 1));
        assertEquals(List.of("06", "13"), plan.categories());
        assertEquals("06,13", plan.categoryCodes());
        assertEquals(LocalDate.of(2000, 1, 1), plan.from(LocalDate.of(2026, 11, 7)));
        for (String invalid : List.of("", "06,", "06,06", "06,01", "../../", "06,13,06"))
            assertThrows(IllegalArgumentException.class, () -> new CollectionQueryPlan(invalid, LocalDate.of(2000, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new CollectionQueryPlan("06", LocalDate.of(1899, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new CollectionQueryPlan("13", LocalDate.now(CollectionSchedule.ZONE).plusDays(1)));
    }

    @Test
    void queryRejectsReversedRangesAndUnsupportedCategories() {
        LocalDate date = LocalDate.of(2026, 11, 7);
        assertThrows(IllegalArgumentException.class, () -> new CollectionQueryPlan.Query("01", date, date));
        assertThrows(IllegalArgumentException.class, () -> new CollectionQueryPlan.Query("13", date, date.minusDays(1)));
    }

    @Test
    void missingTotalContinuesOnlyFullPagesAndRejectsDuplicateItems() {
        var full = new LhNoticeClient.Batch(IntStream.range(0, 100).mapToObj(i -> item("key-" + i)).toList(), 0);
        CollectionPage.validate(full, 1);
        assertTrue(CollectionPage.hasMore(full, 1));
        assertFalse(CollectionPage.hasMore(new LhNoticeClient.Batch(List.of(item("last")), 0), 2));
        assertFalse(CollectionPage.hasMore(new LhNoticeClient.Batch(List.of(), 0), 2));
        var reversed = new ArrayList<>(full.items());
        Collections.reverse(reversed);
        assertEquals(CollectionPage.signature(full), CollectionPage.signature(new LhNoticeClient.Batch(reversed, 0)));
        assertThrows(IllegalArgumentException.class, () -> CollectionPage.validate(new LhNoticeClient.Batch(List.of(item("same"), item("same")), 2), 1));
        assertThrows(IllegalArgumentException.class, () -> CollectionPage.validate(new LhNoticeClient.Batch(List.of(), 201), 2));
    }

    private LhNoticeClient.Item item(String key) {
        return new LhNoticeClient.Item(key, "공식 공고", "서울", "청년 매입임대", "https://apply.lh.or.kr/example", LocalDate.of(2026, 1, 1), key);
    }
}
