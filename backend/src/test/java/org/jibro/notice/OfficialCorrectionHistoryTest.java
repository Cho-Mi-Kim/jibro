package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OfficialCorrectionHistoryTest {
    static final String OLD = "0000061201", NEXT = "0000061206";
    static String url(String id) {
        return "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=" + id
            + "&ccrCnntSysDsCd=02&uppAisTpCd=06&aisTpCd=08";
    }
    static String page(boolean corrected) {
        return OfficialCorrectionTemplateTest.page(corrected ? NEXT : OLD, NEXT, "", corrected ? "02" : "01")
            .replace("ccrCnntSysDsCd = '03'", "ccrCnntSysDsCd = '02'")
            + "<div class='bbs_ViewA'><h3>" + (corrected ? "정정 제목" : "원공고 제목") + "</h3></div>"
            + "<div class='bbsV_cont'><dl><dt>내용</dt><dd><pre>첨부파일명 정정\n상세 안내</pre></dd></dl></div>";
    }
    static String history() {
        return """
            <table><caption>공고이력조회</caption><tbody>
            <tr><th>정정공고</th><td>2026-11-06</td><td>정정 제목</td><td></td></tr>
            <tr><th>일반공고</th><td>2026-11-05</td><td>원공고 제목</td><td>
            <a href="javascript:goUrl('0000061201','02')">바로가기</a></td></tr>
            </tbody></table>
            """;
    }
    @Test void usesOfficialHistoryReverseIdOnlyWithMatchingForwardIdentityAndTitles() {
        var proof = OfficialCorrectionLink.verifyHistory(url(OLD), page(false), url(NEXT), page(true), history());
        assertEquals(OLD, proof.originalPanId()); assertEquals(NEXT, proof.correctedPanId());
        assertNotNull(proof.historyHtmlSha256()); assertEquals("첨부파일명 정정", proof.reason());
        assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verify(url(OLD), page(false), url(NEXT), page(true)));
    }
    @Test void missingMismatchedReversedOrAmbiguousHistoryCannotLinkNotices() {
        for (String bad : new String[]{"", history().replace(OLD, "999"), history().replace("원공고 제목", "다른 제목"),
            history().replace("2026-11-06", "2026-11-01"), history().replace("일반공고", "취소공고"),
            history().replace("</tbody>", "<tr><th>정정공고</th></tr></tbody>")}) {
            assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verifyHistory(url(OLD), page(false), url(NEXT), page(true), bad));
        }
        assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verifyHistory(url(OLD),
            page(false).replace("currPanId = '" + NEXT, "currPanId = '999"), url(NEXT), page(true), history()));
    }
}
