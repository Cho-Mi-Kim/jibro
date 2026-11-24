package org.jibro.collection.extraction;

import java.time.Instant;
import java.util.List;

public final class ExtractionModels {
    private ExtractionModels() {
    }

    public record Part(String location, String text) {
    }

    public record Link(String label, String url) {
    }

    public record Excerpt(String category, String text, String location, String url) {
    }

    public record Source(String label, String url, String sha256, String status, String message,
                         List<Excerpt> excerpts, List<Part> context) {
        public Source {
            context = context == null ? List.of() : List.copyOf(context);
        }

        public Source(String label, String url, String sha256, String status, String message, List<Excerpt> excerpts) {
            this(label, url, sha256, status, message, excerpts, List.of());
        }
    }

    public record Draft(String id, String noticeId, int revision, String status, Instant checkedAt,
                        String message, List<Source> sources) {
    }
}
