package org.jibro.collection.review.followup;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jibro.collection.review.ApplicationPeriodCompiler.Result;
import org.jibro.collection.review.IntakeChannelDates.*;
import org.jibro.collection.review.OfficeHoursAgreement;
import org.jibro.notice.HousingModels.*;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReceptionDeskAgreementTest {
    private static final LocalDate EDITION = LocalDate.parse("2026-11-11");
    private static final LocalDate START = EDITION.plusDays(12);
    private static final LocalDate END = START.plusDays(2);
    private static final String CITY = "https://paldal.suwon.go.kr/bbsplus/view.asp?no=1";
    private static final Evidence LH = new Evidence("공고문 10쪽","https://apply.lh.or.kr/lhFile.do?fileid=123",EDITION.toString());
    private static final String PAGE = """
        <title>우리동소식</title><div class='board'><div class='view'>
        <div class='title'>수원시 영구임대주택 예비입주자 모집(수원우만3)</div>
        <div class='info'><dl><dt>작성부서</dt><dd>우만2동</dd><dt>작성일</dt><dd>2026-11-09</dd></dl></div>
        <div class='substance'>1. 입주자 모집공고일<br>- 2026.11.11.<br>3. 신청 기간<br>
        - 2026.11.23.(월) ~ 11.25.(수)<br>- 점심시간(13:00~14:00) 제외<br>
        관할 행정복지센터 방문 접수<br>문의: 우만2동 행정복지센터</div></div></div>""";

    private static List<Window> read(String html) {
        var target = new OfficialFollowupModels.Target(UUID.randomUUID(),1,LH.url(),"수원시 영구임대주택 예비입주자 모집공고(2026.11.11.)",EDITION);
        return AgencyIntakePost.read(Jsoup.parse(html,CITY),target,START);
    }

    @Test void namedLunchWithoutDailyBoundsKeepsOnlyTheLunchDifferenceAndBothSources() {
        var lunch = read(PAGE);
        assertEquals(1,lunch.size());
        assertTrue(read(PAGE.replace("2026.11.11.","2026.11.10.")).isEmpty());
        assertTrue(read(PAGE.replace("문의: 우만2동","문의: 우만1동")).isEmpty());
        var rows = List.of(new ScheduleTemplate("auto-scoped-1-start","현장 (점심 12:00~13:00 제외) 접수 시작",START.toString(),LH),
            new ScheduleTemplate("auto-scoped-1-end","현장 (점심 12:00~13:00 제외) 접수 종료",END.toString(),LH));
        var result = OfficeHoursAgreement.compare(new Result(START,END,rows,List.of()),lunch,EDITION);
        assertEquals(1,result.reviewIssues().size());
        var issue = result.reviewIssues().get(0);
        assertTrue(issue.detail().contains("2026-11-23~2026-11-25 우만2동 행정복지센터의 12:00~14:00"));
        assertEquals(List.of(LH.url(),CITY),issue.evidence().stream().map(Evidence::url).toList());
        assertFalse(result.schedules().get(2).title().contains("09:00"));
        assertNull(OfficeHoursAgreement.compare(new Result(START,END,rows,List.of()),lunch,EDITION.plusDays(1)));
    }

    private static ScheduleTemplate visit(LocalDate day) {
        return new ScheduleTemplate("auto-visit-round-"+day,"접수 회차 · 현장 (점심 12:00~13:00 제외) 10:00~17:00",day.toString(),LH);
    }

    @Test void onlyTheNamedCountersLastDayHasAnEarlierClosing() {
        var w = new Window("","",START,END,CITY,LocalTime.of(9,0),LocalTime.of(16,0),EDITION,null,null,
            AgencyField.KOHOM_INTAKE,null,null,new Office("수성 관리사무소",EDITION,""));
        var online = new ScheduleTemplate("auto-scoped-2-end","인터넷 접수 종료",END+"T17:00",LH);
        var boundary = new ScheduleTemplate("auto-application-end","신청 접수 마감",END+"T17:00",LH);
        var result = OfficeHoursAgreement.compare(new Result(START,END,List.of(visit(START),visit(END),online,boundary),List.of()),List.of(w),EDITION);
        assertEquals(1,result.reviewIssues().size());
        assertTrue(result.schedules().containsAll(List.of(visit(START),online,boundary)));
        var last = result.schedules().get(result.schedules().size() - 1);
        assertEquals(END.toString(),last.date());
        assertTrue(last.title().endsWith("10:00~12:00, 13:00~16:00 · 확인 필요 16:00~17:00"));
        assertFalse(last.title().contains("09:00"));
        assertNull(OfficeHoursAgreement.compare(new Result(START.minusDays(1),END,List.of(visit(END)),List.of()),List.of(w),EDITION));
    }

    @Test void twoDesksAndAnExplicitFinalDayAreHandledIndependently() {
        var w = read(PAGE.replace("- 점심시간(13:00~14:00) 제외", """
            우만2동 행정복지센터 점심시간(13:00~14:00) 제외<br>우만1동 주민센터 점심시간(12:30~13:00) 제외<br>
            우만1동 주민센터 마지막 날 방문 접수 마감 16:30"""));
        var result = OfficeHoursAgreement.compare(new Result(START,END,List.of(visit(END)),List.of()),w,EDITION);
        assertEquals(2,result.reviewIssues().size());
        var text = result.schedules().stream().map(ScheduleTemplate::title).toList();
        assertTrue(text.stream().anyMatch(t -> t.contains("우만1동") && t.endsWith("확인 필요 12:00~12:30, 16:30~17:00")));
        assertTrue(text.stream().anyMatch(t -> t.contains("우만2동") && t.endsWith("확인 필요 12:00~14:00")));
    }
}
