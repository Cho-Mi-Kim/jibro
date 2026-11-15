package org.jibro.collection;

import java.time.Instant;

/** Safe response evidence only. Request URLs, headers and credentials are never included. */
public record SourceResponse(int status, Instant receivedAt, String bodyStatus,
                             String bodyJson, String sha256, boolean redacted) {
    public static final class Rejected extends Exception {
        private final SourceResponse response;

        Rejected(SourceResponse response, Exception cause) {
            super("SOURCE_RESPONSE_REJECTED", cause);
            this.response = response;
        }

        public SourceResponse response() { return response; }
    }
}
