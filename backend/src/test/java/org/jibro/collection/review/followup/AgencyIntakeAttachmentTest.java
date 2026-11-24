package org.jibro.collection.review.followup;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jibro.collection.extraction.BoundedDocumentParser;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.OcrProvenance;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgencyIntakeAttachmentTest {
    private final LocalDate day = LocalDate.parse("2026-11-16");
    private final String url = "https://www.gimje.go.kr/board/view.gimje?dataSid=1";
    private final OfficialFollowupModels.Target target = new OfficialFollowupModels.Target(UUID.randomUUID(), 1,
        "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=123&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=09",
        "김제검산1단지 영구임대", day.minusDays(7));
    @Test void currentFormKeepsItsEarlierPledgeReferenceButRejectsOtherRecruitments() throws Exception {
        var form = List.of(new Part("HWPX 본문", "입주자격 완화 확약서\n공고(‘26.10.08.)에 따른 입주자로서 재계약 기준을 확인합니다."));
        assertTrue(AgencyIntakeAttachments.samePreparationFile(target, "첨부 신청서 양식.hwpx", form));
        assertFalse(AgencyIntakeAttachments.samePreparationFile(target, "별도 모집 신청서.hwpx", form));
        assertFalse(AgencyIntakeAttachments.samePreparationFile(target, "다른단지 영구임대 모집공고.pdf",
            List.of(new Part("공고문 1쪽", "다른단지 영구임대 입주자 모집공고"))));
        assertFalse(AgencyIntakeAttachments.samePreparationFile(target, "위임장.hwpx",
            List.of(new Part("본문", "원모집 링크 https://apply.lh.or.kr/?panId=999"))));
    }

    @Test void municipalCorrectionMatchesNoticeDateSiteAndHousingIncludingTheFormFilename() {
        var notice = new OfficialFollowupModels.Target(target.noticeId(), 1, target.primaryUrl(),
            "[정정공고]남원노암단지 영구임대주택 입주자격완화 예비입주자 모집공고", LocalDate.parse("2026-09-07"));
        String html = "<title>남원시 고시공고</title><div class='board_view'><h3>남원노암 영구임대주택 예비입주자 모집 수정 변경 공고</h3>"
            + "<time datetime='2026-09-07'></time><div class='view_con'>모집 공고일: 2026.09.07</div>"
            + "<a href='/board/download.do?id=1'>남원노암 영구임대주택 입주자격완화 신청서.hwpx</a></div>";
        var post = AgencyIntakePost.inspect(Jsoup.parse(html, "https://www.namwon.go.kr/board/post/view.do?id=1"), notice, day);
        assertNotNull(post);
        assertNotNull(post.proof());
        assertEquals(1, AgencyIntakeAttachments.links(post).size());
        var separate = AgencyIntakePost.inspect(Jsoup.parse(html.replace("수정 변경 공고", "별도 추가모집"), url), notice, day);
        assertTrue(separate == null || separate.proof() == null);
    }

    private AgencyIntakePost.Post post(boolean linked) {
        String html = "<title>공지사항</title><div class='bbs_view'><h4>김제검산1단지 접수 변경</h4><time datetime='2026-11-13'></time>"
            + "<div class='bbs_con'>접수 일정은 붙임 참고" + (linked ? "<a href='" + target.primaryUrl() + "'>원공고</a>" : "") + "</div>"
            + "<a href='/board/download.gimje?fileSid=7' title='접수일정.pdf 다운로드'>다운로드</a>"
            + "<a href='https://example.org/test.pdf'>접수일정.pdf</a><a href='/viewer?id=1' title='접수일정.pdf 미리보기'>미리보기</a></div>";
        return AgencyIntakePost.inspect(Jsoup.parse(html, url), target, day);
    }

    @Test void attachmentOnlyProofAndScheduleAreBoundToTheNoticeAndExactFile() {
        var post = post(false);
        assertFalse(post.linked());
        assertTrue(post.bodyWindows().isEmpty());
        assertEquals(List.of(new AgencyIntakeAttachments.FileLink("접수일정.pdf",
            "https://www.gimje.go.kr/board/download.gimje?fileSid=7")), AgencyIntakeAttachments.links(post));
        var parts = List.of(new Part("공고문 1쪽", target.title() + " 원모집 공고일: 2026.11.09"),
            new Part("공고문 3쪽", "온라인 접수 기간: 2026.11.17 09:00 ~ 11.20 17:00"));
        var claims = AgencyIntakeAttachments.claims(post, target, "접수일정.pdf", "a".repeat(64), parts);
        assertFalse(claims.isEmpty());
        var w = claims.get(0);
        assertEquals(LocalDate.parse("2026-11-20"), w.end());
        assertEquals(LocalTime.of(17, 0), w.closes());
        assertEquals("온라인", w.channel());
        assertTrue(w.attachment().location().contains("3쪽"));
        assertEquals(url, w.url());
        assertNotEquals(claims, AgencyIntakeAttachments.claims(post, target, "접수일정.pdf", "b".repeat(64), parts));
        assertTrue(FollowupDependencies.missingWindow(w, List.of()));
    }

    @Test void separateRoundWrongOriginalAndOpticalTextCannotResolveTheTime() {
        String clock = "온라인 첫날 접수 시작 시각: 10:00";
        for (String heading : List.of("김제검산1단지 추가모집", "김제검산1단지 별도 모집", "기존 공고일: 2026.10.11", "다른 원문 panId=12345")) {
            assertTrue(AgencyIntakeAttachments.claims(post(true), target, "접수일정.pdf", "sha",
                List.of(new Part("1쪽", heading + "\n" + clock))).isEmpty(), heading);
        }
        assertTrue(AgencyIntakeAttachments.claims(post(true), target, "접수일정.pdf", "sha",
            List.of(new Part(OcrProvenance.MARKER, clock))).isEmpty());
        var actual = AgencyIntakeAttachments.claims(post(true), target, "접수일정.pdf", "sha",
            List.of(new Part("1쪽", "변경 전\n온라인 접수 시작 시각: 09:00\n변경 후\n" + clock)));
        assertEquals(1, actual.size());
        assertEquals(LocalTime.of(10, 0), actual.get(0).opens());
        assertNull(actual.get(0).start());
    }

    @Test void spreadsheetHeaderKeepsDatesAndKoreanClockInTheSameRankAndMethod() {
        var p = new Part("Excel 워크시트 일정", "행 1\nA1: 순위\nB1: 접수방식\nC1: 접수 시작일시\nD1: 접수 마감일시\n"
            + "행 2\nA2: 1순위\nB2: 온라인\nC2: 2026.11.17 오전 9시\nD2: 2026.11.18 오후 4시 30분");
        var result = AgencyIntakeAttachments.claims(post(true), target, "접수일정.xlsx", "sha", List.of(p));
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(w -> w.qualifier().equals("1순위") && w.channel().equals("온라인")));
        assertTrue(result.stream().anyMatch(w -> LocalTime.of(16,30).equals(w.closes())));
    }

    @Test void realWorkbookConfirmedClockKeepsWorksheetRowAndIgnoresHiddenRows() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("접수 안내");
            var hidden = sheet.createRow(0);
            hidden.createCell(0).setCellValue("온라인 접수 마감 시각: 14:00");
            hidden.setZeroHeight(true);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("온라인 접수 마감 시각");
            row.createCell(1).setCellValue("16:00");
            book.write(out);
            bytes = out.toByteArray();
        }
        var parts = new BoundedDocumentParser().parse(bytes, "application/zip");
        var claims = AgencyIntakeAttachments.claims(post(true), target, "접수일정.xlsx", "sha", parts);
        assertEquals(1, claims.size());
        assertEquals(LocalTime.of(16, 0), claims.get(0).closes());
        assertTrue(claims.get(0).attachment().location().contains("행 2"));
    }
}
