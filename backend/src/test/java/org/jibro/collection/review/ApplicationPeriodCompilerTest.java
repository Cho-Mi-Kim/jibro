package org.jibro.collection.review;

import java.time.*;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationPeriodCompilerTest {
    static final String URL = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";

    @Test void legalPurchaseRightDefinitionAndOfficeHoursAreNotOpenEndedApplications() {
        assertTrue(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02\n⑧ 선착순의 방법으로 공급받은 분양권등을 소유한 경우\n■ 운영기간 : 2015.1.1 ~ 공급종료 시 까지")).ready());
    }

    static Source source(String text) {
        return new Source("공고문.pdf", URL, "a".repeat(64), "EXTRACTED", null, List.of(), List.of(new Part("PDF 2쪽", text)));
    }

    ApplicationPeriodCompiler.Result assess(Source... sources) {
        var content = new Content("검수 중", new Evidence("공고", URL, "2026-11-11"), List.of(), List.of(), List.of(), List.of());
        return ApplicationPeriodCompiler.assess(new Draft("id", "notice", 2, "READY", Instant.parse("2026-11-11T00:00:00Z"), "", List.of(sources)), content);
    }

    @Test
    void expiredPeriodIsIndependentOfUnsupportedQualifications() {
        var result = assess(source("자격은 복잡한 예외표 참조\n신청 기간: 2015-04-01 10:00 ~ 2015-04-02 16:00"));
        assertTrue(result.ready());
        assertEquals(LocalDate.of(2015, 4, 2), result.end());
        assertEquals(2, result.schedules().size());
    }

    @Test
    void unknownAndRollingPeriodsNeverClose() {
        assertFalse(assess(source("서류 제출 기간: 2015-04-01 ~ 2015-04-02")).ready());
        assertFalse(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02\n이후 상시 모집")).ready());
        assertFalse(assess(source("신청 기간: 4.1 ~ 4.2")).ready());
    }

    @Test
    void contradictoryDatesAndUnreadAttachmentsBlock() {
        assertFalse(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02"), source("신청 기간: 2015-04-01 ~ 2015-04-03")).ready());
        assertFalse(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02"),
            new Source("정정공고.hwp", URL + "4", null, "FAILED", "", List.of(), List.of())).ready());
    }

    @Test
    void legacyDateMethodPlaceTablePreservesDailyClosingTime() {
        var result = assess(source("신청일시\n신청방법\n신청장소\n2015.10.13(화)∼14(수)\n(10:00 ~ 17:00)\n인터넷신청"));
        assertTrue(result.ready());
        assertEquals(LocalDate.of(2015, 10, 14), result.end());
        assertTrue(result.schedules().get(1).date().contains("17:00"));
    }

    @Test
    void tableWithAnnouncementColumnIsNotAnApplicationRange() {
        assertFalse(assess(source("신청일시\n결과발표\n신청장소\n2015.10.13(화)\n2015.10.20(화)")).ready());
    }

    @Test
    void singleDayAndCrossMonthAreExplicitlyBounded() {
        assertEquals(LocalDate.of(2015, 4, 28), assess(source("신청일자\n신청방법\n신청장소\n2015.4.28(화)\n(10:00~16:00)\n방문신청")).end());
        assertEquals(LocalDate.of(2015, 11, 2), assess(source("신청일시\n신청방법\n신청장소\n2015.10.31~11.2\n인터넷신청")).end());
        assertFalse(assess(source("신청일시\n신청방법\n신청장소\n2015.12.31~1.2\n인터넷신청")).ready());
    }

    @Test
    void inlineHoursAndRankRowsPreserveTheLastApplicationDay() {
        assertEquals(LocalDate.of(2014, 7, 18), assess(source("신청일자\n신청방법\n신청장소\n2014.7.17(목)～18(금) (10:00~17:00)\n인터넷 신청")).end());
        assertEquals(LocalDate.of(2015, 9, 11), assess(source("5. 모집일정\n1순위\n2015.09.10(목)\n(10:00~16:00)\n2순위\n2015.09.11(금)\n(10:00~16:00)")).end());
    }

    @Test
    void missingMergedRankAndLaterOpenEndedContractsRemainUnresolved() {
        assertFalse(assess(source("5. 모집일정\n1순위\n2015.09.10(목)\n(10:00~16:00)\n2순위\n6. 제출 서류")).ready());
        assertFalse(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02\n이후 선착순 동호지정 계약 가능")).ready());
        assertFalse(assess(source("신청 기간: 2015-04-01 ~ 2015-04-02\n계약일시 2015-04-03 ~ 공급종료시")).ready());
    }
}
