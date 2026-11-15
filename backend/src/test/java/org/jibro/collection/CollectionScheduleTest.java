package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.*;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.annotation.Scheduled;

class CollectionScheduleTest {
    @Test
    void koreaScheduleRunsAtAllRequestedHoursAndNoOtherHours() throws Exception {
        var configured = CollectionScheduler.class.getMethod("scheduledHour").getAnnotation(Scheduled.class);
        assertEquals("Asia/Seoul", configured.zone());
        var cron = CronExpression.parse(configured.cron());
        var date = LocalDate.of(2026, 11, 6);
        var cursor = date.atStartOfDay(CollectionSchedule.ZONE).minusSeconds(1);
        for (int hour : List.of(0, 6, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)) {
            cursor = cron.next(cursor);
            assertNotNull(cursor);
            assertEquals(date.atTime(hour, 0).atZone(CollectionSchedule.ZONE), cursor);
        }
        assertEquals(date.plusDays(1).atStartOfDay(CollectionSchedule.ZONE), cron.next(cursor));
    }

    @Test
    void catchesOnlyLatestMissedSlotAndComputesNextAcrossMidnight() {
        Instant afternoon = ZonedDateTime.of(2026, 11, 6, 14, 47, 0, 0, CollectionSchedule.ZONE).toInstant();
        assertEquals(Instant.parse("2026-11-06T05:00:00Z"), CollectionSchedule.latest(afternoon));
        assertEquals(Instant.parse("2026-11-06T06:00:00Z"), CollectionSchedule.next(afternoon));
        Instant night = ZonedDateTime.of(2026, 11, 6, 23, 59, 0, 0, CollectionSchedule.ZONE).toInstant();
        assertEquals(Instant.parse("2026-11-06T09:00:00Z"), CollectionSchedule.latest(night));
        assertEquals(Instant.parse("2026-11-06T15:00:00Z"), CollectionSchedule.next(night));
        assertEquals(Instant.parse("2026-11-06T15:00:00Z"), CollectionSchedule.latest(Instant.parse("2026-11-06T15:00:00Z")));
    }

    @Test
    void retriesOnlyTransientFailuresAndDoesNotShortenProviderDelay() {
        Instant now = Instant.parse("2026-11-06T00:00:00Z");
        assertTrue(CollectionFailure.retryable(new IOException()));
        assertTrue(CollectionFailure.retryable(CollectionFailure.http(429, "120", now)));
        assertTrue(CollectionFailure.retryable(CollectionFailure.http(503, null, now)));
        assertFalse(CollectionFailure.retryable(CollectionFailure.http(403, null, now)));
        assertFalse(CollectionFailure.retryable(new IllegalArgumentException("invalid response")));
        assertFalse(CollectionFailure.retryable(new SSLHandshakeException("invalid TLS")));
        assertFalse(CollectionFailure.retryable(new InterruptedException()));
        assertEquals(Duration.ofMinutes(1), CollectionFailure.delay(new IOException(), 0));
        assertEquals(Duration.ofMinutes(5), CollectionFailure.delay(new IOException(), 1));
        assertEquals(Duration.ofMinutes(15), CollectionFailure.delay(new IOException(), 2));
        assertEquals(Duration.ofMinutes(2), CollectionFailure.delay(CollectionFailure.http(429, "120", now), 0));
        assertEquals(Duration.ofMinutes(3), CollectionFailure.parseRetryAfter("Fri, 06 Nov 2026 00:03:00 GMT", now));
        assertEquals(Duration.ofHours(48), CollectionFailure.delay(CollectionFailure.http(429, "172800", now), 0));
        assertEquals(Duration.ofHours(48), CollectionFailure.providerDelay(CollectionFailure.http(503, "172800", now)));
        assertNull(CollectionFailure.providerDelay(CollectionFailure.http(401, "172800", now)));
        assertNull(CollectionFailure.providerDelay(new IOException()));
        assertEquals(Duration.ofSeconds(99999999), CollectionFailure.parseRetryAfter("99999999", now));
        assertNull(CollectionFailure.parseRetryAfter(Long.toString(Long.MAX_VALUE), now));
        assertNull(CollectionFailure.parseRetryAfter("invalid", now));
    }
}
