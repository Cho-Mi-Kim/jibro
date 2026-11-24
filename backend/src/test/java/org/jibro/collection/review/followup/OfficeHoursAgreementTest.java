package org.jibro.collection.review.followup;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jibro.collection.review.ApplicationPeriodCompiler;
import org.jibro.collection.review.OfficeHoursAgreement;
import org.jibro.notice.ApplicationPeriod;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.ScheduleTemplate;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OfficeHoursAgreementTest {
    private static final LocalDate ISSUE = LocalDate.of(2026, 11, 12);
    private static final LocalDate FIRST = ISSUE.plusDays(13);
    private static final String LH = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=123";
    private static final String CITY = "https://www.wonju.go.kr/www/selectBbsNttView.do?nttNo=495725";
    private static final Evidence SOURCE = new Evidence("공고문 3쪽", LH, ISSUE.toString());
    private static ScheduleTemplate visit(String label) {
        return new ScheduleTemplate("auto-visit-auto-scoped-1-start-" + FIRST, "접수 회차 · " + label, FIRST.toString(), SOURCE);
    }
    private static ApplicationPeriodCompiler.Result period(ScheduleTemplate... rows) {
        return new ApplicationPeriodCompiler.Result(FIRST, FIRST.plusDays(2), List.of(rows), List.of());
    }
    private static List<org.jibro.collection.review.IntakeChannelDates.Window> hours(String field) {
        return OfficeVisitFields.read(field, CITY, ISSUE);
    }

    @Test void cityPostAndNoticeAgreeDuringTwoIntervalsAndOnlyTheEdgesNeedChecking() {
        var t = new OfficialFollowupModels.Target(UUID.randomUUID(), 1, LH, "원주명륜2 영구임대 예비입주자 모집공고", ISSUE);
        var doc = Jsoup.parse("""
            <title>타기관 공고/고시 상세</title><div class="p-wrap bbs bbs__view">
            <div class="bbs_info">작성일 2026.11.12</div><span class="p-table__subject_text">원주시 영구임대 예비입주자 모집 안내</span>
            <div class="ntt_cn_container">모집공고일: 2026.11.12.<br>
            1. 신청기간: 2026.11.25.（수） ~ 11.27.（금） 3일간 9:00 ~ 18:00 （점심시간 12:00~13:00 제외）<br>
            2. 신청장소: 주민등록 주소지 관할 읍면동 행정복지센터</div>
            <a href="download">（261112）원주명륜2 영구임대 예비입주자 모집공고문.hwpx</a></div>
            """, CITY);
        var office = AgencyIntakePost.read(doc, t, ISSUE);
        var start = new ScheduleTemplate("auto-application-start","신청 접수 시작 (10:00)",FIRST + "T10:00",SOURCE);
        var result = OfficeHoursAgreement.compare(period(start, visit("현장 10:00~17:00")), office, ISSUE);
        assertNotNull(result);
        assertEquals("접수 회차 · 방문 10:00~12:00, 13:00~17:00 · 확인 필요 09:00~10:00, 17:00~18:00", result.schedules().get(1).title());
        assertNull(ApplicationPeriod.resolve(FIRST, FIRST, result.schedules()).startsAt());
        assertEquals(2, result.reviewIssues().get(0).evidence().size());
        assertFalse(result.reviewIssues().get(0).detail().contains("12:00"));
        assertTrue(AgencyIntakePost.read(doc, new OfficialFollowupModels.Target(t.noticeId(), 2, LH, t.title(), ISSUE.minusDays(3)), ISSUE).isEmpty());
    }

    @Test void disjointSessionsDoNotCertifyTheUnmentionedGap() {
        var result = OfficeHoursAgreement.compare(period(visit("현장 10:00~12:00")),
            hours("방문 접수일: 2026.11.25 13:00~17:00"), ISSUE);
        assertTrue(result.schedules().get(0).title().contains("공통 확인 시간 없음 · 확인 필요 10:00~12:00, 13:00~17:00"));
    }

    @Test void scopeChannelDateEditionAndLocationMustMatch() {
        var base = period(visit("1순위 · 현장 10:00~17:00"));
        for (var field : List.of("[2순위] 방문 접수일: 2026.11.25 09:00~18:00", "방문 접수일: 2026.11.25 09:00~18:00",
            "[1순위] 온라인 접수일: 2026.11.25 09:00~18:00", "[1순위] 방문 접수일: 2026.11.24 09:00~18:00",
            "[1순위] 방문 접수일: 2026.11.25 09:00~18:00\n접수장소: 무실동 주민센터"))
            assertNull(OfficeHoursAgreement.compare(base, hours(field), ISSUE), field);
        var same = hours("[1순위] 방문 접수일: 2026.11.25 10:00~17:00");
        assertNull(OfficeHoursAgreement.compare(base, same, ISSUE.plusDays(1)));
        assertTrue(OfficeHoursAgreement.compare(base, same, ISSUE).reviewIssues().isEmpty());
    }

    @Test void partialPostOnlyChangesItsDayInARepeatedRange() {
        var base = period(visit("현장 10:00~17:00 · 2026-12-16까지 접수일마다 (공휴일 제외)"));
        var result = OfficeHoursAgreement.compare(base, hours("방문 접수일: 2026.11.27 09:00~18:00"), ISSUE);
        assertEquals(3, result.schedules().size());
        assertEquals(List.of("2026-11-25", "2026-11-27", "2026-11-28"), result.schedules().stream().map(ScheduleTemplate::date).toList());
        assertEquals(List.of(false, true, false), result.schedules().stream().map(s -> s.title().contains("확인 필요")).toList());
    }

    @Test void aCorrectionRetainsEarlierOfficeEvidenceOnlyForUnchangedVisitRounds() {
        var before = period(visit("현장 10:00~17:00"));
        var office = hours("방문 접수일: 2026.11.25 09:00~18:00");
        assertNotNull(OfficeHoursAgreement.compare(before, office, ISSUE.plusDays(1), before, ISSUE));
        var corrected = period(visit("현장 11:00~16:00"));
        assertNull(OfficeHoursAgreement.compare(corrected, office, ISSUE.plusDays(1), before, ISSUE));
        var fresh = OfficeVisitFields.read("방문 접수일: 2026.11.25 10:00~18:00", CITY, ISSUE.plusDays(1));
        assertTrue(OfficeHoursAgreement.compare(corrected, fresh, ISSUE.plusDays(1), before, ISSUE)
            .reviewIssues().get(0).detail().contains("10:00~11:00, 16:00~18:00"));
    }

    @Test void differentBreaksAndAnIndependentOnlineBoundaryKeepTheirOwnMeaning() {
        var online = new ScheduleTemplate("auto-scoped-2-start", "온라인 접수 시작", FIRST + "T10:00", SOURCE);
        var boundary = new ScheduleTemplate("auto-application-start", "신청 접수 시작", FIRST + "T10:00", SOURCE);
        var result = OfficeHoursAgreement.compare(period(visit("현장 (점심 12:00~13:00 제외) 10:00~17:00"), online, boundary),
            hours("현장 신청기간: 2026.11.25 09:00~17:00 (점심시간 12:30~13:30 제외)"), ISSUE);
        assertEquals("접수 회차 · 방문 10:00~12:00, 13:30~17:00 · 확인 필요 09:00~10:00, 12:00~12:30, 13:00~13:30",
            result.schedules().get(0).title());
        assertTrue(result.schedules().containsAll(List.of(online, boundary)));
    }

    @Test void aVerifiedAttachmentCanSupplyTheOfficeHoursAndExactSourceLocation() {
        var source = new org.jibro.collection.extraction.ExtractionModels.Source("모집공고문.pdf", CITY, "a".repeat(64), "EXTRACTED", "",
            List.of(), List.of(new org.jibro.collection.extraction.ExtractionModels.Part("3쪽", """
                방문 접수: 2026.11.25 ~ 2026.11.27
                매일 09:30~17:30
                (점심시간 12:00~13:00 제외)
                """)));
        var windows = OfficeHoursAgreement.attachmentHours(source, ISSUE);
        assertEquals(3, windows.size());
        assertTrue(windows.stream().allMatch(w -> w.attachment().location().contains("3쪽")));
        var result = OfficeHoursAgreement.compare(period(visit("현장 10:00~17:00")), windows, ISSUE);
        assertTrue(result.schedules().get(0).title().contains("09:30~10:00, 17:00~17:30"));
    }
}
