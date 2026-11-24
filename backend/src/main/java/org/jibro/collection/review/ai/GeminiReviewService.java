package org.jibro.collection.review.ai;

import java.time.Instant;
import java.util.List;

import org.jibro.collection.review.local.CompactReviewPacket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import static org.jibro.collection.review.ai.GeminiReviewModels.*;
import static org.jibro.collection.review.ai.ScopedReviewModels.*;

@Service
public class GeminiReviewService {
    private final GeminiReviewClient client;
    private final GeminiBudgetRepository budget;
    private final ObjectMapper json;
    private final String model;
    private final int daily, interval;
    private final boolean enabled;

    public GeminiReviewService(GeminiReviewClient client, GeminiBudgetRepository budget, ObjectMapper json,
                               @Value("${GEMINI_MODEL:}") String model, @Value("${GEMINI_REVIEW_DAILY_LIMIT:10}") int daily,
                               @Value("${GEMINI_REVIEW_INTERVAL_SECONDS:60}") int interval,
                               @Value("${app.collection.gemini-enabled:false}") boolean enabled) {
        this.client = client;
        this.budget = budget;
        this.json = json;
        this.model = model;
        this.daily = daily;
        this.interval = interval;
        this.enabled = enabled;
    }

    public ScopedResult reviewPacket(CompactReviewPacket packet) {
        if (!enabled || !client.configured()) return result(Status.DISABLED, "GEMINI_NOT_CONFIGURED");
        if (!GeminiReviewClient.validPacket(packet))
            return result(Status.INPUT_REJECTED, "GEMINI_PACKET_LIMIT_OR_INVALID");
        String hash = packet.cacheKey(model);
        var reservation = budget.reserve(hash, model, Instant.now(), daily, interval);
        if (reservation.cached() != null) {
            try {
                var cached = json.readValue(reservation.cached(), ScopedResult.class);
                if (cached == null || cached.status() == null || cached.code() == null || cached.code().isBlank() || cached.usage() == null)
                    return result(Status.INVALID_OUTPUT, "GEMINI_CACHED_RESULT_INVALID");
                if (cached.status() == Status.CANDIDATE && !ScopedReviewValidator.valid(packet, cached.decisions()))
                    return result(Status.INVALID_OUTPUT, "GEMINI_CACHED_EVIDENCE_INVALID");
                return cached;
            } catch (RuntimeException invalid) {
                return result(Status.INVALID_OUTPUT, "GEMINI_CACHED_RESULT_INVALID");
            }
        }
        if (!reservation.granted()) return result(Status.RATE_LIMITED, reservation.reason());
        var result = client.reviewPacket(packet);
        budget.finish(hash, result.status().name(), json.writeValueAsString(result));
        return result;
    }

    private static ScopedResult result(Status status, String code) {
        return new ScopedResult(status, code, List.of(), new Usage(0, 0));
    }
}
