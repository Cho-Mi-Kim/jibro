package org.jibro.collection.extraction;

import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.jibro.notice.NoticeService;
import org.jibro.notice.SampleData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExtractionRetryIntegrationTest {
    @Autowired ExtractionStore store;
    @Autowired NoticeService notices;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;
    UUID notice;

    @BeforeEach void setup() {
        notice = notices.insert("retry-test-" + UUID.randomUUID(), "발췌 재시도", "LH", "서울", "임대",
                LocalDate.now(), LocalDate.now().plusDays(30), LocalDate.now(), SampleData.content());
        entities.flush();
    }

    @Test void failedSourcesRetryTwiceWithDelayAndKeepSuccessfulAndEmptySources() {
        var draft = store.enqueue(notice, false);
        UUID id = UUID.fromString(draft.id());
        Source extracted = source("EXTRACTED", "good");
        Source empty = source("EMPTY", "empty");
        Source failed = source("FAILED", "bad");
        for (int attempt = 0; attempt < 3; attempt++) {
            var job = store.claim();
            assertNotNull(job);
            store.finish(job, List.of(extracted, empty, failed), "PARTIAL", "부분 실패");
            var current = store.find(id);
            assertEquals(extracted, current.sources().get(0));
            assertEquals(empty, current.sources().get(1));
            assertEquals(failed.context(), current.sources().get(2).context());
            if (attempt < 2) {
                assertEquals("QUEUED", current.status());
                assertEquals("DEFERRED", current.sources().get(2).status());
                assertEquals(attempt == 0 ? 30 : 60, nextDelay(id));
                assertNull(store.claim());
                due(id);
            } else {
                assertEquals("PARTIAL", current.status());
                assertEquals("FAILED", current.sources().get(2).status());
                assertNull(store.claim());
            }
        }
        assertEquals(2, retries(id));
    }

    @Test void emptyExtractionDoesNotRetryAndContinuationDoesNotUseRetryBudget() {
        UUID id = UUID.fromString(store.enqueue(notice, false).id());
        var first = store.claim();
        store.finish(first, List.of(source("EXTRACTED", "good"), source("DEFERRED", "later")), "QUEUED", "이어서 처리");
        assertEquals(0, retries(id));
        var continuation = store.claim();
        assertNotNull(continuation);
        assertNotEquals(first.claimToken(), continuation.claimToken());
        store.finish(continuation, List.of(source("EXTRACTED", "good"), source("EMPTY", "later")), "PARTIAL", "텍스트 없음");
        assertEquals("PARTIAL", store.find(id).status());
        assertEquals(0, retries(id));
        assertNull(store.claim());
    }

    @Test void recoveredWorkerCannotFinishNewAttemptAndRepeatedInterruptionsAreBounded() {
        UUID id = UUID.fromString(store.enqueue(notice, false).id());
        var old = store.claim();
        expire(id);
        assertNull(store.claim());
        assertEquals("QUEUED", store.find(id).status());
        assertEquals(1, retries(id));
        due(id);
        var resumed = store.claim();
        assertNotEquals(old.claimToken(), resumed.claimToken());
        store.finish(old, List.of(source("EXTRACTED", "late")), "READY", "늦은 완료");
        assertEquals("RUNNING", store.find(id).status());
        expire(id);
        assertNull(store.claim());
        assertEquals(2, retries(id));
        due(id);
        assertNotNull(store.claim());
        expire(id);
        assertNull(store.claim());
        assertEquals("FAILED", store.find(id).status());
        assertEquals(2, retries(id));
    }

    @Test void changedNoticeNeverQueuesAnOldFailedExtraction() {
        UUID id = UUID.fromString(store.enqueue(notice, false).id());
        var job = store.claim();
        notices.updateContent(notice, job.revision(), notices.content(notice));
        entities.flush();
        store.finish(job, List.of(source("FAILED", "bad")), "FAILED", "실패");
        assertEquals("SUPERSEDED", store.find(id).status());
        assertEquals(0, retries(id));
        assertNull(store.claim());
    }

    @Test void explicitForceAfterInterruptedRunCreatesFreshJobAndRejectsLateFinish() {
        UUID id = UUID.fromString(store.enqueue(notice, false).id());
        var old = store.claim();
        expire(id);
        var fresh = store.enqueue(notice, true);
        assertNotEquals(id.toString(), fresh.id());
        assertEquals("FAILED", store.find(id).status());
        store.finish(old, List.of(source("EXTRACTED", "late")), "READY", "늦은 완료");
        assertEquals("FAILED", store.find(id).status());
    }

    @ParameterizedTest
    @ValueSource(ints = {315, 599, 600})
    void runningOcrJobCannotBeReclaimedOrForcedBeforeLeaseExpires(int seconds) {
        UUID id = UUID.fromString(store.enqueue(notice, false).id());
        var running = store.claim(notice);
        age(id, seconds);

        assertNull(store.claim(notice));
        assertEquals(id.toString(), store.enqueue(notice, true).id());
        assertEquals("RUNNING", store.find(id).status());
        assertEquals(0, retries(id));
        assertEquals(running.claimToken(), db.queryForObject(
            "SELECT claim_token FROM notice_extraction WHERE id=?", UUID.class, id));

        store.finish(running, List.of(source("EXTRACTED", "completed-ocr")), "READY", "완료");
        assertEquals("READY", store.find(id).status());
    }

    @Test void automaticRecoveryStartsOnlyAfterTenMinutesAndRejectsTheOldWorker() {
        UUID id = UUID.fromString(store.enqueue(notice, false).id());
        var expired = store.claim(notice);
        age(id, 601);

        assertNull(store.claim(notice));
        assertEquals("QUEUED", store.find(id).status());
        assertEquals(1, retries(id));
        assertEquals(30, nextDelay(id));
        due(id);
        var resumed = store.claim(notice);
        assertNotEquals(expired.claimToken(), resumed.claimToken());
        assertFalse(store.checkpoint(expired, List.of(source("EXTRACTED", "stale-ocr"))));
        store.finish(expired, List.of(source("EXTRACTED", "stale-ocr")), "READY", "늦은 완료");
        assertEquals("RUNNING", store.find(id).status());
        assertTrue(store.find(id).sources().isEmpty());
    }

    @Test void successfulFileCheckpointRenewsTheLeaseForTheNextOcrFile() {
        UUID id = UUID.fromString(store.enqueue(notice, false).id());
        var running = store.claim(notice);
        age(id, 599);
        var completed = List.of(source("EXTRACTED", "first-file"));

        assertTrue(store.checkpoint(running, completed));
        assertEquals(0L, db.queryForObject(
            "SELECT extract(epoch FROM now()-started_at)::bigint FROM notice_extraction WHERE id=?", Long.class, id));
        assertNull(store.claim(notice));
        assertEquals(id.toString(), store.enqueue(notice, true).id());
        assertEquals(completed, store.find(id).sources());
        assertEquals(0, retries(id));
    }

    private Source source(String status, String name) {
        String url = "https://apply.lh.or.kr/" + name;
        return new Source(name, url, status.equals("EXTRACTED") ? "a".repeat(64) : null, status, "원문 확인",
                status.equals("EXTRACTED") ? List.of(new Excerpt("DOCUMENT", "제출 서류 안내", "공고문 2쪽", url)) : List.of(),
                List.of(new Part("공고문 2쪽", "문맥 보존 " + name)));
    }

    private int retries(UUID id) {
        return db.queryForObject("SELECT retries_used FROM notice_extraction WHERE id=?", Integer.class, id);
    }

    private long nextDelay(UUID id) {
        return db.queryForObject("SELECT extract(epoch FROM next_attempt_at-finished_at)::bigint FROM notice_extraction WHERE id=?", Long.class, id);
    }

    private void due(UUID id) {
        db.update("UPDATE notice_extraction SET next_attempt_at=now() WHERE id=?", id);
    }

    private void expire(UUID id) {
        age(id, 601);
    }

    private void age(UUID id, int seconds) {
        db.update("UPDATE notice_extraction SET started_at=now()-(? * interval '1 second') WHERE id=?", seconds, id);
    }
}
