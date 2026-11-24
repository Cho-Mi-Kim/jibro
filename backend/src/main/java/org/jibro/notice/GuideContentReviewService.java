package org.jibro.notice;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.jibro.collection.review.ai.GeminiBudgetRepository;
import org.jibro.collection.review.ai.GeminiReviewModels;
import org.jibro.collection.review.ai.GuideReviewClient;
import org.jibro.notice.HousingModels.Guide;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class GuideContentReviewService {
    public enum Status {CONFIRMED, UPDATED, REVIEW_REQUIRED, DEFERRED, FAILED}

    public record SourceChange(String sourceId, String url, String sha256, String previousText, String currentText) {
    }

    public record Result(Status status, String code, Guide candidate, String fingerprint, String proofJson) {
    }

    private final GuideReviewClient client;
    private final GeminiBudgetRepository budget;
    private final ObjectMapper json;
    private final String model;
    private final int daily, interval;
    private final boolean enabled;

    public GuideContentReviewService(GuideReviewClient client, GeminiBudgetRepository budget, ObjectMapper json,
                                     @Value("${GEMINI_MODEL:}") String model,
                                     @Value("${GEMINI_REVIEW_DAILY_LIMIT:10}") int daily,
                                     @Value("${GEMINI_REVIEW_INTERVAL_SECONDS:60}") int interval,
                                     @Value("${app.guide-refresh.ai-enabled:${app.collection.gemini-enabled:false}}") boolean enabled) {
        this.client = client;
        this.budget = budget;
        this.json = json;
        this.model = model;
        this.daily = daily;
        this.interval = interval;
        this.enabled = enabled;
    }

    public Result review(Guide current, List<SourceChange> sources, Instant now) {
        final GuideContentReviewPlan plan;
        try {
            plan = GuideContentReviewPlan.create(current, sources, json);
        } catch (RuntimeException insufficient) {
            return result(Status.REVIEW_REQUIRED, "GUIDE_REVIEW_CONTEXT_INCOMPLETE", "");
        }
        String fingerprint = plan.fingerprint(model);
        if (!enabled || !client.configured()) return result(Status.DEFERRED, "GUIDE_AI_NOT_CONFIGURED", fingerprint);
        // Transient provider failures remain cached for this day, then can be retried within the same global cap.
        String attempt = GuideContentReviewPlan.digest("guide-review-attempt\n" + fingerprint + "\n"
            + now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
        var reservation = budget.reserve(attempt, model, now, daily, interval);
        GuideReviewClient.Result reviewed;
        if (reservation.cached() != null) {
            try {
                reviewed = json.readValue(reservation.cached(), GuideReviewClient.Result.class);
                if (reviewed == null || reviewed.status() == null || reviewed.code() == null)
                    return result(Status.REVIEW_REQUIRED, "GUIDE_AI_CACHED_RESULT_INVALID", fingerprint);
            } catch (RuntimeException invalid) {
                return result(Status.REVIEW_REQUIRED, "GUIDE_AI_CACHED_RESULT_INVALID", fingerprint);
            }
        } else {
            if (!reservation.granted()) return result(Status.DEFERRED, "GUIDE_AI_" + reservation.reason(), fingerprint);
            reviewed = client.review(plan.payloadJson());
            budget.finish(attempt, reviewed.status().name(), json.writeValueAsString(reviewed));
        }
        if (reviewed.status() != GeminiReviewModels.Status.CANDIDATE) {
            Status status = switch (reviewed.status()) {
                case DISABLED, RATE_LIMITED, AUTH_ERROR, TIMEOUT, PROVIDER_ERROR -> Status.DEFERRED;
                default -> Status.REVIEW_REQUIRED;
            };
            return result(status, reviewed.code(), fingerprint);
        }
        try {
            var validated = GuideContentReviewValidator.validate(plan, reviewed.contentJson(), json);
            return new Result(validated.status(), "GUIDE_" + validated.status().name(), validated.candidate(), fingerprint,
                json.writeValueAsString(java.util.Map.of("policy", GuideContentReviewPlan.POLICY,
                    "sources", plan.sources().stream().map(source -> java.util.Map.of("id", source.sourceId(), "url", source.url(), "sha256", source.sha256())).toList(),
                    "review", json.readTree(reviewed.contentJson()))));
        } catch (RuntimeException invalid) {
            return result(Status.REVIEW_REQUIRED, "GUIDE_AI_PROOF_OR_SCOPE_INVALID", fingerprint);
        }
    }

    private static Result result(Status status, String code, String fingerprint) {
        return new Result(status, code, null, fingerprint, "");
    }
}
