package org.jibro.notice;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jibro.collection.review.ai.GeminiBudgetRepository;
import org.jibro.collection.review.ai.GeminiReviewModels;
import org.jibro.collection.review.ai.GuideReviewClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.jibro.notice.GuideContentReviewService.Status;
import static org.jibro.notice.GuideContentReviewValidatorTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuideContentReviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T01:00:00Z");
    private final JsonMapper json = JsonMapper.builder().build();
    private final GuideReviewClient client = mock(GuideReviewClient.class);
    private final GeminiBudgetRepository budget = mock(GeminiBudgetRepository.class);

    @Test
    void disabledOrUnconfiguredReviewNeverReservesGlobalBudget() {
        assertEquals(Status.DEFERRED, service(false).review(guide(OLD), List.of(source(OLD, NEW)), NOW).status());
        assertEquals(Status.DEFERRED, service(true).review(guide(OLD), List.of(source(OLD, NEW)), NOW).status());
        verifyNoInteractions(budget);
        verify(client, never()).review(any());
    }

    @Test
    void incompleteContextIsKeptWithoutCallingModelOrQuota() {
        assertEquals(Status.REVIEW_REQUIRED, service(true).review(guide(OLD), List.of(), NOW).status());
        verifyNoInteractions(client, budget);
    }

    @Test
    void reservesExistingGlobalCapAndPersistsProofBoundToAllCurrentHashes() {
        when(client.configured()).thenReturn(true);
        when(budget.reserve(anyString(), eq("gemini-test"), eq(NOW), eq(10), eq(60)))
            .thenReturn(new GeminiBudgetRepository.Reservation(true, null, "RESERVED"));
        when(client.review(anyString())).thenReturn(candidate(NEW));
        var result = service(true).review(guide(OLD), List.of(source(OLD, NEW)), NOW);
        assertEquals(Status.UPDATED, result.status());
        assertEquals(NEW, result.candidate().body());
        var proof = json.readTree(result.proofJson());
        assertEquals("a".repeat(64), proof.path("sources").get(0).path("sha256").asText());
        assertEquals(URL, proof.path("sources").get(0).path("url").asText());
        verify(budget).finish(anyString(), eq("CANDIDATE"), anyString());
        verify(client, times(1)).review(anyString());
    }

    @Test
    void cachedSuggestionIsRevalidatedRatherThanBypassingProofRules() {
        when(client.configured()).thenReturn(true);
        when(budget.reserve(anyString(), anyString(), any(), anyInt(), anyInt()))
            .thenReturn(new GeminiBudgetRepository.Reservation(false, json.writeValueAsString(candidate(NEW + " 지원금을 지급해요.")), "CACHED"));
        var result = service(true).review(guide(OLD), List.of(source(OLD, NEW)), NOW);
        assertEquals(Status.REVIEW_REQUIRED, result.status());
        assertNull(result.candidate());
        verify(client, never()).review(anyString());
    }

    @Test
    void quotaWaitAndProviderFailureNeverPublishOrExposeProviderErrors() {
        when(client.configured()).thenReturn(true);
        when(budget.reserve(anyString(), anyString(), any(), anyInt(), anyInt()))
            .thenReturn(new GeminiBudgetRepository.Reservation(false, null, "DAILY_LIMIT"));
        assertEquals("GUIDE_AI_DAILY_LIMIT", service(true).review(guide(OLD), List.of(source(OLD, NEW)), NOW).code());
        verify(client, never()).review(anyString());
        when(budget.reserve(anyString(), anyString(), any(), anyInt(), anyInt()))
            .thenReturn(new GeminiBudgetRepository.Reservation(true, null, "RESERVED"));
        when(client.review(anyString())).thenReturn(new GuideReviewClient.Result(GeminiReviewModels.Status.RATE_LIMITED, "GUIDE_AI_RATE_LIMITED", "", 0, 0));
        assertEquals(Status.DEFERRED, service(true).review(guide(OLD), List.of(source(OLD, NEW)), NOW).status());
    }

    @Test
    void retryKeyChangesOnNextKoreanDayButContentFingerprintDoesNot() {
        when(client.configured()).thenReturn(true);
        when(budget.reserve(anyString(), anyString(), any(), anyInt(), anyInt()))
            .thenReturn(new GeminiBudgetRepository.Reservation(false, null, "DAILY_LIMIT"));
        var service = service(true);
        var first = service.review(guide(OLD), List.of(source(OLD, NEW)), Instant.parse("2026-11-21T14:59:59Z"));
        var second = service.review(guide(OLD), List.of(source(OLD, NEW)), Instant.parse("2026-11-21T15:00:00Z"));
        var keys = ArgumentCaptor.forClass(String.class);
        verify(budget, times(2)).reserve(keys.capture(), anyString(), any(), anyInt(), anyInt());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }

    private GuideContentReviewService service(boolean enabled) {
        return new GuideContentReviewService(client, budget, json, "gemini-test", 10, 60, enabled);
    }

    private GuideReviewClient.Result candidate(String after) {
        return new GuideReviewClient.Result(GeminiReviewModels.Status.CANDIDATE, "GUIDE_AI_SUGGESTION",
            json.writeValueAsString(Map.of("decision", "UPDATED", "reason", "공식 상담 시간 변경", "patches", List.of(patch(OLD, after)), "evidence", List.of(proof(OLD, NEW)))), 12, 15);
    }
}
