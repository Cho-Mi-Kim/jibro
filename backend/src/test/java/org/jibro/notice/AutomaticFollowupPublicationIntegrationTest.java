package org.jibro.notice;

import static org.jibro.notice.FollowupReviewModels.*;
import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AutomaticFollowupPublicationIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired AutomaticPublicationService automatic;
    @Autowired AutomaticFollowupPublicationService publication;
    @Autowired FollowupReviewRepository queue;
    @Autowired AutomaticReviewRepository evidence;
    @Autowired ExtractionStore extraction;
    @Autowired AuthService auth;
    @Autowired PreparationService preparations;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;
    @Autowired ObjectMapper json;
    UUID id, extractionId;
    String url;
    Content content;
    LocalDate start, end;

    @BeforeEach void setup() {
        url = "https://apply.lh.or.kr/detail.do?id=" + UUID.randomUUID();
        id = notices.importSource(new LhNoticeClient.Item(LhNoticeClient.sourceKey(url), "자동 후속 검토 테스트",
            "서울", "청년 매입임대", url, LocalDate.now().minusDays(1), "a".repeat(64)));
        assertTrue(automatic.basic(id));
        entities.flush();
        var draft = extraction.enqueue(id, false);
        extractionId = UUID.fromString(draft.id());
        extraction.finish(extraction.claim(id), List.of(source("원문 전체 자격과 서류 및 일정")), "READY", "완료");
        start = LocalDate.now().minusDays(1); end = LocalDate.now().plusDays(30);
        var e = new Evidence("공식 모집 공고 1쪽", url, LocalDate.now().toString());
        content = new Content("공식 자료의 자격·서류·일정을 확인했습니다.", e, List.of(),
            List.of(new Question("housing", "무주택 기준을 확인했나요?", "가구원 범위를 확인해 주세요.", e),
                new Question("income", "소득 기준을 확인했나요?", "소득 산정 범위를 확인해 주세요.", e)),
            List.of(new DocumentTemplate("registry", "주민등록등본", null, "공식 발급처 확인", "정부24", url, e),
                new DocumentTemplate("income-proof", "소득 증빙", "income", "공식 원문 확인", "LH", url, e)),
            List.of(new ScheduleTemplate("start", "신청 접수 시작", start.toString(), e),
                new ScheduleTemplate("end", "신청 접수 마감", end.toString(), e)));
    }

    Source source(String text) {
        return new Source("공식 상세 본문", url, "b".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("본문", text)));
    }
    Job claim() { return queue.claim(id).orElseThrow(); }
    Receipt stage(Job job, String hash, Decision decision) {
        return queue.stage(job, queue.primary(job).orElseThrow(), hash.repeat(64), Instant.now(),
            "{\"discovery\":{\"related\":[{\"url\":\"https://apply.lh.or.kr/followup.pdf\"}]}}", decision);
    }
    Outcome publish() { return publication.publish(stage(claim(), "c", Decision.ready(content, start, end))); }
    void due() { db.update("UPDATE notice_followup_queue SET next_check_at=clock_timestamp()-interval '1 second' WHERE notice_id=?", id); }
    String state(UUID run) { return db.queryForObject("SELECT state FROM automatic_followup_review WHERE id=?", String.class, run); }
    String storedContent() { return db.queryForObject("SELECT content::text FROM notice WHERE id=?", String.class, id); }
    void clear() { entities.flush(); entities.clear(); }

    @Test void closedNoticeIsNeitherClaimedNorPublishedWhenItClosesDuringVerification() {
        var n = notices.entity(id);
        n.startDate = start; n.endDate = end;
        n.applicationEndAt = Instant.now().minusSeconds(1);
        clear();
        assertTrue(queue.claim(id).isEmpty());
        notices.entity(id).applicationEndAt = null;
        clear();
        var job = claim(); var receipt = stage(job, "d", Decision.ready(content, start, end));
        notices.entity(id).applicationEndAt = Instant.now().minusSeconds(1);
        clear();
        String before = storedContent(); int revision = notices.entity(id).revision;
        assertFalse(publication.publish(receipt).changed());
        assertEquals(before, storedContent()); assertEquals(revision, notices.entity(id).revision);
    }

    @Test void newlyDiscoveredClosedPeriodDoesNotPublishDocumentsOrOtherGuidance() {
        var e = content.evidence();
        LocalDate pastStart = LocalDate.now(ApplicationPeriod.SEOUL).minusDays(3);
        LocalDate pastEnd = pastStart.plusDays(1);
        var closed = new Content(content.summary(), e, content.attachments(), content.questions(), content.documents(),
            List.of(new ScheduleTemplate("start", "신청 접수 시작", pastStart.toString(), e),
                new ScheduleTemplate("end", "신청 접수 마감", pastEnd.toString(), e),
                new ScheduleTemplate("documents", "서류 제출 마감", end.toString(), e)));
        String before = storedContent(); int revision = notices.entity(id).revision;
        var result = publication.publish(stage(claim(), "d", Decision.ready(closed, pastStart, pastEnd)));
        assertEquals("SUPERSEDED", result.state()); assertFalse(result.changed());
        assertEquals(before, storedContent()); assertEquals(revision, notices.entity(id).revision);
    }

    @Test void publishesFromHoldAndReusesOnlyExactOriginalEvidenceOnScheduledChecks() {
        var first = publish();
        assertEquals("PASS", first.state()); assertTrue(first.changed());
        assertEquals("AVAILABLE", notices.notice(id).guideState());
        assertTrue(notices.notice(id).canStart());
        int version = notices.notice(id).version();
        assertTrue(queue.claim(id).isEmpty(), "success schedules a future check, including targeted calls");
        due();
        var job = claim(); var primary = queue.primary(job).orElseThrow();
        assertEquals(extractionId.toString(), primary.snapshot().draft().id());
        assertTrue(primary.originalContent().questions().isEmpty());
        assertTrue(primary.originalContent().schedules().isEmpty());
        assertTrue(queue.sameBundle(job, "c".repeat(64)));
        var repeated = publication.publish(stage(job, "c", Decision.unchanged()));
        assertEquals("UNCHANGED", repeated.state()); assertFalse(repeated.changed());
        assertEquals(first.revision(), repeated.revision()); assertEquals(version, notices.notice(id).version());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM automatic_followup_review WHERE notice_id=?", Integer.class, id));
        assertTrue(queue.latestSources(id, extractionId, primary.snapshot().fingerprint()).isPresent());
    }

    @Test void unchangedCompiledGuideUnderNewBundleDoesNotIncrementVersions() {
        var first = publish(); int version = notices.notice(id).version(); due();
        var outcome = publication.publish(stage(claim(), "d", Decision.ready(content, start, end)));
        assertEquals("UNCHANGED", outcome.state()); assertEquals(first.revision(), outcome.revision());
        assertEquals(version, notices.notice(id).version());
    }

    @Test void partialGuideRetainsUnaffectedQuestionsAndDependentDocuments() {
        var issue = new ReviewIssue("income-conflict", "CONDITION", "소득 기준 확인 필요", "두 공식 자료의 소득 기준이 다릅니다.",
            List.of("income"), List.of(), List.of(content.evidence()));
        var partial = new Content(content.summary(), content.evidence(), content.attachments(), content.questions(), content.documents(), content.schedules(), List.of(issue));
        var result = publication.publish(stage(claim(), "c", Decision.ready(partial, start, end)));
        assertEquals("PARTIAL", result.state());
        var actual = notices.notice(id);
        assertEquals("AVAILABLE", actual.guideState()); assertTrue(actual.canStart());
        assertFalse(actual.questions().get(0).reviewRequired()); assertTrue(actual.questions().get(1).reviewRequired());
        assertEquals(List.of("income-proof"), actual.reviewIssues().get(0).documentIds());
        assertEquals(2, actual.documentTemplates().size());
    }

    @Test void pendingAndFailedChecksBackOffWithoutChangingPublishedGuideOrPreviewingFailureAsEvidence() {
        publish(); String before = storedContent(); int revision = notices.entity(id).revision; int version = notices.notice(id).version();
        var token = evidence.snapshot(extractionId).fingerprint();
        due(); var failed = claim(); queue.fail(failed, "OFFICIAL_SOURCE_DOWNLOAD_FAILED");
        assertEquals("FAILED", state(failed.runId())); assertTrue(queue.claim(id).isEmpty());
        assertTrue(queue.latestSources(id, extractionId, token).isEmpty());
        double delay = db.queryForObject("SELECT extract(epoch FROM next_check_at-clock_timestamp()) FROM notice_followup_queue WHERE notice_id=?", Double.class, id);
        assertTrue(delay > 890 && delay <= 900);
        due(); var deferred = claim(); queue.defer(deferred, "PRIMARY_EXTRACTION_PENDING");
        double second = db.queryForObject("SELECT extract(epoch FROM next_check_at-clock_timestamp()) FROM notice_followup_queue WHERE notice_id=?", Double.class, id);
        assertTrue(second > 1790 && second <= 1800);
        assertEquals(before, storedContent()); assertEquals(revision, notices.entity(id).revision);
        assertEquals(version, notices.notice(id).version()); assertEquals("AVAILABLE", notices.notice(id).guideState());
        assertTrue(notices.content(id).reviewIssues().isEmpty());
    }

    @Test void genuineNewGoverningConflictWithdrawsStaleGuideButRetainsAuditAndPrivateSnapshot() {
        publish();
        String email = "followup-" + UUID.randomUUID() + "@example.com";
        auth.signup("후속 검증", email, "test-password-123");
        String owner = auth.login(email, "test-password-123").user().id();
        var preparation = preparations.start(owner, id, notices.notice(id).version());
        String privateBefore = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, UUID.fromString(preparation.id));
        String publicBefore = storedContent(); int beforeVersion = notices.notice(id).version();
        due(); var job = claim();
        var result = publication.publish(stage(job, "d", Decision.reviewRequired(List.of("NEW_GOVERNING_EXCEPTION_WITH_UNKNOWN_SCOPE"))));
        assertEquals("REVIEW_REQUIRED", result.state());
        assertEquals("CHANGE_REVIEW", notices.notice(id).guideState()); assertFalse(notices.notice(id).canStart());
        assertTrue(notices.content(id).questions().isEmpty()); assertTrue(notices.content(id).schedules().isEmpty());
        assertNull(notices.notice(id).startDate()); assertNull(notices.notice(id).endDate());
        assertEquals(beforeVersion + 1, notices.notice(id).version());
        assertEquals(publicBefore, db.queryForObject("SELECT before_content::text FROM automatic_followup_review WHERE id=?", String.class, job.runId()));
        assertEquals(privateBefore, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, UUID.fromString(preparation.id)));
        due(); assertTrue(queue.primary(claim()).isPresent(), "only the exact audited withdrawn revision may reuse primary evidence");
    }

    @Test void changedRevisionOrContentCannotPublishAnEarlierReceipt() {
        var job = claim(); var receipt = stage(job, "c", Decision.ready(content, start, end));
        notices.entity(id).revision++; clear();
        String before = storedContent();
        assertEquals("SUPERSEDED", publication.publish(receipt).state());
        assertEquals(before, storedContent()); assertEquals("HOLD", notices.entity(id).guideReview);
        assertEquals("SUPERSEDED", state(job.runId()));
    }

    @Test void editedContentEvenWithoutRevisionCannotPublish() {
        var job = claim(); var receipt = stage(job, "c", Decision.ready(content, start, end));
        db.update("UPDATE notice SET content=jsonb_set(content,'{summary}','\"다른 검토 내용\"'::jsonb) WHERE id=?", id);
        entities.clear(); String before = storedContent();
        assertEquals("SUPERSEDED", publication.publish(receipt).state()); assertEquals(before, storedContent());
    }

    @Test void sourceFingerprintChangeInvalidatesStageAndPublication() {
        var job = claim(); var receipt = stage(job, "c", Decision.ready(content, start, end));
        notices.entity(id).sourceFingerprint = "f".repeat(64); clear();
        assertEquals("SUPERSEDED", publication.publish(receipt).state());
        assertEquals("HOLD", notices.entity(id).guideReview);
    }

    @Test void replacementUnderSameExtractionIdInvalidatesReceipt() {
        var job = claim(); var receipt = stage(job, "c", Decision.ready(content, start, end));
        extraction.updateFile(id, extractionId, source("변경된 새 공식 문맥"));
        assertEquals("SUPERSEDED", publication.publish(receipt).state());
        assertEquals("HOLD", notices.entity(id).guideReview);
    }

    @Test void aNewerPendingExtractionBlocksOlderCompletedEvidence() {
        var job = claim(); var receipt = stage(job, "c", Decision.ready(content, start, end));
        var newer = extraction.enqueue(id, true);
        // Both inserts are in one test transaction; explicitly order the fixture instead of relying on now().
        db.update("UPDATE notice_extraction SET created_at=clock_timestamp()+interval '1 second' WHERE id=?", UUID.fromString(newer.id()));
        assertTrue(queue.primary(job).isEmpty());
        assertEquals("SUPERSEDED", publication.publish(receipt).state());
    }

    @Test void receiptExpiresAtTwoMinutesAndCannotBeRestaged() {
        var job = claim(); var primary = queue.primary(job).orElseThrow();
        assertThrows(RuntimeException.class, () -> queue.stage(job, primary, "c".repeat(64), Instant.now().minusSeconds(121), "{}", Decision.ready(content, start, end)));
        var receipt = stage(job, "c", Decision.ready(content, start, end));
        assertThrows(RuntimeException.class, () -> stage(job, "d", Decision.ready(content, start, end)));
        db.update("UPDATE automatic_followup_review SET checked_at=clock_timestamp()-interval '121 seconds' WHERE id=?", job.runId());
        assertEquals("SUPERSEDED", publication.publish(receipt).state());
    }

    @Test void expiredLeaseCannotPublishOrFinishReplacementWorker() {
        var first = claim(); var receipt = stage(first, "c", Decision.ready(content, start, end));
        assertTrue(queue.claim(id).isEmpty());
        db.update("UPDATE notice_followup_queue SET lease_until=clock_timestamp()-interval '1 second' WHERE notice_id=?", id);
        var second = claim();
        assertNotEquals(first.leaseToken(), second.leaseToken()); assertEquals("SUPERSEDED", state(first.runId()));
        assertEquals("SUPERSEDED", publication.publish(receipt).state());
        queue.fail(first, "late old worker");
        assertTrue(queue.renew(second)); assertEquals("RUNNING", state(second.runId()));
    }

    @Test void manualAndSourceReviewedGuidesAreIneligibleEvenWithAReadyReceipt() {
        var job = claim(); var receipt = stage(job, "c", Decision.ready(content, start, end));
        notices.entity(id).automaticReviewBlocked = true; clear();
        assertEquals("SUPERSEDED", publication.publish(receipt).state());
        assertTrue(queue.claim(id).isEmpty());
        notices.entity(id).automaticReviewBlocked = false; clear(); due();
        db.update("""
            INSERT INTO notice_source_review(id,notice_id,source_revision,result_revision,disposition,package_fingerprint,reviewer_kind,payload)
            VALUES(?,?,2,2,'PASS',?,'AI_ASSISTED_SOURCE_REVIEW','{}'::jsonb)
            """, UUID.randomUUID(), id, "e".repeat(64));
        assertTrue(queue.claim(id).isEmpty(), "source-bound human-assisted history remains protected even if a flag was reset");
    }

    @Test void summaryOnlyReviewDoesNotFreezePendingGuidanceButFullReviewStillProtectsIt() {
        db.update("""
            INSERT INTO notice_source_review(id,notice_id,source_revision,result_revision,disposition,package_fingerprint,reviewer_kind,payload)
            VALUES(?,?,2,2,'HOLD',?,'AI_ASSISTED_SOURCE_REVIEW','{"scope":"VERIFIED_SUMMARY_ONLY"}'::jsonb)
            """, UUID.randomUUID(), id, "e".repeat(64));
        assertEquals("HOLD", notices.entity(id).guideReview);
        var job = claim();
        assertTrue(queue.primary(job).isPresent());
        assertEquals("HOLD", notices.entity(id).guideReview, "summary review must not approve guidance");
        var receipt = stage(job, "c", Decision.ready(content, start, end));
        db.update("""
            INSERT INTO notice_source_review(id,notice_id,source_revision,result_revision,disposition,package_fingerprint,reviewer_kind,payload)
            VALUES(?,?,2,2,'PASS',?,'AI_ASSISTED_SOURCE_REVIEW','{"scope":"SUMMARY_ONLY_EXPLICIT_EDIT"}'::jsonb)
            """, UUID.randomUUID(), id, "f".repeat(64));
        assertEquals("SUPERSEDED", publication.publish(receipt).state());
        due();
        assertTrue(queue.claim(id).isEmpty(), "other review history retains existing protection");
    }

    @Test void unknownReceiptAndForgedBundleCannotConsumeRealAssessment() {
        var job = claim(); var receipt = stage(job, "c", Decision.ready(content, start, end));
        assertEquals("SUPERSEDED", publication.publish(new Receipt(job.runId(), UUID.randomUUID(), receipt.bundleHash())).state());
        assertEquals("SUPERSEDED", publication.publish(new Receipt(job.runId(), job.leaseToken(), "d".repeat(64))).state());
        assertEquals("STAGED", state(job.runId()));
        assertEquals("PASS", publication.publish(receipt).state());
        int version = notices.notice(id).version();
        assertEquals("SUPERSEDED", publication.publish(receipt).state()); assertEquals(version, notices.notice(id).version());
    }

    @Test void legacyPublicationNeedsExactAuditContentProofBeforeReusingOlderExtraction() {
        var snapshot = evidence.snapshot(extractionId);
        assertTrue(automatic.guidance(id, notices.entity(id).revision, extractionId, snapshot.fingerprint(), content, start, end, List.of()));
        var job = claim(); assertTrue(queue.primary(job).isPresent());
        db.update("UPDATE automatic_notice_review SET content_fingerprint=NULL WHERE notice_id=? AND phase='GUIDANCE'", id);
        assertTrue(queue.primary(job).isEmpty(), "old unaudited legacy content requires fresh extraction");
    }

    @Test void oldSupplementalAttachmentsCannotContaminateNextPrimaryCompilation() {
        var supplement = new Evidence("후속 공식 자료", "https://apply.lh.or.kr/followup.pdf", LocalDate.now().toString());
        var enriched = new Content(content.summary(), content.evidence(), List.of(supplement), content.questions(), content.documents(), content.schedules());
        assertEquals("PASS", publication.publish(stage(claim(), "c", Decision.ready(enriched, start, end))).state());
        due(); var original = queue.primary(claim()).orElseThrow().originalContent();
        assertEquals(url, original.evidence().url()); assertTrue(original.attachments().isEmpty());
        assertTrue(original.questions().isEmpty()); assertTrue(original.documents().isEmpty());
    }

    @Test void automaticReceiptCannotInventClosureOrPublishMissingApplicationBoundaries() {
        var closure = new OfficialApplicationClosure("공식 상태는 접수마감입니다.", content.evidence());
        var c = new Content(content.summary() + closure.reason(), content.evidence(), content.attachments(), content.questions(), content.documents(), content.schedules(), List.of(), closure);
        assertEquals("FAILED", publication.publish(stage(claim(), "c", Decision.ready(c, start, end))).state());
        assertEquals("HOLD", notices.entity(id).guideReview);
        due();
        assertEquals("FAILED", publication.publish(stage(claim(), "d", Decision.ready(content, start, null))).state());
        assertNull(notices.notice(id).endDate());
    }

    @Test void unseenBundleCannotClaimUnchangedAndClosedNoticesAreExcluded() {
        assertEquals("FAILED", publication.publish(stage(claim(), "c", Decision.unchanged())).state());
        due(); start = LocalDate.now().minusDays(30); end = LocalDate.now().minusDays(1);
        content = new Content(content.summary(), content.evidence(), content.attachments(), content.questions(), content.documents(),
            List.of(new ScheduleTemplate("start", "신청 접수 시작", start.toString(), content.evidence()),
                new ScheduleTemplate("end", "신청 접수 마감", end.toString(), content.evidence())));
        String before = storedContent();
        assertEquals("SUPERSEDED", publish().state());
        assertEquals(before, storedContent());
    }

    @Test void verifiedPrimaryChangeWithdrawsOldPublicFactsAndRequiresNewExtraction() {
        publish(); String before = storedContent(); int version = notices.notice(id).version(); due();
        var job = claim();
        assertTrue(publication.primaryChanged(job));
        assertEquals("HOLD", notices.entity(id).basicReview); assertEquals("HOLD", notices.entity(id).guideReview);
        assertTrue(notices.content(id).questions().isEmpty()); assertNull(notices.entity(id).endDate);
        assertEquals(version + 1, notices.entity(id).version);
        assertEquals(before, db.queryForObject("SELECT before_content::text FROM automatic_followup_review WHERE id=?", String.class, job.runId()));
        assertEquals("REVIEW_REQUIRED", state(job.runId()));
        assertFalse(publication.primaryChanged(job), "the same obsolete job cannot revoke another revision");
        assertTrue(automatic.basic(id)); due();
        var freshJob = claim(); assertTrue(queue.primary(freshJob).isEmpty());
    }

    @Test void primaryChangeCannotOverrideConcurrentManualProtection() {
        publish(); due(); var job = claim(); String before = storedContent();
        notices.entity(id).automaticReviewBlocked = true; clear();
        assertFalse(publication.primaryChanged(job)); assertEquals(before, storedContent());
        assertEquals("PASS", notices.entity(id).guideReview);
    }

    @Test void previewUsesProvedCleanPrimaryInsteadOfAlreadyPublishedSupplementalContent() {
        var supplement = new Evidence("후속 원문", "https://apply.lh.or.kr/later.pdf", LocalDate.now().toString());
        var enriched = new Content(content.summary(), content.evidence(), List.of(supplement), content.questions(), content.documents(), content.schedules());
        assertEquals("PASS", publication.publish(stage(claim(), "c", Decision.ready(enriched, start, end))).state());
        var token = evidence.snapshot(extractionId).fingerprint();
        var clean = queue.previewOriginal(id, extractionId, token).orElseThrow();
        assertEquals(url, clean.evidence().url()); assertTrue(clean.attachments().isEmpty());
        assertTrue(clean.questions().isEmpty()); assertTrue(clean.documents().isEmpty()); assertTrue(clean.schedules().isEmpty());
        assertTrue(queue.previewOriginal(id, extractionId, "f".repeat(64)).isEmpty());
        var newer = extraction.enqueue(id, true);
        db.update("UPDATE notice_extraction SET created_at=clock_timestamp()+interval '1 second' WHERE id=?", UUID.fromString(newer.id()));
        assertTrue(queue.previewOriginal(id, extractionId, token).isEmpty());
    }

    @Test void appliedDependenciesSurviveAReplacementExtractionAndFailedSearchButNotManualProtection() {
        publish(); String applied = queue.latestDependencySources(id).orElseThrow(); due();
        var job = claim(); queue.fail(job, "FOLLOWUP_SEARCH_UNREAD");
        assertEquals(applied, queue.latestDependencySources(id).orElseThrow());
        var newer = extraction.enqueue(id, true);
        db.update("UPDATE notice_extraction SET created_at=clock_timestamp()+interval '1 second' WHERE id=?", UUID.fromString(newer.id()));
        assertEquals(applied, queue.latestDependencySources(id).orElseThrow());
        assertTrue(queue.latestSources(id, extractionId, evidence.snapshot(extractionId).fingerprint()).isEmpty());
        db.update("UPDATE notice SET content=jsonb_set(content,'{summary}','\"새로운 검토\"'::jsonb) WHERE id=?", id);
        entities.clear();
        assertEquals(applied, queue.latestDependencySources(id).orElseThrow());
        notices.entity(id).automaticReviewBlocked = true; clear();
        assertTrue(queue.latestDependencySources(id).isEmpty());
    }

    @Test void originalDependenciesSurvivePrimaryWithdrawalBasicReviewAndReExtraction() {
        publish(); String applied = queue.latestDependencySources(id).orElseThrow(); due();
        assertTrue(publication.primaryChanged(claim()));
        assertTrue(automatic.basic(id)); clear();
        var draft = extraction.enqueue(id, true);
        extraction.finish(extraction.claim(id), List.of(source("다시 읽은 공식 원문")), "READY", "완료");
        assertNotEquals(extractionId.toString(), draft.id());
        assertEquals(applied, queue.latestDependencySources(id).orElseThrow());
    }

    @Test void emptyUnchangedBundleCannotErasePreviouslyAppliedDependencies() {
        publish(); String applied = queue.latestDependencySources(id).orElseThrow(); due();
        var job = claim();
        var receipt = queue.stage(job, queue.primary(job).orElseThrow(), "d".repeat(64), Instant.now(),
            "{\"discovery\":{\"related\":[]}}", Decision.ready(content, start, end));
        assertEquals("UNCHANGED", publication.publish(receipt).state());
        assertEquals(applied, queue.latestDependencySources(id).orElseThrow());
    }

    @Test void dependencyHistoryDoesNotCrossAnUnrelatedPrimaryIdentity() {
        publish(); assertTrue(queue.latestDependencySources(id).isPresent());
        db.update("UPDATE notice SET content=jsonb_set(content,'{evidence,url}',to_jsonb(?::text)) WHERE id=?",
            "https://apply.lh.or.kr/detail.do?id=" + UUID.randomUUID(), id);
        entities.clear();
        assertTrue(queue.latestDependencySources(id).isEmpty());
    }

    @Test void firstVerifiedUninterpretedCorrectionRemainsADependencyEvenBeforeAnyPass() {
        var job = claim();
        assertEquals("REVIEW_REQUIRED", publication.publish(stage(job, "c",
            Decision.reviewRequired(List.of("VERIFIED_CORRECTION_SCOPE_UNSUPPORTED")))).state());
        String dependency = queue.latestDependencySources(id).orElseThrow();
        assertTrue(dependency.contains("followup.pdf"));
        due(); var retry = claim(); queue.fail(retry, "FOLLOWUP_SEARCH_UNREAD");
        assertEquals(dependency, queue.latestDependencySources(id).orElseThrow());
    }
}
