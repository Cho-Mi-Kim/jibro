package org.jibro.notice;

import java.time.Instant;
import java.time.LocalDate;
import org.jibro.notice.HousingModels.ApplicationTiming;
import org.jibro.notice.HousingModels.NoticeLifecycle;

/** List and inline preview data. Full guidance is loaded only by the detail endpoint. */
public record NoticeListItem(String id, String title, String agency, String region, String type,
        String status, LocalDate startDate, LocalDate endDate, LocalDate publishedDate,
        String guideState, boolean partialGuidance, Instant collectedAt,
        NoticeLifecycle lifecycle, ApplicationTiming applicationTiming) {
}
