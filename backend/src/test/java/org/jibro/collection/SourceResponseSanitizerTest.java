package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SourceResponseSanitizerTest {
    final ObjectMapper json = new ObjectMapper();

    @Test
    void preservesOriginalFieldsButRemovesCredentialsBeforeHashingOrStoring() throws Exception {
        String body = """
                {"SS_CODE":"Y","ALL_CNT":0,"unknownField":"keep this","ServiceKey":"private",
                 "nested":{"password":"other-password","message":"sample+/key or sample%2B%2Fkey or sample%2b%2fkey",
                 "url":"https://example.invalid/?access_token=secret&status=ok"}}
                """;
        var safe = new SourceResponseSanitizer(json, "sample+/key").snapshot(200, body, Instant.now());
        assertEquals("JSON", safe.bodyStatus());
        assertTrue(safe.redacted());
        assertFalse(safe.bodyJson().contains("sample"));
        assertFalse(safe.bodyJson().contains("other-password"));
        assertFalse(safe.bodyJson().contains("=secret"));
        assertEquals("keep this", json.readTree(safe.bodyJson()).path("unknownField").asText());
        assertEquals(safe.sha256(), new SourceResponseSanitizer(json, "").snapshot(200, safe.bodyJson(), Instant.now()).sha256());
        assertEquals(64, safe.sha256().length());
    }

    @Test
    void invalidAndOversizedBodiesAreNotRetained() {
        var sanitizer = new SourceResponseSanitizer(json, "private");
        for (String body : List.of("<html>private</html>", "\"private\"", "{")) {
            var response = sanitizer.snapshot(200, body, Instant.now());
            assertEquals("INVALID_JSON", response.bodyStatus());
            assertNull(response.bodyJson());
            assertNull(response.sha256());
        }
        var large = sanitizer.snapshot(200, "a".repeat(SourceResponseSanitizer.MAX_BYTES + 1), Instant.now());
        assertEquals("TOO_LARGE", large.bodyStatus());
        assertNull(large.bodyJson());
    }

    @Test
    void rejectedJsonRetainsEvidenceAndHttpRetryContract() {
        var client = new LhNoticeClient("private", json);
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        var rejection = assertThrows(SourceResponse.Rejected.class,
                () -> client.accept(429, "{\"error\":\"rate limited\",\"apiKey\":\"private\"}", "120", now));
        assertTrue(CollectionFailure.retryable(rejection));
        assertEquals(Duration.ofSeconds(120), CollectionFailure.providerDelay(rejection));
        assertEquals(429, rejection.response().status());
        assertFalse(rejection.response().bodyJson().contains("private"));
        var invalid = assertThrows(SourceResponse.Rejected.class, () -> client.accept(200, "{\"SS_CODE\":\"N\"}", null, now));
        assertFalse(CollectionFailure.retryable(invalid));
        assertEquals("JSON", invalid.response().bodyStatus());
    }

    @Test
    void httpSubscriberCancelsBeforeBufferingBeyondTheLimit() {
        var subscriber = new LimitedJsonSubscriber();
        var cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long count) {
            }

            public void cancel() {
                cancelled.set(true);
            }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[SourceResponseSanitizer.MAX_BYTES])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertTrue(cancelled.get());
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
