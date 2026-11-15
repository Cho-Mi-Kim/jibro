package org.jibro.collection.review.followup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jibro.collection.extraction.ExcerptClassifier;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.ExtractionStore;
import org.jibro.collection.review.local.LocalAnalysisService;
import org.jibro.notice.*;
import org.jibro.notice.FollowupReviewModels.*;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AutomaticFollowupServiceTest {
    private final FollowupReviewRepository queue = mock(FollowupReviewRepository.class);
    private final AutomaticFollowupPublicationService publication = mock(AutomaticFollowupPublicationService.class);
    private final OfficialFollowupDiscovery discovery = mock(OfficialFollowupDiscovery.class);
    private final FollowupPrimaryVerifier primaryVerifier = mock(FollowupPrimaryVerifier.class);
    private final ExtractionStore extraction = mock(ExtractionStore.class);
    private final NoticeService notices = mock(NoticeService.class);
    private final LocalAnalysisService local = mock(LocalAnalysisService.class);
    private final UUID notice = UUID.randomUUID();
    private final Job job = new Job(UUID.randomUUID(), notice, 2, "a".repeat(64), "b".repeat(64), UUID.randomUUID());
    private final Receipt receipt = new Receipt(job.runId(), job.leaseToken(), "c".repeat(64));
    private Primary primary;
    private AutomaticFollowupService service(boolean enabled) {
        return new AutomaticFollowupService(queue, publication, discovery, primaryVerifier, extraction,
            notices, local, new ObjectMapper(), enabled, enabled);
    }

    @BeforeEach void setup() {
        String url = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=123";
        var content = new Content("공식 자료", new Evidence("원공고", url, "2026-11-09"), List.of(), List.of(), List.of(), List.of());
        var parts = List.of(new Part("공고문 1쪽", "신청자는 무주택자이어야 합니다.\n신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.\n공통 제출서류: 주민등록등본\n신청 기간: 2026-11-01 ~ 2026-12-31"));
        var draft = new Draft(UUID.randomUUID().toString(), notice.toString(), 2, "READY", Instant.now().minusSeconds(10), "",
            List.of(new Source("공식 상세 본문", url, "d".repeat(64), "EXTRACTED", "", ExcerptClassifier.extract(parts,url), parts)));
        primary = new Primary(new AutomaticReviewRepository.ExtractionSnapshot(draft, "e".repeat(64)), content);
        when(queue.claim()).thenReturn(Optional.of(job));
        when(queue.renew(job)).thenReturn(true);
        when(queue.primary(job)).thenReturn(Optional.of(primary));
        when(notices.draft(notice)).thenReturn(new NoticeService.Draft("서울 모집", "LH", "서울", "청년 임대", null, null, LocalDate.of(2026,10,30), content));
        when(queue.stage(eq(job), eq(primary), anyString(), any(), anyString(), any())).thenReturn(receipt);
        when(publication.publish(receipt)).thenReturn(new Outcome("APPLIED", true, 3));
    }

    @Test void disabledWorkerCannotReadOrPublishAnything() {
        assertFalse(service(false).runNext());
        verifyNoInteractions(discovery, primaryVerifier, publication);
        verify(queue, never()).claim();
    }

    @Test void incompleteDiscoveryIsRetriedWithoutManufacturingAgencyIssues() throws Exception {
        when(discovery.discover(any(),any())).thenReturn(new OfficialFollowupModels.Discovery(Instant.now(),List.of(),List.of("FOLLOWUP_SEARCH_INCOMPLETE")));
        assertFalse(service(true).runNext());
        verify(queue).defer(job,"FOLLOWUP_SEARCH_INCOMPLETE");
        verifyNoInteractions(publication);
    }

    @Test void missingPrimaryQueuesExtractionInsteadOfGuessingFromFollowups() {
        when(queue.primary(job)).thenReturn(Optional.empty());
        assertFalse(service(true).runNext());
        verify(extraction).enqueue(notice,false);
        verify(queue).defer(job,"PRIMARY_EXTRACTION_PENDING");
        verifyNoInteractions(discovery,publication);
    }

    @Test void provenPrimaryChangeWithdrawsStaleAutomaticFactsAndDoesNotPublishTheNewGuess() throws Exception {
        when(discovery.discover(any(),any())).thenReturn(new OfficialFollowupModels.Discovery(Instant.now(),List.of(),List.of("FOLLOWUP_PRIMARY_CHANGED")));
        assertFalse(service(true).runNext());
        verify(publication).primaryChanged(job);
        verify(publication,never()).publish(any());
        verify(queue,never()).defer(any(),anyString());
    }

    @Test void changedRelatedEvidenceBetweenCompileAndPublishCannotLeak() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(),List.of(),List.of());
        when(discovery.discover(any(),any())).thenReturn(found);
        when(discovery.revalidate(any(),any(),eq(found))).thenReturn(new OfficialFollowupModels.Discovery(Instant.now(),List.of(),List.of("FOLLOWUP_CHANGED")));
        assertFalse(service(true).runNext());
        verify(queue).defer(job,"OFFICIAL_FOLLOWUP_CHANGED_DURING_REVIEW");
        verifyNoInteractions(publication);
    }

    @Test void completeComparisonRechecksPrimaryAndPersistsImmutableEvidenceBeforePublish() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(),List.of(),List.of());
        when(discovery.discover(any(),any())).thenReturn(found);
        when(discovery.revalidate(any(),any(),eq(found))).thenReturn(found);
        assertTrue(service(true).runNext());
        var order = inOrder(primaryVerifier,queue,publication);
        order.verify(primaryVerifier).verify(primary.snapshot().draft(),primary.originalContent());
        order.verify(queue).stage(eq(job),eq(primary),anyString(),any(),contains("resolutions"),argThat(d -> d.state().equals("READY")));
        order.verify(publication).publish(receipt);
    }

    @Test void identicalBundleStillChecksPrimaryAndOnlyRecordsAnUnchangedDecision() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(),List.of(),List.of());
        when(discovery.discover(any(),any())).thenReturn(found);
        when(queue.sameBundle(eq(job),anyString())).thenReturn(true);
        when(publication.publish(receipt)).thenReturn(new Outcome("UNCHANGED",false,2));
        assertFalse(service(true).runNext());
        verify(primaryVerifier).verify(primary.snapshot().draft(),primary.originalContent());
        verify(queue).stage(eq(job),eq(primary),anyString(),any(),anyString(),argThat(d -> d.state().equals("UNCHANGED")));
        verifyNoInteractions(local);
    }

    @Test void missingPreviouslyAppliedEvidenceCannotRevertToOriginalFacts() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        var previous = new OfficialFollowupModels.Discovery(Instant.now(), List.of(correction()), List.of());
        when(queue.latestDependencySources(notice)).thenReturn(Optional.of(new ObjectMapper().writeValueAsString(java.util.Map.of("discovery",previous))));
        when(discovery.discover(any(),any())).thenReturn(found);
        assertFalse(service(true).runNext());
        verify(queue).defer(job,"FOLLOWUP_PREVIOUS_EVIDENCE_MISSING");
        verifyNoInteractions(publication,local,primaryVerifier);
    }

    @Test void verifiedCorrectionStillWithdrawsOldGuidanceWhenAnotherCandidateCannotBeRead() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(correction()), List.of("FOLLOWUP_CANDIDATE_UNREAD"));
        when(discovery.discover(any(),any())).thenReturn(found);
        when(discovery.revalidate(any(),any(),eq(found))).thenReturn(found);
        assertTrue(service(true).runNext());
        verify(primaryVerifier).verify(primary.snapshot().draft(),primary.originalContent());
        verify(queue).stage(eq(job),eq(primary),anyString(),any(),contains("invalidations"),
            argThat(value -> value.state().equals("REVIEW_REQUIRED") && value.blockers().contains("FOLLOWUP_CANDIDATE_UNREAD")));
    }

    @Test void primaryChangeDuringFinalPassWithdrawsStaleGuidance() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        when(discovery.discover(any(),any())).thenReturn(found);
        when(discovery.revalidate(any(),any(),eq(found))).thenReturn(new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of("FOLLOWUP_PRIMARY_CHANGED")));
        assertFalse(service(true).runNext());
        verify(publication).primaryChanged(job);
        verify(publication,never()).publish(any());
    }

    private OfficialFollowupModels.Related correction() {
        String url="https://apply.lh.or.kr/lhapply/apply/noti/an/view.do?bbsSn=101";
        var text="변경 전: 신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.\n변경 후: 신청자의 월평균 소득은 4,000,000원 이하이어야 합니다.";
        var source=new Source("정정 공고",url,"f".repeat(64),"EXTRACTED","",List.of(),List.of(new Part("정정 1쪽",text)));
        return new OfficialFollowupModels.Related("CORRECTION",url,"정정 공고",LocalDate.of(2026,11,1),"공식 양방향 연결",List.of(source));
    }
}
