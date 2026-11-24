package org.jibro.collection.review;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.notice.HousingModels.Evidence;

import static org.jibro.collection.review.ScheduleCandidates.*;

/** Only an explicit before/after pair naming the same method can supersede a method's dates. */
record ChannelAmendment(String method, Period before, Period after, LocalDate published, Evidence evidence) {
    private static final String DATE = "(20\\d{2})[./-](\\d{1,2})[./-](\\d{1,2})\\.?\\s*(?:\\([월화수목금토일]\\))?(?:\\s+(\\d{1,2}):(\\d{2}))?";
    private static final Pattern FIELD = Pattern.compile("^(현장(?:방문)?|방문|인터넷|온라인|우편)\\s*(?:청약\\s*)?(?:접수|신청)\\s*기간\\s*[:：]\\s*"
        + DATE + "\\s*[~∼～–]\\s*" + DATE + "\\s*$");

    record Period(String method, LocalDate start, LocalTime opens, LocalDate end, LocalTime closes) {
        boolean matches(List<Candidate> starts, List<Candidate> ends) {
            return matches(starts, start, opens) && matches(ends, end, closes);
        }

        private boolean matches(List<Candidate> values, LocalDate day, LocalTime time) {
            return values.stream().anyMatch(c -> day.equals(c.day()) && (time == null || time.equals(c.time())));
        }
    }

    static Period parse(String line) {
        if (line == null) return null;
        var m = FIELD.matcher(line);
        if (!m.matches()) return null;
        try {
            LocalDate first = LocalDate.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
            LocalDate last = LocalDate.of(Integer.parseInt(m.group(7)), Integer.parseInt(m.group(8)), Integer.parseInt(m.group(9)));
            LocalTime start = m.group(5) == null ? null : LocalTime.of(Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
            LocalTime end = m.group(10) == null ? null : LocalTime.of(Integer.parseInt(m.group(10)), Integer.parseInt(m.group(11)));
            if (last.isBefore(first) || last.equals(first) && start != null && end != null && end.isBefore(start)) return null;
            return new Period(IntakeChannelDates.channel(m.group(1)), first, start, last, end);
        } catch (java.time.DateTimeException invalid) {
            return null;
        }
    }
}
