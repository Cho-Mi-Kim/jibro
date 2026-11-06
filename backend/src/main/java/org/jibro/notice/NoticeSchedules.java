package org.jibro.notice;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.notice.HousingModels.Notice;
import org.jibro.notice.HousingModels.ScheduleTemplate;

/** Source schedule interpretation used by server-side reminders. */
public final class NoticeSchedules {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern APPLICATION_END = Pattern.compile("(?:신청|접수).*마감");
    private static final Pattern TIME = Pattern.compile("(?:[01]?\\d|2[0-3]):[0-5]\\d");

    private NoticeSchedules() {
    }

    public record Due(String id, String title, LocalDate date) {
    }

    public static List<Due> reminders(Notice notice) {
        List<ScheduleTemplate> schedules = notice.schedules() != null ? notice.schedules() : List.of();
        var matches = schedules.stream().filter(s -> isApplicationDeadline(s.title())
                && notice.endDate() != null && notice.endDate().equals(calendarDay(s.date()))).toList();
        var basic = matches.stream().filter(s -> isBasicDeadlineTitle(s.title())).toList();
        if (!basic.isEmpty()) matches = basic;
        var closing = matches.stream().filter(s -> TIME.matcher(s.date()).find() || TIME.matcher(s.title()).find())
                .findFirst().orElse(matches.isEmpty() ? null : matches.get(0));
        var result = new ArrayList<Due>();
        if (notice.endDate() != null)
            result.add(new Due("end", closing == null ? "신청 접수 마감" : closing.title(), notice.endDate()));
        for (var schedule : schedules) {
            if (closing != null && isDeadlineAlias(schedule, closing)) continue;
            var date = calendarDay(schedule.date());
            if (date != null) result.add(new Due(schedule.id(), schedule.title(), date));
        }
        return result;
    }

    private static boolean isApplicationDeadline(String title) {
        return !title.contains("서류") && APPLICATION_END.matcher(title).find();
    }

    private static boolean isBasicDeadlineTitle(String title) {
        return title.replaceAll("\\([^)]*(?:\\d{1,2}:\\d{2}|조기\\s*중단)[^)]*\\)", "")
                .replaceAll("\\s", "").matches("(?:신청접수|신청|접수)마감");
    }

    private static String time(ScheduleTemplate event) {
        var date = TIME.matcher(event.date());
        if (date.find()) return date.group();
        var title = TIME.matcher(event.title());
        return title.find() ? title.group() : "";
    }

    private static boolean isDeadlineAlias(ScheduleTemplate event, ScheduleTemplate selected) {
        if (event == selected) return true;
        if (!Objects.equals(calendarDay(event.date()), calendarDay(selected.date()))
                || !isBasicDeadlineTitle(event.title()) || !isBasicDeadlineTitle(selected.title())) return false;
        return time(event).isEmpty() || time(event).equals(time(selected));
    }

    private static LocalDate calendarDay(String value) {
        if (value == null) return null;
        try {
            if (value.length() == 10) return LocalDate.parse(value);
            if (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:\\d{2}$"))
                return OffsetDateTime.parse(value).atZoneSameInstant(SEOUL).toLocalDate();
            return LocalDateTime.parse(value).toLocalDate();
        } catch (DateTimeException ex) {
            return null;
        }
    }
}
