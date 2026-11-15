package org.jibro.collection.review.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.jibro.collection.review.ai.GeminiReviewModels.*;
import static org.jibro.collection.review.ai.ScopedReviewModels.*;
import static org.jibro.collection.review.local.CompactReviewPacket.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.jibro.collection.review.local.CompactReviewPacket;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GeminiReviewClientTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String URL = "https://apply.lh.or.kr/notice.pdf";
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String KEY = "unit-test-provider-key";

    @Test void sendsExactBoundedPacketWithHeaderOnlyKeyAndReturnsAdvisoryDecision() throws Exception {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var packet = packet(URL, "신청자는 무주택자이어야 합니다.");
        var result = client(request -> { sent.set(request); return response(decisions("NO_CONFLICT", "c1"), "STOP"); }).reviewPacket(packet);
        assertEquals(Status.CANDIDATE, result.status());
        assertEquals(Disposition.NO_CONFLICT, result.decisions().get(0).disposition());
        assertEquals(21, result.usage().inputTokens());
        assertEquals(34, result.usage().outputTokens());
        var request = sent.get();
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent", request.uri().toString());
        assertEquals(KEY, request.headers().firstValue("x-goog-api-key").orElseThrow());
        assertEquals(Duration.ofSeconds(30), request.timeout().orElseThrow());
        assertNull(request.uri().getQuery());
        String body = requestBody(request);
        assertFalse(body.contains(KEY));
        var payload = json.readTree(body);
        assertEquals(2048, payload.path("generationConfig").path("maxOutputTokens").asInt());
        assertEquals("application/json", payload.path("generationConfig").path("responseMimeType").asText());
        assertEquals("object", payload.path("generationConfig").path("responseJsonSchema").path("type").asText());
        assertEquals(packet.payloadJson(), payload.path("contents").get(0).path("parts").get(0).path("text").asText());
        assertFalse(payload.path("generationConfig").path("responseJsonSchema").path("properties").has("complete"));
    }

    @Test void missingKeyOrModelNeverCallsProviderOrChoosesFallback() {
        GeminiReviewClient.Transport noCall = request -> { fail("Disabled provider called"); return null; };
        var packet = packet(URL, "신청 조건");
        assertEquals(Status.DISABLED, new GeminiReviewClient("", MODEL, json, noCall).reviewPacket(packet).status());
        assertEquals(Status.DISABLED, new GeminiReviewClient(KEY, "", json, noCall).reviewPacket(packet).status());
        assertEquals(Status.DISABLED, new GeminiReviewClient(KEY, "gemini-x/../../other", json, noCall).reviewPacket(packet).status());
        assertEquals(Status.DISABLED, new GeminiReviewClient("header\ninjection", MODEL, json, noCall).reviewPacket(packet).status());
    }

    @Test void rejectsOversizedOrUntrustedPacketsWithoutTruncatingOrCallingProvider() {
        var client = client(request -> { fail("Invalid input called provider"); return null; });
        assertEquals(Status.INPUT_REJECTED, client.reviewPacket(packet(URL, "a".repeat(6001))).status());
        // Escaping can exceed the wire limit even when the raw quote is shorter.
        assertEquals(Status.INPUT_REJECTED, client.reviewPacket(packet(URL, "\\".repeat(3001))).status());
        assertEquals(Status.INPUT_REJECTED, client.reviewPacket(packet(URL, "  \n")).status());
        assertEquals(Status.INPUT_REJECTED, client.reviewPacket(packet("https://apply.lh.or.kr.evil.example/file", "public text")).status());
        assertEquals(Status.INPUT_REJECTED, client.reviewPacket(packet("https://apply.lh.or.kr/file?ServiceKey=hidden", "public text")).status());
        assertEquals(Status.INPUT_REJECTED, client.reviewPacket(null).status());
        var good = packet(URL, "public text");
        var incomplete = new CompactReviewPacket(good.id(), good.policy(), good.sources(), good.clauses(), good.facts(),
                List.of(new Issue("i1", "EXCEPTION_SCOPE", List.of("not-in-packet"))));
        assertEquals(Status.INPUT_REJECTED, client.reviewPacket(incomplete).status());
        for (var issue : List.of(new Issue("i1", "EXCEPTION_SCOPE", List.of("c1", "c1")),
                new Issue("i 1", "EXCEPTION_SCOPE", List.of("c1")),
                new Issue("i".repeat(161), "EXCEPTION_SCOPE", List.of("c1")))) {
            assertEquals(Status.INPUT_REJECTED, client.reviewPacket(new CompactReviewPacket(good.id(), good.policy(),
                    good.sources(), good.clauses(), good.facts(), List.of(issue))).status());
        }
    }

    @Test void classifiesHttpFailuresWithoutReturningRawErrorOrRetrying() {
        for (int status : List.of(301, 400, 401, 403, 429, 500)) {
            AtomicInteger calls = new AtomicInteger();
            var result = client(request -> { calls.incrementAndGet(); return new GeminiReviewClient.Response(status, ("private error " + KEY).getBytes(StandardCharsets.UTF_8)); })
                    .reviewPacket(packet(URL, "신청 조건"));
            assertEquals(1, calls.get());
            assertEquals(status == 401 || status == 403 ? Status.AUTH_ERROR : status == 429 ? Status.RATE_LIMITED : Status.PROVIDER_ERROR, result.status());
            assertFalse(result.toString().contains(KEY));
            assertTrue(result.decisions().isEmpty());
        }
    }

    @Test void rejectsTokenTruncationAndSafetyStops() {
        for (String reason : List.of("MAX_TOKENS", "SAFETY", "CONTINUATION", "")) {
            var result = client(request -> response(decisions("NO_CONFLICT", "c1"), reason)).reviewPacket(packet(URL, "신청 조건"));
            assertEquals(Status.INVALID_OUTPUT, result.status());
            assertEquals("GEMINI_INCOMPLETE_OUTPUT", result.code());
        }
    }

    @Test void preservesUnresolvedOrConflictingIssuesWithoutApprovingTheNotice() {
        for (String disposition : List.of("CONFIRMED_CONFLICT", "UNRESOLVED")) {
            var result = client(request -> response(decisions(disposition, "c1", "c2"), "STOP")).reviewPacket(packet(URL, "신청 조건"));
            assertEquals(Status.CANDIDATE, result.status());
            assertEquals(Disposition.valueOf(disposition), result.decisions().get(0).disposition());
        }
    }

    @Test void rejectsMalformedSchemaInventedEvidenceAndInsufficientConflictProof() {
        assertEquals(Status.INVALID_OUTPUT, client(request -> new GeminiReviewClient.Response(200, "not JSON".getBytes(StandardCharsets.UTF_8)))
                .reviewPacket(packet(URL, "신청 조건")).status());
        for (Map<String, Object> value : List.of(Map.<String, Object>of("complete", true),
                decisions("PASS", "c1"), decisions("NO_CONFLICT", "invented"),
                decisions("CONFIRMED_CONFLICT", "c1"), decisions("CONFIRMED_CONFLICT", "c1", "c1"),
                Map.<String, Object>of("decisions", List.of(Map.of("issueId", "invented", "disposition", "NO_CONFLICT", "evidenceClauseIds", List.of("c1")))))) {
            assertEquals(Status.INVALID_OUTPUT, client(request -> response(value, "STOP")).reviewPacket(packet(URL, "신청 조건")).status());
        }
    }

    @Test void rejectsMissingCandidatesAndThoughtOnlyOutput() {
        assertEquals(Status.INVALID_OUTPUT, client(request -> raw(Map.of("promptFeedback", Map.of("blockReason", "SAFETY"))))
                .reviewPacket(packet(URL, "신청 조건")).status());
        var thought = Map.of("candidates", List.of(Map.of("finishReason", "STOP", "content", Map.of("parts", List.of(Map.of("text", json.writeValueAsString(decisions("NO_CONFLICT", "c1")), "thought", true))))));
        assertEquals(Status.INVALID_OUTPUT, client(request -> raw(thought)).reviewPacket(packet(URL, "신청 조건")).status());
    }

    @Test void reportsTimeoutAndTransportFailureWithoutLeakingExceptionText() {
        assertEquals(Status.TIMEOUT, client(request -> { throw new HttpTimeoutException(KEY); }).reviewPacket(packet(URL, "신청 조건")).status());
        var failed = client(request -> { throw new IOException(KEY); }).reviewPacket(packet(URL, "신청 조건"));
        assertEquals(Status.PROVIDER_ERROR, failed.status());
        assertFalse(failed.toString().contains(KEY));
    }

    @Test void interruptionIsRestoredAndNoRetryPerformed() {
        try {
            var result = client(request -> { throw new InterruptedException(KEY); }).reviewPacket(packet(URL, "신청 조건"));
            assertEquals("GEMINI_INTERRUPTED", result.code());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void rejectsExcessiveProviderBodyAndBoundedSubscriberCancelsDuringDownload() {
        var bytes = new byte[GeminiResponseSubscriber.MAX_BYTES + 1];
        assertEquals("GEMINI_RESPONSE_TOO_LARGE", client(request -> new GeminiReviewClient.Response(200, bytes)).reviewPacket(packet(URL, "신청 조건")).code());
        var subscriber = new GeminiResponseSubscriber();
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() { public void request(long count) {} public void cancel() { cancelled.set(true); } });
        subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
        assertTrue(cancelled.get());
        assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
        var wrapped = client(request -> { throw new IOException(new GeminiResponseSubscriber.TooLarge()); }).reviewPacket(packet(URL, "신청 조건"));
        assertEquals("GEMINI_RESPONSE_TOO_LARGE", wrapped.code());
    }

    static CompactReviewPacket packet(String url, String quote) {
        return new CompactReviewPacket("packet-fixture", "local-compact-v1/local-rules-v1",
                List.of(new SourceRef("s1", url, "a".repeat(64))),
                List.of(clause("c1", quote), clause("c2", "예외 조건은 원문을 확인합니다.")), List.of(),
                List.of(new Issue("i1", "EXCEPTION_SCOPE", List.of("c1", "c2"))));
    }

    private static EvidenceClause clause(String id, String quote) {
        return new EvidenceClause(id, "s1", "1쪽", 0, quote.length(), quote, "신청 조건", List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private GeminiReviewClient client(GeminiReviewClient.Transport transport) { return new GeminiReviewClient(KEY, MODEL, json, transport); }
    private Map<String, Object> decisions(String disposition, String... evidence) {
        return Map.of("decisions", List.of(Map.of("issueId", "i1", "disposition", disposition, "evidenceClauseIds", List.of(evidence))));
    }
    private GeminiReviewClient.Response response(Object decision, String reason) {
        return raw(Map.of("candidates", List.of(Map.of("finishReason", reason, "content", Map.of("parts", List.of(Map.of("text", json.writeValueAsString(decision)))))),
                "usageMetadata", Map.of("promptTokenCount", 21, "candidatesTokenCount", 34)));
    }
    private GeminiReviewClient.Response raw(Object value) { return new GeminiReviewClient.Response(200, json.writeValueAsBytes(value)); }
    private static String requestBody(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { var chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk); }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.get(2, TimeUnit.SECONDS);
    }
}
