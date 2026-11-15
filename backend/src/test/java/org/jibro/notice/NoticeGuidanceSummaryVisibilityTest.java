package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NoticeGuidanceSummaryVisibilityTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final NoticeService service = new NoticeService(null, null, json, null);
    private final Instant now = Instant.parse("2030-05-01T09:00:00Z");
    private final Evidence source = new Evidence("공고문 4쪽", "https://apply.lh.or.kr/notice", "2030-05-01");

    private NoticeEntity reviewed() {
        var n = new NoticeEntity();
        n.id = UUID.randomUUID();
        n.title = "요약 공개 경계 테스트";
        n.agency = "LH";
        n.basicReview = n.guideReview = "PASS";
        n.startDate = LocalDate.of(2030, 4, 1);
        n.endDate = LocalDate.of(2030, 5, 31);
        n.content = json.writeValueAsString(new Content("무주택세대구성원이 대상입니다.", source, List.of(),
            List.of(new Question("rank", "선정 기준", "동일순위에서 남원시 거주자를 우선합니다.", source)),
            List.of(new DocumentTemplate("family", "가족관계증명서", null, "발급", "정부24", null, source)), List.of()));
        return n;
    }

    private void visible(NoticeEntity n, Instant at, boolean expected) {
        String stored = n.content;
        var view = service.view(n, false, at);
        assertEquals(expected, view.qualificationSummary() != null);
        assertEquals(expected, view.documentSummary() != null);
        assertEquals(stored, n.content);
    }

    @Test void upcomingAndOpenNoticesUseSameSummaryWithoutExtraWrite() {
        var n = reviewed();
        visible(n, now, true);
        n.startDate = LocalDate.of(2030, 5, 20);
        visible(n, now, true);
    }

    @Test void knownDeadlineExcludesClosedNoticeEvenWhenStartIsMissing() {
        var n = reviewed();
        n.startDate = null;
        n.endDate = LocalDate.of(2030, 4, 30);
        visible(n, now, false);
        n.endDate = null;
        n.applicationEndAt = now;
        visible(n, now.minusNanos(1), true);
        visible(n, now, false);
        visible(n, now.plusNanos(1), false);
    }

    @Test void dateOnlyDeadlineClosesAtNextMidnightInSeoul() {
        var n = reviewed();
        n.endDate = LocalDate.of(2030, 5, 1);
        Instant midnight = Instant.parse("2030-05-01T15:00:00Z");
        visible(n, midnight.minusNanos(1), true);
        visible(n, midnight, false);
    }

    @Test void officialClosureAndInterruptedLifecycleNeverExposeNewSummaries() {
        var n = reviewed();
        for (String state : List.of("EARLY_CLOSED", "CANCELLED")) {
            n.lifecycleState = state;
            visible(n, now, false);
        }
        n.lifecycleState = "NORMAL";
        var c = json.readValue(n.content, Content.class);
        n.content = json.writeValueAsString(new Content(c.summary(), c.evidence(), c.attachments(), c.questions(),
            c.documents(), c.schedules(), c.reviewIssues(), new OfficialApplicationClosure("공식 조기 종료", source)));
        visible(n, now, false);
    }

    @Test void changedOrUnreviewedContentIsHiddenUntilReviewedAgain() {
        var n = reviewed();
        n.changeReview = true;
        visible(n, now, false);
        n.changeReview = false;
        n.guideReview = "HOLD";
        visible(n, now, false);
        n.guideReview = "PASS";
        n.basicReview = "HOLD";
        visible(n, now, false);
        n.basicReview = "PASS";
        visible(n, now, true);
    }
}
