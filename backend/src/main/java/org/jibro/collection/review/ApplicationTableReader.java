package org.jibro.collection.review;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.review.local.DocumentContextReader;

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
            // The first column is the intake period. Do not pull a time from a place or contract column.
            var layout = part.text().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
            for (int i = 0; i + 1 < layout.size(); i++) {
                if (i + 3 < layout.size() && layout.get(i).matches("(?:신청|접수)\\s*기간")
                    && layout.get(i + 1).matches("(?:신청|접수)\\s*장소") && layout.get(i + 2).equals("계약안내")) {
                    String field = "접수 기간: " + layout.get(i + 3);
                    if (IntakeExplanation.matches(field))
                        result.add(new Excerpt("SCHEDULE", field, part.location(), source.url()));
                }
                if (!layout.get(i).matches("(?:신청|접수)\\s*기간\\s+(?:신청|접수)\\s*장소(?:\\s+.*)?")) continue;
                String field = "접수 기간: " + layout.get(i + 1).split("\\s{2,}", 2)[0];
                field = field.replaceFirst("\\s+[가-힣]{1,12}(?:시|군|구)\\s+내\\s+거주지\\s+관할.*$", "");
                if (IntakeExplanation.matches(field))
                    result.add(new Excerpt("SCHEDULE", field, part.location(), source.url()));
            }
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

    public record Entry(String scope, String text, String location, ScheduleCandidates.Kind kind, String cohort) {
    }

    private record Cell(DocumentContextReader.Cell at, String text) {
    }

    private record Header(int intake, int result, int site, int rank, int stage, int place, int width) {
    }

    public static List<Entry> read(Source source, Part part) {
        if (!part.text().contains("⟦HWPX:TABLE") && !part.text().contains("⟦PDF:TABLE")) return List.of();
        var reading = DocumentContextReader.read(source, part);
        if (!reading.valid()) return List.of();
        var tables = new LinkedHashMap<Integer, List<Cell>>();
        var first = new HashMap<Integer, Integer>();
        var last = new HashMap<Integer, Integer>();
        var cellText = new LinkedHashMap<DocumentContextReader.Cell, StringBuilder>();
        for (var segment : reading.segments()) {
            var ctx = segment.context();
            if (ctx == null || ctx.cells().size() != 1 || !ctx.regions().isEmpty() || !ctx.cell().known()) continue;
            String value = part.text().substring(segment.start(), segment.end()).strip();
            if (value.isEmpty()) continue;
            var at = ctx.cell();
            cellText.computeIfAbsent(at, ignored -> new StringBuilder()).append(value).append('\n');
            first.putIfAbsent(at.table(), segment.start());
            last.put(at.table(), segment.end());
        }
        cellText.forEach((at, value) -> tables.computeIfAbsent(at.table(), ignored -> new ArrayList<>()).add(new Cell(at, value.toString().strip())));
        List<Entry> entries = new ArrayList<>();
        Header previous = null;
        int prior = -1;
        for (var table : tables.entrySet()) {
            var cells = table.getValue();
            int id = table.getKey();
            int width = cells.stream().mapToInt(c -> c.at.column() + c.at.columnSpan() - 1).max().orElse(0);
            Header header = header(cells, width);
            boolean hasHeader = header != null;
            if (!hasHeader && previous != null && previous.width == width && id == prior + 1 && last.get(prior) <= first.get(id)) {
                String gap = part.text().substring(last.get(prior), first.get(id));
                if (gap.replaceAll("⟦(?:HWPX|PDF):[^⟧]+⟧|\\s", "").isEmpty()) header = previous;
            }
            // Some LH files store the floating header just after the body in XML order.
            var next = tables.get(id + 1);
            if (header == null && next != null && next.stream().allMatch(c -> c.at.row() == 1)
                && next.stream().mapToInt(c -> c.at.column() + c.at.columnSpan() - 1).max().orElse(0) == width
                && last.get(id) <= first.get(id + 1)
                && part.text().substring(last.get(id), first.get(id + 1)).replaceAll("⟦(?:HWPX|PDF):[^⟧]+⟧|\\s", "").isEmpty())
                header = header(next, width);
            if (header != null) {
                var physical = reading.tables().stream().filter(t -> t.id() == id).findFirst().orElseThrow();
                var companion = reading.rowCompanion(physical);
                List<Cell> rankCells = cells;
                int rankColumn = header.rank;
                if (companion != null && rankColumn < 0) {
                    var labels = tables.get(companion.id());
                    if (companion.columns() != 1 || labels == null || labels.stream().noneMatch(c -> c.at.row() == 1
                        && c.at.rowSpan() == 1 && c.text.replaceAll("\\s+", "").matches("신청자격|신청순위|순위")))
                        continue;
                    rankCells = labels;
                    rankColumn = 1;
                }
                for (Cell cell : cells) {
                    boolean announcement = cell.at.column() == header.result;
                    if ((!announcement && cell.at.column() != header.intake) || cell.at.columnSpan() != 1
                        || hasHeader && cell.at.row() == 1) continue;
                    if (header.stage > 0 && !values(cells, cell, header.stage).matches("(?:청약신청|신청접수|신청|접수)")) continue;
                    String site = values(cells, cell, header.site);
                    String rank = values(rankCells, cell, rankColumn);
                    if (header.site > 0 && site.isBlank() || rankColumn > 0 && rank.isBlank()) continue;
                    String scope = String.join(" · ", java.util.stream.Stream.of(site, rank).filter(s -> !s.isBlank()).toList());
                    String cohort = scope;
                    String place = values(cells, cell, header.place);
                    if (place.matches(".*(?:행정복지센터|주민센터|관리사무소|관리소).*")
                        && !place.matches(".*(?:온라인|인터넷|모바일|우편|https?).*")
                        && !cell.text.matches("(?s).*(?:온라인|인터넷|모바일|현장|방문|우편).*"))
                        scope = scope.isBlank() ? "현장" : scope + " · 현장";
                    if (scope.length() > 150) continue;
                    String location = part.location() + " · 표 " + id + " 행 " + cell.at.row() + " 열 " + cell.at.column();
                    if (companion != null && rankCells != cells)
                        location += " · 신청순위 표 " + companion.id() + " 행 " + cell.at.row();
                    String value = cell.text;
                    if (announcement) {
                        String resultScope = String.join(" · ", java.util.stream.Stream.of(site, rank)
                            .filter(s -> !s.isBlank()).map(s -> s.replaceAll("\\s*([()·⋅])\\s*", "$1").replace('⋅', '·')).toList());
                        entries.add(new Entry(resultScope, value, location, ScheduleCandidates.Kind.RESULT, cohort));
                        continue;
                    }
                    if (scope.endsWith("현장")) {
                        String tail = part.text().substring(last.get(id), Math.min(last.get(id) + 230, part.text().length()));
                        var lunch = java.util.regex.Pattern.compile("^\\s*⟦(?:HWPX|PDF):/CELL⟧\\s*⟦(?:HWPX|PDF):/TABLE⟧\\s*[*※]?\\s*점심시간\\s*\\((\\d{1,2}:\\d{2})\\s*~\\s*(\\d{1,2}:\\d{2})\\)에는\\s*신청접수\\s*받지\\s*않습니다").matcher(tail);
                        if (lunch.find() && !value.contains("점심"))
                            value += " (점심 " + lunch.group(1) + "~" + lunch.group(2) + " 제외)";
                        // A separately labelled online instruction must specify the same hours on each application day.
                        if (header.site < 0 && header.rank < 0 && cells.stream().mapToInt(c -> c.at.row()).max().orElse(0) == 2
                            && sameOnlineHours(cell.text, part.text()))
                            entries.add(new Entry(scope.replaceFirst("현장$", "인터넷"), cell.text, location + " · 인터넷신청 가능시간", ScheduleCandidates.Kind.START, cohort));
                    }
                    entries.add(new Entry(scope, value, location, ScheduleCandidates.Kind.START, cohort));
                }
            }
            previous = hasHeader && cells.stream().allMatch(c -> c.at.row() == 1) ? header : null;
            prior = id;
        }
        return entries;
    }

    private static boolean sameOnlineHours(String cell, String section) {
        var hours = java.util.regex.Pattern.compile("(\\d{2}):00\\s*~\\s*(\\d{2}):00\\s*$").matcher(cell);
        if (!hours.find()) return false;
        var online = java.util.regex.Pattern.compile("인터넷신청\\s*가능시간은\\s*해당\\s*신청접수일의\\s*오전\\s*(\\d{1,2})시부터\\s*오후\\s*(\\d{1,2})시까지").matcher(section);
        if (!online.find()) return false;
        int open = Integer.parseInt(online.group(1)), close = Integer.parseInt(online.group(2));
        if (close > 0 && close < 12) close += 12;
        return !online.find() && open < 12 && close < 24
            && open == Integer.parseInt(hours.group(1)) && close == Integer.parseInt(hours.group(2));
    }

    private static Header header(List<Cell> cells, int width) {
        int intake = -1, result = -1, site = -1, rank = -1, stage = -1, date = -1, place = -1;
        for (Cell cell : cells) {
            if (cell.at.row() != 1 || cell.at.columnSpan() != 1) continue;
            String name = cell.text.replaceAll("\\s+", "");
            int col = cell.at.column();
            if (name.matches("(?:청약신청|청약신청접수|신청접수|신청기간|접수기간|신청접수일|접수일)(?:\\((?:택1|현장/인터넷/모바일|인터넷또는현장)\\))?"))
                intake = col;
            if (name.matches("(?:입주자)?(?:선정)?결과발표(?:일)?|당첨자발표(?:일)?|동호지정순번및순번별계약일정발표")) result = col;
            if (name.equals("단지명") || name.equals("단지")) site = col;
            if (name.equals("신청순위") || name.equals("순위") || name.equals("신청자격")) rank = col;
            if (name.equals("공급단계") || name.equals("구분")) stage = col;
            if (name.equals("일정")) date = col;
            if (name.matches("(?:현장방문|현장|방문)?(?:신청|접수)장소")) place = col;
        }
        if (intake > 0) return new Header(intake, result, site, rank, -1, place, width);
        return stage > 0 && date > 0 ? new Header(date, -1, -1, -1, stage, place, width) : null;
    }

    private static String values(List<Cell> cells, Cell target, int column) {
        if (column < 0) return "";
        return cells.stream().filter(c -> c.at.column() == column && c.at.columnSpan() == 1
                && c.at.row() < target.at.row() + target.at.rowSpan()
                && target.at.row() < c.at.row() + c.at.rowSpan())
            .map(c -> c.text.replaceAll("\\s+", " ").strip()).distinct()
            .collect(java.util.stream.Collectors.joining("·"));
    }
}
