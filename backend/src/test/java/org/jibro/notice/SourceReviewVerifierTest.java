package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OfficialDetailParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.jibro.notice.SourceReviewModels.*;
import static org.jibro.notice.HousingModels.*;

class SourceReviewVerifierTest {
    final AutomaticReviewRepository extractions = mock(AutomaticReviewRepository.class);
    final NoticeService notices = mock(NoticeService.class);
    final AttachmentFingerprintClient http = mock(AttachmentFingerprintClient.class);
    final SourceReviewVerifier verifier = new SourceReviewVerifier(extractions, notices, http, new ObjectMapper());
    final UUID id = UUID.randomUUID(), extraction = UUID.randomUUID();
    final String detailUrl = "https://apply.lh.or.kr/detail", fileUrl = "https://apply.lh.or.kr/notice.pdf";
    final String html = "<main><p>공고일 2026.11.04</p><a href='/notice.pdf'>공고문</a></main>";
    SourceReviewModels.Package fixture(String fileHash) throws Exception {
        var parts = OfficialDetailParser.parse(html, detailUrl).parts();
        var e = new Evidence("공식 원문", detailUrl, "2026-11-08");
        var content = new Content("주거 신청 대상 밖의 모집", e, List.of(new Evidence("공고문",fileUrl,"2026-11-08")), List.of(), List.of(), List.of());
        var draft = new Draft(extraction.toString(), id.toString(), 7, "READY", Instant.now(), "", List.of(new Source("공식 상세 본문",detailUrl,"b".repeat(64),"EXTRACTED","",List.of(),parts), new Source("공고문",fileUrl,"a".repeat(64),"EXTRACTED","",List.of(),List.of(new Part("1쪽","기관 모집")))));
        when(extractions.snapshot(extraction)).thenReturn(new AutomaticReviewRepository.ExtractionSnapshot(draft, "c".repeat(64)));
        when(extractions.matchesCurrentEvidence(id,7,extraction,"c".repeat(64))).thenReturn(true);
        when(notices.attachmentSnapshot(id)).thenReturn(new NoticeService.AttachmentSnapshot(7, content));
        when(http.html(detailUrl)).thenReturn(html);
        when(http.fetchForSourceReview(fileUrl)).thenReturn(new AttachmentFingerprintClient.Fingerprint(fileHash,123,"application/pdf"));
        return new SourceReviewModels.Package("source-test",id,7,extraction,"UNSUPPORTED","기업체 대상 공급 확인",List.of(),List.of(new SourceReview(detailUrl,"b".repeat(64),"공식 상세 본문","DETAIL","모집 대상 확인"),new SourceReview(fileUrl,"a".repeat(64),"공고문","RULES","기업체 신청 확인")),content,null,null);
    }
    @Test void freshlyVerifiesEverySource() throws Exception {
        var p = fixture("a".repeat(64));
        assertEquals(2,verifier.verify(p).freshDigests().size());
        verify(http).fetchForSourceReview(fileUrl);
    }
    final String officialDetail = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=2015122300020933&ccrCnntSysDsCd=03&uppAisTpCd=13&aisTpCd=26&mi=1026";
    final String officialFile = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69108015";
    final String officialHtml = "<main><p>신청 및 서류제출 2026.11.02~11.13</p><a href='/lhapply/lhFile.do?fileid=69108015'>공고문</a></main>";

    SourceReviewModels.Package pdfFixture() throws Exception {
        var content = new Content("신청과 서류를 함께 제출", new Evidence("공고문 7쪽", officialFile + "#page=7", "2026-11-10"),
            List.of(new Evidence("공고문", officialFile, "2026-11-10")), List.of(), List.of(), List.of());
        var sources = List.of(
            new Source("공식 상세 본문", officialDetail, "b".repeat(64), "EXTRACTED", "", List.of(), OfficialDetailParser.parse(officialHtml, officialDetail).parts()),
            new Source("공고문", officialFile, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("7쪽", "신청 및 서류제출"))));
        var draft = new Draft(extraction.toString(), id.toString(), 7, "READY", Instant.now(), "", sources);
        when(extractions.snapshot(extraction)).thenReturn(new AutomaticReviewRepository.ExtractionSnapshot(draft, "c".repeat(64)));
        when(extractions.matchesCurrentEvidence(id, 7, extraction, "c".repeat(64))).thenReturn(true);
        when(notices.attachmentSnapshot(id)).thenReturn(new NoticeService.AttachmentSnapshot(7, content));
        var notice = new NoticeEntity();
        notice.sourceKey = LhNoticeClient.sourceKey(officialDetail);
        when(notices.entity(id)).thenReturn(notice);
        when(http.html(officialDetail)).thenReturn(officialHtml);
        when(http.fetchForSourceReview(officialFile)).thenReturn(new AttachmentFingerprintClient.Fingerprint("a".repeat(64), 123, "application/pdf"));
        return new SourceReviewModels.Package("pdf-primary", id, 7, extraction, "UNSUPPORTED", "공식 원문 대조", List.of(),
            List.of(new SourceReview(officialDetail, "b".repeat(64), "공식 상세 본문", "DETAIL", "모집 식별 확인"),
                new SourceReview(officialFile, "a".repeat(64), "공고문", "RULES", "접수기간 확인")), content, null, null);
    }

    void replacePdfDetailSources(List<Source> sources) {
        var draft = extractions.snapshot(extraction).draft();
        var changed = new Draft(draft.id(), draft.noticeId(), draft.revision(), draft.status(), draft.checkedAt(), draft.message(), sources);
        when(extractions.snapshot(extraction)).thenReturn(new AutomaticReviewRepository.ExtractionSnapshot(changed, "c".repeat(64)));
    }

    @Test void pdfPrimaryEvidenceUsesExtractedDetailBoundToCollectedNotice() throws Exception {
        var p = pdfFixture();
        var receipt = verifier.verify(p);
        assertEquals(officialDetail, receipt.detailUrl());
        assertEquals(Set.of(officialDetail, officialFile), receipt.freshDigests().keySet());
        verify(http).html(officialDetail);
        verify(http, never()).html(officialFile + "#page=7");
        verify(http).fetchForSourceReview(officialFile);
    }

    @Test void pdfPrimaryEvidenceCannotUseAnotherNoticesDetailOrAnUnboundNotice() throws Exception {
        var p = pdfFixture();
        for (String sourceKey : Arrays.asList(LhNoticeClient.sourceKey(officialDetail.replace("2015122300020933", "2015122300020999")), null)) {
            notices.entity(id).sourceKey = sourceKey;
            assertEquals("OFFICIAL_DETAIL_SOURCE_REQUIRED", assertThrows(IllegalArgumentException.class, () -> verifier.verify(p)).getMessage());
        }
        verifyNoInteractions(http);
    }

    @Test void sourceKeyAloneCannotPromoteAnArbitraryUrlToOfficialDetail() throws Exception {
        var p = pdfFixture();
        for (String arbitrary : List.of("https://apply.lh.or.kr/arbitrary", officialDetail.replace("apply.lh.or.kr", "example.com"))) {
            notices.entity(id).sourceKey = LhNoticeClient.sourceKey(arbitrary);
            replacePdfDetailSources(List.of(new Source("공식 상세 본문", arbitrary, "b".repeat(64), "EXTRACTED", "", List.of(), List.of())));
            assertEquals("OFFICIAL_DETAIL_SOURCE_REQUIRED", assertThrows(IllegalArgumentException.class, () -> verifier.verify(p)).getMessage());
        }
        verifyNoInteractions(http);
    }

    @Test void pdfPrimaryEvidenceRequiresOneSuccessfullyExtractedDetail() throws Exception {
        var p = pdfFixture();
        var detail = extractions.snapshot(extraction).draft().sources().get(0);
        replacePdfDetailSources(List.of(new Source(detail.label(), detail.url(), detail.sha256(), "FAILED", "읽기 실패", List.of(), detail.context())));
        assertEquals("OFFICIAL_DETAIL_SOURCE_REQUIRED", assertThrows(IllegalArgumentException.class, () -> verifier.verify(p)).getMessage());
        replacePdfDetailSources(List.of(detail, detail));
        assertEquals("OFFICIAL_DETAIL_SOURCE_REQUIRED", assertThrows(IllegalArgumentException.class, () -> verifier.verify(p)).getMessage());
        verifyNoInteractions(http);
    }

    @Test void pdfPrimaryEvidenceDoesNotBypassLiveDetailOrAttachmentVerification() throws Exception {
        var p = pdfFixture();
        when(http.html(officialDetail)).thenReturn(officialHtml.replace("11.13", "11.20"));
        assertEquals("OFFICIAL_DETAIL_CHANGED", assertThrows(IllegalArgumentException.class, () -> verifier.verify(p)).getMessage());
        when(http.html(officialDetail)).thenReturn(officialHtml);
        when(http.fetchForSourceReview(officialFile)).thenReturn(new AttachmentFingerprintClient.Fingerprint("d".repeat(64), 123, "application/pdf"));
        assertEquals("REVIEWED_FILE_CHANGED", assertThrows(IllegalArgumentException.class, () -> verifier.verify(p)).getMessage());
    }

    @Test void pdfPrimaryEvidenceCannotExcuseAnUnreviewedNewAttachment() throws Exception {
        var p = pdfFixture();
        when(http.html(officialDetail)).thenReturn(officialHtml.replace("</main>", "<a href='/additional.pdf'>추가 첨부</a></main>"));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(p));
        verify(http, never()).fetchForSourceReview(officialFile);
    }
    @Test void changedFileCannotReusePreviouslyReadGuidance() throws Exception {
        var p = fixture("d".repeat(64));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(p));
    }
    @Test void changedOrNewOfficialAttachmentBlocksPublication() throws Exception {
        var p = fixture("a".repeat(64));
        when(http.html(detailUrl)).thenReturn(html.replace("</main>","<a href='/additional.pdf'>수정 공고</a></main>"));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(p));
    }
    @Test void changedDetailBlocksPublication() throws Exception {
        var p = fixture("a".repeat(64));
        when(http.html(detailUrl)).thenReturn(html.replace("2026.11.04","2026.11.05"));
        assertThrows(IllegalArgumentException.class,()->verifier.verify(p));
    }
    @Test void wrongHtmlSnapshotHashCannotBeDescribedAsReviewed() throws Exception {
        var p = fixture("a".repeat(64));
        var wrong = new SourceReviewModels.Package(p.packageId(),p.noticeId(),p.revision(),p.extractionId(),p.disposition(),p.reviewNotes(),p.unresolvedFindings(),
            List.of(new SourceReview(detailUrl,"d".repeat(64),"공식 상세 본문","DETAIL","확인"),p.sourceReviews().get(1)),p.content(),p.startDate(),p.endDate());
        assertThrows(IllegalArgumentException.class,()->verifier.verify(wrong));
    }
    @Test void replacingReviewedHashWithLiveHashCannotApproveAnOlderExtractedFile() throws Exception {
        var p = fixture("d".repeat(64));
        var wrong = new SourceReviewModels.Package(p.packageId(),p.noticeId(),p.revision(),p.extractionId(),p.disposition(),p.reviewNotes(),p.unresolvedFindings(),
            List.of(p.sourceReviews().get(0),new SourceReview(fileUrl,"d".repeat(64),"공고문","RULES","확인")),p.content(),p.startDate(),p.endDate());
        var error = assertThrows(IllegalArgumentException.class,()->verifier.verify(wrong));
        assertEquals("REVIEWED_FILE_SNAPSHOT_CHANGED",error.getMessage());
        verify(http,never()).fetchForSourceReview(fileUrl);
    }
    SourceReviewModels.Package partial(String evidenceUrl, List<String> findings, String disposition) throws Exception {
        var p=fixture("a".repeat(64)); var c=p.content();
        var issue=new ReviewIssue("schedule-conflict","SCHEDULE","발표일 확인 필요","공식 본문과 첨부의 발표연도가 다릅니다.",List.of(),List.of(),List.of(new Evidence("발표일 근거",evidenceUrl,"2026-11-08")));
        var content=new Content(c.summary(),c.evidence(),c.attachments(),c.questions(),c.documents(),c.schedules(),List.of(issue));
        return new SourceReviewModels.Package(p.packageId(),p.noticeId(),p.revision(),p.extractionId(),disposition,p.reviewNotes(),findings,p.sourceReviews(),content,p.startDate(),p.endDate());
    }
    @Test void scopedConflictRequiresExactFindingCoverage() throws Exception {
        var p=partial(fileUrl,List.of("other-conflict"),"PARTIAL");
        assertEquals("UNMAPPED_OFFICIAL_CONFLICT",assertThrows(IllegalArgumentException.class,()->verifier.verify(p)).getMessage());
    }
    @Test void scopedConflictCannotHideUnreviewedEvidence() throws Exception {
        var p=partial("https://apply.lh.or.kr/unreviewed.pdf",List.of("schedule-conflict"),"PARTIAL");
        assertEquals("CONTENT_HAS_UNREVIEWED_EVIDENCE",assertThrows(IllegalArgumentException.class,()->verifier.verify(p)).getMessage());
    }
    @Test void partialVerificationChecksAllSourcesAndKeepsIssue() throws Exception {
        var p=partial(fileUrl,List.of("schedule-conflict"),"PARTIAL");
        assertEquals(2,verifier.verify(p).freshDigests().size());
        assertEquals(1,p.content().reviewIssues().size());
    }
    @Test void fullPassCannotCarryUnresolvedIssue() throws Exception {
        var p=partial(fileUrl,List.of(),"PASS");
        assertEquals("PARTIAL_REVIEW_DISPOSITION_REQUIRED",assertThrows(IllegalArgumentException.class,()->verifier.verify(p)).getMessage());
    }
    SourceReviewModels.Package withSupplement(SourceReviewModels.Package p, List<SourceReview> originals,
                                              String evidenceUrl, List<String> selectedFiles) throws Exception {
        var parsed = SupplementalSourceVerifier.parse(SupplementalSourceVerifierTest.PAGE, SupplementalSourceVerifierTest.HTML);
        var reference = new SupplementalReference(SupplementalSourceVerifierTest.PAGE, parsed.title(), parsed.sha256(),
            "이 모집의 후속 공식 발표", selectedFiles);
        var reviews = new ArrayList<>(originals);
        reviews.add(new SourceReview(reference.url(), reference.sha256(), reference.title(), "SUPPLEMENTAL_DETAIL", "모집 식별자 대조"));
        var content = new Content(p.content().summary(), new Evidence("후속 공식 근거", evidenceUrl, "2026-11-09"),
            p.content().attachments(), List.of(), List.of(), List.of());
        when(http.html(reference.url())).thenReturn(SupplementalSourceVerifierTest.HTML);
        return new SourceReviewModels.Package(p.packageId(), p.noticeId(), p.revision(), p.extractionId(), p.disposition(),
            p.reviewNotes(), p.unresolvedFindings(), reviews, content, p.startDate(), p.endDate(), null, List.of(reference), null);
    }
    @Test void supplementalPageEvidenceIsAcceptedWithoutReplacingPrimarySnapshot() throws Exception {
        var p = fixture("a".repeat(64));
        var expanded = withSupplement(p, p.sourceReviews(), SupplementalSourceVerifierTest.PAGE, List.of());
        var receipt = verifier.verify(expanded);
        assertEquals(3, receipt.freshDigests().size());
        assertEquals(detailUrl, receipt.detailUrl());
        verify(http).fetchForSourceReview(fileUrl);
    }
    @Test void supplementalPageNeverExcusesMissingPrimaryFile() throws Exception {
        var p = fixture("a".repeat(64));
        var expanded = withSupplement(p, List.of(p.sourceReviews().get(0)), SupplementalSourceVerifierTest.PAGE, List.of());
        assertEquals("SOURCE_REVIEW_INCOMPLETE", assertThrows(IllegalArgumentException.class, () -> verifier.verify(expanded)).getMessage());
        verify(http, never()).html(SupplementalSourceVerifierTest.PAGE);
    }
    @Test void supplementalEvidenceCannotExcuseChangedPrimaryFileOrCurrentRevision() throws Exception {
        var p = fixture("d".repeat(64));
        var expanded = withSupplement(p, p.sourceReviews(), SupplementalSourceVerifierTest.PAGE, List.of());
        assertEquals("REVIEWED_FILE_CHANGED", assertThrows(IllegalArgumentException.class, () -> verifier.verify(expanded)).getMessage());
        when(extractions.matchesCurrentEvidence(id,7,extraction,"c".repeat(64))).thenReturn(false);
        assertThrows(org.jibro.global.common.ApiException.class, () -> verifier.verify(expanded));
    }
    @Test void unselectedLinkedSupplementalFileCannotAppearAsContentEvidence() throws Exception {
        var p = fixture("a".repeat(64));
        var expanded = withSupplement(p, p.sourceReviews(), SupplementalSourceVerifierTest.FILE, List.of());
        assertEquals("CONTENT_HAS_UNREVIEWED_EVIDENCE", assertThrows(IllegalArgumentException.class, () -> verifier.verify(expanded)).getMessage());
    }
    @Test void openEndedStatementRequiresVerifiedEvidenceToo() throws Exception {
        var p = fixture("a".repeat(64));
        var period = new ConfirmedOpenEndedPeriod("모집호수를 초과하면 마감", new Evidence("미확인 후속 공지", SupplementalSourceVerifierTest.PAGE, "2026-11-09"));
        var wrong = new SourceReviewModels.Package(p.packageId(),p.noticeId(),p.revision(),p.extractionId(),p.disposition(),p.reviewNotes(),
            p.unresolvedFindings(),p.sourceReviews(),p.content(),p.startDate(),p.endDate(),null,List.of(),period);
        assertEquals("CONTENT_HAS_UNREVIEWED_EVIDENCE", assertThrows(IllegalArgumentException.class, () -> verifier.verify(wrong)).getMessage());
    }
    @Test void closureStatementRequiresVerifiedEvidenceToo() throws Exception {
        var p = fixture("a".repeat(64)); var c = p.content();
        var closure = new OfficialApplicationClosure("현재 공식 상세 접수마감", new Evidence("미확인 후속 공지", SupplementalSourceVerifierTest.PAGE, "2026-11-09"));
        var content = new Content(c.summary(),c.evidence(),c.attachments(),c.questions(),c.documents(),c.schedules(),c.reviewIssues(),closure);
        var wrong = new SourceReviewModels.Package(p.packageId(),p.noticeId(),p.revision(),p.extractionId(),p.disposition(),p.reviewNotes(),
            p.unresolvedFindings(),p.sourceReviews(),content,p.startDate(),p.endDate());
        assertEquals("CONTENT_HAS_UNREVIEWED_EVIDENCE", assertThrows(IllegalArgumentException.class, () -> verifier.verify(wrong)).getMessage());
    }
}
