package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.jibro.auth.AuthService;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.ExtractionStore;
import org.jibro.preparation.PreparationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AutomaticScopedPublicationIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired AutomaticPublicationService publication;
    @Autowired AutomaticReviewRepository repository;
    @Autowired ExtractionStore extraction;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;
    @Autowired AuthService auth;
    @Autowired PreparationService preparations;

    UUID id;
    UUID extractionId;
    AutomaticReviewRepository.ExtractionSnapshot snapshot;
    Content content;
    LocalDate start = LocalDate.now().minusDays(1);
    LocalDate end = LocalDate.now().plusDays(30);

    @BeforeEach void setup() {
        String url = "https://apply.lh.or.kr/detail.do?id=" + UUID.randomUUID();
        id = notices.importSource(new LhNoticeClient.Item("LH:" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)),
            "부분 검수 공고", "서울", "청년 매입임대", url, start, "a".repeat(64)));
        assertTrue(publication.basic(id));
        entities.flush();
        extractionId = UUID.fromString(extraction.enqueue(id, true).id());
        var job = extraction.claim(id);
        extraction.finish(job, List.of(new Source("공식 상세 본문", url, "b".repeat(64), "EXTRACTED", "원문",
            List.of(), List.of(new Part("공고문 1쪽", "신청 자격과 제출 서류, 신청 기간의 공식 근거")))), "READY", "발췌 완료");
        snapshot = repository.snapshot(extractionId);
        var evidence = new Evidence("공고문 1쪽", url, LocalDate.now().toString());
        var issue = new ReviewIssue("income-conflict", "CONDITION", "소득 기준 확인 필요", "공식 자료의 소득 기준이 서로 다릅니다.",
            List.of("income"), List.of(), List.of(evidence));
        content = new Content("확인된 기준을 안내합니다.", evidence, List.of(),
            List.of(new Question("housing", "무주택 기준을 확인했나요?", "공고의 가구 범위를 확인합니다.", evidence),
                new Question("income", "소득 기준을 확인했나요?", "기관 자료의 불일치를 확인합니다.", evidence)),
            List.of(new DocumentTemplate("registry", "주민등록등본", null, "공식 발급 안내", "정부24", null, evidence),
                new DocumentTemplate("income-proof", "소득 증명서", "income", "공고의 소득 서류 범위를 확인합니다.", "LH", null, evidence)),
            List.of(new ScheduleTemplate("start", "신청 접수 시작", start.toString(), evidence),
                new ScheduleTemplate("end", "신청 접수 마감", end.toString(), evidence)), List.of(issue));
    }

    private boolean publish(Content candidate, List<String> blockers) {
        return publication.guidance(id, snapshot.draft().revision(), extractionId, snapshot.fingerprint(), candidate, start, end, blockers);
    }

    @Test void scopedGuidanceIsAvailableButAuditedAsPartialWithExactDependentScope() {
        assertTrue(publish(content, List.of()));
        var notice = notices.notice(id);
        assertEquals("AVAILABLE", notice.guideState());
        assertTrue(notice.canStart());
        assertEquals(1, notice.reviewIssues().size());
        assertEquals(List.of("income-proof"), notice.reviewIssues().get(0).documentIds());
        assertFalse(notice.questions().get(0).reviewRequired());
        assertTrue(notice.questions().get(1).reviewRequired());
        assertEquals("PARTIAL", auditDecision());
        assertEquals("income-proof", db.queryForObject("SELECT review_issues->0->'documentIds'->>0 FROM automatic_notice_review WHERE extraction_id=?",
            String.class, extractionId));
        assertTrue(notices.entity(id).reason.contains("일부 제공"));
        int version = notice.version();
        assertFalse(publish(content, List.of()));
        assertEquals(version, notices.notice(id).version());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM automatic_notice_review WHERE extraction_id=?", Integer.class, extractionId));
    }

    @Test void unresolvedSourceBlockerStillHoldsAnOtherwiseValidPartialCandidate() {
        var original = notices.content(id);
        assertFalse(publish(content, List.of("SOURCE_COVERAGE_INCOMPLETE")));
        assertEquals(original, notices.content(id));
        assertEquals("REVIEWING", notices.notice(id).guideState());
        assertEquals("HOLD", auditDecision());
        assertEquals(0, auditIssueCount());
        assertTrue(notices.entity(id).reason.contains("SOURCE_COVERAGE_INCOMPLETE"));
    }

    @Test void unknownQuestionReferenceCannotBePublishedAsPartial() {
        var invalid = new ReviewIssue("unknown-scope", "CONDITION", "조건 확인 필요", "공식 자료가 상충합니다.",
            List.of("missing-question"), List.of(), List.of(content.evidence()));
        var candidate = new Content(content.summary(), content.evidence(), content.attachments(),
            List.of(new Question("housing", "조건을 확인했나요?", "공식 원문을 확인합니다.", content.evidence())),
            List.of(content.documents().get(0)), content.schedules(), List.of(invalid));
        assertFalse(publish(candidate, List.of()));
        assertEquals("HOLD", auditDecision());
        assertEquals(0, auditIssueCount());
        assertTrue(notices.entity(id).reason.contains("COMPILED_CONTENT_INVALID"));
    }

    @Test void partialGuidanceCannotInventMissingConfirmedApplicationBoundary() {
        var candidate = new Content(content.summary(), content.evidence(), content.attachments(), content.questions(),
            content.documents(), List.of(content.schedules().get(0)), content.reviewIssues());
        assertFalse(publish(candidate, List.of()));
        assertEquals("HOLD", auditDecision());
        assertTrue(notices.entity(id).reason.contains("APPLICATION_BOUNDARY_MISSING"));
    }

    @Test void automaticCandidateCannotAssertAnOfficialClosureObservation() {
        String reason = "공식 상세의 공고상태가 접수마감입니다.";
        var candidate = new Content(reason, content.evidence(), content.attachments(), content.questions(),
            content.documents(), content.schedules(), content.reviewIssues(),
            new OfficialApplicationClosure(reason, content.evidence()));
        assertFalse(publish(candidate, List.of()));
        assertEquals("HOLD", auditDecision());
        assertTrue(notices.entity(id).reason.contains("OFFICIAL_CLOSURE_REQUIRES_SOURCE_REVIEW"));
        assertNull(notices.content(id).applicationClosure());
    }

    @Test void changedEvidenceRejectsPartialPublicationWithoutRecordingAStaleDecision() {
        extraction.updateFile(id, extractionId, new Source("변경 본문", content.evidence().url(), "c".repeat(64), "EXTRACTED", "변경 원문",
            List.of(), List.of(new Part("공고문 1쪽", "새로운 공고 조건"))));
        assertFalse(publish(content, List.of()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM automatic_notice_review WHERE extraction_id=?", Integer.class, extractionId));
        assertEquals("REVIEWING", notices.notice(id).guideState());
    }

    @Test void humanEditedDraftCannotBeReplacedByAutomaticPartialGuidance() {
        notices.updateDraft(id, notices.entity(id).revision, notices.draft(id));
        assertFalse(publish(content, List.of()));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM automatic_notice_review WHERE extraction_id=?", Integer.class, extractionId));
    }

    @Test void changedNoticeWithdrawsPartialPublicFactsButPreservesItsAuditAndPrivateSnapshot() {
        assertTrue(publish(content, List.of()));
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("준비 기록 검증", email, "test-password-123");
        String user = auth.login(email, "test-password-123").user().id();
        var preparation = preparations.start(user, id, notices.notice(id).version());
        assertEquals("UNKNOWN", preparation.documents.stream().filter(d -> d.id.equals("income-proof")).findFirst().orElseThrow().necessity);
        entities.flush();
        UUID preparationId = UUID.fromString(preparation.id);
        String previous = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, preparationId);
        var original = notices.entity(id);
        notices.importSource(new LhNoticeClient.Item(original.sourceKey, "정정 공고", "서울", "청년 매입임대",
            content.evidence().url(), LocalDate.now(), "d".repeat(64)));
        assertTrue(publication.basic(id));
        assertEquals("CHANGE_REVIEW", notices.notice(id).guideState());
        assertTrue(notices.notice(id).reviewIssues().isEmpty());
        assertTrue(notices.notice(id).questions().isEmpty());
        entities.flush();
        assertEquals(previous, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, preparationId));
        assertEquals("PARTIAL", auditDecision());
        assertEquals(1, auditIssueCount());
    }

    private String auditDecision() {
        return db.queryForObject("SELECT decision FROM automatic_notice_review WHERE extraction_id=?", String.class, extractionId);
    }

    private int auditIssueCount() {
        return db.queryForObject("SELECT jsonb_array_length(review_issues) FROM automatic_notice_review WHERE extraction_id=?", Integer.class, extractionId);
    }
}
