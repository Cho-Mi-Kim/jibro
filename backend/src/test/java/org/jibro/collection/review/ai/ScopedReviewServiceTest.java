package org.jibro.collection.review.ai;

import java.time.Instant;
import java.util.*;

import org.jibro.collection.review.local.CompactReviewPacket;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.jibro.collection.review.ai.GeminiReviewModels.*;
import static org.jibro.collection.review.ai.ScopedReviewModels.*;
import static org.jibro.collection.review.local.CompactReviewPacket.*;
import static org.junit.jupiter.api.Assertions.*;

class ScopedReviewServiceTest {
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String URL = "https://apply.lh.or.kr/notice.pdf";
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void reusesTheSameScopedResultAcrossServiceInstancesWithoutAnotherProviderCall() {
        var client = new FakeClient();
        var budget = new MemoryBudget();
        var packet = GeminiReviewClientTest.packet(URL, "예외에 해당하면 별도 기준을 확인합니다.");
        var first = service(client, budget, MODEL, true).reviewPacket(packet);
        var cached = service(client, budget, MODEL, true).reviewPacket(packet);
        assertEquals(Status.CANDIDATE, first.status());
        assertEquals(first, cached);
        assertEquals(1, client.calls);
        assertEquals(1, budget.finishes);
        assertEquals(1, budget.saved.size());
        assertEquals("CANDIDATE", budget.lastStatus);
        assertEquals(10, budget.lastDaily);
        assertEquals(60, budget.lastInterval);
    }

    @Test
    void sourceContentHashIssueClosurePolicyAndModelChangesCannotReuseAnOldDecision() {
        var client = new FakeClient();
        var budget = new MemoryBudget();
        var packet = GeminiReviewClientTest.packet(URL, "공통 기준");
        var service = service(client, budget, MODEL, true);
        service.reviewPacket(packet);
        service.reviewPacket(GeminiReviewClientTest.packet(URL, "변경된 기준"));
        service.reviewPacket(new CompactReviewPacket(packet.id(), packet.policy(),
                List.of(new SourceRef("s1", URL, "b".repeat(64))), packet.clauses(), packet.facts(), packet.issues()));
        service.reviewPacket(new CompactReviewPacket(packet.id(), packet.policy(), packet.sources(), packet.clauses(),
                packet.facts(), List.of(new Issue("i1", "EXCEPTION_SCOPE", List.of("c1")))));
        service.reviewPacket(new CompactReviewPacket(packet.id(), packet.policy() + "-new", packet.sources(),
                packet.clauses(), packet.facts(), packet.issues()));
        service(client, budget, "gemini-other-model", true).reviewPacket(packet);
        assertEquals(6, client.calls);
        assertEquals(6, budget.saved.size());
    }

    @Test
    void invalidOrDisabledInputConsumesNoReservationAndDoesNotCallTheProvider() {
        var client = new FakeClient();
        var budget = new MemoryBudget();
        var packet = GeminiReviewClientTest.packet(URL, "공통 기준");
        assertEquals(Status.DISABLED, service(client, budget, MODEL, false).reviewPacket(packet).status());
        client.available = false;
        assertEquals(Status.DISABLED, service(client, budget, MODEL, true).reviewPacket(packet).status());
        client.available = true;
        assertEquals(Status.INPUT_REJECTED, service(client, budget, MODEL, true).reviewPacket(null).status());
        assertEquals(Status.INPUT_REJECTED, service(client, budget, MODEL, true)
                .reviewPacket(GeminiReviewClientTest.packet(URL, "x".repeat(6001))).status());
        assertEquals(0, budget.reservations);
        assertEquals(0, budget.finishes);
        assertEquals(0, client.calls);
    }

    @Test
    void deniedBudgetIsReturnedWithoutRetryingOrOverwritingAnotherAttempt() {
        for (String reason : List.of("RATE_WAIT", "DAILY_LIMIT", "IN_FLIGHT", "INTERRUPTED_REVIEW", "PROVIDER_PAUSED")) {
            var client = new FakeClient();
            var budget = new MemoryBudget();
            budget.denied = reason;
            var result = service(client, budget, MODEL, true).reviewPacket(GeminiReviewClientTest.packet(URL, "공통 기준"));
            assertEquals(Status.RATE_LIMITED, result.status());
            assertEquals(reason, result.code());
            assertEquals(1, budget.reservations);
            assertEquals(0, budget.finishes);
            assertEquals(0, client.calls);
        }
    }

    @Test
    void cachedMalformedOrUnrelatedDecisionsAreRejectedWithoutSpendingMoreQuota() {
        var invalidDecision = new ScopedResult(Status.CANDIDATE, "GEMINI_SCOPED_CANDIDATE_RECEIVED",
                List.of(new Decision("different-issue", Disposition.NO_CONFLICT, List.of("c1"))), new Usage(1, 1));
        for (String corrupt : List.of("not JSON", "{\"status\":\"CANDIDATE\",\"candidate\":{\"complete\":true}}",
                "{\"status\":null,\"code\":\"cached\",\"decisions\":[],\"usage\":{\"inputTokens\":0,\"outputTokens\":0}}",
                json.writeValueAsString(invalidDecision))) {
            var client = new FakeClient();
            var budget = new MemoryBudget();
            budget.overrideCache = corrupt;
            var result = service(client, budget, MODEL, true).reviewPacket(GeminiReviewClientTest.packet(URL, "공통 기준"));
            assertEquals(Status.INVALID_OUTPUT, result.status());
            assertTrue(result.decisions().isEmpty());
            assertEquals(0, client.calls);
            assertEquals(0, budget.finishes);
        }
    }

    @Test
    void providerFailureIsSavedOnceAndReusedWithoutSilentRetries() {
        var client = new FakeClient();
        client.failure = new ScopedResult(Status.RATE_LIMITED, "GEMINI_RATE_LIMITED", List.of(), new Usage(0, 0));
        var budget = new MemoryBudget();
        var packet = GeminiReviewClientTest.packet(URL, "공통 기준");
        var service = service(client, budget, MODEL, true);
        assertEquals(Status.RATE_LIMITED, service.reviewPacket(packet).status());
        assertEquals(Status.RATE_LIMITED, service.reviewPacket(packet).status());
        assertEquals(1, client.calls);
        assertEquals(1, budget.finishes);
        assertEquals("RATE_LIMITED", budget.lastStatus);
    }

    private GeminiReviewService service(FakeClient client, MemoryBudget budget, String model, boolean enabled) {
        return new GeminiReviewService(client, budget, json, model, 10, 60, enabled);
    }

    private static final class FakeClient extends GeminiReviewClient {
        int calls;
        boolean available = true;
        ScopedResult failure;

        FakeClient() {
            super("test-key", MODEL, new JsonMapper(), request -> {
                throw new AssertionError("Network must not run");
            });
        }

        @Override
        public boolean configured() {
            return available;
        }

        @Override
        public ScopedResult reviewPacket(CompactReviewPacket packet) {
            calls++;
            if (failure != null) return failure;
            return new ScopedResult(Status.CANDIDATE, "GEMINI_SCOPED_CANDIDATE_RECEIVED", packet.issues().stream()
                    .map(issue -> new Decision(issue.id(), Disposition.UNRESOLVED, List.of(issue.clauseIds().get(0)))).toList(), new Usage(20, 10));
        }
    }

    /**
     * In-memory persistence test double; production quota/locking SQL has separate integration tests.
     */
    private static final class MemoryBudget extends GeminiBudgetRepository {
        final Map<String, String> saved = new HashMap<>();
        int reservations, finishes, lastDaily, lastInterval;
        String denied, overrideCache, lastStatus;

        MemoryBudget() {
            super(null);
        }

        @Override
        public Reservation reserve(String hash, String model, Instant now, int daily, int interval) {
            reservations++;
            lastDaily = daily;
            lastInterval = interval;
            if (overrideCache != null) return new Reservation(false, overrideCache, "CACHED");
            if (saved.containsKey(hash)) return new Reservation(false, saved.get(hash), "CACHED");
            return denied == null ? new Reservation(true, null, "RESERVED") : new Reservation(false, null, denied);
        }

        @Override
        public void finish(String hash, String status, String result) {
            finishes++;
            lastStatus = status;
            saved.put(hash, result);
        }
    }
}
