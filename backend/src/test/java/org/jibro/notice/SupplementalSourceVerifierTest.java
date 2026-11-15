package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.junit.jupiter.api.Test;
import static org.jibro.notice.SourceReviewModels.*;

class SupplementalSourceVerifierTest {
    static final String PAGE = "https://apply.lh.or.kr/lhapply/apply/noti/an/view.do?bbsSn=9100221057&ccrCnntSysDsCd=02&slBbsKdCd=03&mi=1079";
    static final String DETAIL = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=0000058683&ccrCnntSysDsCd=02&uppAisTpCd=06&aisTpCd=08&mi=1026";
    static final String FILE = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=24662701";
    static final String LAW = "https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=124711&lsId=008243&chrClsCd=010202&efYd=20120415";
    static final String HTML = """
        <html><header>동적 방문 정보</header><div class='bbs_ViewA'><h3>동일 모집 후속 접수결과</h3>
        <ul class='bbsV_data'><li><strong>게시일</strong>2013-12-24</li><li><strong>조회수</strong>123</li></ul>
        <div class='bbsV_cont'><p>전평형 접수가 마감되었습니다.</p><p>발표일 12월 30일</p></div>
        <div class='bbsV_atchmnfl'><a href='/lhapply/lhFile.do?fileid=24662701'>최종 집계.htm</a></div></div></html>
        """;
    static final String LAW_HTML = """
        <input id='lsNm' value='주택공급에 관한 규칙'><input id='lsId' value='008243'>
        <input id='lsiSeq' value='124711'><input id='efYd' value='20120415'>
        <div id='conTop'><h2>주택공급에 관한 규칙</h2><div class='ct_sub'>[시행 2012.4.15.] [국토해양부령 제456호]</div></div>
        <div id='conScroll'>제31조 입주자 선정
        <p>제1항 제7호 해당 공급 범위</p></div>
        """;
    final AttachmentFingerprintClient lh = mock(AttachmentFingerprintClient.class);
    final AttachmentFingerprintClient law = mock(AttachmentFingerprintClient.class);
    final SupplementalSourceVerifier verifier = new SupplementalSourceVerifier(lh, law);

    static SupplementalReference reference(String url, String html, List<String> files) {
        var page = SupplementalSourceVerifier.parse(url, html);
        return new SupplementalReference(url, page.title(), page.sha256(), "해당 원공고의 후속 발표를 명시한 공식 자료", files);
    }
    static SourceReview source(String url, String sha, String role) {
        return new SourceReview(url, sha, "공식 확인 자료", role, "모집 식별자와 실제 본문을 대조함");
    }
    @Test void counterAndChromeChangesDoNotChangeReviewedContent() {
        var first = SupplementalSourceVerifier.parse(PAGE, HTML);
        var second = SupplementalSourceVerifier.parse(PAGE, HTML.replace("123", "99999").replace("동적 방문 정보", "새 방문 정보"));
        assertEquals(first, second);
        assertEquals(List.of(FILE), first.attachmentUrls());
        assertTrue(first.text().contains("2013-12-24"));
        assertFalse(first.text().contains("조회수"));
    }
    @Test void changedTitleBodyDateOrChildLinkAlwaysChangesFingerprint() {
        var original = SupplementalSourceVerifier.parse(PAGE, HTML).sha256();
        for (String changed : List.of(HTML.replace("동일 모집 후속 접수결과", "다른 모집"),
            HTML.replace("12월 30일", "12월 31일"), HTML.replace("2013-12-24", "2013-12-25"),
            HTML.replace("24662701", "24662702"))) {
            assertNotEquals(original, SupplementalSourceVerifier.parse(PAGE, changed).sha256());
        }
    }
    @Test void supportsExplicitLhDetailWithoutTreatingItAsAutomaticCorrection() {
        var parsed = SupplementalSourceVerifier.parse(DETAIL, "<div class='bbs_ViewA'><h3>정정 모집</h3><p>공급 271호에서 269호</p><a href='/lhapply/lhFile.do?fileid=24662701'>공고문</a></div>");
        assertEquals("정정 모집", parsed.title());
        assertTrue(parsed.text().contains("271호에서 269호"));
        assertEquals(List.of(FILE), parsed.attachmentUrls());
    }
    @Test void historicalLawPinsRequestedVersionAndEffectiveDate() {
        var parsed = SupplementalSourceVerifier.parse(LAW, LAW_HTML);
        assertEquals("주택공급에 관한 규칙", parsed.title());
        assertTrue(parsed.text().contains("20120415"));
        assertTrue(parsed.text().contains("제31조"));
        for (String changed : List.of(LAW_HTML.replace("value='124711'", "value='203510'"),
            LAW_HTML.replace("value='20120415'", "value='20180504'"),
            LAW_HTML.replace("value='008243'", "value='008244'"))) {
            assertEquals("HISTORICAL_LAW_VERSION_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> SupplementalSourceVerifier.parse(LAW, changed)).getMessage());
        }
    }
    @Test void refusesWrapperOrLawBodyWithoutArticles() {
        assertThrows(IllegalArgumentException.class, () -> SupplementalSourceVerifier.parse(LAW.replace("lsInfoR", "lsInfoP"), LAW_HTML));
        assertThrows(IllegalArgumentException.class, () -> SupplementalSourceVerifier.parse(LAW, LAW_HTML.replace("id='conScroll'", "id='shell'")));
        assertThrows(IllegalArgumentException.class, () -> SupplementalSourceVerifier.parse(LAW, LAW_HTML.replace("제31조", "법령 목록")));
    }
    @Test void historicalHiddenEchoCannotReplaceDisplayedTitleOrEffectiveDate() {
        for (String changed : List.of(LAW_HTML.replace("[시행 2012.4.15.]", "[시행 2018.5.4.]"),
            LAW_HTML.replace("<h2>주택공급에 관한 규칙</h2>", "<h2>다른 법령</h2>"),
            LAW_HTML.replace("[시행 2012.4.15.]", "[시행 2012.2.31.]"))) {
            assertEquals("HISTORICAL_LAW_VERSION_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> SupplementalSourceVerifier.parse(LAW, changed)).getMessage());
        }
        assertNotEquals(SupplementalSourceVerifier.parse(LAW, LAW_HTML).sha256(),
            SupplementalSourceVerifier.parse(LAW, LAW_HTML.replace("제456호", "제457호")).sha256());
    }
    @Test void rejectsUntrustedAmbiguousAndUnversionedUrisBeforeAnyTransport() {
        for (String url : List.of(PAGE.replace("https:", "http:"), PAGE.replace("apply.lh.or.kr", "apply.lh.or.kr.evil.test"),
            PAGE.replace("https://", "https://user@"), PAGE + "#body", PAGE + "&bbsSn=1", PAGE + "&redirect=https://example.com",
            PAGE.replace("/noti/an/view.do", "/arbitrary.do"), LAW.replace("&efYd=20120415", ""),
            LAW.replace("20120415", "20120231"), LAW.replace("20120415", "20990415"),
            LAW.replace("www.law.go.kr", "law.go.kr"))) {
            assertThrows(IllegalArgumentException.class, () -> SupplementalSourceVerifier.parse(url, HTML), url);
        }
        verifyNoInteractions(lh, law);
    }
    @Test void sourceReviewMustBindBothPageAndSelectedLinkedFile() throws Exception {
        var ref = reference(PAGE, HTML, List.of(FILE));
        when(lh.html(PAGE)).thenReturn(HTML);
        when(lh.fetchForSourceReview(FILE)).thenReturn(new AttachmentFingerprintClient.Fingerprint("a".repeat(64), 50, "application/pdf"));
        var reviews = Map.of(PAGE, source(PAGE, ref.sha256(), "SUPPLEMENTAL_DETAIL"), FILE, source(FILE, "a".repeat(64), "RULES"));
        assertEquals(Map.of(PAGE, ref.sha256(), FILE, "a".repeat(64)), verifier.verify(List.of(ref), reviews));
        verifyNoInteractions(law);
    }
    @Test void unrelatedOfficialFileCannotBeLaunderedThroughReference() throws Exception {
        String unrelated = FILE.replace("24662701", "99999");
        var ref = reference(PAGE, HTML, List.of(unrelated));
        when(lh.html(PAGE)).thenReturn(HTML);
        assertEquals("SUPPLEMENTAL_ATTACHMENT_NOT_LINKED", assertThrows(IllegalArgumentException.class,
            () -> verifier.verify(List.of(ref), Map.of(PAGE, source(PAGE, ref.sha256(), "DETAIL"), unrelated, source(unrelated, "a".repeat(64), "RULES")))).getMessage());
        verify(lh, never()).fetchForSourceReview(anyString());
    }
    @Test void linkedButUnusedFilesNeedNotBeClaimedAsRead() throws Exception {
        var ref = reference(PAGE, HTML, List.of());
        when(lh.html(PAGE)).thenReturn(HTML);
        assertEquals(Map.of(PAGE, ref.sha256()), verifier.verify(List.of(ref), Map.of(PAGE, source(PAGE, ref.sha256(), "DETAIL"))));
        verify(lh, never()).fetchForSourceReview(anyString());
    }
    @Test void newlyAddedAttachmentInvalidatesEvenBodyOnlyReference() throws Exception {
        var ref = reference(PAGE, HTML, List.of());
        when(lh.html(PAGE)).thenReturn(HTML.replace("</html>", "<a href='/lhapply/lhFile.do?fileid=333'>정정자료</a></html>"));
        assertEquals("SUPPLEMENTAL_PAGE_CHANGED", assertThrows(IllegalArgumentException.class,
            () -> verifier.verify(List.of(ref), Map.of(PAGE, source(PAGE, ref.sha256(), "DETAIL")))).getMessage());
    }
    @Test void changedFileOrReviewHashCannotReuseApproval() throws Exception {
        var ref = reference(PAGE, HTML, List.of(FILE));
        when(lh.html(PAGE)).thenReturn(HTML);
        when(lh.fetchForSourceReview(FILE)).thenReturn(new AttachmentFingerprintClient.Fingerprint("c".repeat(64), 50, "application/pdf"));
        assertEquals("SUPPLEMENTAL_REVIEW_CHANGED", assertThrows(IllegalArgumentException.class,
            () -> verifier.verify(List.of(ref), Map.of(PAGE, source(PAGE, ref.sha256(), "DETAIL"), FILE, source(FILE, "a".repeat(64), "RULES")))).getMessage());
        assertEquals("SUPPLEMENTAL_REVIEW_INCOMPLETE", assertThrows(IllegalArgumentException.class,
            () -> verifier.verify(List.of(ref), Map.of(PAGE, source(PAGE, ref.sha256(), "DETAIL")))).getMessage());
    }
    @Test void lawUsesSeparateHostClientAndDoesNotAdoptAnnexFiles() throws Exception {
        var ref = reference(LAW, LAW_HTML, List.of());
        when(law.html(LAW)).thenReturn(LAW_HTML);
        assertEquals(Map.of(LAW, ref.sha256()), verifier.verify(List.of(ref), Map.of(LAW, source(LAW, ref.sha256(), "HISTORICAL_LAW"))));
        verifyNoInteractions(lh);
        assertThrows(IllegalArgumentException.class, () -> SupplementalSourceVerifier.validate(reference(LAW, LAW_HTML, List.of(FILE))));
    }
    @Test void htmlAttachmentIsExplicitlyBoundToItsRawBytesAndNeverInterpretedAsExecutable() throws Exception {
        var ref = reference(PAGE, HTML, List.of(FILE));
        when(lh.html(PAGE)).thenReturn(HTML);
        byte[] bytes = "<html><body>전평형 접수마감 · 발표 12/30<script>alert('not executed')</script></body></html>".getBytes(StandardCharsets.UTF_8);
        var sha = SourceReviewVerifier.hash(new String(bytes, StandardCharsets.UTF_8));
        when(lh.htmlAttachmentForSourceReview(FILE)).thenReturn(new AttachmentFingerprintClient.Download(new AttachmentFingerprintClient.Fingerprint(sha, bytes.length, "text/html"), bytes));
        assertEquals(sha, verifier.verify(List.of(ref), Map.of(PAGE, source(PAGE, ref.sha256(), "DETAIL"), FILE, source(FILE, sha, "SUPPLEMENTAL_HTML_ATTACHMENT"))).get(FILE));
        verify(lh, never()).fetchForSourceReview(FILE);
    }
}
