package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IndividualAnnouncementIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired ObjectMapper json;

    private Content content(ScheduleTemplate event) {
        var base = SampleData.content();
        return new Content(base.summary(), base.evidence(), base.attachments(), base.questions(), base.documents(), List.of(event));
    }

    @Test void explanatoryAnnouncementRoundTripsWithoutInventingDate() {
        var event = new ScheduleTemplate("result-individual", "결과 발표 · 개별 안내", "", SampleData.content().evidence(), "소득·자산 확인 필요시 신청일부터 최소 10주 소요. 문자·이메일 개별 안내.");
        assertDoesNotThrow(() -> notices.validateContent(content(event)));
        var restored = json.readValue(json.writeValueAsString(content(event)), Content.class);
        assertEquals(event, restored.schedules().get(0));
        assertNull(ApplicationPeriod.day(event.date()));
        assertNull(ApplicationPeriod.instant(event));
    }

    @Test void missingDateNeedsExplicitAnnouncementExplanationAndCannotReplaceAnApplicationDeadline() {
        var evidence = SampleData.content().evidence();
        assertThrows(IllegalArgumentException.class, () -> notices.validateContent(content(new ScheduleTemplate("result", "결과 발표", "", evidence))));
        assertThrows(IllegalArgumentException.class, () -> notices.validateContent(content(new ScheduleTemplate("end", "접수 마감", "", evidence, "개별 안내"))));
        assertThrows(IllegalArgumentException.class, () -> notices.validateContent(content(new ScheduleTemplate("result", "결과 발표", "2026-12-31", evidence, "최소 10주"))));
    }
}
