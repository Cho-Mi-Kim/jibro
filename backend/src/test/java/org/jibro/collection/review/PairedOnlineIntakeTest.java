package org.jibro.collection.review;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PairedOnlineIntakeTest {
    private static final String DETAIL = "구분 : 무순위\n신청일시 : 2026.11.25 10:00 ~ 2026.11.26 17:00\n신청방법 : 인터넷접수";
    private static final String STATEMENT = "인터넷 신청 가능시간은 11.25(수) 10시 ~ 11.26(목) 17시입니다.";
    private static final String BASE = "https://apply.lh.or.kr/";

    private ApplicationPeriodCompiler.Result compare(String pdf, String body, String issue) {
        var header = new Part("표 제목", "구분 : 신청일시 : 신청방법");
        var source = new Source("공식 상세 본문", BASE + "detail", "a".repeat(64), "EXTRACTED", "", List.of(), List.of(header, new Part("공식 일정", body)));
        var pdfFile = new Source("모집공고.pdf", BASE + "pdf", "b".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("공고문 8쪽", pdf)));
        var hancom = new Source("모집공고.hwpx", BASE + "hwpx", "c".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("한글 본문", STATEMENT)));
        var draft = new Draft("x", "y", 1, "READY", Instant.now(), "", List.of(source, pdfFile, hancom));
        var original = new Content("공식", new Evidence("상세", source.url(), "2026-11-17"), List.of(), List.of(), List.of(), List.of());
        return PairedOnlineIntake.confirmed(draft, original, new ApplicationPeriodCompiler.Result(null, null, List.of(), List.of(issue)));
    }

    @Test
    void readsOnlyOneElectronicSessionAgreedByBothEditionsAndTheDetailTable() {
        var verified = compare(STATEMENT, DETAIL, "APPLICATION_PERIOD_MISSING");
        assertNotNull(verified);
        assertEquals(LocalDate.of(2026, 11, 25), verified.start());
        assertEquals("2026-11-26T17:00", verified.schedules().get(1).date());
        assertNull(compare(STATEMENT.replace("17시", "16시"), DETAIL, "APPLICATION_PERIOD_MISSING"));
        assertNull(compare(STATEMENT, DETAIL + "\n" + DETAIL, "APPLICATION_PERIOD_MISSING"));
        assertNull(compare(STATEMENT, DETAIL, "SCHEDULE_CONFLICT:CLOSE"));
    }
}

