package org.jibro.notice;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;

/**
 * Static official history rows, ordered newest first. Titles never establish a relationship.
 */
public final class OfficialCorrectionHistory {
    public record Entry(String url, String title, LocalDate date, String kind) {
    }

    private static final Pattern LINK = Pattern.compile("javascript:goUrl\\('([0-9]{1,30})','([0-9]{2})'\\)");

    private OfficialCorrectionHistory() {
    }

    public static List<Entry> read(String latestUrl, String html) {
        var latest = OfficialCorrectionLink.identity(latestUrl);
        if (html == null || html.length() > 2 * 1024 * 1024) throw invalid();
        var tables = Jsoup.parse(html).select("table").stream()
                .filter(t -> t.select("caption").text().startsWith("공고이력조회")).toList();
        if (tables.size() != 1) throw invalid();
        var rows = tables.get(0).select("tbody tr");
        if (rows.size() < 2 || rows.size() > 20) throw invalid();
        var result = new ArrayList<Entry>();
        var ids = new HashSet<String>();
        LocalDate previous = LocalDate.MAX;
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var cells = row.select("td");
            var anchors = row.select("a");
            String kind = i == rows.size() - 1 ? "일반공고" : "정정공고";
            if (cells.size() != 3 || !row.select("th").text().equals(kind)) throw invalid();
            String id = latest.panId();
            if (i == 0) {
                if (!anchors.isEmpty()) throw invalid();
            } else {
                if (anchors.size() != 1) throw invalid();
                var match = LINK.matcher(anchors.first().attr("href"));
                if (!match.matches() || !match.group(2).equals(latest.system())) throw invalid();
                id = match.group(1);
            }
            LocalDate date;
            try {
                date = LocalDate.parse(cells.get(0).text());
            } catch (RuntimeException ex) {
                throw invalid();
            }
            String title = cells.get(1).text();
            if (!ids.add(id) || date.isAfter(previous) || title.isBlank() || title.length() > 300) throw invalid();
            previous = date;
            result.add(new Entry(OfficialCorrectionLink.withPanId(latestUrl, id), title, date,
                    kind.equals("일반공고") ? "01" : "02"));
        }
        return List.copyOf(result);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("OFFICIAL_CORRECTION_HISTORY_UNVERIFIED");
    }
}
