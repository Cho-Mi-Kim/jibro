package org.jibro.collection.extraction;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.jibro.notice.NoticeService;
import org.jibro.notice.SampleData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.jibro.collection.extraction.ExtractionModels.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExtractionQueuePriorityIntegrationTest {
    @Autowired ExtractionStore store;
    @Autowired NoticeService notices;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;

    private UUID queued(int publicationDaysAgo, int waitingHours, String guide) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        UUID notice = notices.insert("queue-test-" + UUID.randomUUID(), "대기 순서 검증", "LH", "서울", "임대",
            today, today.plusDays(30), today.minusDays(publicationDaysAgo), SampleData.content());
        entities.flush();
        db.update("UPDATE notice SET basic_review='PASS',guide_review=? WHERE id=?", guide, notice);
        UUID extraction = UUID.fromString(store.enqueue(notice, false).id());
        db.update("UPDATE notice_extraction SET created_at=now()-(? * interval '1 hour'),next_attempt_at=now()-(? * interval '1 hour') WHERE id=?",
            waitingHours, waitingHours, extraction);
        return notice;
    }

    @Test void recentUnreviewedNoticePrecedesFreshlyQueuedHistoricalBacklog() {
        queued(365, 6, "HOLD");
        UUID recent = queued(3, 0, "HOLD");
        assertEquals(recent, store.claim().noticeId());
    }

    @Test void continuationDoesNotMoveBehindUnstartedJobsWhenNextAttemptBecomesNow() {
        UUID first = queued(365, 3, "HOLD");
        queued(365, 2, "HOLD");
        var job = store.claim();
        assertEquals(first, job.noticeId());
        var deferred = new Source("다음 첨부", "https://apply.lh.or.kr/next.pdf", null, "DEFERRED", "이어서 읽기", List.of());
        store.finish(job, List.of(deferred), "QUEUED", "이어서 읽기");
        assertEquals(first, store.claim().noticeId());
    }

    @Test void boundedRecentBonusDoesNotStarveOlderWaitingNotices() {
        UUID older = queued(365, 48, "HOLD");
        queued(1, 0, "HOLD");
        assertEquals(older, store.claim().noticeId());
    }

    @Test void retryBackoffStillBlocksEvenHighestPriorityNotice() {
        UUID recent = queued(1, 2, "HOLD");
        UUID historical = queued(365, 1, "HOLD");
        db.update("UPDATE notice_extraction SET next_attempt_at=now()+interval '30 seconds' WHERE notice_id=?", recent);
        assertEquals(historical, store.claim().noticeId());
        assertNull(store.claim());
    }

    @Test void unreviewedPublicNoticesPrecedeAlreadyApprovedGuidance() {
        queued(1, 48, "PASS");
        UUID held = queued(1, 0, "HOLD");
        assertEquals(held, store.claim().noticeId());
    }
}
