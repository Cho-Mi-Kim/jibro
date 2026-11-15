package org.jibro.notice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Explicit projection: never hydrate the notice's large content JSON for a list. */
record NoticeListSource(UUID id, String title, String agency, String region, String type,
        LocalDate startDate, LocalDate endDate, LocalDate publishedDate,
        boolean changeReview, String guideReview, boolean partialGuidance, boolean officiallyClosed,
        Instant collectedAt, String lifecycleState, String lifecycleReason, String lifecycleEvidence,
        Instant lifecycleCheckedAt, int lifecycleRevision, Instant startsAt, Instant endsAt) {
}
