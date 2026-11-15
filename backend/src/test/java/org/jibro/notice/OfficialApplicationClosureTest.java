package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OfficialApplicationClosureTest {
    private final ObjectMapper json = new ObjectMapper();
    private final NoticeService notices = new NoticeService(mock(NoticeRepository.class), mock(NoticeQueryRepository.class),
        json, mock(NoticeAuditRepository.class));
    private final Evidence source = new Evidence("공식 상세 · 공고상태 접수마감", "https://apply.lh.or.kr/detail", "2026-11-08");
    private final String reason = "공식 상세에서 접수마감 상태를 확인했습니다. 실제 종료일은 알 수 없습니다.";

    private Content content(String summary, OfficialApplicationClosure closure) {
        return new Content(summary, source, List.of(), List.of(), List.of(), List.of(), List.of(), closure);
    }

    @Test void closureRoundTripsWithoutCreatingAScheduleOrDeadline() {
        var c = content(reason, new OfficialApplicationClosure(reason, source));
        assertDoesNotThrow(() -> notices.validateContent(c));
        assertEquals(c, json.readValue(json.writeValueAsString(c), Content.class));
        assertTrue(c.schedules().isEmpty());
    }

    @Test void closureRequiresBoundedReasonInSummaryAndValidDatedSource() {
        for (var invalid : List.of(
            new OfficialApplicationClosure("", source),
            new OfficialApplicationClosure("가".repeat(501), source),
            new OfficialApplicationClosure("다른 설명", source),
            new OfficialApplicationClosure(reason, null),
            new OfficialApplicationClosure(reason, new Evidence("공식 상태", null, "2026-11-08")),
            new OfficialApplicationClosure(reason, new Evidence("공식 상태", "http://apply.lh.or.kr/detail", "2026-11-08")),
            new OfficialApplicationClosure(reason, new Evidence("공식 상태", source.url(), "2999-01-01")))) {
            assertThrows(IllegalArgumentException.class, () -> notices.validateContent(content(reason, invalid)));
        }
    }

    @Test void existingConstructorsAndOldJsonNeverInferAClosureFromSummary() {
        var six = new Content(reason, source, List.of(), List.of(), List.of(), List.of());
        var seven = new Content(reason, source, List.of(), List.of(), List.of(), List.of(), List.of());
        assertNull(six.applicationClosure());
        assertNull(seven.applicationClosure());
        String old = json.writeValueAsString(seven).replace(",\"applicationClosure\":null", "");
        assertNull(json.readValue(old, Content.class).applicationClosure());
        var previous = content(reason, new OfficialApplicationClosure(reason, source));
        var rebuilt = new Content(previous.summary(), previous.evidence(), previous.attachments(), previous.questions(),
            previous.documents(), previous.schedules(), previous.reviewIssues());
        assertNull(rebuilt.applicationClosure());
    }
}
