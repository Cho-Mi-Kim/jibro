package org.jibro.collection.review.ai;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GeminiBudgetIntegrationTest {
    @Autowired GeminiBudgetRepository budget;
    @Autowired JdbcTemplate db;
    Instant now = Instant.parse("2026-11-07T10:00:00Z");

    @Test void duplicatesUseCacheAndRequestsAreSpacedAtLeastOneMinute() {
        assertTrue(budget.reserve("a".repeat(64), "test", now, 10, 60).granted());
        assertEquals("IN_FLIGHT", budget.reserve("a".repeat(64), "test", now, 10, 60).reason());
        assertEquals("RATE_WAIT", budget.reserve("b".repeat(64), "test", now.plusSeconds(59), 10, 1).reason());
        budget.finish("a".repeat(64), "CANDIDATE", "{}");
        assertEquals("{}", budget.reserve("a".repeat(64), "test", now.plusSeconds(60), 10, 60).cached());
        assertTrue(budget.reserve("b".repeat(64), "test", now.plusSeconds(61), 10, 60).granted());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM gemini_review_attempt", Integer.class));
    }

    @Test void dailyCapCannotBeRaisedBeyondTenAndRestartDoesNotResetIt() {
        for(int i=0;i<10;i++) assertTrue(budget.reserve(String.format("%064d", i), "test", now.plusSeconds(61L*i), 100, 60).granted());
        assertEquals("DAILY_LIMIT", budget.reserve("c".repeat(64), "test", now.plusSeconds(900), 100, 60).reason());
        assertTrue(budget.reserve("c".repeat(64), "test", now.plusSeconds(86400), 10, 60).granted());
    }

    @Test void quotaOrAuthenticationFailureStopsAllFurtherRequestsForTheDay() {
        budget.reserve("a".repeat(64), "test", now, 10, 60);
        budget.finish("a".repeat(64), "RATE_LIMITED", "{}");
        db.update("UPDATE gemini_review_cache SET updated_at=?", java.sql.Timestamp.from(now));
        assertEquals("PROVIDER_PAUSED", budget.reserve("b".repeat(64), "test", now.plusSeconds(61), 10, 60).reason());
    }
}
