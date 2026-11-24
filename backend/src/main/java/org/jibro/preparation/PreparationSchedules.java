package org.jibro.preparation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.notice.HousingModels.Schedule;

/**
 * Personal completion policy; mirrored by housing/schedules.canRecordSchedule.
 */
public final class PreparationSchedules {
    private PreparationSchedules() {
    }

    public static boolean canRecord(Schedule event, List<Schedule> schedules) {
        String kind = kind(event.title);
        if (kind.equals("application") || kind.equals("end")) {
            Schedule selected = select(schedules, "application");
            if (selected == null) selected = select(schedules, "end");
            return selected != null && selected.id.equals(event.id);
        }
        String title = event.title == null ? "" : event.title;
        return !kind.equals("start") && !(kind.equals("other") && matches("신청|접수", title) && !title.contains("서류"));
    }

    private static Schedule select(List<Schedule> schedules, String kind) {
        var candidates = schedules.stream().filter(s -> kind(s.title).equals(kind) && validDate(s.date)).toList();
        if (kind.equals("end")) {
            var basic = candidates.stream().filter(s -> s.title.replaceAll("\\([^)]*(?:\\d{1,2}:\\d{2}|조기\\s*중단)[^)]*\\)", "")
                    .replaceAll("\\s", "").matches("(?:신청접수|신청|접수)마감")).toList();
            if (!basic.isEmpty()) candidates = basic;
        }
        return candidates.stream().filter(s -> matches("[T ](?:[01]\\d|2[0-3]):[0-5]\\d", s.date)
                        || matches("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b", s.title)).findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));
    }

    private static boolean validDate(String value) {
        if (value == null) return false;
        try {
            if (value.endsWith("Z") || value.matches(".*[+-]\\d{2}:\\d{2}$"))
                return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate() != null;
            LocalDate.parse(value.substring(0, 10));
            return true;
        } catch (DateTimeException | IndexOutOfBoundsException ex) {
            return false;
        }
    }

    private static String kind(String title) {
        if (title == null) return "other";
        if (matches("서류.*마감", title)) return "documents";
        if (matches("(?:대상|결과|당첨|예비입주자).*발표", title)) return "result";
        if (matches("공고.*(?:게시|발표)|공고일", title)) return "published";
        if (!title.contains("서류")) {
            if (matches("공식\\s*신청.*(?:여부|확인)|신청.*완료", title)) return "application";
            if (matches("(?:신청|접수).*마감", title)) return "end";
            if (matches("(?:신청|접수).*(?:시작|개시)", title)) return "start";
        }
        return "other";
    }

    private static boolean matches(String pattern, String value) {
        return Pattern.compile(pattern).matcher(value).find();
    }
}
