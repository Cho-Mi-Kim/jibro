package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.jibro.collection.attachments.AttachmentFingerprintClient.Page;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GuideSourceClientTest {
    private static final String URL = "https://apply.lh.or.kr/lhapply/cm/cntnts/cntntsView.do?cntntsId=1021&mi=1142";
    private static final String RULE = "무주택 세대구성원은 도시근로자 월평균 소득 70% 이하이면 신청할 수 있어요. 1인 가구는 90%, 2인 가구는 80% 이하를 적용해요.";
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC);

    private GuideSourceClient client(GuideSourceClient.Fetcher fetcher) {
        return new GuideSourceClient(fetcher, bytes -> List.of(new Part("공고문 1쪽", RULE)), json, clock);
    }

    private Page html(String body) {
        return new Page(200, "text/html;charset=UTF-8", "", body.getBytes(StandardCharsets.UTF_8));
    }

    private Page ordinary(String before, String rule) {
        return html("<title>국민임대</title><nav>" + before + "</nav><div id=cntntsView><p>" + rule + "</p><p>조회수 " + before + "</p></div><footer>" + before + "</footer>");
    }

    @Test
    void normalizesSourceAndIgnoresMenusVisitCountersAndRequestTokens() {
        var first = client(url -> ordinary("12", RULE)).fetch(URL);
        var second = client(url -> ordinary("25", RULE)).fetch(URL);
        assertEquals(GuideSourceClient.Status.AVAILABLE, first.status());
        assertEquals(first.snapshot().contentHash(), second.snapshot().contentHash());
        assertEquals(clock.instant(), first.snapshot().checkedAt());
        assertFalse(first.snapshot().text().contains("조회수"));
        assertTrue(first.snapshot().text().contains("90%"));
    }

    @Test
    void preservesActualEffectiveDatesAmountsExceptionsAndContactChanges() {
        String rule = RULE + " 2026년 11월 21일부터 적용해요. 문의 1600-1004.";
        var before = client(url -> ordinary("12", rule)).fetch(URL);
        for (String changed : List.of(rule.replace("70%", "80%"), rule.replace("11월 21일", "11월 22일"), rule.replace("1600-1004", "1600-0777"))) {
            assertNotEquals(before.snapshot().contentHash(), client(url -> ordinary("12", changed)).fetch(URL).snapshot().contentHash());
        }
    }

    @Test
    void allowsBoundedSameInstitutionMovesButReportsFinalUrlForContentReview() {
        String target = "https://www.lh.or.kr/revised-guide";
        var result = client(url -> url.equals(URL) ? new Page(302, "", target, new byte[0])
            : html("<title>주택 안내</title><div id=contents_body>" + RULE + "</div>")).fetch(URL);
        assertEquals(GuideSourceClient.Status.AVAILABLE, result.status());
        assertEquals(target, result.snapshot().finalUrl());
        assertEquals(List.of(target), result.snapshot().redirects());
    }

    @Test
    void refusesExternalCrossInstitutionAndOtherNoticeRedirectsBeforeRequestingThem() {
        for (String target : List.of("https://evil.example/", "https://www.gov.kr/", URL.replace("1021", "1023"), "http://apply.lh.or.kr/", "https://user@apply.lh.or.kr/")) {
            var calls = new AtomicInteger();
            var result = client(url -> {
                calls.incrementAndGet();
                return new Page(302, "", target, new byte[0]);
            }).fetch(URL);
            assertEquals(GuideSourceClient.Status.UNREADABLE, result.status());
            assertEquals(1, calls.get());
            assertNull(result.snapshot());
        }
    }

    @Test
    void blocksLoopsAndExcessiveRedirects() {
        assertEquals("GUIDE_SOURCE_REDIRECT_LOOP", client(url -> new Page(302, "", URL, new byte[0])).fetch(URL).code());
        var result = client(url -> new Page(302, "", "https://apply.lh.or.kr/move/" + UUID.randomUUID(), new byte[0])).fetch(URL);
        assertEquals("GUIDE_SOURCE_REDIRECT_LIMIT", result.code());
    }

    @Test
    void refusesOversizedAndNonDocumentBodiesWithoutParsing() {
        var result = client(url -> new Page(200, "text/html", "", new byte[8 * 1024 * 1024 + 1])).fetch(URL);
        assertEquals("GUIDE_SOURCE_SIZE_REJECTED", result.code());
        assertEquals("GUIDE_SOURCE_MEDIA_REJECTED", client(url -> new Page(200, "application/json", "", "{}".getBytes(StandardCharsets.UTF_8))).fetch(URL).code());
    }

    @Test
    void distinguishesNetworkFailureAndSuccessfulErrorPageWithoutLeakingMessages() {
        var result = client(url -> {
            throw new Exception("secret original response body and token");
        }).fetch(URL);
        assertEquals(GuideSourceClient.Status.UNREACHABLE, result.status());
        assertEquals("GUIDE_SOURCE_FETCH_FAILED", result.code());
        assertNull(result.snapshot());
        for (String error : List.of("서비스 접속 대기", "접근이 차단되었습니다", "서비스 점검 중")) {
            result = client(url -> html("<title>" + error + "</title><div id=cntntsView>" + error.repeat(10) + "</div>")).fetch(URL);
            assertEquals(GuideSourceClient.Status.UNREADABLE, result.status());
        }
    }

    @Test
    void hiddenQueueScriptsDoNotReplaceActualGovernmentServiceContent() {
        String url = "https://www.gov.kr/mw/AA020InfoCappView.do?CappBizCD=13100000015";
        var result = client(link -> html("<title>주민등록등본 발급</title><script>접속량이 많아 서비스 접속 대기</script><div class=contentsWrap><div class=wrap_col><div class=left>" + RULE + "</div></div></div>")).fetch(url);
        assertEquals(GuideSourceClient.Status.AVAILABLE, result.status());
        assertFalse(result.snapshot().text().contains("접속 대기"));
    }

    @Test
    void readsCollapsedOfficialFaqAnswersAndScopedCourtSupportContacts() {
        var faq = client(url -> html("<div id=content><dl class=faq_list><dt>무주택 기준</dt><dd style='display:none'>" + RULE + "</dd></dl></div>")).fetch("https://www.kohom.or.kr/web/mainComm/HM002003.do");
        assertTrue(faq.snapshot().text().contains("1인 가구"));
        var court = client(url -> html("<div id=content><section class='serviceWrap iss'>가족관계증명서는 부모, 배우자, 자녀에 관한 사항을 확인하는 증명서예요.</section></div><footer><div class=csWrap>사용자지원센터 1899-2732 월~금 09:00~18:00</div><p>무관한 새 공지 2026-11-22</p></footer>")).fetch("https://efamily.scourt.go.kr/index.jsp");
        assertTrue(court.snapshot().text().contains("1899-2732"));
        assertFalse(court.snapshot().text().contains("무관한"));
    }

    @Test
    void readsWelfareInitJsonWithoutExecutingScriptsOrRetainingRequestMetadata() throws Exception {
        String source = welfare("WLF00003201", "2026-11-21T01:00:00");
        String url = "https://www.bokjiro.go.kr/ssis-tbu/twataa/wlfareInfo/moveTWAT52011M.do?wlfareInfoId=WLF00003201";
        var result = client(link -> html(source)).fetch(url);
        assertEquals(GuideSourceClient.Status.AVAILABLE, result.status());
        assertTrue(result.snapshot().text().contains("주거비"));
        assertFalse(result.snapshot().text().contains("01:00:00"));
        assertFalse(result.snapshot().text().contains("doEvil"));
        assertEquals(result.snapshot().contentHash(), client(link -> html(source.replace("01:00:00", "02:00:00"))).fetch(url).snapshot().contentHash());
        assertEquals(GuideSourceClient.Status.UNREADABLE, client(link -> html(source.replace("WLF00003201", "OTHER_SERVICE"))).fetch(url).status());
    }

    private String welfare(String id, String timestamp) throws Exception {
        var service = Map.of("wlfareInfoId", id, "wlfareInfoNm", "주거급여", "wlfareSprtTrgtCn", "<p>" + RULE + "</p>",
            "wlfareSprtBnftCn", "<p>임차 가구에 주거비를 지원해요.</p>", "frstCrtPtm", timestamp, "rprsCtadr", "1600-0777");
        return "<script>doEvil();cpr.core.Platform.INSTANCE.initParameter(" + json.writeValueAsString(Map.of("initValue", Map.of("dmWlfareInfo", json.writeValueAsString(service)))) + ");</script>";
    }

    @Test
    void directPdfAndOfficialNoticeAttachmentChangesAreChecked() {
        String url = "https://www.lh.or.kr/boardDownload.es?list_no=123&seq=3";
        assertEquals(GuideSourceClient.Status.AVAILABLE, client(link -> new Page(200, "application/octet-stream", "", "%PDF-1.7".getBytes(StandardCharsets.UTF_8))).fetch(url).status());
        String notice = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=123";
        var count = new AtomicInteger();
        var result = client(link -> {
            count.incrementAndGet();
            if (link.contains("lhFile.do?fileid=1234"))
                return new Page(200, "application/pdf", "", "%PDF-1.7".getBytes(StandardCharsets.UTF_8));
            return html("<div id=sub_container><section><div class=bbs_ViewA><h3>국민임대 공고</h3>" + RULE + "<div class=bbsV_atchmnfl><a href=\"javascript:fileDownLoad('1234');\">모집공고문.pdf</a></div></div></section></div>");
        }).fetch(notice);
        assertEquals(GuideSourceClient.Status.AVAILABLE, result.status());
        assertEquals(2, count.get());
        assertTrue(result.snapshot().text().contains("공식 첨부"));
        assertTrue(result.snapshot().text().contains("공고문 1쪽"));
    }

    @Test
    void unreadablePdfOrNoticeWithoutItsDocumentDoesNotCertifyPartialContent() {
        var reader = new GuideSourceClient(url -> new Page(200, "application/pdf", "", "%PDF-1.7".getBytes(StandardCharsets.UTF_8)), bytes -> List.of(new Part("1쪽", "")), json, clock);
        assertEquals(GuideSourceClient.Status.UNREADABLE, reader.fetch(URL).status());
        var result = client(url -> html("<div id=sub_container><section>" + RULE + "</section></div>")).fetch("https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=123");
        assertEquals(GuideSourceClient.Status.UNREADABLE, result.status());
    }

    @Test
    void commonPolicyRejectsMisleadingHostsCredentialsPortsAndResourceChanges() {
        for (String url : List.of("http://www.gov.kr/", "https://www.gov.kr.evil.test/", "https://www.gov.kr:8443/", "https://user@www.gov.kr/", "https://127.0.0.1/", "https://www.gov.kr/\n", "https://www.gov.kr/?serviceKey=secret", "https://www.gov.kr/?access_token=secret"))
            assertFalse(GuideSourcePolicy.isOfficialUrl(url));
        assertTrue(GuideSourcePolicy.isOfficialUrl(URL));
        assertFalse(GuideSourcePolicy.preservesResourceIdentity(URI.create(URL), URI.create(URL.replace("1021", "1023"))));
    }
}
