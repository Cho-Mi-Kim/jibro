package org.jibro.notice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.HousingModels.ScheduleTemplate;

/**
 * The automatic review boundary excludes closed notices, including a deadline passed during a worker run.
 */
public final class AutomaticReviewEligibility {
    // All callers use alias n. Explicit official closure or reviewed dates, never API listing expiry.
    public static final String NOT_CLOSED_SQL = """
            n.source_closed = false AND n.service_closed = false AND n.service_excluded = false
            AND (n.content->'applicationClosure' IS NULL OR n.content->'applicationClosure'='null'::jsonb)
            AND (COALESCE(n.application_end_at, (n.end_date + 1)::timestamp AT TIME ZONE 'Asia/Seoul') IS NULL
                OR COALESCE(n.application_end_at, (n.end_date + 1)::timestamp AT TIME ZONE 'Asia/Seoul') > clock_timestamp())
            """;

    private AutomaticReviewEligibility() {
    }

    static boolean closed(NoticeEntity notice, Content content, Instant now) {
        return notice.sourceClosed || notice.serviceClosed || notice.serviceExcluded || content != null && content.applicationClosure() != null
                || passed(notice.endDate, notice.applicationEndAt, now);
    }

    public static boolean closed(LocalDate start, LocalDate end, List<ScheduleTemplate> schedules, Instant now) {
        return passed(end, ApplicationPeriod.resolve(start, end, schedules).endsAt(), now);
    }

    static boolean passed(LocalDate day, Instant time, Instant now) {
        Instant boundary = deadline(day, time);
        return boundary != null && !now.isBefore(boundary);
    }

    static Instant deadline(LocalDate day, Instant time) {
        return time != null ? time : day == null ? null : day.plusDays(1).atStartOfDay(ApplicationPeriod.SEOUL).toInstant();
    }
}
