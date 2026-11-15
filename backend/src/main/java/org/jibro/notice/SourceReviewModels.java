package org.jibro.notice;

import java.time.*;
import java.util.*;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.HousingModels.Evidence;

/** An explicit, source-by-source editorial review; never represented as a human administrator decision. */
public final class SourceReviewModels {
    private SourceReviewModels() {}
    public record SourceReview(String url, String sha256, String label, String role, String reviewNotes) {}
    public record SupplementalReference(String url, String title, String sha256, String relation,
                                        List<String> attachmentUrls) {
        public SupplementalReference {
            attachmentUrls = attachmentUrls == null ? List.of() : List.copyOf(attachmentUrls);
        }
    }
    public record ConfirmedOpenEndedPeriod(String reason, Evidence evidence) {}
    public record Package(String packageId, UUID noticeId, int revision, UUID extractionId,
                          String disposition, String reviewNotes, List<String> unresolvedFindings,
                          List<SourceReview> sourceReviews,
                          Content content, LocalDate startDate, LocalDate endDate,
                          String applicationPeriodIssueId, List<SupplementalReference> supplementalReferences,
                          ConfirmedOpenEndedPeriod confirmedOpenEndedPeriod) {
        public Package {
            supplementalReferences = supplementalReferences == null ? List.of() : List.copyOf(supplementalReferences);
        }
        public Package(String packageId, UUID noticeId, int revision, UUID extractionId,
                       String disposition, String reviewNotes, List<String> unresolvedFindings,
                       List<SourceReview> sourceReviews, Content content, LocalDate startDate, LocalDate endDate) {
            this(packageId, noticeId, revision, extractionId, disposition, reviewNotes, unresolvedFindings,
                sourceReviews, content, startDate, endDate, null, List.of(), null);
        }
        public Package(String packageId, UUID noticeId, int revision, UUID extractionId,
                       String disposition, String reviewNotes, List<String> unresolvedFindings,
                       List<SourceReview> sourceReviews, Content content, LocalDate startDate, LocalDate endDate,
                       String applicationPeriodIssueId) {
            this(packageId, noticeId, revision, extractionId, disposition, reviewNotes, unresolvedFindings,
                sourceReviews, content, startDate, endDate, applicationPeriodIssueId, List.of(), null);
        }
    }
}
