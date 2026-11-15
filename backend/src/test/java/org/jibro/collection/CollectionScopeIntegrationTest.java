package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.jibro.auth.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"app.collection.max-pages=2", "app.collection.category-codes=06,13", "app.collection.history-start-date=2000-01-01"})
@ActiveProfiles("test")
@Transactional
class CollectionScopeIntegrationTest {
    @Autowired
    CollectionService collections;
    @Autowired
    CollectionRunStore runs;
    @Autowired
    CollectionResponseService archive;
    @Autowired
    AuthService auth;
    @Autowired
    JdbcTemplate db;
    @MockitoBean
    LhNoticeClient client;
    String actor;
    String prefix;

    @BeforeEach
    void setup() throws Exception {
        prefix = UUID.randomUUID().toString();
        String email = prefix + "@example.invalid";
        auth.signup("수집 범위 검사", email, "collection-test-password");
        actor = db.queryForObject("SELECT id::text FROM app_user WHERE email=?", String.class, email);
        when(client.configured()).thenReturn(true);
        when(client.fetch(anyInt(), any(CollectionQueryPlan.Query.class))).thenReturn(new LhNoticeClient.Batch(List.of(), 0));
    }

    @Test
    void bothCategoriesImportOldNoticesAndRepeatedCollectionDoesNotDuplicateOrPublishThem() throws Exception {
        LocalDate oldPublication = LocalDate.of(2024, 1, 1);
        when(client.fetch(eq(1), any(CollectionQueryPlan.Query.class))).thenAnswer(call -> {
            CollectionQueryPlan.Query query = call.getArgument(1);
            return new LhNoticeClient.Batch(List.of(item(query.categoryCode(), oldPublication)), 1);
        });
        var first = collections.collect(actor, 1);
        var second = collections.collect(actor, 1);
        assertEquals("SUCCEEDED", first.status());
        assertEquals("SUCCEEDED", second.status());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM notice WHERE source_key LIKE ?", Integer.class, prefix + "%"));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM notice WHERE source_key LIKE ? AND version=1 AND basic_review='HOLD' AND guide_review='HOLD'", Integer.class, prefix + "%"));
        var receipts = archive.list(UUID.fromString(first.id()));
        assertEquals(List.of("06", "13"), receipts.stream().map(CollectionResponseRepository.Receipt::categoryCode).toList());
        assertTrue(receipts.stream().allMatch(receipt -> receipt.publishedFrom().equals(LocalDate.of(2000, 1, 1))
            && receipt.publishedThrough().equals(receipt.queryDate()) && receipt.page() == 1));
        assertEquals(2, db.queryForObject("SELECT scope_index FROM collection_run WHERE id=?", Integer.class, UUID.fromString(first.id())));
    }

    @Test
    void retryAfterCategoryTransitionKeepsRangeAndDoesNotRestartCompletedCategory() throws Exception {
        LocalDate originalDate = LocalDate.of(2026, 2, 1);
        var welfare = new CollectionQueryPlan.Query("13", LocalDate.of(2000, 1, 1), originalDate);
        when(client.fetch(1, welfare)).thenThrow(new IOException("transient")).thenReturn(new LhNoticeClient.Batch(List.of(), 0));
        UUID id = runs.enqueueManual(actor, 1, Instant.parse("2026-02-01T12:00:00Z"));
        collections.processDue();
        assertEquals("RETRY_WAIT", runs.view(id).status());
        assertEquals(1, db.queryForObject("SELECT scope_index FROM collection_run WHERE id=?", Integer.class, id));
        db.update("UPDATE collection_run SET next_attempt_at=now()-interval '1 second' WHERE id=?", id);
        collections.processDue();
        assertEquals("SUCCEEDED", runs.view(id).status());
        verify(client, times(1)).fetch(1, new CollectionQueryPlan.Query("06", LocalDate.of(2000, 1, 1), originalDate));
        verify(client, times(2)).fetch(1, welfare);
        assertEquals(List.of("06", "13", "13"), archive.list(id).stream().map(CollectionResponseRepository.Receipt::categoryCode).toList());
        assertTrue(archive.list(id).stream().allMatch(receipt -> receipt.queryDate().equals(originalDate)));
    }

    @Test
    void categoryCursorAndCompletedPageSignaturesSurviveChunkBoundaries() throws Exception {
        var firstPage = new LhNoticeClient.Batch(IntStream.range(0, 100).mapToObj(i -> item("first-" + i, LocalDate.of(2024, 1, 1))).toList(), 300);
        var secondPage = new LhNoticeClient.Batch(IntStream.range(0, 100).mapToObj(i -> item("second-" + i, LocalDate.of(2024, 1, 1))).toList(), 300);
        when(client.fetch(eq(1), any(CollectionQueryPlan.Query.class))).thenReturn(firstPage);
        when(client.fetch(eq(2), any(CollectionQueryPlan.Query.class))).thenReturn(secondPage);
        when(client.fetch(eq(3), any(CollectionQueryPlan.Query.class))).thenReturn(firstPage);
        var run = collections.collect(actor, 1);
        assertEquals("QUEUED", run.status());
        UUID id = UUID.fromString(run.id());
        collections.processDue();
        assertEquals("FAILED", runs.view(id).status());
        assertEquals(200, db.queryForObject("SELECT processed_count FROM collection_run WHERE id=?", Integer.class, id));
        assertEquals("REJECTED", archive.list(id).get(2).outcome());
        verify(client, never()).fetch(anyInt(), argThat(query -> query.categoryCode().equals("13")));
    }

    @Test
    void repeatedFullPageWithoutTotalStopsInsteadOfLoopingToSafetyLimit() throws Exception {
        var repeated = new LhNoticeClient.Batch(IntStream.range(0, 100).mapToObj(i -> item("repeat-" + i, LocalDate.of(2024, 1, 1))).toList(), 0);
        when(client.fetch(anyInt(), any(CollectionQueryPlan.Query.class))).thenReturn(repeated);
        var run = collections.collect(actor, 1);
        assertEquals("FAILED", run.status());
        var query = ArgumentCaptor.forClass(CollectionQueryPlan.Query.class);
        verify(client, times(2)).fetch(anyInt(), query.capture());
        assertEquals("06", query.getValue().categoryCode());
        assertEquals(100, db.queryForObject("SELECT processed_count FROM collection_run WHERE id=?", Integer.class, UUID.fromString(run.id())));
    }

    @Test
    void safetyLimitIsPartialAndStillChecksTheOtherCategory() throws Exception {
        when(client.fetch(eq(500), any(CollectionQueryPlan.Query.class))).thenReturn(new LhNoticeClient.Batch(
            IntStream.range(0, 100).mapToObj(i -> item("limit-" + i, LocalDate.of(2024, 1, 1))).toList(), 100_001));
        var run = collections.collect(actor, 500);
        assertEquals("PARTIAL", run.status());
        assertEquals(1, db.queryForObject("SELECT issue_count FROM collection_run WHERE id=?", Integer.class, UUID.fromString(run.id())));
        verify(client).fetch(eq(1), argThat(query -> query.categoryCode().equals("13")));
    }

    @Test
    void scheduledRecoveryEnqueuesOneLatestSlotWithBothPersistedScopes() {
        Instant now = Instant.parse("2026-11-07T05:47:00Z");
        collections.enqueueLatest(now);
        collections.enqueueLatest(now.plusSeconds(10));
        var slot = CollectionSchedule.latest(now);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM collection_run WHERE scheduled_slot=?", Integer.class, java.sql.Timestamp.from(slot)));
        assertEquals("06,13", db.queryForObject("SELECT category_codes FROM collection_run WHERE scheduled_slot=?", String.class, java.sql.Timestamp.from(slot)));
        assertEquals(LocalDate.of(2000, 1, 1), db.queryForObject("SELECT query_from FROM collection_run WHERE scheduled_slot=?", java.sql.Date.class, java.sql.Timestamp.from(slot)).toLocalDate());
    }

    private LhNoticeClient.Item item(String key, LocalDate published) {
        return new LhNoticeClient.Item(prefix + "-" + key, "검수 전 공식 공고 " + key, "서울", "청년 매입임대",
            "https://apply.lh.or.kr/example?id=" + key, published, "stable-" + key);
    }
}
