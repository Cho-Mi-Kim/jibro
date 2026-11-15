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
    @Autowired NoticeService notices;
    @Autowired AutomaticPublicationService automatic;
    @Autowired SourceReviewPublicationService publication;
    @Autowired AutomaticReviewRepository extractions;
    @Autowired ExtractionStore store;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;
    @Autowired ObjectMapper json;
    @MockitoBean AttachmentFingerprintClient http;
    SourceReviewModels.Package review;
    Verification receipt;
    UUID id;
    static final String URL = "https://apply.lh.or.kr/detail";
    static final String FILE = "https://apply.lh.or.kr/notice.pdf";
    static final String HTML = "<main><p>공고일 2026.11.04</p><a href='/notice.pdf'>공고문</a></main>";

    @BeforeEach void setup() throws Exception {
        when(http.html(URL)).thenReturn(HTML);
        when(http.fetchForSourceReview(FILE)).thenReturn(new AttachmentFingerprintClient.Fingerprint("a".repeat(64),123,"application/pdf"));
        id = notices.importSource(new LhNoticeClient.Item("LH:" + UUID.randomUUID(), "실제 출처 검토 테스트", "서울", "국민임대", URL, LocalDate.now(), "b".repeat(64)));
        var n = notices.entity(id); n.basicReview = "PASS";
        entities.flush();
        var draft = store.enqueue(id, false);
        var job = store.claim(id);
        store.finish(job, List.of(new Source("공식 상세 본문", URL, "a".repeat(64), "EXTRACTED", "", List.of(), OfficialDetailParser.parse(HTML,URL).parts()), new Source("공고문", FILE, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", "신청 자격")))), "READY", "");
        var e = new Evidence("공고문 1쪽", URL, LocalDate.now().toString());
        var content = new Content("원문 대조 안내", e, List.of(e), List.of(new Question("housing", "무주택 기준을 확인했나요?", "가구 범위를 공식 원문에서 확인합니다.", e)),
            List.of(new DocumentTemplate("consent", "개인정보 동의서", null, "공고 첨부 양식", "LH", URL, e)),
            List.of(new ScheduleTemplate("apply-start", "신청 접수 시작", "2026-11-25", e), new ScheduleTemplate("apply-end", "신청 접수 마감", "2026-12-16", e)));
        review = new SourceReviewModels.Package("test-source-review", id, draft.revision(), UUID.fromString(draft.id()), "PASS", "공식 원문 전체 대조", List.of(),
            List.of(new SourceReview(URL, "a".repeat(64), "공식 상세 본문", "DETAIL", "상세 확인"), new SourceReview(FILE, "a".repeat(64), "공고문", "RULES", "자격·서류·일정 확인")), content, LocalDate.of(2026,11,25), LocalDate.of(2026,12,16));
        receipt = receipt(review, Instant.now());
    }
    Verification receipt(SourceReviewModels.Package p, Instant at) {
        try { return new SourceReviewVerifier(extractions, notices, http, json, Clock.fixed(at, ZoneOffset.UTC)).verify(p); }
        catch(Exception failure) { throw new IllegalArgumentException(failure); }
    }
    SourceReviewModels.Package decision(String outcome, List<String> findings) {
        return new SourceReviewModels.Package(review.packageId(), id, review.revision(), review.extractionId(), outcome, review.reviewNotes(), findings, review.sourceReviews(), review.content(), review.startDate(), review.endDate());
    }
    @Test void publishesReviewedContentAndAuditsAiAssistanceWithoutInventingAnAdministrator() {
        assertTrue(publication.apply(review, receipt));
        entities.flush(); entities.clear();
        assertEquals("AVAILABLE", notices.notice(id).guideState());
        assertEquals(1, notices.content(id).questions().size());
        assertEquals(1, db.queryForObject("select count(*) from notice_source_review where notice_id=? and reviewer_kind='AI_ASSISTED_SOURCE_REVIEW'", Integer.class, id));
        assertEquals(0, db.queryForObject("select count(*) from review_audit where notice_id=?", Integer.class, id));
        assertTrue(notices.entity(id).automaticReviewBlocked);
        assertFalse(publication.apply(review, receipt));
    }
    @Test void genuineConflictCannotBePublished() {
        var conflict = decision("PASS", List.of("공식 문서의 마감시간이 다릅니다."));
        assertThrows(IllegalArgumentException.class, () -> publication.apply(conflict, receipt(conflict, Instant.now())));
        assertEquals("HOLD", notices.entity(id).guideReview);
    }
    @Test void partiallyReviewedGuidanceIsAvailableWithScopedIssuesAndPartialAudit() {
        var c=review.content();
        var issue=new ReviewIssue("housing-date","CONDITION","무주택 기준일 확인 필요","공식 자료의 기준일이 서로 다릅니다.",List.of("housing"),List.of(),List.of(c.evidence()));
        var content=new Content(c.summary(),c.evidence(),c.attachments(),c.questions(),c.documents(),c.schedules(),List.of(issue));
        var p=new SourceReviewModels.Package(review.packageId(),id,review.revision(),review.extractionId(),"PARTIAL",review.reviewNotes(),List.of(issue.id()),review.sourceReviews(),content,review.startDate(),review.endDate());
        assertTrue(publication.apply(p,receipt(p,Instant.now())));
        entities.flush(); entities.clear();
        var result=notices.notice(id);
        assertEquals("AVAILABLE",result.guideState());
        assertTrue(result.canStart());
        assertEquals(List.of(issue),result.reviewIssues());
        assertTrue(result.questions().get(0).reviewRequired());
        assertEquals(1,result.documentTemplates().size());
        assertEquals(2,result.schedules().size());
        assertEquals("PARTIAL",db.queryForObject("select disposition from notice_source_review where notice_id=?",String.class,id));
        assertTrue(notices.entity(id).automaticReviewBlocked);
    }
    @Test void holdPreservesExistingContentDatesAndRevision() {
        var before = notices.attachmentSnapshot(id);
        var hold = decision("HOLD", List.of("기관 확인 필요"));
        assertTrue(publication.apply(hold, receipt(hold, Instant.now())));
        assertEquals(before, notices.attachmentSnapshot(id));
        assertNull(notices.notice(id).startDate());
    }
    @Test void unsupportedDoesNotExposeCandidateQuestions() {
        var unsupported = decision("UNSUPPORTED", List.of());
        assertTrue(publication.apply(unsupported, receipt(unsupported, Instant.now())));
        assertEquals("UNSUPPORTED", notices.notice(id).guideState());
        assertTrue(notices.notice(id).questions().isEmpty());
        assertFalse(notices.notice(id).canStart());
    }
    @Test void changedExtractionCannotApplyOldReview() {
        store.updateFile(id, review.extractionId(), new Source("변경 공고", URL, "c".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", "변경된 자격"))));
        assertThrows(RuntimeException.class, () -> publication.apply(review, receipt));
    }
    @Test void changedNoticeCannotApplyOldReview() {
        notices.entity(id).revision++;
        assertThrows(RuntimeException.class, () -> publication.apply(review, receipt));
    }
    @Test void staleOrWrongReceiptCannotApply() {
        assertThrows(IllegalArgumentException.class, () -> publication.apply(review, receipt(review, Instant.now().minusSeconds(901))));
        assertThrows(IllegalArgumentException.class, () -> publication.apply(decision("HOLD", List.of()), receipt));
    }
    @Test void existingApprovedGuidanceIsNotReplaced() {
        notices.entity(id).guideReview = "PASS";
        assertThrows(RuntimeException.class, () -> publication.apply(review, receipt));
    }
    SourceReviewModels.Package amended() {
        publication.apply(review, receipt);
        entities.flush(); entities.clear();
        var previousSources = store.find(review.extractionId()).sources();
        var draft = store.enqueue(id, false);
        var job = store.claim(id);
        store.finish(job, previousSources, "READY", "재확인한 공식 자료");
        var c = review.content();
        var content = new Content("서류 적용 범위를 다시 대조한 안내", c.evidence(), c.attachments(), c.questions(), c.documents(), c.schedules());
        return new SourceReviewModels.Package("test-source-review-amended", id, draft.revision(), UUID.fromString(draft.id()),
            "PASS", "서류 적용 범위 정정", List.of(), review.sourceReviews(), content, review.startDate(), review.endDate());
    }
    @Test void explicitAmendmentPreservesPreviousAuditAndRequiresFreshSourceVerification() {
        var p = amended();
        assertThrows(RuntimeException.class, () -> publication.amend(p, receipt, receipt.packageFingerprint()));
        assertTrue(publication.amend(p, receipt(p, Instant.now()), receipt.packageFingerprint()));
        entities.flush(); entities.clear();
        assertEquals(p.content(), notices.content(id));
        assertEquals(2, db.queryForObject("select count(*) from notice_source_review where notice_id=?", Integer.class, id));
        assertEquals(0, db.queryForObject("select count(*) from review_audit where notice_id=?", Integer.class, id));
    }
    @Test void amendmentCannotReplaceAnUnrelatedOrInterveningPublication() {
        var p = amended();
        var verified = receipt(p, Instant.now());
        assertThrows(RuntimeException.class, () -> publication.amend(p, verified, "0".repeat(64)));
        var n = notices.entity(id);
        var c = notices.content(id);
        n.content = json.writeValueAsString(new Content("다른 담당자가 수정한 안내", c.evidence(), c.attachments(), c.questions(), c.documents(), c.schedules()));
        entities.flush();
        assertThrows(RuntimeException.class, () -> publication.amend(p, verified, receipt.packageFingerprint()));
    }
    @Test void amendmentCannotReplaceGuidanceWithAHumanReviewEvenWhenContentStillMatches() {
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
    @Test void detailRankScheduleAloneCannotSilentlyLoseKnownApplicationBoundary() {
        var c=review.content();
        var content=new Content(c.summary(),c.evidence(),c.attachments(),c.questions(),c.documents(),
            List.of(new ScheduleTemplate("rank", "3순위 신청 접수 (10:00~16:00)", "2026-12-16", c.evidence())));
        var p=new SourceReviewModels.Package(review.packageId(),id,review.revision(),review.extractionId(),"PASS",review.reviewNotes(),List.of(),review.sourceReviews(),content,review.startDate(),review.endDate());
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt(p,Instant.now())));
    }

    SourceReviewModels.Package periodReview(LocalDate start, LocalDate end, List<ScheduleTemplate> schedules, String linkedIssue) {
        var c = review.content();
        var issue = new ReviewIssue("application-period", "SCHEDULE", "신청 기간 확인 필요",
            "목록의 기간과 첨부의 단지별 접수기간이 달라 미확정 날짜를 제외했습니다.", List.of(), List.of(), List.of(c.evidence()));
        var content = new Content(c.summary(), c.evidence(), c.attachments(), c.questions(), c.documents(), schedules, List.of(issue));
        return new SourceReviewModels.Package(review.packageId(), id, review.revision(), review.extractionId(),
            "PARTIAL", review.reviewNotes(), List.of(issue.id()), review.sourceReviews(), content, start, end, linkedIssue);
    }

    @Test void confirmedConditionsAndDocumentsRemainAvailableWithoutInventingAnUnknownDeadline() {
        var p = periodReview(review.startDate(), null, List.of(review.content().schedules().get(0)), "application-period");
        assertTrue(publication.apply(p, receipt(p, Instant.now())));
        entities.flush(); entities.clear();
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

    @Test void explicitlyReviewedOfficialClosureBlocksStartingButDoesNotInventHistoricalDeadline() throws Exception {
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
        entities.flush(); entities.clear();
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

    @Test void bothUnconfirmedBoundariesLeaveNoCalendarDatesButKeepReviewedPreparationContent() {
        var p = periodReview(null, null, List.of(), "application-period");
        assertTrue(publication.apply(p, receipt(p, Instant.now())));
        assertTrue(notices.notice(id).schedules().isEmpty());
        assertNull(notices.notice(id).startDate());
        assertNull(notices.notice(id).endDate());
        assertEquals(1, notices.notice(id).questions().size());
    }

    @Test void aGenericPartialIssueDoesNotImplicitlyAuthorizeMissingApplicationDates() {
        var p = periodReview(review.startDate(), null, List.of(review.content().schedules().get(0)), null);
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt(p, Instant.now())));
        assertEquals("HOLD", notices.entity(id).guideReview);
    }

    @Test void unconfirmedClosingBoundaryCannotLeakIntoConfirmedSchedules() {
        var p = periodReview(review.startDate(), null, review.content().schedules(), "application-period");
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt(p, Instant.now())));
    }

    @Test void confirmedOpeningDateStillRequiresAMatchingBoundary() {
        var p = periodReview(review.startDate(), null, List.of(), "application-period");
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt(p, Instant.now())));
    }

    @Test void applicationPeriodIssueMustReferenceTheExactScopedFinding() {
        var p = periodReview(null, null, List.of(), "unrelated-period");
        assertThrows(IllegalArgumentException.class, () -> SourceReviewVerifier.validate(p));
    }

    @Test void passStillRequiresCompleteApplicationDates() {
        var p = new SourceReviewModels.Package(review.packageId(), id, review.revision(), review.extractionId(),
            "PASS", review.reviewNotes(), List.of(), review.sourceReviews(), review.content(), review.startDate(), null);
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt(p, Instant.now())));
    }

    @Test void unconfirmedPeriodCannotBeAddedAfterTheEvidenceReceiptWasIssued() {
        var p = periodReview(null, null, List.of(), "application-period");
        assertThrows(IllegalArgumentException.class, () -> publication.apply(p, receipt));
    }
}
