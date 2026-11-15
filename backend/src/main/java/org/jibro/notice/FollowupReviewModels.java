package org.jibro.notice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jibro.notice.HousingModels.Content;

/** Internal worker handoff. No controller accepts caller-created evidence or publication receipts. */
public final class FollowupReviewModels {
    private FollowupReviewModels() {}

    public record Job(UUID runId, UUID noticeId, int revision, String sourceFingerprint,
                      String contentFingerprint, UUID leaseToken) {}
    public record Primary(AutomaticReviewRepository.ExtractionSnapshot snapshot, Content originalContent) {}
    public record Receipt(UUID runId, UUID leaseToken, String bundleHash) {}
    public record Decision(String state, Content content, LocalDate startDate, LocalDate endDate,
                           List<String> blockers) {
        public Decision { blockers = blockers == null ? List.of() : List.copyOf(blockers); }
        public static Decision ready(Content content, LocalDate start, LocalDate end) {
            return new Decision("READY", content, start, end, List.of());
        }
        public static Decision reviewRequired(List<String> reasons) {
            return new Decision("REVIEW_REQUIRED", null, null, null, reasons);
        }
        public static Decision unchanged() { return new Decision("UNCHANGED", null, null, null, List.of()); }
    }
    public record Staged(Job job, Primary primary, String bundleHash, Instant checkedAt,
                         Decision decision, String sourcesJson) {}
    public record Outcome(String state, boolean changed, int revision) {}
}
