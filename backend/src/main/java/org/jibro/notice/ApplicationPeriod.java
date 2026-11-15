package org.jibro.notice;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.notice.HousingModels.ScheduleTemplate;

/**
 * Interprets reviewed application schedules. Missing times remain unknown.
 */
public final class ApplicationPeriod {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern TIME = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)|(?<!\\d)([01]?\\d|2[0-3])시(?:\\s*([0-5]?\\d)분)?");

    private ApplicationPeriod() {
    }

    public record Window(Instant startsAt, Instant endsAt) {
    }

    public static Window resolve(LocalDate start, LocalDate end, List<ScheduleTemplate> schedules) {
        return new Window(boundary(start, schedules, false), boundary(end, schedules, true));
    }

    private static Instant boundary(LocalDate day, List<ScheduleTemplate> schedules, boolean closing) {
        if (day == null || schedules == null) return null;
        var candidates = schedules.stream()
            .filter(event -> event != null && basicTitle(event.title(), closing) && day.equals(day(event.date())))
            .map(ApplicationPeriod::instant).filter(java.util.Objects::nonNull).distinct().toList();
        // Conflicting times require review; do not silently choose the first/earliest one.
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    public static boolean basicTitle(String title, boolean closing) {
        if (title == null) return false;
        String plain = title.replaceAll("\\([^)]*(?:\\d{1,2}:\\d{2}|\\d{1,2}시|조기\\s*중단)[^)]*\\)", "")
            .replaceAll("\\s", "");
        return plain.matches(closing ? "(?:신청접수|신청|접수)마감" : "(?:신청접수|신청|접수)(?:시작|개시)");
    }

    public static LocalDate day(String value) {
        try {
            if (value == null) return null;
            if (value.length() == 10) return LocalDate.parse(value);
            if (hasOffset(value)) return OffsetDateTime.parse(value).atZoneSameInstant(SEOUL).toLocalDate();
            return LocalDateTime.parse(value).toLocalDate();
        } catch (DateTimeException ex) {
            return null;
        }
    }

    public static Instant instant(ScheduleTemplate event) {
        if (event == null || day(event.date()) == null) return null;
        try {
            if (event.date().length() > 10) {
                return hasOffset(event.date()) ? OffsetDateTime.parse(event.date()).toInstant()
                    : LocalDateTime.parse(event.date()).atZone(SEOUL).toInstant();
            }
            var match = TIME.matcher(event.title());
            if (!match.find()) return null;
            int hour = Integer.parseInt(match.group(1) == null ? match.group(3) : match.group(1));
            String minute = match.group(1) == null ? match.group(4) : match.group(2);
            var period = Pattern.compile("(오전|오후)\\s*$").matcher(event.title().substring(0, match.start()));
            if (period.find()) {
                if (hour < 1 || hour > 12) return null;
                hour = hour % 12 + (period.group(1).equals("오후") ? 12 : 0);
            }
            LocalTime time = LocalTime.of(hour, minute == null ? 0 : Integer.parseInt(minute));
            // A range/multiple clock values is not a single confirmed deadline.
            if (match.find()) return null;
            return day(event.date()).atTime(time).atZone(SEOUL).toInstant();
        } catch (DateTimeException | NumberFormatException ex) {
            return null;
        }
    }

    private static boolean hasOffset(String value) {
        return value.endsWith("Z") || value.matches(".*[+-]\\d{2}:\\d{2}$");
    }

    public static String status(LocalDate start, LocalDate end, Window window, Instant now) {
        if (start == null || end == null) return "UNKNOWN";
        Instant closes = window.endsAt() == null ? end.plusDays(1).atStartOfDay(SEOUL).toInstant() : window.endsAt();
        Instant opens = window.startsAt() == null ? start.atStartOfDay(SEOUL).toInstant() : window.startsAt();
        if (!now.isBefore(closes)) return "CLOSED";
        return now.isBefore(opens) ? "UPCOMING" : "OPEN";
    }

    public static Instant nextChange(LocalDate start, LocalDate end, Window window, Instant now) {
        if (start == null || end == null) return null;
        Instant opens = window.startsAt() == null ? start.atStartOfDay(SEOUL).toInstant() : window.startsAt();
        Instant closes = window.endsAt() == null ? end.plusDays(1).atStartOfDay(SEOUL).toInstant() : window.endsAt();
        if (now.isBefore(opens) && opens.isBefore(closes)) return opens;
        return now.isBefore(closes) ? closes : null;
    }
}
