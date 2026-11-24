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
    private final AgencyIntakeClient agencies = mock(AgencyIntakeClient.class);
    private final MyHomeIntakeClient myHome = mock(MyHomeIntakeClient.class);
    private final UUID notice = UUID.randomUUID();
    private final Job job = new Job(UUID.randomUUID(), notice, 2, "a".repeat(64), "b".repeat(64), UUID.randomUUID());
    private final Receipt receipt = new Receipt(job.runId(), job.leaseToken(), "c".repeat(64));
    private Primary primary;

    private AutomaticFollowupService service(boolean enabled) {
        return new AutomaticFollowupService(queue, publication, discovery, primaryVerifier, extraction,
                notices, local, new ObjectMapper(), myHome, agencies, enabled, enabled);
    }

    @BeforeEach
    void setup() {
        String url = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=123";
        var content = new Content("공식 자료", new Evidence("원공고", url, "2026-11-09"), List.of(), List.of(), List.of(), List.of());
        var parts = List.of(new Part("공고문 1쪽", "신청자는 무주택자이어야 합니다.\n신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.\n공통 제출서류: 주민등록등본\n신청 기간: 2026-11-01 ~ 2026-12-31"));
        var draft = new Draft(UUID.randomUUID().toString(), notice.toString(), 2, "READY", Instant.now().minusSeconds(10), "",
                List.of(new Source("공식 상세 본문", url, "d".repeat(64), "EXTRACTED", "", ExcerptClassifier.extract(parts, url), parts)));
        primary = new Primary(new AutomaticReviewRepository.ExtractionSnapshot(draft, "e".repeat(64)), content);
        when(queue.claim()).thenReturn(Optional.of(job));
        when(queue.renew(job)).thenReturn(true);
        when(queue.primary(job)).thenReturn(Optional.of(primary));
        when(notices.draft(notice)).thenReturn(new NoticeService.Draft("서울 모집", "LH", "서울", "청년 임대", null, null, LocalDate.of(2026, 10, 30), content));
        when(queue.stage(eq(job), eq(primary), anyString(), any(), anyString(), any())).thenReturn(receipt);
        when(publication.publish(receipt)).thenReturn(new Outcome("APPLIED", true, 3));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void completedVisitTimesStillTriggerOfficeComparisonAndRevalidation(boolean lastDay) throws Exception {
        var start = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
        var end = start.plusDays(2);
        var content = primary.originalContent();
        var file = new Source("모집공고.pdf", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=321",
                "b".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("3쪽", "방문 접수: " + start + " ~ " + end + "\n매일 10:00~17:00")));
        var portal = new Source("공식 상세 본문", content.evidence().url(), "a".repeat(64), "EXTRACTED", "",
                List.of(), List.of(new Part("공식 상세 본문 · 항목 1", "접수기간: " + start + " ~ " + end)));
        var draft = new Draft(UUID.randomUUID().toString(), notice.toString(), 2, "READY", Instant.now().minusSeconds(10), "", List.of(file, portal));
        var input = new Primary(new AutomaticReviewRepository.ExtractionSnapshot(draft, "e".repeat(64)), content);
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        var windows = OfficeVisitFields.read("방문 접수일: " + start + " ~ " + end + " 09:00~18:00 (점심시간 12:00~13:00 제외)",
                "https://www.wonju.go.kr/www/selectBbsNttView.do?nttNo=100", start.minusDays(3));
        when(queue.primary(job)).thenReturn(Optional.of(input));
        when(queue.stage(eq(job), eq(input), anyString(), any(), anyString(), any())).thenReturn(receipt);
        when(discovery.discover(any(), any())).thenReturn(found);
        when(discovery.revalidate(any(), any(), eq(found))).thenReturn(found);
        if (lastDay) {
            var edition = LocalDate.of(2026, 10, 30);
            windows = List.of(new org.jibro.collection.review.IntakeChannelDates.Window("현장", "", start, end,
                    "https://www.kohom.or.kr/web/mainComm/HM001002002.do?mode=view&p_idx=123", null, java.time.LocalTime.of(16, 0),
                    edition, null, null, org.jibro.collection.review.IntakeChannelDates.AgencyField.KOHOM_INTAKE, null, null,
                    new org.jibro.collection.review.IntakeChannelDates.Office("접수 관리사무소", edition, "")));
        }
        when(agencies.find(any())).thenReturn(windows);
        assertTrue(service(true).runNext());
        verify(agencies, times(2)).find(any());
        if (lastDay) {
            verify(queue).stage(eq(job), eq(input), anyString(), any(), contains("KOHOM_INTAKE"), argThat(d ->
                    d.content().schedules().stream().anyMatch(s -> end.toString().equals(s.date())
                            && s.title().contains("관리사무소 방문 · 10:00~16:00 · 확인 필요 16:00~17:00"))
                            && d.content().reviewIssues().stream().filter(i -> i.detail().contains("16:00~17:00")).count() == 1));
            return;
        }
        verify(queue).stage(eq(job), eq(input), anyString(), any(), contains("VISIT_DAILY"), argThat(d ->
                d.content().schedules().stream().filter(s -> s.title().contains("10:00~12:00, 13:00~17:00")).count() == 3
                        && d.content().reviewIssues().stream().allMatch(i -> i.detail().contains("09:00~10:00, 17:00~18:00"))));
    }

    @Test
    void unreadableOfficialFieldKeepsItsReasonAndCannotPublishAnEmptyComparison() {
        var failure = new AgencyIntakePost.InvalidField("AGENCY_AMENDMENT_BEFORE_INVALID", "https://www.gimje.go.kr/board/view.gimje?dataSid=1",
                "온라인 접수 마감: 2026.11.31 16:00 → 2026.12.01 17:00", new java.time.DateTimeException("invalid day"));
        when(discovery.discover(any(), any())).thenThrow(failure);
        assertFalse(service(true).runNext());
        verify(queue).fail(job, failure.getMessage());
        verifyNoInteractions(publication);
    }

    @Test
    void workerRechecksManagementClocksWhenTheOriginalAttachmentHasOnlyDates() throws Exception {
        var start = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusWeeks(2);
        var end = start.plusDays(2);
        var content = primary.originalContent();
        var file = new Source("모집공고.pdf", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=321",
                "b".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("공고문 10쪽", "현장 접수 기간: " + start + " ~ " + end)));
        var portal = new Source("공식 상세 본문", content.evidence().url(), "a".repeat(64), "EXTRACTED", "",
                List.of(), List.of(new Part("공식 상세 본문 · 항목 1", "접수기간: " + start + " ~ " + end)));
        var draft = new Draft(UUID.randomUUID().toString(), notice.toString(), 2, "READY", Instant.now().minusSeconds(10), "", List.of(file, portal));
        var input = new Primary(new AutomaticReviewRepository.ExtractionSnapshot(draft, "e".repeat(64)), content);
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        var field = new org.jibro.collection.review.IntakeChannelDates.Window("", "", start, end,
                "https://www.kohom.or.kr/web/mainComm/HM001002002.do?mode=view&p_idx=10", java.time.LocalTime.of(9, 0),
                java.time.LocalTime.of(18, 0), start.minusWeeks(2), null, null, org.jibro.collection.review.IntakeChannelDates.AgencyField.KOHOM_INTAKE);
        when(queue.primary(job)).thenReturn(Optional.of(input));
        when(queue.stage(eq(job), eq(input), anyString(), any(), anyString(), any())).thenReturn(receipt);
        when(discovery.discover(any(), any())).thenReturn(found);
        when(discovery.revalidate(any(), any(), eq(found))).thenReturn(found);
        when(agencies.find(any())).thenReturn(List.of(field));
        assertTrue(service(true).runNext());
        verify(agencies, times(2)).find(any());
        verify(queue).stage(eq(job), eq(input), anyString(), any(), contains("KOHOM_INTAKE"), argThat(d ->
                d.content().schedules().stream().anyMatch(s -> s.date().equals(end + "T18:00") && field.url().equals(s.evidence().url()))));
    }

    @Test
    void workerRechecksMethodEvidenceBeforePublishingOnlyTheIntakeDates() throws Exception {
        var content = primary.originalContent();
        String file = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=321";
        var parts = List.of(new Part("공고문 1쪽", "현장 접수 기간: 2026.12.01 10:00 ~ 2026.12.02 15:00"));
        var sources = List.of(new Source("공식 상세 본문", content.evidence().url(), "a".repeat(64), "EXTRACTED", "", List.of(),
                        List.of(new Part("공식 상세 본문 · 항목 1", "접수기간: 2026.12.01 ~ 2026.12.03"))),
                new Source("모집공고.pdf", file, "b".repeat(64), "EXTRACTED", "", List.of(), parts));
        var draft = new Draft(UUID.randomUUID().toString(), notice.toString(), 2, "READY", Instant.now().minusSeconds(10), "", sources);
        var input = new Primary(new AutomaticReviewRepository.ExtractionSnapshot(draft, "e".repeat(64)), content);
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        var window = new org.jibro.collection.review.IntakeChannelDates.Window("현장", "", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 2), "https://www.myhome.go.kr/");
        when(queue.primary(job)).thenReturn(Optional.of(input));
        when(queue.stage(eq(job), eq(input), anyString(), any(), anyString(), any())).thenReturn(receipt);
        when(discovery.discover(any(), any())).thenReturn(found);
        when(discovery.revalidate(any(), any(), eq(found))).thenReturn(found);
        when(myHome.find(any())).thenReturn(List.of(window));
        assertTrue(service(true).runNext());
        verify(myHome, times(2)).find(any());
        verify(agencies, times(2)).find(any());
        verify(queue).stage(eq(job), eq(input), anyString(), any(), contains("myHomeWindows"),
                argThat(d -> d.state().equals("INTAKE") && window.end().equals(d.endDate())));
    }

    @Test
    void disabledWorkerCannotReadOrPublishAnything() {
        assertFalse(service(false).runNext());
        verifyNoInteractions(discovery, primaryVerifier, publication);
        verify(queue, never()).claim();
    }

    @Test
    void incompleteDiscoveryIsRetriedWithoutManufacturingAgencyIssues() throws Exception {
        when(discovery.discover(any(), any())).thenReturn(new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of("FOLLOWUP_SEARCH_INCOMPLETE")));
        assertFalse(service(true).runNext());
        verify(queue).defer(job, "FOLLOWUP_SEARCH_INCOMPLETE");
        verifyNoInteractions(publication);
    }

    @Test
    void missingPrimaryQueuesExtractionInsteadOfGuessingFromFollowups() {
        when(queue.primary(job)).thenReturn(Optional.empty());
        assertFalse(service(true).runNext());
        verify(extraction).enqueue(notice, false);
        verify(queue).defer(job, "PRIMARY_EXTRACTION_PENDING");
        verifyNoInteractions(discovery, publication);
    }

    @Test
    void provenPrimaryChangeWithdrawsStaleAutomaticFactsAndDoesNotPublishTheNewGuess() throws Exception {
        when(discovery.discover(any(), any())).thenReturn(new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of("FOLLOWUP_PRIMARY_CHANGED")));
        assertFalse(service(true).runNext());
        verify(publication).primaryChanged(job);
        verify(publication, never()).publish(any());
        verify(queue, never()).defer(any(), anyString());
    }

    @Test
    void changedRelatedEvidenceBetweenCompileAndPublishCannotLeak() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        when(discovery.discover(any(), any())).thenReturn(found);
        when(discovery.revalidate(any(), any(), eq(found))).thenReturn(new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of("FOLLOWUP_CHANGED")));
        assertFalse(service(true).runNext());
        verify(queue).defer(job, "OFFICIAL_FOLLOWUP_CHANGED_DURING_REVIEW");
        verifyNoInteractions(publication);
    }

    @Test
    void completeComparisonRechecksPrimaryAndPersistsImmutableEvidenceBeforePublish() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        when(discovery.discover(any(), any())).thenReturn(found);
        when(discovery.revalidate(any(), any(), eq(found))).thenReturn(found);
        assertTrue(service(true).runNext());
        var order = inOrder(primaryVerifier, queue, publication);
        order.verify(primaryVerifier).verify(primary.snapshot().draft(), primary.originalContent());
        order.verify(queue).stage(eq(job), eq(primary), anyString(), any(), contains("resolutions"), argThat(d -> d.state().equals("READY")));
        order.verify(publication).publish(receipt);
    }

    @Test
    void identicalBundleStillChecksPrimaryAndOnlyRecordsAnUnchangedDecision() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        when(discovery.discover(any(), any())).thenReturn(found);
        when(queue.sameBundle(eq(job), anyString())).thenReturn(true);
        when(queue.previousCoverage(eq(job), anyString())).thenReturn(Optional.of("{\"conditions\":2,\"documents\":1}"));
        when(publication.publish(receipt)).thenReturn(new Outcome("UNCHANGED", false, 2));
        assertFalse(service(true).runNext());
        verify(primaryVerifier).verify(primary.snapshot().draft(), primary.originalContent());
        verify(queue).stage(eq(job), eq(primary), anyString(), any(), contains("\"itemCoverage\""), argThat(d -> d.state().equals("UNCHANGED")));
        verifyNoInteractions(local);
    }

    @Test
    void missingPreviousDocumentKeepsOnlyIndependentlyConfirmedCurrentItems() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        when(discovery.discover(any(), any())).thenReturn(found);
        when(discovery.revalidate(any(), any(), eq(found))).thenReturn(found);
        var old = primary.originalContent();
        var missing = new DocumentTemplate("retired-proof", "이전 모집의 별도 증명서", null, "발급처 방문", "기관", null, old.evidence());
        var previous = new Content(old.summary(), old.evidence(), List.of(), List.of(), List.of(missing), List.of());
        when(queue.priorGuides(notice)).thenReturn(List.of(new FollowupReviewRepository.PriorGuide(UUID.randomUUID(), 1, previous, null, null, "previous", List.of())));
        assertTrue(service(true).runNext());
        var capture = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(queue).stage(eq(job), eq(primary), anyString(), any(), capture.capture(), argThat(d -> "INTAKE".equals(d.state())));
        var sources = new ObjectMapper().readTree(capture.getValue());
        assertEquals("RECHECK", sources.path("itemCoverage").path("comparisons").get(0).path("state").asString());
        assertTrue(sources.path("confirmedItems").size() >= 2);
        assertFalse(sources.path("confirmedItems").toString().contains("이전 모집의 별도 증명서"));
    }

    @Test
    void missingPreviouslyAppliedEvidenceCannotRevertToOriginalFacts() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        var previous = new OfficialFollowupModels.Discovery(Instant.now(), List.of(correction()), List.of());
        when(queue.latestDependencySources(notice)).thenReturn(Optional.of(new ObjectMapper().writeValueAsString(java.util.Map.of("discovery", previous))));
        when(discovery.discover(any(), any())).thenReturn(found);
        assertFalse(service(true).runNext());
        verify(queue).defer(job, "FOLLOWUP_PREVIOUS_EVIDENCE_MISSING");
        verifyNoInteractions(publication, local, primaryVerifier);
    }

    @Test
    void verifiedCorrectionStillWithdrawsOldGuidanceWhenAnotherCandidateCannotBeRead() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(correction()), List.of("FOLLOWUP_CANDIDATE_UNREAD"));
        when(discovery.discover(any(), any())).thenReturn(found);
        when(discovery.revalidate(any(), any(), eq(found))).thenReturn(found);
        assertTrue(service(true).runNext());
        verify(primaryVerifier).verify(primary.snapshot().draft(), primary.originalContent());
        verify(queue).stage(eq(job), eq(primary), anyString(), any(), contains("invalidations"),
                argThat(value -> value.state().equals("REVIEW_REQUIRED") && value.blockers().contains("FOLLOWUP_CANDIDATE_UNREAD")));
    }

    @Test
    void primaryChangeDuringFinalPassWithdrawsStaleGuidance() throws Exception {
        var found = new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of());
        when(discovery.discover(any(), any())).thenReturn(found);
        when(discovery.revalidate(any(), any(), eq(found))).thenReturn(new OfficialFollowupModels.Discovery(Instant.now(), List.of(), List.of("FOLLOWUP_PRIMARY_CHANGED")));
        assertFalse(service(true).runNext());
        verify(publication).primaryChanged(job);
        verify(publication, never()).publish(any());
    }

    private OfficialFollowupModels.Related correction() {
        String url = "https://apply.lh.or.kr/lhapply/apply/noti/an/view.do?bbsSn=101";
        var text = "변경 전: 신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.\n변경 후: 신청자의 월평균 소득은 4,000,000원 이하이어야 합니다.";
        var source = new Source("정정 공고", url, "f".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("정정 1쪽", text)));
        return new OfficialFollowupModels.Related("CORRECTION", url, "정정 공고", LocalDate.of(2026, 11, 1), "공식 양방향 연결", List.of(source));
    }
}
