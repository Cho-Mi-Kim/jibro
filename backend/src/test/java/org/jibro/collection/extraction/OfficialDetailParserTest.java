package org.jibro.collection.extraction;

import static org.junit.jupiter.api.Assertions.*;
import static org.jibro.collection.extraction.ExtractionModels.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class OfficialDetailParserTest {
    final String url = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do";

    @Test
    void retainsOnlyOfficialAttachmentsAndPlainSourceSentences() {
        String html = """
                <html><header>제출 서류 가짜 메뉴</header><main><p>무주택 신청 자격은 원문 대조 필요</p>
                <script>접수 마감 fake script</script><style>.접수{}</style>
                <a href='/lhapply/lhFile.do?fileid=123'>공고문</a>
                <a onclick="lhFileDown('456')">제출 서류 양식</a>
                <a href='https://apply.lh.or.kr/notice.pdf'>PDF</a>
                <a href='http://apply.lh.or.kr/insecure.pdf'>비암호화</a>
                <a href='https://apply.lh.or.kr:8443/port.pdf'>비표준 포트</a>
                <a href='https://apply.lh.or.kr.evil.invalid/fake.pdf'>다른 호스트</a>
                <a href='https://user@apply.lh.or.kr/userinfo.pdf'>사용자 정보</a>
                <a href='javascript:alert(1)'>스크립트</a><a href='/not-an-attachment'>게시물</a>
                </main></html>
                """;
        var detail = OfficialDetailParser.parse(html, url);
        assertEquals(3, detail.attachments().size(), detail.attachments().toString());
        assertTrue(detail.attachments().stream().anyMatch(l -> l.url().endsWith("fileid=456")));
        assertEquals(List.of(new Part("공식 상세 본문 · 항목 1", "무주택 신청 자격은 원문 대조 필요")), detail.parts());
    }

    @Test
    void excerptsPreserveTextAndLocationWithoutGeneratingRulesOrDuplicatingSentences() {
        String line = "신청 자격과 제출 서류는 접수 마감 전에 확인하세요.";
        var excerpts = ExcerptClassifier.extract(List.of(new Part("공고문 4쪽", line + "\n" + line)), url);
        assertEquals(3, excerpts.size());
        assertTrue(excerpts.stream().allMatch(e -> e.text().equals(line) && e.location().equals("공고문 4쪽") && e.url().equals(url)));
        assertEquals(List.of("CONDITION", "DOCUMENT", "SCHEDULE"), excerpts.stream().map(Excerpt::category).toList());
    }

    @Test
    void spreadsheetsUseTheSameOfficialHttpsAttachmentBoundary() {
        var detail = OfficialDetailParser.parse("""
                <main><a href='/files/housing.xlsx'>주택 목록</a><a href='/files/prices.XLS'>임대조건</a>
                <a href='http://apply.lh.or.kr/files/insecure.xlsx'>비암호화</a>
                <a href='https://apply.lh.or.kr:8443/files/port.xlsx'>비표준 포트</a>
                <a href='https://other.invalid/files/housing.xlsx'>다른 호스트</a>
                <a href='https://user@apply.lh.or.kr/files/private.xlsx'>사용자 정보</a></main>
                """, url);
        assertEquals(List.of("https://apply.lh.or.kr/files/housing.xlsx", "https://apply.lh.or.kr/files/prices.XLS"),
                detail.attachments().stream().map(Link::url).toList());
    }

    @Test
    void keepsLhScheduleAndApplicantNotesOutsideTheMetadataBox() {
        String html = """
                <main><div class="bbs_ViewA"><table><tr><th>공고일</th><td>2026.11.05</td></tr></table></div>
                <section class="panDtlInfo"><h3>공급정보</h3><p>주택 9,000개 주소 목록</p></section>
                <section><h3>공급일정</h3><ul>
                  <li><span>접수기간</span> : <label>2026.11.16 ~ 2026.11.17</label></li>
                  <li><span>서류제출대상자 발표일</span> : <label>2026.12.07</label></li>
                  <li><span>서류접수기간</span> : <label>2026.12.08 ~ 2026.12.10</label></li>
                  <li><span>당첨자발표일</span> : <label>2027.03.26</label></li>
                </ul></section>
                <section><h3>기타사항</h3><ul><pre>1,2순위 마감 시 3순위 접수는 불가능합니다.
                모든 제출서류는 공고일 이후 발급분만 인정됩니다.</pre></ul></section></main>
                """;
        var detail = OfficialDetailParser.parse(html, url);
        String text = String.join("\n", detail.parts().stream().map(Part::text).toList());
        assertTrue(text.contains("접수기간 : 2026.11.16 ~ 2026.11.17"));
        assertTrue(text.contains("서류제출대상자 발표일 : 2026.12.07"));
        assertTrue(text.contains("3순위 접수는 불가능합니다."));
        assertFalse(text.contains("9,000개"));
        assertEquals(6, detail.parts().size());
    }

    @Test
    void keepsSimpleTableColumnsBoundAndDoesNotDuplicateNestedParagraphs() {
        var detail = OfficialDetailParser.parse("""
                <main><table><thead><tr><th>접수기간</th><th>결과 발표</th></tr></thead>
                <tbody><tr><td><p>2026.11.16 ~ 2026.11.17</p></td><td><p>2027.03.26</p></td></tr></tbody>
                </table></main>
                """, url);
        assertEquals(2, detail.parts().size());
        assertTrue(detail.parts().get(1).text().contains("접수기간 : 2026.11.16 ~ 2026.11.17\n결과 발표 : 2027.03.26"));
    }

    @Test
    void keepsReceptionNotesEvenWhenTheyDisagreeWithTheStructuredSchedule() {
        var detail = OfficialDetailParser.parse("""
                <main><div class="bbs_ViewA"><p>공고일 2026.11.04</p></div>
                <section><h3>공급일정</h3><p>당첨자발표일 : 2027.07.06</p></section>
                <section><h3>접수처정보</h3><pre>당첨자발표일 : 2026.07.06(화) 10:00시 이후</pre></section></main>
                """, url);
        var text = detail.parts().toString();
        assertTrue(text.contains("2027.07.06"));
        assertTrue(text.contains("2026.07.06"));
    }
}
