package org.jibro.collection.review;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;

/**
 * Recognizes the explicit date/method/place table, not arbitrary adjacent PDF columns.
 */
final class ApplicationTableReader {
    private static final String WEEK = "(?:\\([월화수목금토일]\\))?";
    private static final Pattern PERIOD = Pattern.compile("^(20\\d{2})[./-](\\d{1,2})[./-](\\d{1,2})\\.?" + WEEK
        + "(?:[~∼～-](?:(20\\d{2})[./-])?(?:(\\d{1,2})[./-])?(\\d{1,2})\\.?" + WEEK + ")?$");
    private static final Pattern HOURS = Pattern.compile("^\\(?(\\d{1,2}:\\d{2})[~∼～-](\\d{1,2}:\\d{2})\\)?$");

    private ApplicationTableReader() {
    }

    static List<Excerpt> read(Source source) {
        var result = new ArrayList<Excerpt>();
        for (var part : source.context()) {
            var lines = part.text().lines().map(s -> s.replaceAll("\\s+", "")).filter(s -> !s.isEmpty()).toList();
            for (int i = 0; i + 3 < lines.size(); i++) {
                if (!Set.of("신청일시", "신청일자", "접수일시", "접수일자").contains(lines.get(i))
                    || !lines.get(i + 1).equals("신청방법") || !lines.get(i + 2).equals("신청장소")) continue;
                String cell = lines.get(i + 3);
                var inlineHours = Pattern.compile("\\((\\d{1,2}:\\d{2})[~∼～-](\\d{1,2}:\\d{2})\\)$").matcher(cell);
                String inlineStart = "", inlineEnd = "";
                if (inlineHours.find()) {
                    inlineStart = " " + inlineHours.group(1);
                    inlineEnd = " " + inlineHours.group(2);
                    cell = cell.substring(0, inlineHours.start());
                }
                var match = PERIOD.matcher(cell);
                if (!match.matches()) continue;
                try {
                    int year = Integer.parseInt(match.group(1)), month = Integer.parseInt(match.group(2));
                    var start = LocalDate.of(year, month, Integer.parseInt(match.group(3)));
                    var end = match.group(6) == null ? start : LocalDate.of(match.group(4) == null ? year : Integer.parseInt(match.group(4)),
                        match.group(5) == null ? month : Integer.parseInt(match.group(5)), Integer.parseInt(match.group(6)));
                    if (end.isBefore(start)) continue;
                    String startClock = inlineStart, endClock = inlineEnd;
                    if (inlineStart.isEmpty() && i + 4 < lines.size()) {
                        var hours = HOURS.matcher(lines.get(i + 4));
                        if (hours.matches()) {
                            startClock = " " + hours.group(1);
                            endClock = " " + hours.group(2);
                        } else if (lines.get(i + 4).matches(".*(?:\\d|이후|까지|연장|상시).*")) continue;
                    }
                    result.add(new Excerpt("SCHEDULE", "접수 기간: " + start + startClock + " ~ " + end + endClock,
                        part.location() + " · 신청일시/방법/장소 표", source.url()));
                } catch (java.time.DateTimeException invalid) { /* An invalid cell must remain unresolved. */ }
            }
        }
        return List.copyOf(result);
    }
}
