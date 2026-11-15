package org.jibro.member;

/**
 * Provider acceptance is different from receipt on a user's device.
 */
final class PushDeliveryPolicy {
    enum Outcome {ACCEPTED, EXPIRED, RETRY, REJECTED}

    private PushDeliveryPolicy() {
    }

    static Outcome outcome(int status) {
        if (status >= 200 && status < 300) return Outcome.ACCEPTED;
        if (status == 404 || status == 410) return Outcome.EXPIRED;
        if (status == 408 || status == 429 || status >= 500) return Outcome.RETRY;
        return Outcome.REJECTED;
    }
}
