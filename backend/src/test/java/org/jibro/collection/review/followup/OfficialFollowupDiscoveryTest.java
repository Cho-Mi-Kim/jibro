package org.jibro.collection.review.followup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.*;
import org.junit.jupiter.api.Test;

import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.jibro.collection.review.followup.OfficialFollowupModels.*;

class OfficialFollowupDiscoveryTest {
    static final String PRIMARY = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=2015122300012096&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10&mi=1026";
    static final String PAGE = "https://apply.lh.or.kr/lhapply/apply/noti/an/view.do?bbsSn=9102829719&ccrCnntSysDsCd=03&slBbsKdCd=03&mi=1079";
    static final String FILE = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=49704964";
    static final UUID ID = UUID.fromString("b9cf7e29-5f27-46ef-b64f-2420e21b59db");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC);
    static final Target TARGET = new Target(ID, 3, PRIMARY, "[정정공고]화성시 병점 복합타운 A-1블럭 행복주택 예비입주자 모집", LocalDate.parse("2022-08-05"));
    static final String TITLE = "[접수마감게시] 화성시 병점복합타운 A-1블럭 행복주택 예비입주자 모집 관련(2022.08.05)";
    static final String MAIN = "<div class='bbs_ViewA'><h3>원 모집공고</h3><p>공고일 2022.08.05</p><p>신청 접수 안내</p></div>";
    final AttachmentFingerprintClient http = mock(AttachmentFingerprintClient.class);
    final NoticeExtractionService extractor = mock(NoticeExtractionService.class);
    final OfficialFollowupDiscovery discovery = new OfficialFollowupDiscovery(http, extractor, CLOCK, org.mockito.Mockito.mock(AgencyIntakeClient.class));

    Draft primary(String html) {
        return new Draft(UUID.randomUUID().toString(), ID.toString(), 3, "READY", CLOCK.instant(), "",
                List.of(new Source("공식 상세 본문", PRIMARY, "a".repeat(64), "EXTRACTED", "", List.of(), OfficialDetailParser.parse(html, PRIMARY).parts())));
    }

    static String search(int total, int page, int pages, String title, String id) {
        return "<p class='bbs_total'>전체 " + total + " 건 " + page + " / " + pages + "페이지</p><div class='bbs_ListA'><table><tbody>"
                + (title == null ? "" : "<tr><td><a class='slpaInfoBtn' data-id1='" + id + "' data-id2='03'>" + title
                + "</a></td><td>2022.08.17</td></tr>") + "</tbody></table></div>";
    }

    static String page(String title, String body, String files) {
        return "<html><div class='bbs_ViewA'><h3>" + title + "</h3><ul class='bbsV_data'><li><strong>게시일</strong>2022-08-17</li>"
                + "<li><strong>조회수</strong>123</li></ul><div class='bbsV_cont'><p>" + body + "</p></div>"
                + "<div class='bbsV_atchmnfl'>" + files + "</div></div></html>";
    }

    void setup(String body, String files) throws Exception {
        when(http.html(PRIMARY)).thenReturn(MAIN);
        when(http.html(OfficialFollowupDiscovery.searchUrl("병점", TARGET.publishedDate(), LocalDate.now(CLOCK), 1)))
                .thenReturn(search(1, 1, 1, TITLE, "9102829719"));
        when(http.html(PAGE)).thenReturn(page(TITLE, body, files));
    }

    @Test
    void completeEmptySearchIsDifferentFromAnUnreadSearch() throws Exception {
        setup("", "");
        String query = OfficialFollowupDiscovery.searchUrl("병점", TARGET.publishedDate(), LocalDate.now(CLOCK), 1);
        when(http.html(query)).thenReturn(search(0, 0, 0, null, null));
        assertTrue(discovery.discover(TARGET, primary(MAIN)).complete());
        assertTrue(discovery.discover(TARGET, primary(MAIN)).related().isEmpty());
        when(http.html(query)).thenThrow(new java.io.IOException("offline"));
        assertTrue(discovery.discover(TARGET, primary(MAIN)).blockers().contains("FOLLOWUP_SEARCH_UNREAD"));
        verifyNoInteractions(extractor);
    }

    @Test
    void exactOriginalDateAndScopeLinkTheActualFollowupAndReadAllFiles() throws Exception {
        setup("2022.8.5. 화성시 병점복합타운 A-1블럭 행복주택 예비입주자 모집공고에 따른 서류제출자 발표 : 2022.8.23 17:00 이후",
                "<a href='" + FILE + "'>최종신청현황.xlsx</a>");
        when(extractor.extract(new Link("최종신청현황.xlsx", FILE))).thenReturn(new Source("최종신청현황.xlsx", FILE, "b".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("시트1", "단지 신청자 합계 100"))));
        var result = discovery.discover(TARGET, primary(MAIN));
        assertTrue(result.complete(), result.blockers().toString());
        assertEquals(1, result.related().size());
        var related = result.related().get(0);
        assertEquals("FOLLOWUP", related.kind());
        assertEquals(LocalDate.parse("2022-08-17"), related.publishedDate());
        assertTrue(related.relationshipProof().contains("2022-08-05"));
        assertEquals(2, related.sources().size());
        assertEquals("b".repeat(64), related.sources().get(1).sha256());
    }

    @Test
    void genericTitleStillGetsItsExactPrimaryLinkExamined() throws Exception {
        setup("", "");
        String title = "추가 일정 안내";
        String query = OfficialFollowupDiscovery.searchUrl("병점", TARGET.publishedDate(), LocalDate.now(CLOCK), 1);
        when(http.html(query)).thenReturn(search(1, 1, 1, title, "9102829719"));
        when(http.html(PAGE)).thenReturn(page(title, "<a href='" + PRIMARY + "'>해당 모집공고</a> 발표 변경", ""));
        var result = discovery.discover(TARGET, primary(MAIN));
        assertTrue(result.complete());
        assertEquals(1, result.related().size());
        assertTrue(result.related().get(0).relationshipProof().contains("공고 ID"));
    }

    @Test
    void newFileAndChangedFactInvalidateRevalidationButVisitCountDoesNot() throws Exception {
        setup("서류제출자 발표 2022.8.23 17:00 이후", "");
        var original = discovery.discover(TARGET, primary(MAIN));
        assertTrue(original.complete());
        when(http.html(PAGE)).thenReturn(page(TITLE, "서류제출자 발표 2022.8.23 17:00 이후", "").replace(">123<", ">999<"));
        assertTrue(discovery.revalidate(TARGET, primary(MAIN), original).complete());
        when(http.html(PAGE)).thenReturn(page(TITLE, "서류제출자 발표 2022.8.24 17:00 이후", ""));
        assertTrue(discovery.revalidate(TARGET, primary(MAIN), original).blockers().contains("FOLLOWUP_EVIDENCE_CHANGED"));
    }

    @Test
    void matchingTitleCannotSubstituteForAnotherRecruitmentsExplicitLink() throws Exception {
        setup("2022.08.05 화성시 병점복합타운 A-1블럭 행복주택 예비입주자 모집공고에 따른 발표 <a href='"
                + PRIMARY.replace("2015122300012096", "2015122300099999") + "'>모집공고</a>", "");
        var result = discovery.discover(TARGET, primary(MAIN));
        assertTrue(result.related().isEmpty());
        assertTrue(result.blockers().contains("FOLLOWUP_RELATION_UNVERIFIED"));
    }

    @Test
    void changedPrimaryNeverStartsSupplementalSearch() throws Exception {
        setup("서류제출자 발표", "");
        when(http.html(PRIMARY)).thenReturn(MAIN.replace("2022.08.05", "2022.08.06"));
        assertEquals(List.of("FOLLOWUP_PRIMARY_CHANGED"), discovery.discover(TARGET, primary(MAIN)).blockers());
        verify(http, times(1)).html(anyString());
    }

    @Test
    void attachmentFailureAndOcrAreTechnicalBlockersRatherThanConfirmedConflicts() throws Exception {
        setup("서류제출자 발표", "<a href='" + FILE + "'>안내.pdf</a>");
        when(extractor.extract(any())).thenReturn(new Source("안내.pdf", FILE, null, "FAILED", "", List.of()));
        assertTrue(discovery.discover(TARGET, primary(MAIN)).blockers().contains("FOLLOWUP_ATTACHMENT_UNREAD"));
        when(extractor.extract(any())).thenReturn(new Source("안내.pdf", FILE, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part(OcrProvenance.MARKER + "1쪽", "읽힌 숫자 100"))));
        var result = discovery.discover(TARGET, primary(MAIN));
        assertTrue(result.blockers().contains("FOLLOWUP_OCR_REQUIRES_SOURCE_REVIEW"));
        assertEquals(2, result.related().get(0).sources().size());
    }

    @Test
    void readsExplicitHtmlAttachmentWithoutBroadeningTheBinaryReader() throws Exception {
        setup("서류제출자 발표", "<a href='" + FILE + "'>최종접수.htm</a>");
        byte[] bytes = "<html><body><table><tr><td>신청마감</td><td>8월17일</td></tr></table></body></html>".getBytes(StandardCharsets.UTF_8);
        when(http.htmlAttachmentForSourceReview(FILE)).thenReturn(new AttachmentFingerprintClient.Download(new AttachmentFingerprintClient.Fingerprint("c".repeat(64), bytes.length, "text/html"), bytes));
        var result = discovery.discover(TARGET, primary(MAIN));
        assertTrue(result.complete(), result.blockers().toString());
        assertTrue(result.related().get(0).sources().get(1).context().get(0).text().contains("신청마감 : 8월17일"));
        verifyNoInteractions(extractor);
    }

    @Test
    void boundedAttachmentCountDoesNotPretendTheRestWasRead() throws Exception {
        String files = "";
        for (int i = 1; i <= 9; i++)
            files += "<a href='https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + i + "'>문서" + i + ".pdf</a>";
        setup("서류제출자 발표", files);
        when(extractor.extract(any())).thenAnswer(call -> {
            Link link = call.getArgument(0);
            return new Source(link.label(), link.url(), "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", "본문")));
        });
        var result = discovery.discover(TARGET, primary(MAIN));
        assertTrue(result.blockers().contains("FOLLOWUP_ATTACHMENT_LIMIT"));
        assertEquals(10, result.related().get(0).sources().size());
        assertEquals("DEFERRED", result.related().get(0).sources().get(9).status());
        verify(extractor, times(8)).extract(any());
    }

    @Test
    void incompletePaginationAndListDetailMismatchAreNotNegativeSearchResults() throws Exception {
        setup("서류제출자 발표", "");
        String query = OfficialFollowupDiscovery.searchUrl("병점", TARGET.publishedDate(), LocalDate.now(CLOCK), 1);
        String second = OfficialFollowupDiscovery.searchUrl("병점", TARGET.publishedDate(), LocalDate.now(CLOCK), 2);
        when(http.html(query)).thenReturn(search(201, 1, 3, TITLE, "9102829719"));
        when(http.html(second)).thenReturn(search(201, 2, 3, null, null));
        assertFalse(discovery.discover(TARGET, primary(MAIN)).complete());
        when(http.html(query)).thenReturn(search(1, 1, 1, TITLE, "9102829719"));
        when(http.html(PAGE)).thenReturn(page(TITLE, "서류제출자 발표", "").replace("2022-08-17", "2022-08-18"));
        assertTrue(discovery.discover(TARGET, primary(MAIN)).blockers().contains("FOLLOWUP_LIST_DETAIL_CHANGED"));
    }

    @Test
    void fingerprintExcludesOnlyCheckTime() throws Exception {
        setup("서류제출자 발표", "");
        var result = discovery.discover(TARGET, primary(MAIN));
        assertEquals(OfficialFollowupDiscovery.fingerprint(result), OfficialFollowupDiscovery.fingerprint(new Discovery(CLOCK.instant().plusSeconds(60), result.related(), result.blockers())));
        assertNotEquals(OfficialFollowupDiscovery.fingerprint(result), OfficialFollowupDiscovery.fingerprint(new Discovery(result.checkedAt(), result.related(), List.of("FOLLOWUP_SEARCH_UNREAD"))));
    }

    @Test
    void oneWayOrUnrecognizedCorrectionIsNotAnAcceptedRelationship() throws Exception {
        setup("서류제출자 발표", "");
        String old = MAIN + "<script>\nvar currPanId='2015122300012097';\n</script>";
        String corrected = PRIMARY.replace("2015122300012096", "2015122300012097");
        when(http.html(PRIMARY)).thenReturn(old);
        when(http.html(corrected)).thenReturn(MAIN + "<script>var sOtxtPanId='';</script>");
        var result = discovery.discover(TARGET, primary(old));
        assertTrue(result.blockers().contains("FOLLOWUP_CORRECTION_UNVERIFIED"));
        assertTrue(result.related().stream().noneMatch(value -> value.kind().equals("CORRECTION")));
    }

    @Test
    void multipleExplicitPrimaryLinksCannotAdoptTheWholeSharedNotice() throws Exception {
        setup("<a href='" + PRIMARY + "'>모집1</a><a href='" + PRIMARY.replace("2015122300012096", "2015122300012097") + "'>모집2</a>", "");
        var result = discovery.discover(TARGET, primary(MAIN));
        assertTrue(result.blockers().contains("FOLLOWUP_RELATION_UNVERIFIED"));
        assertTrue(result.related().isEmpty());
    }

    @Test
    void explicitlyReferencedOriginalDateIsReadBeforeNewerCandidatesWithoutHidingTheLimit() throws Exception {
        setup("서류제출자 발표 2022.8.23", "");
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            String id = "91030000" + String.format("%02d", i);
            String title = "다른 모집 안내 " + i;
            rows.append("<tr><td><a class='slpaInfoBtn' data-id1='").append(id)
                    .append("' data-id2='03'>").append(title).append("</a></td><td>2026.01.01</td></tr>");
            String url = PAGE.replace("9102829719", id);
            when(http.html(url)).thenReturn(page(title, "다른 단지의 별도 모집입니다.", "")
                    .replace("2022-08-17", "2026-01-01"));
        }
        rows.append("<tr><td><a class='slpaInfoBtn' data-id1='9102829719' data-id2='03'>")
                .append(TITLE).append("</a></td><td>2022.08.17</td></tr>");
        String query = OfficialFollowupDiscovery.searchUrl("병점", TARGET.publishedDate(), LocalDate.now(CLOCK), 1);
        when(http.html(query)).thenReturn("<p class='bbs_total'>전체 13 건 1 / 1페이지</p>"
                + "<div class='bbs_ListA'><table><tbody>" + rows + "</tbody></table></div>");
        var result = discovery.discover(TARGET, primary(MAIN));
        assertEquals(1, result.related().size());
        assertEquals(PAGE, result.related().get(0).url());
        assertTrue(result.blockers().contains("FOLLOWUP_CANDIDATE_LIMIT"));
        verify(http, never()).html(PAGE.replace("9102829719", "9103000012"));
        var order = inOrder(http);
        order.verify(http).html(PRIMARY);
        order.verify(http).html(query);
        order.verify(http).html(PAGE);
    }
}
