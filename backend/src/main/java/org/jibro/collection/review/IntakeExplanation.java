package org.jibro.collection.review;

import java.time.LocalTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jibro.notice.HousingModels.ApplicationTiming;
import org.jibro.notice.HousingModels.ScheduleTemplate;

import static org.jibro.collection.review.ScheduleCandidates.*;

/** Source-bound, non-calendar intake information. An undated clock never triggers a reminder. */
final class IntakeExplanation {
    private static final Pattern ROLLING = Pattern.compile("^[\\s■□▪•*※◎-]*(?:(?:(?:신청|청약|접수)\\s*(?:접수\\s*)?(?:기간|일시)|(?:입주자\\s*)?모집\\s*기간)\\s*[:：은는]?\\s*"
        + "(?:(?<start>" + ScheduleSourceReader.FULL_DATE + ScheduleSourceReader.WEEKDAY
        + "(?:\\s*(?:오[전후]\\s*)?\\d{1,2}(?::\\d{2}|시(?:\\s*\\d{1,2}분)?))?)\\s*(?:[~∼〜～–—-]|부터)\\s*)?"
        + "모집\\s*완료\\s*(?:시\\s*)?까지(?:\\s*(?:신청|접수)\\s*(?:가능|받습니다|합니다)?)?"
        + "|모집\\s*완료\\s*(?:시\\s*)?까지\\s*(?:신청\\s*)?접수\\s*(?:가능|받습니다|합니다))"
        + "(?:\\s+\\(?\\d{1,2}:\\d{2}\\s*[~∼～–-]\\s*\\d{1,2}:\\d{2}\\)?)?\\s*[.。]?"
        + "(?:\\s*\\(점심시간[^()\\n]{0,70}제외\\)|\\s*\\(점심시간[^()\\n]{0,70}받지 않습니다\\.\\))?$");

    private IntakeExplanation() {
    }

    static Matcher rolling(String field) {
        return ROLLING.matcher(field.strip());
    }

    static boolean matches(String field) {
        return rolling(field).matches();
    }

    static boolean differentDates(Map<Kind, List<Candidate>> values) {
        return values.values().stream().anyMatch(group -> group.stream().map(Candidate::day).distinct().count() > 1);
    }

    static List<ScheduleTemplate> clocks(Map<Kind, List<Candidate>> values, String scope, int group) {
        var result = new ArrayList<ScheduleTemplate>();
        for (var kind : List.of(Kind.START, Kind.END)) {
            var candidates = values.getOrDefault(kind, List.of());
            var timed = candidates.stream().filter(c -> c.time() != null).toList();
            if (timed.isEmpty() || timed.stream().map(Candidate::time).distinct().count() != 1) continue;
            // Keep the clock attached to its evidence, never to either proposed calendar date.
            var first = timed.get(0);
            String clock = LocalTime.MAX.equals(first.time()) ? "24:00" : first.time().toString();
            String label = (scope.isBlank() ? "" : scope + " · ") + (kind == Kind.START ? "시작 " : "마감 ") + clock;
            result.add(new ScheduleTemplate("auto-undated-" + group + "-" + kind.name().toLowerCase(java.util.Locale.ROOT),
                ApplicationTiming.UNDATED_HOURS_SESSION, "", first.evidence(), label + " · 공식 자료의 접수 날짜가 서로 다릅니다."));
        }
        return result;
    }

    static List<ScheduleTemplate> conflictingClocks(Map<Kind, List<Candidate>> common,
                                                   Map<String, Map<Kind, List<Candidate>>> groups, LocalDate checked) {
        var ends = new ArrayList<>(common.getOrDefault(Kind.END, List.of()));
        groups.values().forEach(g -> ends.addAll(g.getOrDefault(Kind.END, List.of())));
        if (!ends.isEmpty() && ends.stream().allMatch(c -> c.day().isBefore(checked))) return List.of();
        if (groups.isEmpty()) return differentDates(common) ? clocks(common, "", 0) : List.of();
        var events = new ArrayList<ScheduleTemplate>();
        int index = 0;
        for (var group : groups.entrySet()) {
            var claims = groups.size() == 1 ? compareCommonPeriod(group.getValue(), common) : group.getValue();
            if (differentDates(claims)) events.addAll(clocks(claims, group.getKey(), ++index));
        }
        return events;
    }

    private static Map<Kind, List<Candidate>> compareCommonPeriod(Map<Kind, List<Candidate>> scoped, Map<Kind, List<Candidate>> common) {
        var combined = new EnumMap<Kind, List<Candidate>>(Kind.class);
        for (var kind : List.of(Kind.START, Kind.END)) {
            var claims = new LinkedHashSet<>(scoped.getOrDefault(kind, List.of()));
            boolean scopedClock = claims.stream().anyMatch(c -> c.time() != null);
            common.getOrDefault(kind, List.of()).stream().filter(c -> scopedClock || c.time() == null).forEach(claims::add);
            combined.put(kind, List.copyOf(claims));
        }
        return combined;
    }
}
