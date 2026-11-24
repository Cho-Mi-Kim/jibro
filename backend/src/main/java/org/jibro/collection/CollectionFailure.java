package org.jibro.collection;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import javax.net.ssl.SSLException;

/**
 * Only transport failures and explicit provider throttling/server failures are retryable.
 */
public final class CollectionFailure extends IOException {
    private final boolean retryable;
    private final Duration retryAfter;

    public CollectionFailure(boolean retryable, Duration retryAfter) {
        super(retryable ? "SOURCE_TEMPORARILY_UNAVAILABLE" : "SOURCE_REQUEST_REJECTED");
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public static CollectionFailure http(int status, String retryAfter, Instant now) {
        return new CollectionFailure(status == 429 || status >= 500 && status <= 599, parseRetryAfter(retryAfter, now));
    }

    public static boolean retryable(Throwable error) {
        if (error instanceof SourceResponse.Rejected rejected) return retryable(rejected.getCause());
        if (error instanceof CollectionFailure failure) return failure.retryable;
        if (error instanceof SSLException || error instanceof InterruptedException) return false;
        return error instanceof IOException || error instanceof HttpTimeoutException;
    }

    public static Duration delay(Throwable error, int retriesUsed) {
        if (error instanceof SourceResponse.Rejected rejected) return delay(rejected.getCause(), retriesUsed);
        long seconds = switch (retriesUsed) {
            case 0 -> 60;
            case 1 -> 300;
            default -> 900;
        };
        Duration base = Duration.ofSeconds(seconds);
        if (error instanceof CollectionFailure failure && failure.retryAfter != null && failure.retryAfter.compareTo(base) > 0)
            return failure.retryAfter;
        return base;
    }

    public static Duration providerDelay(Throwable error) {
        if (error instanceof SourceResponse.Rejected rejected) return providerDelay(rejected.getCause());
        return error instanceof CollectionFailure failure && failure.retryable
                && failure.retryAfter != null && failure.retryAfter.compareTo(Duration.ZERO) > 0 ? failure.retryAfter : null;
    }

    static Duration parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        try {
            Duration result = value.trim().matches("\\d+") ? Duration.ofSeconds(Long.parseLong(value.trim()))
                    : Duration.between(now, ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            if (result.isNegative()) return Duration.ZERO;
            now.plus(result); // Reject values outside the supported instant range instead of overflowing the queue.
            return result;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
