package org.jibro.notice;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import jakarta.persistence.EntityManager;
import org.jibro.auth.AuthService;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.extraction.*;
import org.jibro.collection.review.AutomaticReviewService;
import org.jibro.preparation.PreparationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AutoReviewRaceIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    AutomaticPublicationService publication;
    @Autowired
    AutomaticReviewRepository repository;
    @Autowired
    AutomaticReviewService review;
    @Autowired
    ExtractionStore extraction;
    @Autowired
    JdbcTemplate db;
    @Autowired
    EntityManager entities;
    @Autowired
    AuthService auth;
    @Autowired
    PreparationService preparations;

    @Test
    void aPriorExecutionFailureIsRecheckedUnderTheNewPolicyWithoutLosingItsAudit() {
        UUID id = imported();
        UUID draft = ready(id, true);
        var snapshot = repository.snapshot(draft);
        assertFalse(publication.guidance(id, snapshot.draft().revision(), draft, snapshot.fingerprint(),
                null, null, null, List.of("COMPILATION_FAILED")));
        db.update("UPDATE automatic_notice_review SET policy='official-rules-v11' WHERE extraction_id=?", draft);
        assertTrue(repository.pendingGuidance().contains(draft));
        assertTrue(review.review(draft));
        assertEquals("AVAILABLE", notices.notice(id).guideState());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM automatic_notice_review WHERE extraction_id=? AND policy='official-rules-v11' AND decision='HOLD'", Integer.class, draft));
    }

    @Test
    void changedFileInSameExtractionCanBeReviewedAgainAfterHold() {
        UUID id = imported();
        UUID draft = ready(id, false);
        assertFalse(review.review(draft));
        assertFalse(repository.pendingGuidance().contains(draft));
        extraction.updateFile(id, draft, source(id, true));
        assertTrue(repository.pendingGuidance().contains(draft));
        assertTrue(review.review(draft));
        assertEquals("AVAILABLE", notices.notice(id).guideState());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM automatic_notice_review WHERE extraction_id=?", Integer.class, draft));
    }

    @Test
    void changedEvidenceAndNewerExtractionRejectAnOldCompiledDecision() {
        UUID id = imported();
        UUID draft = ready(id, false);
        var old = repository.snapshot(draft);
        extraction.updateFile(id, draft, source(id, true));
        assertFalse(publication.guidance(id, old.draft().revision(), draft, old.fingerprint(), SampleData.content(),
                LocalDate.now(), LocalDate.now().plusDays(1), List.of()));
        var updated = repository.snapshot(draft);
        extraction.enqueue(id, true);
        assertFalse(publication.guidance(id, updated.draft().revision(), draft, updated.fingerprint(), SampleData.content(),
                LocalDate.now(), LocalDate.now().plusDays(1), List.of()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM automatic_notice_review WHERE extraction_id=?", Integer.class, draft));
        assertEquals("HOLD", notices.entity(id).guideReview);
    }

    @Test
    void invalidCompiledStructureIsRecordedAsHoldInsteadOfRetryingForever() {
        UUID id = imported();
        UUID draft = ready(id, true);
        var snapshot = repository.snapshot(draft);
        Content sample = SampleData.content();
        var invalid = new Content(sample.summary(), sample.evidence(), sample.attachments(),
                Collections.nCopies(101, sample.questions().get(0)), sample.documents(), sample.schedules());
        assertFalse(publication.guidance(id, snapshot.draft().revision(), draft, snapshot.fingerprint(), invalid,
                LocalDate.now(), LocalDate.now().plusDays(1), List.of()));
        assertTrue(notices.entity(id).reason.contains("COMPILED_CONTENT_INVALID"));
        assertFalse(repository.pendingGuidance().contains(draft));
        assertEquals("HOLD", notices.entity(id).guideReview);
    }

    @Test
    void changedOfficialSourceWithdrawsStalePublicGuidanceButKeepsPrivateSnapshot() {
        UUID id = imported();
        assertTrue(review.review(ready(id, true)));
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("준비 기록 검증", email, "test-password-123");
        String user = auth.login(email, "test-password-123").user().id();
        var preparation = preparations.start(user, id, notices.notice(id).version());
        entities.flush();
        String previous = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, UUID.fromString(preparation.id));
        var old = notices.entity(id);
        String url = notices.content(id).evidence().url();
        notices.importSource(new LhNoticeClient.Item(old.sourceKey, "변경된 공고", "서울", "청년 매입임대", url,
                LocalDate.now(), "c".repeat(64)));
        assertTrue(publication.basic(id));
        var current = notices.notice(id);
        assertNull(current.startDate());
        assertNull(current.endDate());
        assertNull(current.applicationTiming().endsAt());
        assertTrue(current.schedules().isEmpty());
        assertTrue(current.questions().isEmpty());
        assertTrue(current.summary().contains("변경된"));
        assertFalse(current.canStart());
        entities.flush();
        assertEquals(previous, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, UUID.fromString(preparation.id)));
    }

    @Test
    void cancelledSourcesCannotOccupyPendingWorkSlots() {
        var cancelled = new ArrayList<UUID>();
        for (int i = 0; i < 10; i++) {
            UUID id = imported();
            notices.entity(id).lifecycleState = "CANCELLED";
            notices.entity(id).basicReview = "HOLD";
            cancelled.add(id);
        }
        UUID valid = imported();
        entities.flush();
        assertTrue(repository.pendingExtraction().contains(valid));
        assertTrue(Collections.disjoint(cancelled, repository.pendingBasic()));
        assertTrue(Collections.disjoint(cancelled, repository.pendingExtraction()));
        UUID draft = ready(valid, true);
        notices.entity(valid).lifecycleState = "EARLY_CLOSED";
        entities.flush();
        assertFalse(repository.pendingGuidance().contains(draft));
    }

    @Test
    void nullableManualSourceIsSafelySkipped() {
        UUID id = notices.insert(null, "수동 원고", "LH", "서울", "임대", null, null, LocalDate.now(), SampleData.content());
        assertFalse(publication.basic(id));
    }

    @Test
    void oneUnavailableNoticeDoesNotPreventNextQueuedNotice() {
        var queue = mock(AutomaticReviewRepository.class);
        var publisher = mock(AutomaticPublicationService.class);
        var store = mock(ExtractionStore.class);
        UUID first = UUID.randomUUID(), next = UUID.randomUUID();
        when(queue.pendingBasic()).thenReturn(List.of(first, next));
        when(queue.pendingExtraction()).thenReturn(List.of());
        when(queue.pendingGuidance()).thenReturn(List.of());
        when(publisher.basic(first)).thenThrow(new IllegalStateException());
        when(publisher.basic(next)).thenReturn(true);
        new AutomaticReviewService(queue, publisher, store, notices, mock(org.jibro.collection.review.local.LocalAnalysisService.class), true, null, null, false).tick();
        verify(store).enqueue(next, false);
    }

    private UUID imported() {
        String url = "https://apply.lh.or.kr/detail.do?id=" + UUID.randomUUID();
        UUID id = notices.importSource(new LhNoticeClient.Item("LH:" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)),
                "청년 모집", "서울", "청년 매입임대", url, LocalDate.now().minusDays(1), "a".repeat(64)));
        assertTrue(publication.basic(id));
        return id;
    }

    private UUID ready(UUID id, boolean supported) {
        entities.flush();
        UUID draft = UUID.fromString(extraction.enqueue(id, true).id());
        var job = extraction.claim();
        assertEquals(draft, job.id());
        extraction.finish(job, List.of(source(id, supported)), "READY", "발췌 완료");
        return draft;
    }

    private Source source(UUID id, boolean supported) {
        String url = notices.content(id).evidence().url();
        String text = supported ? "신청자는 무주택자이어야 합니다.\n공통 제출서류: 주민등록등본\n신청 기간: "
                + LocalDate.now().minusDays(1) + " ~ " + LocalDate.now().plusDays(30) : "가구원 범위와 예외는 별도 표를 확인하세요.";
        var parts = List.of(new Part("공고문 1쪽", text));
        return new Source("공식 상세 본문", url, (supported ? "b" : "c").repeat(64), "EXTRACTED", "원문",
                ExcerptClassifier.extract(parts, url), parts);
    }
}
