package org.jibro.collection.review.followup;

import java.io.IOException;
import java.time.LocalDate;
import org.jibro.notice.OfficialCorrectionLink;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MyHomeIntakeClientTest {
    private static final String LH = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=2015122300020730&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07";

    @Test
    void readsOnlyMethodRowsAndVerifiesTheSupplierLink() throws IOException {
        var document = Jsoup.parse("<button onclick=\"window.open('" + LH + "')\">공급기관 원문</button><table>"
            + "<tr><th>접수 일정</th><td><div class='rank'><p class='text-gray'>2026년 12월 31일</p></div></td></tr>"
            + "<tr><th>현장접수 일정</th><td><div class='rank'><p class='em'>1순위</p>"
            + "<p class='text-gray'>2026년 09월 29일 ~ 2026년 12월 30일</p></div></td></tr></table>");
        var windows = MyHomeIntakeClient.windows(document, OfficialCorrectionLink.identity(LH), "https://www.myhome.go.kr/");
        assertEquals(1, windows.size());
        assertEquals("현장", windows.get(0).channel());
        assertEquals("1순위", windows.get(0).qualifier());
        assertEquals(LocalDate.parse("2026-12-30"), windows.get(0).end());
        assertThrows(IOException.class, () -> MyHomeIntakeClient.windows(document, OfficialCorrectionLink.identity(LH.replace("0730", "0731")), ""));
    }
    @Test
    void selectsTheOfficialDownloadOnlyForTheSameRecruitmentAndDate() throws IOException {
        var target = new OfficialFollowupModels.Target(java.util.UUID.randomUUID(), 2, LH, "공주 국민임대", LocalDate.of(2026, 11, 2));
        var url = "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=123";
        var html = "<p class='notice-title'>공주 국민임대</p><ul class='notice-action-list'><li>"
            + "<button onclick=\"window.open('" + LH + "')\">해당기관 공고보기</button></li></ul>"
            + "<div class='cpnt-common-detail'><table><tr><th>모집공고일</th><td>2026년 11월 02일</td></tr>"
            + "<tr><th>공고문</th><td><a href=\"javascript:fnDownFile('abc123', '1')\">공고.pdf</a></td></tr></table></div>";
        var post = MyHomeIntakeClient.preparationPost(Jsoup.parse(html, url), target);
        assertEquals("https://www.myhome.go.kr/hws/com/fms/cvplFileDownload.do?atchFileId=abc123&fileSn=1",
            AgencyIntakeAttachments.links(post).get(0).url());
        assertNull(MyHomeIntakeClient.preparationPost(Jsoup.parse(html.replace("11월 02일", "11월 03일"), url), target));
        assertNull(MyHomeIntakeClient.preparationPost(Jsoup.parse(html.replace("20730", "20999"), url), target));
    }
}
