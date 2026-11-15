package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.junit.jupiter.api.Test;

class ReviewedLegacyCorrectionTest {
    static final String OLD = LegacyCorrectionHistoryTest.OLD, NEXT = LegacyCorrectionHistoryTest.NEXT;

    static String url(String id) {
        return LegacyCorrectionHistoryTest.url(id);
    }

    static String page(boolean corrected) {
        String html = corrected ? LegacyCorrectionHistoryTest.latest() : LegacyCorrectionHistoryTest.original();
        html = html.replace(corrected ? "정정 제목" : "원공고 제목", (corrected ? "[정정공고] " : "") + "동일 회차 추가 입주자 모집");
        return html + "<div class='bbs_ViewA'><ul class='bbsV_data'><li><strong>공고일</strong>2012.11.26</li></ul>"
            + "<a href=\"javascript:fileDownLoad('" + (corrected ? "103" : "101") + "');\">모집 공고문.pdf</a>"
            + "<a href=\"javascript:fileDownLoad('" + (corrected ? "104" : "102") + "');\">모집 공고문.hwp</a></div>";
    }

    static ReviewedLegacyCorrection.Review review() {
        return new ReviewedLegacyCorrection.Review(url(OLD), url(NEXT), "동일 회차 추가 입주자 모집", "2012-11-26",
            "수정정보 : 주택관리번호 변경\n수정된 번호 안내", "두 모집 공고문 전체 대조: 동일 회차 및 정정 후 내용을 확인함.",
            List.of(new ReviewedLegacyCorrection.Attachment("101", "103", "a".repeat(64), "application/pdf"),
                new ReviewedLegacyCorrection.Attachment("102", "104", "b".repeat(64), "application/x-ole-storage")));
    }

    static void mockFiles(AttachmentFingerprintClient http) throws Exception {
        for (var a : review().attachments())
            for (String id : List.of(a.originalFileId(), a.correctedFileId()))
                when(http.fetch("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + id))
                    .thenReturn(new AttachmentFingerprintClient.Fingerprint(a.sha256(), 500000, a.mediaType()));
    }

    static OfficialCorrectionLink.Proof verify(String old, String latest, AttachmentFingerprintClient http) throws Exception {
        return ReviewedLegacyCorrection.verify(review(), url(OLD), old, url(NEXT), latest, http);
    }

    @Test
    void explicitReviewBindsBothCompleteDocumentsAndMetadata() throws Exception {
        var http = mock(AttachmentFingerprintClient.class);
        mockFiles(http);
        var proof = verify(page(false), page(true), http);
        assertEquals(OLD, proof.originalPanId());
        assertEquals(NEXT, proof.correctedPanId());
        assertNull(proof.historyHtmlSha256());
        org.mockito.Mockito.verify(http, times(4)).fetch(anyString());
    }

    @Test
    void changedBytesWrongMediaOrSmallGenericAttachmentRejectThePair() throws Exception {
        for (var bad : List.of(new AttachmentFingerprintClient.Fingerprint("c".repeat(64), 500000, "application/pdf"),
            new AttachmentFingerprintClient.Fingerprint("a".repeat(64), 500000, "text/html"),
            new AttachmentFingerprintClient.Fingerprint("a".repeat(64), 500, "application/pdf"))) {
            var http = mock(AttachmentFingerprintClient.class);
            mockFiles(http);
            when(http.fetch("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=103")).thenReturn(bad);
            assertThrows(IllegalArgumentException.class, () -> verify(page(false), page(true), http));
        }
    }

    @Test
    void separateRecruitmentMissingOfficialAttachmentOrChangedReasonRejectBeforeFetch() {
        for (String bad : List.of(page(true).replace("2012.11.26", "2013.01.15"),
            page(true).replace("[정정공고] ", ""), page(true).replace("주택관리번호 변경", "공급 세대수 변경"),
            page(true).replace("fileDownLoad('103')", "fileDownLoad('999')"),
            page(true).replace("모집 공고문.pdf", "공통 개인정보 동의서.pdf"))) {
            var http = mock(AttachmentFingerprintClient.class);
            assertThrows(IllegalArgumentException.class, () -> verify(page(false), bad, http));
        }
    }

    @Test
    void wrongOriginalIdentityAndUnreviewedInputAreRejected() {
        var http = mock(AttachmentFingerprintClient.class);
        assertThrows(IllegalArgumentException.class, () -> verify(page(false).replace("currPanId = '" + OLD, "currPanId = '999"), page(true), http));
        assertThrows(IllegalArgumentException.class, () -> ReviewedLegacyCorrection.verify(null, url(OLD), page(false), url(NEXT), page(true), http));
        verifyNoInteractions(http);
    }
}
