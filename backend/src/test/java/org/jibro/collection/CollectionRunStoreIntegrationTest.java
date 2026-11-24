package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;

import org.jibro.auth.AuthService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@SpringBootTest(properties = "LH_SERVICE_KEY=")
@ActiveProfiles("test")
@Transactional
class CollectionRunStoreIntegrationTest {
    @Autowired
    CollectionRunStore runs;
    @Autowired
    JdbcTemplate db;
    @Autowired
    AuthService auth;
    String actor;
    Instant now = Instant.parse("2090-01-01T00:00:00Z");

    @BeforeEach
    void setup() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("수집 검사", email, "collection-test-password");
        actor = db.queryForObject("SELECT id::text FROM app_user WHERE email=?", String.class, email);
    }

    @Test
    void scheduledSlotIsUniqueAndOneExecutionLeaseCoversManualAndScheduledWork() {
        Instant slot = CollectionSchedule.latest(now);
        runs.enqueueScheduled(slot, now);
        runs.enqueueScheduled(slot, now.plusSeconds(30));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM collection_run WHERE scheduled_slot=?", Integer.class, Timestamp.from(slot)));
        var scheduled = runs.claim(null, now);
        assertNotNull(scheduled);
        assertNull(scheduled.actor());
        UUID manual = runs.enqueueManual(actor, 4, now);
        assertNull(runs.claim(manual, now.plusSeconds(10)));
        runs.complete(scheduled, "SUCCEEDED", "수집 완료", now.plusSeconds(20));
        var work = runs.claim(manual, now.plusSeconds(20));
        assertEquals(4, work.page());
        assertEquals(actor, work.actor());
    }

    @Test
    void retryDueTimeAndLastCompletedPageSurviveSeparateClaimAttempts() {
        UUID id = runs.enqueueManual(actor, 1, now);
        var work = runs.claim(id, now);
        runs.checkpoint(work, new CollectionRunStore.PageProgress(0, 2, null, 0, 3, 200, false), now.plusSeconds(5));
        runs.failure(work, new IOException("temporary"), now.plusSeconds(10));
        assertEquals("RETRY_WAIT", runs.view(id).status());
        assertNull(runs.claim(id, now.plusSeconds(69)));
        var retry = runs.claim(id, now.plusSeconds(70));
        assertEquals(3, retry.page());
        assertEquals(200, retry.processed());
        assertEquals(1, retry.retriesUsed());
        assertNotEquals(work.token(), retry.token());
        assertThrows(CollectionRunStore.LeaseLost.class, () -> runs.complete(work, "SUCCEEDED", "old", now.plusSeconds(71)));
    }

    @Test
    void threeRetriesArePersistedThenStopAndPermanentFailuresNeverRetry() {
        UUID id = runs.enqueueManual(actor, 1, now);
        Instant time = now;
        for (int retry = 0; retry < 4; retry++) {
            var work = runs.claim(id, time);
            assertNotNull(work);
            runs.failure(work, new IOException(), time);
            if (retry < 3) {
                assertEquals("RETRY_WAIT", runs.view(id).status());
                time = time.plus(CollectionFailure.delay(new IOException(), retry));
            } else assertEquals("FAILED", runs.view(id).status());
        }
        assertEquals(4, db.queryForObject("SELECT attempt FROM collection_run WHERE id=?", Integer.class, id));
        UUID invalid = runs.enqueueManual(actor, 1, time);
        var work = runs.claim(invalid, time);
        runs.failure(work, CollectionFailure.http(401, null, time), time);
        assertEquals("FAILED", runs.view(invalid).status());
        assertEquals(0, db.queryForObject("SELECT retries_used FROM collection_run WHERE id=?", Integer.class, invalid));
    }

    @Test
    void restartRecoversExpiredLeaseAndFencesAnOldWorker() {
        UUID id = runs.enqueueManual(actor, 2, now);
        var abandoned = runs.claim(id, now);
        runs.checkpoint(abandoned, new CollectionRunStore.PageProgress(0, 4, null, 0, 5, 300, false), now.plusSeconds(10));
        var resumed = runs.claim(id, now.plusSeconds(131));
        assertNotNull(resumed);
        assertEquals(5, resumed.page());
        assertEquals(1, resumed.retriesUsed());
        assertThrows(CollectionRunStore.LeaseLost.class, () -> runs.withinLease(abandoned, now.plusSeconds(132), () -> "old result"));
        assertEquals("current", runs.withinLease(resumed, now.plusSeconds(132), () -> "current"));
    }

    @Test
    void providerDelayAlsoBlocksNewManualAndScheduledRunsUntilItsFullDeadline() {
        UUID first = runs.enqueueManual(actor, 1, now);
        var throttled = runs.claim(first, now);
        runs.failure(throttled, CollectionFailure.http(429, "172800", now), now);
        Instant due = now.plus(Duration.ofHours(48));
        assertEquals(due, db.queryForObject("SELECT next_attempt_at FROM collection_run WHERE id=?", Timestamp.class, first).toInstant());
        UUID manual = runs.enqueueManual(actor, 1, now.plusSeconds(1));
        Instant slot = CollectionSchedule.latest(now.plusSeconds(3600));
        runs.enqueueScheduled(slot, now.plusSeconds(3600));
        UUID scheduled = db.queryForObject("SELECT id FROM collection_run WHERE scheduled_slot=?", UUID.class, Timestamp.from(slot));
        assertNull(runs.claim(manual, now.plus(Duration.ofHours(24))));
        assertNull(runs.claim(scheduled, due.minusSeconds(1)));
        assertNull(runs.claim(null, due.minusSeconds(1)));
        assertEquals(0, db.queryForObject("SELECT attempt FROM collection_run WHERE id=?", Integer.class, manual));
        var resumed = runs.claim(manual, due);
        assertNotNull(resumed);
        runs.complete(resumed, "SUCCEEDED", "공급자 대기 후 실행", due);
        assertNotNull(runs.claim(scheduled, due));
    }

    @Test
    void finalFailedAttemptRetainsProviderHoldAfterRetryBudgetIsExhausted() {
        UUID first = runs.enqueueManual(actor, 1, now);
        Instant attemptAt = now;
        for (int retry = 0; retry < 3; retry++) {
            var work = runs.claim(first, attemptAt);
            runs.failure(work, new IOException("temporary"), attemptAt);
            attemptAt = attemptAt.plus(CollectionFailure.delay(new IOException(), retry));
        }
        var finalAttempt = runs.claim(first, attemptAt);
        runs.failure(finalAttempt, CollectionFailure.http(429, "172800", attemptAt), attemptAt);
        Instant providerDue = attemptAt.plus(Duration.ofHours(48));
        assertEquals("FAILED", runs.view(first).status());
        assertNull(db.queryForObject("SELECT next_attempt_at FROM collection_run WHERE id=?", Timestamp.class, first));
        assertEquals(providerDue, runs.providerResumeAt(attemptAt));
        assertTrue(runs.view(first).message().contains("새 수집에도 적용됩니다"));

        UUID manual = runs.enqueueManual(actor, 1, attemptAt.plusSeconds(1));
        Instant slot = CollectionSchedule.latest(attemptAt.plusSeconds(3600));
        runs.enqueueScheduled(slot, attemptAt.plusSeconds(3600));
        UUID scheduled = db.queryForObject("SELECT id FROM collection_run WHERE scheduled_slot=?", UUID.class, Timestamp.from(slot));
        assertNull(runs.claim(manual, providerDue.minusSeconds(1)));
        assertNull(runs.claim(scheduled, providerDue.minusSeconds(1)));
        // Provider state is independent of the failed run and remains after its audit row is removed.
        db.update("DELETE FROM collection_run WHERE id=?", first);
        assertEquals(providerDue, runs.providerResumeAt(providerDue.minusSeconds(1)));
        assertNull(runs.claim(null, providerDue.minusSeconds(1)));
        assertNull(runs.providerResumeAt(providerDue));
        var next = runs.claim(manual, providerDue);
        assertNotNull(next);
        runs.complete(next, "SUCCEEDED", "공급자 대기 종료", providerDue);
        assertNotNull(runs.claim(scheduled, providerDue));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void competingInstancesCommitOneSlotAndOnlyOneExecutionClaim() throws Exception {
        Instant slot = Instant.parse("2095-01-01T00:00:00Z");
        UUID id = null;
        var workers = Executors.newFixedThreadPool(2);
        try {
            var enqueueStart = new CountDownLatch(1);
            Callable<Void> enqueue = () -> {
                enqueueStart.await();
                runs.enqueueScheduled(slot, slot);
                return null;
            };
            var first = workers.submit(enqueue);
            var second = workers.submit(enqueue);
            enqueueStart.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(1, db.queryForObject("SELECT count(*) FROM collection_run WHERE scheduled_slot=?", Integer.class, Timestamp.from(slot)));
            id = db.queryForObject("SELECT id FROM collection_run WHERE scheduled_slot=?", UUID.class, Timestamp.from(slot));
            UUID requested = id;
            var claimStart = new CountDownLatch(1);
            Callable<CollectionRunStore.Work> claim = () -> {
                claimStart.await();
                return runs.claim(requested, slot);
            };
            var a = workers.submit(claim);
            var b = workers.submit(claim);
            claimStart.countDown();
            var claimedA = a.get(10, TimeUnit.SECONDS);
            var claimedB = b.get(10, TimeUnit.SECONDS);
            assertEquals(1, (claimedA == null ? 0 : 1) + (claimedB == null ? 0 : 1));
            assertEquals(1, db.queryForObject("SELECT attempt FROM collection_run WHERE id=?", Integer.class, id));
            runs.complete(claimedA != null ? claimedA : claimedB, "SUCCEEDED", "동시 실행 검사", slot);
        } finally {
            workers.shutdownNow();
            if (id != null) db.update("DELETE FROM collection_run WHERE id=?", id);
            else db.update("DELETE FROM collection_run WHERE scheduled_slot=?", Timestamp.from(slot));
            db.update("DELETE FROM app_user WHERE id=?", UUID.fromString(actor));
        }
    }
}
