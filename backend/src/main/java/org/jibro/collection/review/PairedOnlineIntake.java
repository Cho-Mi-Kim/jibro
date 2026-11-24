package org.jibro.collection.review;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.attachments.HousingGraphic;
import org.jibro.collection.review.local.GuidanceTableProjection;
import org.jibro.notice.HousingModels.*;

/**
 * An LH electronic intake row with full year and time, repeated in both editions of the recruitment.
 */
public final class PairedOnlineIntake {
    private static final Pattern DETAIL = Pattern.compile("(?m)^신청일시\\s*:\\s*(\\d{4}\\.\\d{2}\\.\\d{2} \\d{2}:\\d{2})\\s*~\\s*(\\d{4}\\.\\d{2}\\.\\d{2} \\d{2}:\\d{2})\\s*$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu.MM.dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private static final String BOUNDARY = "(\\d{1,2})\\.(\\d{1,2})\\.?\\([월화수목금토일]\\)(\\d{1,2})시(?:(\\d{1,2})분)?";
    private static final Pattern WINDOW = Pattern.compile("(?:인터넷신청가능시간|인터넷및모바일청약시간)은" + BOUNDARY + "[~∼～]" + BOUNDARY);

    private PairedOnlineIntake() {
    }

    public static ApplicationPeriodCompiler.Result confirmed(Draft draft, Content original, ApplicationPeriodCompiler.Result period) {
        if (draft == null || draft.sources() == null || draft.sources().stream().anyMatch(s -> s == null || s.label() == null
            || s.url() == null || s.context() == null || s.context().stream().anyMatch(part -> part == null || part.text() == null)))
            return null;
        if (period.ready() || period.issues().isEmpty() || period.issues().stream().anyMatch(issue -> !issue.equals("APPLICATION_PERIOD_MISSING")
            && draft.sources().stream().noneMatch(s -> HousingGraphic.matches(s.label(), s.url()) && issue.equals("AUTHORITATIVE_SOURCE_INCOMPLETE:" + s.label()))))
            return null;
        var details = draft.sources().stream().filter(s -> s.url().equals(original.evidence().url())).toList();
        if (details.size() != 1) return null;
        var sessions = details.get(0).context().stream().filter(p -> !p.text().replaceAll("\\s+", "").equals("구분:신청일시:신청방법")).filter(p -> p.text().matches("(?s).*(?:신청일시|접수기간)\\s*[:：].*")).toList();
        if (sessions.size() != 1 || !sessions.get(0).text().contains("신청방법 : 인터넷접수")) return null;
        var match = DETAIL.matcher(sessions.get(0).text());
        if (!match.find()) return null;
        LocalDateTime start, end;
        try {
            start = LocalDateTime.parse(match.group(1), TIME);
            end = LocalDateTime.parse(match.group(2), TIME);
        } catch (java.time.DateTimeException invalid) {
            return null;
        }
        if (match.find() || !end.isAfter(start) || start.getYear() != end.getYear()) return null;
        var print = draft.sources().stream().filter(s -> s.label().contains("공고") && s.label().endsWith(".pdf")).toList();
        var structured = draft.sources().stream().filter(s -> s.label().contains("공고") && GuidanceTableProjection.structuredLabel(s.label())).toList();
        if (print.size() != 1 || structured.size() != 1 || !matches(print.get(0), start, end) || !matches(structured.get(0), start, end))
            return null;
        return new ApplicationPeriodCompiler.Result(start.toLocalDate(), end.toLocalDate(), List.of(
            new ScheduleTemplate("auto-application-start", "신청 접수 시작", start.toString(), original.evidence()),
            new ScheduleTemplate("auto-application-end", "신청 접수 마감", end.toString(), original.evidence())), List.of());
    }

    private static boolean matches(Source source, LocalDateTime start, LocalDateTime end) {
        boolean found = false;
        for (var part : source.context()) {
            var match = WINDOW.matcher(part.text().replaceAll("[\\s\\p{Zs}]+", ""));
            while (match.find()) {
                if (!same(match, 1, start) || !same(match, 5, end)) return false;
                found = true;
            }
        }
        return found;
    }

    private static boolean same(java.util.regex.Matcher match, int offset, LocalDateTime time) {
        return Integer.parseInt(match.group(offset)) == time.getMonthValue() && Integer.parseInt(match.group(offset + 1)) == time.getDayOfMonth()
            && Integer.parseInt(match.group(offset + 2)) == time.getHour()
            && (match.group(offset + 3) == null ? 0 : Integer.parseInt(match.group(offset + 3))) == time.getMinute();
    }
}

