package org.jibro.notice;

import java.time.LocalDate;
import java.util.regex.Pattern;

import org.jibro.notice.SourceReviewModels.Package;

/**
 * Explicitly reviewed content distinguishes unknown dates from a verified, condition-based closing.
 */
public final class ReviewedApplicationPeriod {
    private static final Pattern UNCERTAIN = Pattern.compile("확인\\s*필요|미\\s*확정|미\\s*정");
    private static final Pattern SEPARATE_PROCEDURE = Pattern.compile("서류|계약|발표|면접|동호");
    private static final Pattern TIME_NOTE = Pattern.compile("\\((?:오[전후]\\s*)?\\d{1,2}(?::\\d{2}|시)[^)]*\\)|\\(조기\\s*중단[^)]*\\)");

    private ReviewedApplicationPeriod() {
    }

    public static void validate(Package review) {
        boolean missing = review.startDate() == null || review.endDate() == null;
        String issueId = review.applicationPeriodIssueId();
        if (review.confirmedOpenEndedPeriod() != null) {
            confirmedOpenEnded(review);
        } else if (missing) {
            if (!"PARTIAL".equals(review.disposition()) || issueId == null
                    || review.content().reviewIssues().stream().noneMatch(issue -> issue.id().equals(issueId)
                    && issue.area().equals("SCHEDULE") && issue.questionIds().isEmpty() && issue.documentIds().isEmpty()))
                throw new IllegalArgumentException("REVIEWED_APPLICATION_PERIOD_ISSUE_REQUIRED");
        } else {
            if (issueId != null) throw new IllegalArgumentException("APPLICATION_PERIOD_ISSUE_WITH_CONFIRMED_DATES");
            if (review.startDate().isAfter(review.endDate())) throw new IllegalArgumentException("GUIDANCE_INCOMPLETE");
        }
        boundary(review, review.startDate(), false);
        boundary(review, review.endDate(), true);
    }

    /**
     * The verifier separately binds this evidence to a freshly verified official source.
     */
    private static void confirmedOpenEnded(Package review) {
        var confirmed = review.confirmedOpenEndedPeriod();
        if (!java.util.Set.of("PASS", "PARTIAL").contains(review.disposition())
                || review.startDate() == null || review.endDate() != null
                || review.applicationPeriodIssueId() != null || confirmed.reason() == null
                || confirmed.reason().isBlank() || confirmed.reason().length() > 500
                || review.content() == null || review.content().summary() == null
                || !review.content().summary().contains(confirmed.reason())
                || confirmed.evidence() == null || confirmed.evidence().url() == null
                || confirmed.evidence().url().isBlank())
            throw new IllegalArgumentException("INVALID_CONFIRMED_OPEN_ENDED_PERIOD");
        EvidenceValidation.validate(confirmed.evidence());
    }

    private static void boundary(Package review, LocalDate day, boolean closing) {
        var events = review.content().schedules().stream()
                .filter(event -> ApplicationPeriod.basicTitle(event.title(), closing)).toList();
        if (day == null) {
            if (!events.isEmpty() || review.content().schedules().stream()
                    .anyMatch(event -> unconfirmedBoundary(event.title(), closing)))
                throw new IllegalArgumentException("UNCONFIRMED_APPLICATION_BOUNDARY_PRESENT");
        } else if (events.isEmpty() || events.stream().anyMatch(event -> !day.equals(ApplicationPeriod.day(event.date())))) {
            throw new IllegalArgumentException("REVIEWED_APPLICATION_BOUNDARIES_REQUIRED");
        }
    }

    /**
     * Reject aliases and explicitly uncertain dates without treating a rank/site deadline as the overall window.
     */
    private static boolean unconfirmedBoundary(String title, boolean closing) {
        String compact = TIME_NOTE.matcher(title).replaceAll("").replaceAll("\\s", "");
        String action = closing ? "(?:마감|종료)" : "(?:시작|개시)";
        if (compact.matches("(?:신청접수|신청|접수)(?:기간)?" + action + "(?:일|일시)?")) return true;
        if (!UNCERTAIN.matcher(title).find()) return false;
        String procedure = title.replaceAll("\\([^)]*\\)", "");
        if (SEPARATE_PROCEDURE.matcher(procedure).find()) return false;
        return Pattern.compile("(?:신청접수|신청|접수)(?:기간|일)?" + action).matcher(compact).find();
    }
}
