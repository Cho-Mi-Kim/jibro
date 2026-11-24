package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.*;
import java.util.*;

import org.jibro.auth.AuthService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"app.collection.max-pages=2", "app.collection.category-codes=06"})
@ActiveProfiles("test")
@Transactional
class CollectionPaginationIntegrationTest {
    @Autowired
    CollectionService collections;
    @Autowired
    CollectionRunStore runs;
    @Autowired
    AuthService auth;
    @Autowired
    JdbcTemplate db;
    @MockitoBean
    LhNoticeClient lh;
    String actor;
    String prefix;

    @BeforeEach
    void setup() {
        prefix = UUID.randomUUID().toString();
        String email = prefix + "@example.invalid";
        auth.signup("페이지 검사", email, "collection-test-password");
        actor = db.queryForObject("SELECT id::text FROM app_user WHERE email=?", String.class, email);
        when(lh.configured()).thenReturn(true);
    }

    @Test
    void aPageChunkQueuesTheSameRunAndAutomaticallyContinuesUntilTheLastPage() throws Exception {
        when(lh.fetch(eq(1), any(CollectionQueryPlan.Query.class))).thenReturn(batch(1, 300));
        when(lh.fetch(eq(2), any(CollectionQueryPlan.Query.class))).thenReturn(batch(2, 300));
        when(lh.fetch(eq(3), any(CollectionQueryPlan.Query.class))).thenReturn(batch(3, 300));
        var first = collections.collect(actor, 1);
        UUID id = UUID.fromString(first.id());
        assertEquals("QUEUED", first.status());
        assertEquals(3, db.queryForObject("SELECT next_page FROM collection_run WHERE id=?", Integer.class, id));
        collections.processDue();
        assertEquals("SUCCEEDED", runs.view(id).status());
        assertEquals(300, db.queryForObject("SELECT processed_count FROM collection_run WHERE id=?", Integer.class, id));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM collection_run WHERE actor_id=?", Integer.class, UUID.fromString(actor)));
        assertEquals(0, db.queryForObject("SELECT retries_used FROM collection_run WHERE id=?", Integer.class, id));
        verify(lh, times(1)).fetch(eq(1), any(CollectionQueryPlan.Query.class));
        verify(lh, times(1)).fetch(eq(2), any(CollectionQueryPlan.Query.class));
        verify(lh, times(1)).fetch(eq(3), any(CollectionQueryPlan.Query.class));
    }

    @Test
    void transientFailureResumesTheFailedPageWithTheOriginalSearchDateAfterRestart() throws Exception {
        Instant originalStart = Instant.parse("2020-01-01T14:59:00Z");
        LocalDate queryDate = LocalDate.of(2020, 1, 1);
        var query = new CollectionQueryPlan.Query("06", LocalDate.of(2000, 1, 1), queryDate);
        UUID id = runs.enqueueManual(actor, 1, originalStart);
        when(lh.fetch(1, query)).thenReturn(batch(1, 300));
        when(lh.fetch(2, query)).thenThrow(new IOException("temporary")).thenReturn(batch(2, 300));
        when(lh.fetch(3, query)).thenReturn(batch(3, 300));
        collections.processDue();
        assertEquals("RETRY_WAIT", runs.view(id).status());
        assertEquals(2, db.queryForObject("SELECT next_page FROM collection_run WHERE id=?", Integer.class, id));
        db.update("UPDATE collection_run SET next_attempt_at=now()-interval '1 second' WHERE id=?", id);
        collections.processDue();
        assertEquals("SUCCEEDED", runs.view(id).status());
        assertEquals(300, db.queryForObject("SELECT processed_count FROM collection_run WHERE id=?", Integer.class, id));
        assertEquals(queryDate, db.queryForObject("SELECT query_date FROM collection_run WHERE id=?", java.sql.Date.class, id).toLocalDate());
        verify(lh, times(1)).fetch(1, query);
        verify(lh, times(2)).fetch(2, query);
        verify(lh, times(1)).fetch(3, query);
    }

    @Test
    void inconsistentEmptyPageDoesNotBecomeSuccessOrAnInfiniteQueue() throws Exception {
        when(lh.fetch(eq(1), any(CollectionQueryPlan.Query.class))).thenReturn(new LhNoticeClient.Batch(List.of(), 300));
        var run = collections.collect(actor, 1);
        assertEquals("FAILED", run.status());
        assertEquals(0, db.queryForObject("SELECT retries_used FROM collection_run WHERE id=?", Integer.class, UUID.fromString(run.id())));
        assertThrows(IllegalArgumentException.class, () -> runs.enqueueManual(actor, 501, Instant.now()));
    }

    private LhNoticeClient.Batch batch(int page, int total) {
        return new LhNoticeClient.Batch(java.util.stream.IntStream.range(0, Math.min(100, Math.max(0, total - (page - 1) * 100)))
                .mapToObj(index -> new LhNoticeClient.Item(prefix + "-" + page + "-" + index, "페이지 " + page,
                        "서울", "청년 매입임대", "https://apply.lh.or.kr/example?page=" + page + "&item=" + index, LocalDate.now(), "same")).toList(), total);
    }
}
