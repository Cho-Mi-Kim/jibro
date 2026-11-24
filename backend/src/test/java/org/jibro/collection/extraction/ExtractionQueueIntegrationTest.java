package org.jibro.collection.extraction;

import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.jibro.notice.NoticeService;
import org.jibro.notice.SampleData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExtractionQueueIntegrationTest {
    @Autowired
    ExtractionStore store;
    @Autowired
    NoticeService notices;
    @Autowired
    JdbcTemplate db;
    @Autowired
    EntityManager entities;

    @Test
    void unchangedRecollectionReusesOldCompletedEvidenceUntilExplicitRetry() {
        UUID notice = notice();
        fingerprint(notice, "a");
        var initial = store.enqueue(notice, false);
        var job = store.claim();
        store.finish(job, List.of(extracted()), "READY", "완료");
        age(job.id());

        var reused = store.enqueue(notice, false);

        assertEquals(initial.id(), reused.id());
        assertEquals("READY", reused.status());
        assertEquals(List.of(extracted()), reused.sources());
        assertNull(store.claim());
        assertEquals(1, count(notice));
        assertNotEquals(initial.id(), store.enqueue(notice, true).id());
    }

    @Test
    void completedManualNoticeWithNoFingerprintAlsoRemainsReusable() {
        UUID notice = notice();
        var initial = store.enqueue(notice, false);
        var job = store.claim();
        store.finish(job, List.of(extracted()), "READY", "완료");
        age(job.id());

        assertEquals(initial.id(), store.enqueue(notice, false).id());
        assertEquals(1, count(notice));
    }

    @Test
    void recollectionDoesNotRestartExhaustedFailureOrPartialRetryBudget() {
        for (String terminal : List.of("FAILED", "PARTIAL")) {
            UUID notice = notice();
            var draft = store.enqueue(notice, false);
            var job = store.claim();
            db.update("UPDATE notice_extraction SET retries_used=2 WHERE id=?", job.id());
            store.finish(job, List.of(failed()), terminal, "재시도 소진");
            age(job.id());

            assertEquals(draft.id(), store.enqueue(notice, false).id());
            assertEquals(terminal, store.find(job.id()).status());
            assertEquals(2, db.queryForObject("SELECT retries_used FROM notice_extraction WHERE id=?", Integer.class, job.id()));
            assertNull(store.claim());
            var fresh = store.enqueue(notice, true);
            assertNotEquals(draft.id(), fresh.id());
            assertEquals(0, db.queryForObject("SELECT retries_used FROM notice_extraction WHERE id=?", Integer.class, UUID.fromString(fresh.id())));
            store.finish(store.claim(), List.of(extracted()), "READY", "명시적 재시도 완료");
        }
    }

    @Test
    void attachmentRevisionAndSourceFingerprintEachInvalidateCompletedEvidence() {
        UUID notice = notice();
        fingerprint(notice, "a");
        var first = store.enqueue(notice, false);
        store.finish(store.claim(), List.of(extracted()), "READY", "완료");

        db.update("UPDATE notice SET revision=revision+1 WHERE id=?", notice);
        var revisionChanged = store.enqueue(notice, false);
        assertNotEquals(first.id(), revisionChanged.id());
        store.finish(store.claim(), List.of(extracted()), "READY", "완료");

        fingerprint(notice, "b");
        var fingerprintChanged = store.enqueue(notice, false);
        assertNotEquals(revisionChanged.id(), fingerprintChanged.id());
        assertEquals(3, count(notice));
    }

    @Test
    void oldQueuedRevisionsAreRetiredTogetherBeforeClaimingCurrentWork() {
        UUID revisionNotice = notice();
        UUID fingerprintNotice = notice();
        UUID currentNotice = notice();
        fingerprint(fingerprintNotice, "a");
        var oldRevision = store.enqueue(revisionNotice, false);
        var oldFingerprint = store.enqueue(fingerprintNotice, false);
        var current = store.enqueue(currentNotice, false);
        db.update("UPDATE notice SET revision=revision+1 WHERE id=?", revisionNotice);
        fingerprint(fingerprintNotice, "b");

        var claimed = store.claim();

        assertEquals(current.id(), claimed.id().toString());
        assertEquals("SUPERSEDED", store.find(UUID.fromString(oldRevision.id())).status());
        assertEquals("SUPERSEDED", store.find(UUID.fromString(oldFingerprint.id())).status());
        assertNull(store.claim());
        assertNull(db.queryForObject("SELECT started_at FROM notice_extraction WHERE id=?", java.sql.Timestamp.class, UUID.fromString(oldRevision.id())));
    }

    @Test
    void changedSourceCanReplaceActiveJobAndOldWorkerCannotWriteIntoNewAttempt() {
        UUID notice = notice();
        fingerprint(notice, "a");
        store.enqueue(notice, false);
        var old = store.claim();
        fingerprint(notice, "b");

        var fresh = store.enqueue(notice, false);

        assertNotEquals(old.id().toString(), fresh.id());
        assertEquals("SUPERSEDED", store.find(old.id()).status());
        assertFalse(store.checkpoint(old, List.of(extracted())));
        store.finish(old, List.of(extracted()), "READY", "늦은 완료");
        assertEquals("SUPERSEDED", store.find(old.id()).status());
        assertEquals(fresh.id(), store.claim().id().toString());
    }

    @Test
    void publicCurrentWorkPrecedesOlderHeldBacklogWithoutPublishingEitherNotice() {
        UUID held = notice();
        UUID visible = notice();
        db.update("UPDATE notice SET basic_review='PASS' WHERE id=?", visible);
        var heldDraft = store.enqueue(held, true);
        var publicDraft = store.enqueue(visible, false);
        db.update("UPDATE notice_extraction SET created_at=now()-interval '1 day',next_attempt_at=now()-interval '1 day' WHERE id=?", UUID.fromString(heldDraft.id()));

        var first = store.claim();

        assertEquals(publicDraft.id(), first.id().toString());
        store.finish(first, List.of(extracted()), "READY", "완료");
        assertEquals(heldDraft.id(), store.claim().id().toString());
        assertEquals("HOLD", db.queryForObject("SELECT basic_review FROM notice WHERE id=?", String.class, held));
        assertEquals("HOLD", db.queryForObject("SELECT guide_review FROM notice WHERE id=?", String.class, visible));
    }

    @Test
    void priorityDoesNotBypassRetryDelayOrDiscardEligibleHeldWork() {
        UUID held = notice();
        UUID visible = notice();
        db.update("UPDATE notice SET basic_review='PASS' WHERE id=?", visible);
        var heldDraft = store.enqueue(held, true);
        var publicDraft = store.enqueue(visible, false);
        db.update("UPDATE notice_extraction SET next_attempt_at=now()+interval '1 minute' WHERE id=?", UUID.fromString(publicDraft.id()));

        assertEquals(heldDraft.id(), store.claim().id().toString());
        assertNull(store.claim());
        assertEquals("QUEUED", store.find(UUID.fromString(publicDraft.id())).status());
    }

    private UUID notice() {
        UUID id = notices.insert("queue-test-" + UUID.randomUUID(), "발췌 대기열", "LH", "서울", "임대",
                LocalDate.now(), LocalDate.now().plusDays(30), LocalDate.now(), SampleData.content());
        entities.flush();
        return id;
    }

    private void fingerprint(UUID notice, String value) {
        db.update("UPDATE notice SET source_fingerprint=? WHERE id=?", value.repeat(64), notice);
    }

    private void age(UUID id) {
        db.update("UPDATE notice_extraction SET created_at=now()-interval '2 days' WHERE id=?", id);
    }

    private int count(UUID notice) {
        return db.queryForObject("SELECT count(*) FROM notice_extraction WHERE notice_id=?", Integer.class, notice);
    }

    private Source extracted() {
        return new Source("공고문", "https://apply.lh.or.kr/notice", "a".repeat(64), "EXTRACTED", "원문 확인",
                List.of(new Excerpt("DOCUMENT", "제출 서류 안내", "공고문 2쪽", "https://apply.lh.or.kr/notice")));
    }

    private Source failed() {
        return new Source("공고문", "https://apply.lh.or.kr/notice", null, "FAILED", "읽기 실패", List.of());
    }
}
