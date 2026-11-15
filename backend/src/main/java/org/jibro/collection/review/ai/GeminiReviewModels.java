package org.jibro.collection.review.ai;

/** Provider status and measured resource usage; never publication decisions. */
public final class GeminiReviewModels {
    private GeminiReviewModels() {}

    public record Usage(int inputTokens, int outputTokens) {}
    public enum Status { CANDIDATE, DISABLED, INPUT_REJECTED, AUTH_ERROR, RATE_LIMITED, PROVIDER_ERROR, INVALID_OUTPUT, TIMEOUT }
}
