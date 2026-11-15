package org.jibro.collection.review.followup;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jibro.collection.extraction.ExtractionModels.Source;

/** Fetched official evidence and the explicit relationship that permits comparing it. */
public final class OfficialFollowupModels {
    private OfficialFollowupModels() {}

    public record Target(UUID noticeId, int revision, String primaryUrl, String title, LocalDate publishedDate) {}

    public record Related(String kind, String url, String title, LocalDate publishedDate,
                          String relationshipProof, List<Source> sources) {
        public Related { sources = List.copyOf(sources); }
    }

    /** Blockers describe unfinished machine work, never an invented agency contradiction. */
    public record Discovery(Instant checkedAt, List<Related> related, List<String> blockers) {
        public Discovery { related = List.copyOf(related); blockers = List.copyOf(blockers); }
        public boolean complete() { return blockers.isEmpty(); }
    }
}
