package org.jibro.collection.review.followup;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgencyIntakePostTest {
    private final LocalDate check = LocalDate.parse("2026-11-16");
    private final String primary = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=123&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=09";
    private final OfficialFollowupModels.Target target = new OfficialFollowupModels.Target(UUID.randomUUID(), 2,
            primary, "김제검산1단지 영구임대 상시모집", LocalDate.parse("2026-11-01"));

    private org.jsoup.nodes.Document page(String title, String link) {
        return Jsoup.parse("<title>읍면동 소식</title><div class='bbs_view'><h4>" + title + "</h4>"
                + "<time datetime='2026-11-05'></time><div class='bbs_con'><a href='" + link + "'>원모집공고</a>"
                + "<p>1순위 현장 접수 시작: 2026.11.20(금) 13:30</p><p>온라인 접수 기간: 2026.11.20 10:00 ~ 2026.11.24 16:00</p>"
                + "</div></div>", "https://www.gimje.go.kr/board/view.gimje?dataSid=1");
    }

    @Test
    void unrelatedRecruitmentsAreExcludedBeforeTheirMalformedPublicationDateIsRead() {
        var additional = page("김제검산1단지 추가모집", primary);
        var otherNotice = page("접수 일정 안내", primary.replace("panId=123", "panId=456"));
        var separateBody = page("별도 모집 안내", primary);
        separateBody.selectFirst(".bbs_con").appendElement("p").text("별도 모집입니다.");
        for (var post : java.util.List.of(additional, otherNotice, separateBody)) {
            post.selectFirst("time").attr("datetime", "2026-11-31");
            assertNull(AgencyIntakePost.inspect(post, target, check));
        }
        var sameNotice = page("접수 일정 안내", primary);
        sameNotice.selectFirst("time").attr("datetime", "2026-11-31");
        var failure = assertThrows(AgencyIntakePost.InvalidField.class, () -> AgencyIntakePost.inspect(sameNotice, target, check));
        assertTrue(failure.getMessage().startsWith("AGENCY_PUBLICATION_DATE_INVALID"));
    }

    @Test
    void malformedOfficialAmendmentsAreNotSilentlyDiscardedAsAbsentFields() {
        for (String statement : java.util.List.of(
                "온라인 접수 마감: 2026.11.31 16:00 → 2026.12.01 17:00",
                "온라인 접수 마감: 2026.11.24 26:00 → 2026.11.25 17:00",
                "온라인 접수 마감: 2026.11.24 16:00 → 2026.11.31 17:00",
                "현장 접수 시작 시각: 25:00")) {
            var post = page("접수 일정 변경", primary);
            post.selectFirst(".bbs_con").appendElement("p").text(statement);
            var failure = assertThrows(AgencyIntakePost.InvalidField.class, () -> AgencyIntakePost.read(post, target, check));
            assertTrue(failure.getMessage().contains(statement));
            assertTrue(failure.getMessage().contains("https://www.gimje.go.kr/board/view.gimje?dataSid=1"));
        }
        var noFields = page("접수 일정 안내", primary);
        noFields.select(".bbs_con p").remove();
        assertTrue(AgencyIntakePost.read(noFields, target, check).isEmpty());
    }

    @Test
    void noticeLinkAndScopedBoundaries() {
        var windows = AgencyIntakePost.read(page("김제검산1단지 상시모집 접수 일정 안내", primary), target, check);
        assertEquals(2, windows.size());
        assertEquals(LocalTime.of(13, 30), windows.get(0).opens());
        assertEquals("1순위", windows.get(0).qualifier());
        assertNull(windows.get(0).end());
        assertEquals(LocalDate.parse("2026-11-24"), windows.get(1).end());
    }

    @Test
    void kohomStructuredIntakeRequiresTheSameRecruitmentAndIgnoresOtherDates() {
        var local = new OfficialFollowupModels.Target(UUID.randomUUID(), 1, primary,
                "수원시 영구임대주택 예비입주자 모집공고(2026.11.11.)", LocalDate.parse("2026-11-11"));
        var html = "<title>예비입주자 모집안내</title><div id='content'><div class='txt_frm'><table>"
                + "<tr><th>제목</th><td>수원시 영구임대주택 예비입주자 모집공고(2026.11.11)</td></tr>"
                + "<tr><th>작성일</th><td>2026-11-12</td></tr><tr><th>모집공고일</th><td>2026-11-11</td></tr>"
                + "<tr><th>신청접수일</th><td>2026-11-23 09:00 ~ 2026-11-25 18:00</td></tr>"
                + "<tr><th>당첨자발표일</th><td>2027-04-30 16:00</td></tr></table></div></div>";
        String url = "https://www.kohom.or.kr/web/mainComm/HM001002002.do?mode=view&p_idx=16087";
        var result = AgencyIntakePost.read(Jsoup.parse(html, url), local, check);
        assertEquals(1, result.size());
        assertEquals(org.jibro.collection.review.IntakeChannelDates.AgencyField.KOHOM_INTAKE, result.get(0).field());
        assertEquals(LocalTime.of(9, 0), result.get(0).opens());
        assertEquals(LocalTime.of(18, 0), result.get(0).closes());
        var neighboringNotice = new OfficialFollowupModels.Target(UUID.randomUUID(), 1, primary,
                "천안병천신한 국민임대주택 예비입주자 모집공고", local.publishedDate());
        var withNavigation = Jsoup.parse(html, url);
        withNavigation.selectFirst(".txt_frm").appendElement("dl").addClass("prev_next")
                .appendElement("dd").appendElement("a").attr("href", primary).text(neighboringNotice.title());
        assertTrue(AgencyIntakePost.read(withNavigation, neighboringNotice, check).isEmpty());
        assertEquals(result, AgencyIntakePost.read(withNavigation, local, check));
        for (String other : java.util.List.of(html.replace("수원시", "인근시"), html.replace("예비입주자 모집공고", "추가모집 공고"),
                html.replace(">2026-11-11<", ">2026-11-10<"), html.replace("신청접수일", "상담일시"))) {
            assertTrue(AgencyIntakePost.read(Jsoup.parse(other, url), local, check).isEmpty());
        }
    }

    @Test
    void explicitReplacementKeepsItsOldValue() {
        var page = page("접수 일정 변경", primary);
        page.selectFirst(".bbs_con").html("<a href='" + primary + "'>원공고</a><p>온라인 접수 마감: 2026.11.24 16:00 → 2026.11.25 17:00</p>");
        var value = AgencyIntakePost.read(page, target, check).get(0);
        assertEquals(LocalDate.parse("2026-11-24"), value.previous().day());
        assertEquals(LocalTime.of(17, 0), value.closes());
    }

    @Test
    void timeOnlyPostDoesNotInventADay() {
        var page = page("접수 일정", primary);
        page.selectFirst(".bbs_con").appendElement("p").text("현장 첫날 접수 시작 시각: 14:00");
        var value = AgencyIntakePost.read(page, target, check).get(2);
        assertNull(value.start());
        assertEquals(LocalTime.of(14, 0), value.opens());
    }

    @Test
    void separateRoundsOtherIdsAndOutOfDatePostsCannotConfirmAnything() {
        assertTrue(AgencyIntakePost.read(page("김제검산1단지 자격완화 모집", primary), target, check).isEmpty());
        assertTrue(AgencyIntakePost.read(page("기존 모집 관련", primary.replace("123", "456")), target, check).isEmpty());
        assertTrue(AgencyIntakePost.read(page("접수 일정", primary), target, target.publishedDate()).isEmpty());
    }
}
