package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.OfficialDetailParser;
import org.jibro.notice.SourceReviewVerifier.Verification;

import java.time.*;
import java.util.*;

import jakarta.persistence.EntityManager;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.ExtractionStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.jibro.notice.HousingModels.*;
import static org.jibro.notice.SourceReviewModels.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SourceReviewPublicationIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    AutomaticPublicationService automatic;
    @Autowired
    SourceReviewPublicationService publication;
    @Autowired
    AutomaticReviewRepository extractions;
    @Autowired
    ExtractionStore store;
    @Autowired
    JdbcTemplate db;
    @Autowired
    EntityManager entities;
    @Autowired
    ObjectMapper json;
    @Autowired
    SourceReviewRepository sourceHistory;
    @Autowired
    org.jibro.preparation.PreparationService preparations;
    @MockitoBean
    AttachmentFingerprintClient http;
    SourceReviewModels.Package review;
    Verification receipt;
    UUID id;
    static final String URL = "https://apply.lh.or.kr/detail";
    static final String FILE = "https://apply.lh.or.kr/notice.pdf";
    static final String HTML = "<main><p>공고일 2026.11.04</p><a href='/notice.pdf'>공고문</a></main>";

    @BeforeEach
    void setup() throws Exception {
        when(http.html(URL)).thenReturn(HTML);
        when(http.fetchForSourceReview(FILE)).thenReturn(new AttachmentFingerprintClient.Fingerprint("a".repeat(64), 123, "application/pdf"));
        id = notices.importSource(new LhNoticeClient.Item("LH:" + UUID.randomUUID(), "실제 출처 검토 테스트", "서울", "국민임대", URL, LocalDate.now(), "b".repeat(64)));
        var n = notices.entity(id);
        n.basicReview = "PASS";
        entities.flush();
        var draft = store.enqueue(id, false);
        var job = store.claim(id);
        store.finish(job, List.of(new Source("공식 상세 본문", URL, "a".repeat(64), "EXTRACTED", "", List.of(), OfficialDetailParser.parse(HTML, URL).parts()), new Source("공고문", FILE, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", "신청 자격")))), "READY", "");
        var e = new Evidence("공고문 1쪽", URL, LocalDate.now().toString());
        var content = new Content("원문 대조 안내", e, List.of(e), List.of(new Question("housing", "무주택 기준을 확인했나요?", "가구 범위를 공식 원문에서 확인합니다.", e)),
                List.of(new DocumentTemplate("consent", "개인정보 동의서", null, "공고 첨부 양식", "LH", URL, e)),
                List.of(new ScheduleTemplate("apply-start", "신청 접수 시작", "2026-11-25", e), new ScheduleTemplate("apply-end", "신청 접수 마감", "2026-12-16", e)));
        review = new SourceReviewModels.Package("test-source-review", id, draft.revision(), UUID.fromString(draft.id()), "PASS", "공식 원문 전체 대조", List.of(),
                List.of(new SourceReview(URL, "a".repeat(64), "공식 상세 본문", "DETAIL", "상세 확인"), new SourceReview(FILE, "a".repeat(64), "공고문", "RULES", "자격·서류·일정 확인")), content, LocalDate.of(2026, 11, 25), LocalDate.of(2026, 12, 16));
        receipt = receipt(review, Instant.now());
    }

    Verification receipt(SourceReviewModels.Package p, Instant at) {
        try {
            return new SourceReviewVerifier(extractions, notices, http, json, Clock.fixed(at, ZoneOffset.UTC)).verify(p);
        } catch (Exception failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    SourceReviewModels.Package decision(String outcome, List<String> findings) {
        return new SourceReviewModels.Package(review.packageId(), id, review.revision(), review.extractionId(), outcome, review.reviewNotes(), findings, review.sourceReviews(), review.content(), review.startDate(), review.endDate());
    }

    @Test
    void publishesReviewedContentAndAuditsAiAssistanceWithoutInventingAnAdministrator() {
        assertTrue(publication.apply(review, receipt));
        entities.flush();
        entities.clear();
        assertEquals("AVAILABLE", notices.notice(id).guideState());
        assertEquals(1, notices.content(id).questions().size());
        assertEquals(1, db.queryForObject("select count(*) from notice_source_review where notice_id=? and reviewer_kind='AI_ASSISTED_SOURCE_REVIEW'", Integer.class, id));
        assertEquals(0, db.queryForObject("select count(*) from review_audit where notice_id=?", Integer.class, id));
        assertTrue(notices.entity(id).automaticReviewBlocked);
        assertFalse(publication.apply(review, receipt));
    }

    @Test
    void freshFullSourceReviewRestoresChangedBasicsAndGuidanceTogether() {
        var n = notices.entity(id);
        n.sourceKey = LhNoticeClient.sourceKey(URL);
        n.basicReview = "HOLD";
        n.guideReview = "HOLD";
        n.changeReview = true;
        n.automaticReviewBlocked = true;
        assertFalse(automatic.basic(id), "a previous editorial decision must not be overwritten by the automatic queue");
        var fresh = receipt(review, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertTrue(publication.apply(review, fresh));
        entities.flush();
        entities.clear();
        assertEquals("AVAILABLE", notices.notice(id).guideState());
        assertEquals("PASS", notices.entity(id).basicReview);
        assertEquals(fresh.checkedAt(), notices.entity(id).basicCheckedAt);
        assertFalse(notices.entity(id).changeReview);
        assertTrue(notices.entity(id).automaticReviewBlocked);
        assertEquals(0, db.queryForObject("select count(*) from review_audit where notice_id=?", Integer.class, id));
    }

    @Test
    void sourceReviewCannotRestoreBasicsWithWrongIdentityOrClosedRecruitment() {
        var n = notices.entity(id);
        n.basicReview = "HOLD";
        n.changeReview = true;
        n.automaticReviewBlocked = true;
        assertThrows(RuntimeException.class, () -> publication.apply(review, receipt));
        n.sourceKey = LhNoticeClient.sourceKey(URL);
        n.sourceClosed = true;
        assertThrows(RuntimeException.class, () -> publication.apply(review, receipt));
        n.sourceClosed = false;
        n.endDate = LocalDate.now().minusDays(1);
        assertThrows(RuntimeException.class, () -> publication.apply(review, receipt));
        n.endDate = null;
        n.publishedDate = LocalDate.now().plusDays(1);
        assertThrows(RuntimeException.class, () -> publication.apply(review, receipt));
        n.publishedDate = LocalDate.now();
        var c = review.content();
        var closedContent = new Content(c.summary() + " 공식 원문에서 조기 접수마감 확인", c.evidence(), c.attachments(), c.questions(), c.documents(), c.schedules(),
                c.reviewIssues(), new OfficialApplicationClosure("공식 원문에서 조기 접수마감 확인", c.evidence()));
        var closed = new SourceReviewModels.Package(review.packageId(), id, review.revision(), review.extractionId(), "PASS",
                review.reviewNotes(), List.of(), review.sourceReviews(), closedContent, review.startDate(), review.endDate());
        assertDoesNotThrow(() -> notices.validateContent(closedContent));
        assertThrows(RuntimeException.class, () -> publication.apply(closed, receipt(closed, Instant.now())));
        assertEquals("HOLD", n.basicReview);
        assertEquals("HOLD", n.guideReview);
    }

    @Test
    void genuineConflictCannotBePublished() {
        var conflict = decision("PASS", List.of("공식 문서의 마감시간이 다릅니다."));
        assertThrows(IllegalArgumentException.class, () -> publication.apply(conflict, receipt(conflict, Instant.now())));
        assertEquals("HOLD", notices.entity(id).guideReview);
    }

    @Test
    void partiallyReviewedGuidanceIsAvailableWithScopedIssuesAndPartialAudit() {
        var c = review.content();
        var issue = new ReviewIssue("housing-date", "CONDITION", "무주택 기준일 확인 필요", "공식 자료의 기준일이 서로 다릅니다.", List.of("housing"), List.of(), List.of(c.evidence()));
        var content = new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), c.schedules(), List.of(issue));
        var p = new SourceReviewModels.Package(review.packageId(), id, review.revision(), review.extractionId(), "PARTIAL", review.reviewNotes(), List.of(issue.id()), review.sourceReviews(), content, review.startDate(), review.endDate());
        assertTrue(publication.apply(p, receipt(p, Instant.now())));
        entities.flush();
        entities.clear();
        var result = notices.notice(id);
        assertEquals("AVAILABLE", result.guideState());
        assertTrue(result.canStart());
        assertEquals(List.of(issue), result.reviewIssues());
        assertTrue(result.questions().get(0).reviewRequired());
        assertEquals(1, result.documentTemplates().size());
        assertEquals(2, result.schedules().size());
        assertEquals("PARTIAL", db.queryForObject("select disposition from notice_source_review where notice_id=?", String.class, id));
        assertTrue(notices.entity(id).automaticReviewBlocked);
    }

    @Test
    void holdPreservesExistingContentDatesAndRevision() {
        var before = notices.attachmentSnapshot(id);
        var hold = decision("HOLD", List.of("기관 확인 필요"));
        assertTrue(publication.apply(hold, receipt(hold, Instant.now())));
        assertEquals(before, notices.attachmentSnapshot(id));
        assertNull(notices.notice(id).startDate());
    }

    @Test
    void unsupportedDoesNotExposeCandidateQuestions() {
        var unsupported = decision("UNSUPPORTED", List.of());
        assertTrue(publication.apply(unsupported, receipt(unsupported, Instant.now())));
        assertEquals("UNSUPPORTED", notices.notice(id).guideState());
        assertTrue(notices.notice(id).questions().isEmpty());
        assertFalse(notices.notice(id).canStart());
    }

    @Test
    void changedExtractionCannotApplyOldReview() {
        store.updateFile(id, review.extractionId(), new Source("변경 공고", URL, "c".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", "변경된 자격"))));
        assertThrows(RuntimeException.class, () -> publication.apply(review, receipt));
    }

    @Test
    void changedNoticeCannotApplyOldReview() {
        notices.entity(id).revision++;
        assertThrows(RuntimeException.class, () -> publication.apply(review, receipt));
    }

    @Test
    void staleOrWrongReceiptCannotApply() {
        assertThrows(IllegalArgumentException.class, () -> publication.apply(review, receipt(review, Instant.now().minusSeconds(901))));
        assertThrows(IllegalArgumentException.class, () -> publication.apply(decision("HOLD", List.of()), receipt));
    }

    @Test
    void existingApprovedGuidanceIsNotReplaced() {
        notices.entity(id).guideReview = "PASS";
        assertThrows(RuntimeException.class, () -> publication.apply(review, receipt));
    }

    SourceReviewModels.Package amended() {
        publication.apply(review, receipt);
        entities.flush();
        entities.clear();
        var previousSources = store.find(review.extractionId()).sources();
        var draft = store.enqueue(id, false);
        var job = store.claim(id);
        store.finish(job, previousSources, "READY", "재확인한 공식 자료");
        var c = review.content();
        var content = new Content("서류 적용 범위를 다시 대조한 안내", c.evidence(), c.attachments(), c.questions(), c.documents(), c.schedules());
        return new SourceReviewModels.Package("test-source-review-amended", id, draft.revision(), UUID.fromString(draft.id()),
                "PASS", "서류 적용 범위 정정", List.of(), review.sourceReviews(), content, review.startDate(), review.endDate());
    }

    @Test
    void explicitAmendmentPreservesPreviousAuditAndRequiresFreshSourceVerification() {
        var p = amended();
        assertThrows(RuntimeException.class, () -> publication.amend(p, receipt, receipt.packageFingerprint()));
        assertTrue(publication.amend(p, receipt(p, Instant.now()), receipt.packageFingerprint()));
        entities.flush();
        entities.clear();
        assertEquals(p.content(), notices.content(id));
        assertEquals(2, db.queryForObject("select count(*) from notice_source_review where notice_id=?", Integer.class, id));
        assertEquals(0, db.queryForObject("select count(*) from review_audit where notice_id=?", Integer.class, id));
    }

    SourceReviewModels.Package afterIndependentScheduleReview() {
        var p = amended();
        var n = notices.entity(id);
        var c = notices.content(id);
        var schedules = new ArrayList<>(c.schedules());
        schedules.add(new ScheduleTemplate("visit", "현장 접수 14:00~16:00", "2026-12-01", c.evidence()));
        n.content = json.writeValueAsString(new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), schedules));
        n.revision++;
        entities.flush();
        var prior = store.find(p.extractionId()).sources();
        var draft = store.enqueue(id, false);
        var job = store.claim(id);
        store.finish(job, prior, "READY", "현재 일정과 공식 원문 재대조");
        var updated = new Content(p.content().summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), schedules);
        return new SourceReviewModels.Package("current-publication-rereview", id, draft.revision(), UUID.fromString(draft.id()),
                "PASS", "현재 공개 원고를 명시적으로 다시 대조", List.of(), p.sourceReviews(), updated, p.startDate(), p.endDate());
    }

    SourceReviewPublicationService.PublicationBaseline baseline() {
        var n = notices.entity(id);
        return new SourceReviewPublicationService.PublicationBaseline(id, n.revision, n.version,
                sourceHistory.contentFingerprint(n.content), n.sourceFingerprint, receipt.packageFingerprint());
    }

    SourceReviewModels.Package conditionRefinement() {
        var p = afterIndependentScheduleReview();
        var c = notices.content(id);
        var questions = List.of(new Question("housing", "집이 없어야 하나요?", "신청자와 심사 대상 가족의 주택을 확인해요.", c.evidence()));
        var updated = new Content(c.summary(), c.evidence(), c.attachments(), questions, c.documents(), c.schedules(), c.reviewIssues(), c.applicationClosure());
        return new SourceReviewModels.Package("condition-refinement", id, p.revision(), p.extractionId(), "PASS", "질문과 제출서류만 원문 대조", List.of(), p.sourceReviews(), updated, p.startDate(), p.endDate());
    }

    SourceReviewModels.Package currentCollectedSources() throws Exception {
        publication.apply(review, receipt);
        var n = notices.entity(id);
        var c = notices.content(id);
        String nextFile = "https://apply.lh.or.kr/corrected.pdf";
        String nextHtml = "<main><p>정정 공고일 2026.11.20</p><a href='/corrected.pdf'>최신 공고문</a></main>";
        var e = new Evidence("최신 공고문 1쪽", nextFile, "2026-11-21");
        n.content = json.writeValueAsString(new Content(c.summary(), c.evidence(), List.of(e), c.questions(), c.documents(), c.schedules()));
        n.revision++;
        entities.flush();
        when(http.html(URL)).thenReturn(nextHtml);
        when(http.fetchForSourceReview(nextFile)).thenReturn(new AttachmentFingerprintClient.Fingerprint("d".repeat(64), 456, "application/pdf"));
        var draft = store.enqueue(id, true);
        var job = store.claim(id);
        store.finish(job, List.of(new Source("공식 상세", URL, "e".repeat(64), "EXTRACTED", "", List.of(), OfficialDetailParser.parse(nextHtml, URL).parts()),
                new Source("최신 공고문", nextFile, "d".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", "정정된 신청 자격과 서류")))), "READY", "최신 원문 전체 재대조");
        var latest = new Content(c.summary(), c.evidence(), List.of(e), List.of(new Question("housing", "집이 없어야 하나요?", "신청자와 심사 대상 가족의 주택을 확인해요.", e)),
                List.of(new DocumentTemplate("consent", "개인정보 동의서", null, "최신 공고 양식", "LH", nextFile, e)), c.schedules());
        return new SourceReviewModels.Package("current-collected-reconciliation", id, draft.revision(), UUID.fromString(draft.id()), "PASS", "최신 첨부 전체 대조", List.of(),
                List.of(new SourceReview(URL, "e".repeat(64), "공식 상세", "DETAIL", "현재 모집 확인"), new SourceReview(nextFile, "d".repeat(64), "최신 공고문", "RULES", "자격·서류·일정 전체 대조")), latest, review.startDate(), review.endDate());
    }

    @Test
    void reconcilesAlreadyCollectedCurrentFilesWithoutRewritingPreviousPublication() throws Exception {
        var p = currentCollectedSources();
        var base = baseline();
        var oldAudit = db.queryForObject("SELECT payload::text FROM notice_source_review WHERE notice_id=?", String.class, id);
        var verified = receipt(p, Instant.now());
        assertTrue(publication.reconcileCurrentSources(p, verified, base));
        assertFalse(publication.reconcileCurrentSources(p, verified, base));
        assertEquals(p.content(), notices.content(id));
        assertEquals(oldAudit, db.queryForObject("SELECT payload::text FROM notice_source_review WHERE notice_id=? AND payload->>'scope' IS NULL", String.class, id));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM notice_source_review WHERE notice_id=? AND payload->>'scope'='CURRENT_COLLECTED_SOURCES_RECONCILIATION'", Integer.class, id));
    }

    @Test
    void currentSourceReconciliationRejectsUncollectedAttachmentsAndChangedBaseline() throws Exception {
        var p = currentCollectedSources();
        var base = baseline();
        var c = p.content();
        var changed = new Content(c.summary(), c.evidence(), List.of(c.evidence()), c.questions(), c.documents(), c.schedules());
        var wrong = new SourceReviewModels.Package(p.packageId(), id, p.revision(), p.extractionId(), "PASS", p.reviewNotes(), List.of(), p.sourceReviews(), changed, p.startDate(), p.endDate());
        assertThrows(RuntimeException.class, () -> publication.reconcileCurrentSources(wrong, receipt(wrong, Instant.now()), base));
        var verified = receipt(p, Instant.now());
        notices.entity(id).version++;
        assertThrows(RuntimeException.class, () -> publication.reconcileCurrentSources(p, verified, base));
    }

    @Test
    void currentSourceReconciliationCannotReopenClosedOrPendingGuidance() throws Exception {
        var p = currentCollectedSources();
        var base = baseline();
        var verified = receipt(p, Instant.now());
        var n = notices.entity(id);
        n.sourceClosed = true;
        assertThrows(RuntimeException.class, () -> publication.reconcileCurrentSources(p, verified, base));
        n.sourceClosed = false;
        n.changeReview = true;
        assertThrows(RuntimeException.class, () -> publication.reconcileCurrentSources(p, verified, base));
    }

    @Test
    void currentSourceReconciliationRequiresFreshReceiptAndReviewedScheduleBoundaries() throws Exception {
        var p = currentCollectedSources();
        var base = baseline();
        assertThrows(RuntimeException.class, () -> publication.reconcileCurrentSources(p, receipt(p, Instant.now().minusSeconds(901)), base));
        var wrong = new SourceReviewModels.Package(p.packageId(), id, p.revision(), p.extractionId(), "PASS", p.reviewNotes(), List.of(), p.sourceReviews(), p.content(), p.startDate().plusDays(1), p.endDate());
        assertThrows(RuntimeException.class, () -> publication.reconcileCurrentSources(wrong, receipt(wrong, Instant.now()), base));
    }

    @Test
    void conditionOnlyReceiptKeepsUnchangedAncillaryEvidenceButCannotPublishAFullRereview() throws Exception {
        var p = conditionRefinement();
        var n = notices.entity(id);
        var c = notices.content(id);
        var ancillary = new Evidence("기존 지자체 후속 공지", "https://www.wonju.go.kr/www/selectBbsNttView.do?bbsNo=144&key=220&nttNo=495725", "2026-11-20");
        var schedules = new ArrayList<>(c.schedules());
        schedules.add(new ScheduleTemplate("office-followup", "방문 접수 안내", "2026-12-01", ancillary));
        n.content = json.writeValueAsString(new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), schedules));
        entities.flush();
        var next = new Content(c.summary(), c.evidence(), c.attachments(), p.content().questions(), c.documents(), schedules);
        var update = new SourceReviewModels.Package(p.packageId(), id, p.revision(), p.extractionId(), "PASS", p.reviewNotes(), List.of(), p.sourceReviews(), next, p.startDate(), p.endDate());
        var base = baseline();
        var verifier = new SourceReviewVerifier(extractions, notices, http, json);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(update));
        var limited = verifier.verifyConditions(update);
        assertEquals(Set.of(ancillary.url()), limited.retainedEvidence());
        assertThrows(IllegalArgumentException.class, () -> publication.reconcileCurrentSources(update, limited, base));
        assertTrue(publication.refineConditions(update, limited, base));
        assertEquals(schedules, notices.content(id).schedules());
    }

    @Test
    void conditionOnlyVerificationCannotReuseUnverifiedScheduleEvidenceForANewQuestion() throws Exception {
        var p = conditionRefinement();
        var n = notices.entity(id);
        var c = notices.content(id);
        var ancillary = new Evidence("기존 지자체 공지", "https://www.wonju.go.kr/www/selectBbsNttView.do?bbsNo=144&key=220&nttNo=495725", "2026-11-20");
        var schedules = new ArrayList<>(c.schedules());
        schedules.add(new ScheduleTemplate("office-followup", "방문 접수 안내", "2026-12-01", ancillary));
        n.content = json.writeValueAsString(new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), schedules));
        entities.flush();
        var next = new Content(c.summary(), c.evidence(), c.attachments(), List.of(new Question("housing", "새 자격인가요?", "추가 자격을 확인해요.", ancillary)), c.documents(), schedules);
        var wrong = new SourceReviewModels.Package(p.packageId(), id, p.revision(), p.extractionId(), "PASS", p.reviewNotes(), List.of(), p.sourceReviews(), next, p.startDate(), p.endDate());
        assertThrows(IllegalArgumentException.class, () -> new SourceReviewVerifier(extractions, notices, http, json).verifyConditions(wrong));
    }

    @Test
    void conditionRefinementKeepsClosedGuideAndScheduleStateWithoutReplacingOldSnapshots() {
        var p = conditionRefinement();
        var n = notices.entity(id);
        n.sourceClosed = true;
        entities.flush();
        var base = baseline();
        var checked = n.guideCheckedAt;
        var timing = n.applicationEndAt;
        var versions = db.queryForObject("SELECT jsonb_agg(to_jsonb(v) ORDER BY revision)::text FROM published_notice_version v WHERE notice_id=?", String.class, id);
        var verified = receipt(p, Instant.now());
        assertTrue(publication.refineConditions(p, verified, base));
        assertFalse(publication.refineConditions(p, verified, base));
        entities.flush();
        assertTrue(n.sourceClosed);
        assertEquals(checked, n.guideCheckedAt);
        assertEquals(timing, n.applicationEndAt);
        assertEquals("CLOSED", notices.notice(id).status());
        assertEquals("AVAILABLE", notices.notice(id).guideState());
        assertEquals(versions, db.queryForObject("SELECT jsonb_agg(to_jsonb(v) ORDER BY revision)::text FROM published_notice_version v WHERE notice_id=? AND revision<=?", String.class, id, base.revision()));
    }

    @Test
    void conditionRefinementRejectsSummaryScheduleAndDateChanges() {
        var p = conditionRefinement();
        var base = baseline();
        var c = p.content();
        var altered = List.of(new Content("바뀐 지원 요약", c.evidence(), c.attachments(), c.questions(), c.documents(), c.schedules()),
                new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), List.of()));
        for (var content : altered) {
            var changed = new SourceReviewModels.Package(p.packageId(), id, p.revision(), p.extractionId(), "PASS", p.reviewNotes(), List.of(), p.sourceReviews(), content, p.startDate(), p.endDate());
            assertThrows(RuntimeException.class, () -> publication.refineConditions(changed, receipt(changed, Instant.now()), base));
        }
        var changed = new SourceReviewModels.Package(p.packageId(), id, p.revision(), p.extractionId(), "PASS", p.reviewNotes(), List.of(), p.sourceReviews(), c, p.startDate(), p.endDate().plusDays(1));
        assertThrows(RuntimeException.class, () -> publication.refineConditions(changed, receipt(changed, Instant.now()), base));
    }

    @Test
    void conditionRefinementRejectsConcurrentEditsAndExpiredEvidence() {
        var p = conditionRefinement();
        var base = baseline();
        assertThrows(RuntimeException.class, () -> publication.refineConditions(p, receipt(p, Instant.now().minusSeconds(901)), base));
        var verified = receipt(p, Instant.now());
        notices.entity(id).version++;
        assertThrows(RuntimeException.class, () -> publication.refineConditions(p, verified, base));
    }

    @Test
    void conditionRefinementCannotOverrideHumanReviewOrPendingChanges() {
        var p = conditionRefinement();
        var base = baseline();
        var verified = receipt(p, Instant.now());
        var n = notices.entity(id);
        n.changeReview = true;
        assertThrows(RuntimeException.class, () -> publication.refineConditions(p, verified, base));
        n.changeReview = false;
        var user = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,name,email) VALUES(?,?,?)", user, "검토자", user + "@example.invalid");
        db.update("INSERT INTO review_audit(notice_id,reviewer_id,revision,basic_review,guide_review,reason) VALUES(?,?,?,'PASS','PASS',?)", id, user, base.revision(), "기관 확인");
        assertThrows(RuntimeException.class, () -> publication.refineConditions(p, verified, base));
    }

    @Test
    void conditionRefinementCannotRemoveUnresolvedOfficialQuestions() {
        var p = conditionRefinement();
        var n = notices.entity(id);
        var c = notices.content(id);
        var flagged = new Question("housing", "주택 기준", "기관 확인 필요", c.evidence(), null, "SINGLE", null, true);
        n.content = json.writeValueAsString(new Content(c.summary(), c.evidence(), c.attachments(), List.of(flagged), c.documents(), c.schedules()));
        entities.flush();
        var base = baseline();
        assertThrows(RuntimeException.class, () -> publication.refineConditions(p, receipt(p, Instant.now()), base));
    }

    @Test
    void conditionRefinementCanAddOnlyScopedConditionUncertainty() {
        var p = conditionRefinement();
        var base = baseline();
        var c = p.content();
        var issue = new ReviewIssue("new-conflict", "CONDITION", "주택 기준 확인", "원문의 표와 예외 설명이 달라요.", List.of("housing"), List.of(), List.of(c.evidence()));
        var content = new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), c.schedules(), List.of(issue));
        var partial = new SourceReviewModels.Package(p.packageId(), id, p.revision(), p.extractionId(), "PARTIAL", p.reviewNotes(), List.of(issue.id()), p.sourceReviews(), content, p.startDate(), p.endDate());
        assertTrue(publication.refineConditions(partial, receipt(partial, Instant.now()), base));
        assertTrue(notices.content(id).questions().get(0).reviewRequired());
        assertEquals("AVAILABLE", notices.notice(id).guideState());
    }

    @Test
    void conditionRefinementCannotCreateScheduleUncertaintyOutsideItsScope() {
        var p = conditionRefinement();
        var base = baseline();
        var c = p.content();
        var issue = new ReviewIssue("schedule", "SCHEDULE", "접수 기간", "다른 일정", List.of(), List.of(), List.of(c.evidence()));
        var content = new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), c.schedules(), List.of(issue));
        var partial = new SourceReviewModels.Package(p.packageId(), id, p.revision(), p.extractionId(), "PARTIAL", p.reviewNotes(), List.of(issue.id()), p.sourceReviews(), content, p.startDate(), p.endDate());
        assertThrows(RuntimeException.class, () -> publication.refineConditions(partial, receipt(partial, Instant.now()), base));
    }

    @Test
    void currentPublicationRereviewPreservesPersonalAndPublishedSnapshotsAndIsIdempotent() {
        var p = afterIndependentScheduleReview();
        var base = baseline();
        var verified = receipt(p, Instant.now());
        var user = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,name,email) VALUES(?,?,?)", user, "준비 기록 테스트", user + "@example.invalid");
        var preparation = preparations.start(user.toString(), id, base.version());
        var preparationId = UUID.fromString(preparation.id);
        String personal = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, preparationId);
        String previousVersions = db.queryForObject("SELECT jsonb_agg(to_jsonb(v) ORDER BY revision)::text FROM published_notice_version v WHERE notice_id=?", String.class, id);
        assertThrows(RuntimeException.class, () -> publication.amend(p, verified, receipt.packageFingerprint()));
        assertTrue(publication.rereview(p, verified, base));
        assertFalse(publication.rereview(p, verified, base));
        entities.flush();
        assertEquals(base.version() + 1, notices.entity(id).version);
        assertEquals(base.revision() + 1, notices.entity(id).revision);
        assertEquals(personal, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, preparationId));
        assertEquals(previousVersions, db.queryForObject("SELECT jsonb_agg(to_jsonb(v) ORDER BY revision)::text FROM published_notice_version v WHERE notice_id=? AND revision<=?", String.class, id, base.revision()));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM notice_source_review WHERE notice_id=?", Integer.class, id));
        assertEquals(base.contentFingerprint(), db.queryForObject("SELECT payload#>>'{baseline,contentFingerprint}' FROM notice_source_review WHERE notice_id=? AND payload->>'scope'='CURRENT_PUBLICATION_SOURCE_REREVIEW'", String.class, id));
    }

    @Test
    void documentDeadlineAmendmentOnlyAddsTheApplicationDayAndRetainsOtherReviewedContent() throws Exception {
        var prior = afterIndependentScheduleReview();
        var c = notices.content(id);
        var schedules = new ArrayList<>(c.schedules());
        schedules.add(new ScheduleTemplate("documents-end", "서류 제출 마감", prior.endDate().toString(), c.evidence()));
        var content = new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), schedules, c.reviewIssues(), c.applicationClosure());
        var update = new SourceReviewModels.Package("document-deadline", id, prior.revision(), prior.extractionId(),
                "PASS", "신청 시 서류 제출을 공식 원문과 대조", List.of(), prior.sourceReviews(), content, prior.startDate(), prior.endDate());
        var base = baseline();
        var verifier = new SourceReviewVerifier(extractions, notices, http, json);
        var limited = verifier.verifyConditions(update);
        assertThrows(RuntimeException.class, () -> publication.rereview(update, limited, base));
        for (boolean wrongDate : List.of(false, true)) {
            var changedSchedules = new ArrayList<>(schedules);
            if (wrongDate) changedSchedules.set(changedSchedules.size() - 1,
                    new ScheduleTemplate("documents-end", "서류 제출 마감", prior.endDate().plusDays(1).toString(), c.evidence()));
            var changed = new Content(wrongDate ? c.summary() : "다른 요약", c.evidence(), c.attachments(), c.questions(),
                    c.documents(), changedSchedules, c.reviewIssues(), c.applicationClosure());
            var invalid = new SourceReviewModels.Package("invalid-document-deadline", id, prior.revision(), prior.extractionId(),
                    "PASS", update.reviewNotes(), List.of(), prior.sourceReviews(), changed, prior.startDate(), prior.endDate());
            assertThrows(RuntimeException.class, () -> publication.amendDocumentDeadline(invalid, verifier.verifyConditions(invalid), base));
        }
        assertTrue(publication.amendDocumentDeadline(update, limited, base));
        assertEquals(content, notices.content(id));
        assertEquals("CURRENT_PUBLICATION_DOCUMENT_DEADLINE_AMENDMENT", db.queryForObject(
                "SELECT payload->>'scope' FROM notice_source_review WHERE notice_id=? ORDER BY result_revision DESC LIMIT 1", String.class, id));
    }

    @Test
    void currentPublicationRereviewRejectsChangedContentVersionRevisionAndSourceIdentity() {
        var p = afterIndependentScheduleReview();
        var base = baseline();
        var verified = receipt(p, Instant.now());
        var n = notices.entity(id);
        String content = n.content;
        n.content = content.replace("원문 대조 안내", "다른 담당자가 바꾼 원고");
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, base));
        n.content = content;
        n.version++;
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, base));
        n.version--;
        n.revision++;
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, base));
        n.revision--;
        n.sourceFingerprint = "d".repeat(64);
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, base));
    }

    @Test
    void currentPublicationRereviewCannotReplaceAHumanDecisionOrUnrelatedPreviousReview() {
        var p = afterIndependentScheduleReview();
        var base = baseline();
        var verified = receipt(p, Instant.now());
        var wrong = new SourceReviewPublicationService.PublicationBaseline(id, base.revision(), base.version(),
                base.contentFingerprint(), base.sourceFingerprint(), "0".repeat(64));
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, wrong));
        var user = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,name,email) VALUES(?,?,?)", user, "검토 테스트", user + "@example.invalid");
        db.update("INSERT INTO review_audit(notice_id,reviewer_id,revision,basic_review,guide_review,reason) VALUES(?,?,?,'PASS','PASS',?)",
                id, user, base.revision(), "공개 안내 관리자 확인");
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, base));
    }

    @Test
    void currentPublicationRereviewKeepsClosedAndPendingReviewProtection() {
        var p = afterIndependentScheduleReview();
        var base = baseline();
        var verified = receipt(p, Instant.now());
        var n = notices.entity(id);
        n.sourceClosed = true;
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, base));
        n.sourceClosed = false;
        n.changeReview = true;
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, base));
        n.changeReview = false;
        n.guideReview = "HOLD";
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, base));
        n.guideReview = "PASS";
        n.lifecycleState = "CORRECTED";
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, base));
    }

    @Test
    void currentPublicationRereviewRequiresFreshUnchangedOfficialSources() {
        var p = afterIndependentScheduleReview();
        var base = baseline();
        assertThrows(RuntimeException.class, () -> publication.rereview(p, receipt(p, Instant.now().minusSeconds(901)), base));
        var verified = receipt(p, Instant.now());
        store.updateFile(id, p.extractionId(), new Source("변경 공고", FILE, "c".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", "변경된 자격"))));
        assertThrows(RuntimeException.class, () -> publication.rereview(p, verified, base));
    }

    @Test
    void currentPublicationRereviewCannotTreatAPdfEvidenceUrlAsTheHtmlDigestException() throws Exception {
        String detailUrl = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=2015122300020963&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10&mi=1026";
        String pdfUrl = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69143584";
        String html = HTML.replace("/notice.pdf", "/lhapply/lhFile.do?fileid=69143584");
        when(http.html(detailUrl)).thenReturn(html);
        when(http.fetchForSourceReview(pdfUrl)).thenReturn(new AttachmentFingerprintClient.Fingerprint("a".repeat(64), 123, "application/pdf"));
        id = notices.importSource(new LhNoticeClient.Item(LhNoticeClient.sourceKey(detailUrl), "PDF 출처 재검수 테스트", "경북", "행복주택",
                detailUrl, LocalDate.now(), "b".repeat(64)));
        notices.entity(id).basicReview = "PASS";
        entities.flush();
        var draft = store.enqueue(id, false);
        var job = store.claim(id);
        store.finish(job, List.of(new Source("공식 상세 본문", detailUrl, "a".repeat(64), "EXTRACTED", "", List.of(), OfficialDetailParser.parse(html, detailUrl).parts()),
                new Source("공고문", pdfUrl, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", "신청 자격")))), "READY", "");
        var c = review.content();
        var pdfEvidence = new Evidence("공고문", pdfUrl, LocalDate.now().toString());
        var pdfContent = new Content(c.summary(), pdfEvidence, List.of(pdfEvidence),
                List.of(new Question("housing", "주택 기준은 무엇인가요?", "공식 원문 주택 기준을 확인해요.", pdfEvidence)),
                List.of(new DocumentTemplate("consent", "동의서", null, "공식 양식을 작성해요.", "LH", pdfUrl, pdfEvidence)),
                c.schedules().stream().map(s -> new ScheduleTemplate(s.id(), s.title(), s.date(), pdfEvidence)).toList());
        review = new SourceReviewModels.Package(review.packageId(), id, draft.revision(), UUID.fromString(draft.id()), "PASS",
                review.reviewNotes(), List.of(), List.of(new SourceReview(detailUrl, "a".repeat(64), "공식 상세", "DETAIL", "확인"),
                new SourceReview(pdfUrl, "a".repeat(64), "공고문", "RULES", "확인")), pdfContent, review.startDate(), review.endDate());
        receipt = receipt(review, Instant.now());
        var p = afterIndependentScheduleReview();
        var base = baseline();
        when(http.fetchForSourceReview(pdfUrl)).thenReturn(new AttachmentFingerprintClient.Fingerprint("c".repeat(64), 123, "application/pdf"));
        store.updateFile(id, p.extractionId(), new Source("공고문", pdfUrl, "c".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", "새로운 신청 자격"))));
        var changedSources = p.sourceReviews().stream().map(s -> s.url().equals(pdfUrl)
                ? new SourceReview(s.url(), "c".repeat(64), s.label(), s.role(), s.reviewNotes()) : s).toList();
        var changed = new SourceReviewModels.Package(p.packageId(), id, p.revision(), p.extractionId(), "PASS",
                p.reviewNotes(), List.of(), changedSources, p.content(), p.startDate(), p.endDate());
        var verified = receipt(changed, Instant.now());
        assertEquals(detailUrl, verified.detailUrl());
        assertThrows(RuntimeException.class, () -> publication.rereview(changed, verified, base));
    }

    @Test
    void amendmentCannotReplaceAnUnrelatedOrInterveningPublication() {
        var p = amended();
        var verified = receipt(p, Instant.now());
        assertThrows(RuntimeException.class, () -> publication.amend(p, verified, "0".repeat(64)));
        var n = notices.entity(id);
        var c = notices.content(id);
        n.content = json.writeValueAsString(new Content("다른 담당자가 수정한 안내", c.evidence(), c.attachments(), c.questions(), c.documents(), c.schedules()));
        entities.flush();
        assertThrows(RuntimeException.class, () -> publication.amend(p, verified, receipt.packageFingerprint()));
    }

    @Test
    void summaryOnlyPublicationCanBeAmendedOnlyWhileItsFinalContentMatches() {
        var p = amended();
        var verified = receipt(p, Instant.now());
        db.update("""
                UPDATE notice_source_review SET payload=jsonb_build_object(
                    'scope','SUMMARY_ONLY_EXPLICIT_EDIT','afterContent',jsonb_build_object('summary','다른 요약'))
                WHERE package_fingerprint=?
                """, receipt.packageFingerprint());
        assertThrows(RuntimeException.class, () -> publication.amend(p, verified, receipt.packageFingerprint()));
        db.update("""
                UPDATE notice_source_review SET payload=jsonb_set(payload,'{afterContent}',n.content::jsonb)
                FROM notice n WHERE notice_id=n.id AND package_fingerprint=?
                """, receipt.packageFingerprint());
        assertTrue(publication.amend(p, verified, receipt.packageFingerprint()));
    }

    @Test
    void amendmentCannotReplaceGuidanceWithAHumanReviewEvenWhenContentStillMatches() {
        var p = amended();
        var verified = receipt(p, Instant.now());
        var reviewer = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,name,email) VALUES(?,?,?)", reviewer, "검토 테스트", reviewer + "@example.invalid");
        db.update("INSERT INTO review_audit(notice_id,reviewer_id,revision,basic_review,guide_review,reason) VALUES(?,?,?,'PASS','PASS',?)",
                id, reviewer, p.revision(), "동일 원고에 대한 관리자 확인");
        var before = notices.content(id);
        assertThrows(RuntimeException.class, () -> publication.amend(p, verified, receipt.packageFingerprint()));
        assertEquals(before, notices.content(id));
        assertEquals(1, db.queryForObject("select count(*) from notice_source_review where notice_id=?", Integer.class, id));
        assertEquals(1, db.queryForObject("select count(*) from review_audit where notice_id=?", Integer.class, id));
    }

    @Test
    void detailRankScheduleAloneCannotSilentlyLoseKnownApplicationBoundary() {
        var c = review.content();
        var content = new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(),
                List.of(new ScheduleTemplate("rank", "3순위 신청 접수 (10:00~16:00)", "2026-12-16", c.evidence())));
        var p = new SourceReviewModels.Package(review.packageId(), id, review.revision(), review.extractionId(), "PASS", review.reviewNotes(), List.of(), review.sourceReviews(), content, review.startDate(), review.endDate());
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt(p, Instant.now())));
    }

    SourceReviewModels.Package periodReview(LocalDate start, LocalDate end, List<ScheduleTemplate> schedules, String linkedIssue) {
        var c = review.content();
        var issue = new ReviewIssue("application-period", "SCHEDULE", "신청 기간 확인 필요",
                "목록의 기간과 첨부의 단지별 접수기간이 달라 미확정 날짜를 제외했습니다.", List.of(), List.of(), List.of(c.evidence()));
        var content = new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), schedules, List.of(issue));
        return new SourceReviewModels.Package(review.packageId(), id, review.revision(), review.extractionId(),
                "PARTIAL", review.reviewNotes(), List.of(issue.id()), review.sourceReviews(), content, start, end, linkedIssue);
    }

    @Test
    void confirmedConditionsAndDocumentsRemainAvailableWithoutInventingAnUnknownDeadline() {
        var p = periodReview(review.startDate(), null, List.of(review.content().schedules().get(0)), "application-period");
        assertTrue(publication.apply(p, receipt(p, Instant.now())));
        entities.flush();
        entities.clear();
        var result = notices.notice(id);
        assertEquals("AVAILABLE", result.guideState());
        assertEquals("UNKNOWN", result.status());
        assertTrue(result.canStart());
        assertEquals(1, result.questions().size());
        assertEquals(1, result.documentTemplates().size());
        assertNull(result.endDate());
        assertNull(notices.entity(id).applicationEndAt);
        assertEquals(1, result.schedules().size());
        assertFalse(result.questions().get(0).reviewRequired());
        assertEquals("application-period", result.reviewIssues().get(0).id());
        assertEquals("PARTIAL", db.queryForObject("select disposition from notice_source_review where notice_id=?", String.class, id));
    }

    @Test
    void explicitlyReviewedOfficialClosureBlocksStartingButDoesNotInventHistoricalDeadline() throws Exception {
        String closedHtml = HTML.replace("<main>", "<main><p>공고상태 접수마감</p>");
        when(http.html(URL)).thenReturn(closedHtml);
        store.updateFile(id, review.extractionId(), new Source("공식 상세 본문", URL, "a".repeat(64),
                "EXTRACTED", "", List.of(), OfficialDetailParser.parse(closedHtml, URL).parts()));
        var partial = periodReview(review.startDate(), null, List.of(review.content().schedules().get(0)), "application-period");
        var c = partial.content();
        String reason = "공식 상세의 공고상태가 접수마감입니다. 실제 종료일은 확인되지 않았습니다.";
        var evidence = new Evidence("공식 상세 · 공고상태 접수마감", URL, LocalDate.now().toString());
        var content = new Content(c.summary() + " " + reason, c.evidence(), c.attachments(), c.questions(),
                c.documents(), c.schedules(), c.reviewIssues(), new OfficialApplicationClosure(reason, evidence));
        var p = new SourceReviewModels.Package(partial.packageId(), id, partial.revision(), partial.extractionId(),
                partial.disposition(), partial.reviewNotes(), partial.unresolvedFindings(), partial.sourceReviews(), content,
                partial.startDate(), null, partial.applicationPeriodIssueId());
        assertTrue(publication.apply(p, receipt(p, Instant.now())));
        entities.flush();
        entities.clear();
        var result = notices.notice(id);
        assertEquals("CLOSED", result.status());
        assertFalse(result.canStart());
        assertEquals("AVAILABLE", result.guideState());
        assertEquals(1, result.questions().size());
        assertEquals(1, result.documentTemplates().size());
        assertEquals(1, result.reviewIssues().size());
        assertNull(result.endDate());
        assertNull(result.applicationTiming().endsAt());
        assertNull(result.applicationTiming().nextChangeAt());
        assertEquals(new OfficialApplicationClosure(reason, evidence), notices.content(id).applicationClosure());
    }

    @Test
    void bothUnconfirmedBoundariesLeaveNoCalendarDatesButKeepReviewedPreparationContent() {
        var p = periodReview(null, null, List.of(), "application-period");
        assertTrue(publication.apply(p, receipt(p, Instant.now())));
        assertTrue(notices.notice(id).schedules().isEmpty());
        assertNull(notices.notice(id).startDate());
        assertNull(notices.notice(id).endDate());
        assertEquals(1, notices.notice(id).questions().size());
    }

    @Test
    void aGenericPartialIssueDoesNotImplicitlyAuthorizeMissingApplicationDates() {
        var p = periodReview(review.startDate(), null, List.of(review.content().schedules().get(0)), null);
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt(p, Instant.now())));
        assertEquals("HOLD", notices.entity(id).guideReview);
    }

    @Test
    void unconfirmedClosingBoundaryCannotLeakIntoConfirmedSchedules() {
        var p = periodReview(review.startDate(), null, review.content().schedules(), "application-period");
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt(p, Instant.now())));
    }

    @Test
    void confirmedOpeningDateStillRequiresAMatchingBoundary() {
        var p = periodReview(review.startDate(), null, List.of(), "application-period");
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt(p, Instant.now())));
    }

    @Test
    void applicationPeriodIssueMustReferenceTheExactScopedFinding() {
        var p = periodReview(null, null, List.of(), "unrelated-period");
        assertThrows(IllegalArgumentException.class, () -> SourceReviewVerifier.validate(p));
    }

    @Test
    void passStillRequiresCompleteApplicationDates() {
        var p = new SourceReviewModels.Package(review.packageId(), id, review.revision(), review.extractionId(),
                "PASS", review.reviewNotes(), List.of(), review.sourceReviews(), review.content(), review.startDate(), null);
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt(p, Instant.now())));
    }

    @Test
    void unconfirmedPeriodCannotBeAddedAfterTheEvidenceReceiptWasIssued() {
        var p = periodReview(null, null, List.of(), "application-period");
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt));
    }
}
