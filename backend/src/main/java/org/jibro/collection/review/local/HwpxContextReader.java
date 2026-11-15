package org.jibro.collection.review.local;

import java.util.*;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;

/** Reads only parser-generated HWPX structure, retaining offsets into the unchanged Part.text. */
final class HwpxContextReader {
    private static final Pattern LINE = Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)");
    private static final Pattern TABLE = Pattern.compile("TABLE id=(\\d+) rows=(\\d+|unknown) cols=(\\d+|unknown)");
    private static final Pattern CELL = Pattern.compile("CELL table=(\\d+) row=(\\d+|unknown) col=(\\d+|unknown) rowspan=(\\d+|unknown) colspan=(\\d+|unknown)");
    private static final Set<String> REGIONS = Set.of("HEADER", "FOOTER", "FOOTNOTE", "ENDNOTE");
    private static final int MAX_LINKS = 64;

    private HwpxContextReader() {}

    record Cell(int table, int row, int column, int rowSpan, int columnSpan) {
        boolean known() { return row > 0 && column > 0 && rowSpan > 0 && columnSpan > 0; }
        boolean overlapsColumns(Cell other) { return column < other.column + other.columnSpan && other.column < column + columnSpan; }
    }
    record Context(List<Cell> cells, List<String> regions) {
        Context { cells = List.copyOf(cells); regions = List.copyOf(regions); }
        Cell cell() { return cells.isEmpty() ? null : cells.get(cells.size() - 1); }
        String description() {
            var values = new ArrayList<String>();
            for (Cell cell : cells) values.add("표 " + cell.table + " 행 " + display(cell.row) + " 열 " + display(cell.column)
                + " 병합 " + display(cell.rowSpan) + "×" + display(cell.columnSpan));
            values.addAll(regions);
            return values.isEmpty() ? "" : "HWPX " + String.join(" / ", values);
        }
        private static String display(int number) { return number < 0 ? "미상" : String.valueOf(number); }
    }
    record Segment(int start, int end, Context context) {}
    record Reading(boolean valid, List<Segment> segments) { Reading { segments = List.copyOf(segments); } }
    record Occurrence(String id, String partKey, Context context) {}
    record Links(Map<String, List<String>> related, List<String> incomplete) {}
    private record Frame(String kind, int table, Cell cell) {}
    private record CellKey(String part, Cell cell) {}
    private record TableKey(String part, int table) {}

    static Reading read(Source source, Part part) {
        String text = part.text();
        boolean hwpx = source.label() != null && source.label().toLowerCase(Locale.ROOT).endsWith(".hwpx")
            || part.location().matches(".*(?:^| · )HWPX Contents/section\\d+\\.xml \\(쪽 번호 확인 필요\\)$");
        if (!hwpx || !text.contains("⟦HWPX:")) return new Reading(true, List.of(new Segment(0, text.length(), null)));
        var stack = new ArrayDeque<Frame>();
        var tableIds = new HashSet<Integer>();
        var segments = new ArrayList<Segment>();
        var lines = LINE.matcher(text);
        int start = 0;
        try {
            while (lines.find()) {
                if (lines.start() == lines.end()) break;
                String value = lines.group().stripTrailing();
                if (!value.startsWith("⟦HWPX:")) continue;
                if (!value.endsWith("⟧")) return new Reading(false, List.of());
                if (start < lines.start()) segments.add(new Segment(start, lines.start(), context(stack)));
                String marker = value.substring(6, value.length() - 1);
                var table = TABLE.matcher(marker);
                var cell = CELL.matcher(marker);
                if (table.matches()) {
                    int id = number(table.group(1), 2048);
                    number(table.group(2), 2048); number(table.group(3), 256);
                    if (!tableIds.add(id) || stack.size() >= 128) return new Reading(false, List.of());
                    stack.push(new Frame("TABLE", id, null));
                } else if (cell.matches()) {
                    int id = number(cell.group(1), 2048);
                    if (stack.isEmpty() || !stack.peek().kind.equals("TABLE") || stack.peek().table != id) return new Reading(false, List.of());
                    stack.push(new Frame("CELL", id, new Cell(id, number(cell.group(2), 2048), number(cell.group(3), 256),
                        number(cell.group(4), 2048), number(cell.group(5), 256))));
                } else if (REGIONS.contains(marker)) stack.push(new Frame(marker, -1, null));
                else if (marker.startsWith("/") && !stack.isEmpty() && stack.peek().kind.equals(marker.substring(1))) stack.pop();
                else return new Reading(false, List.of());
                start = lines.end();
            }
            if (!stack.isEmpty()) return new Reading(false, List.of());
            if (start < text.length()) segments.add(new Segment(start, text.length(), context(stack)));
            return new Reading(true, segments);
        } catch (IllegalArgumentException invalid) { return new Reading(false, List.of()); }
    }

    static boolean sameFlow(Context left, Context right) { return Objects.equals(left, right); }

    /** Physical neighbours are context, never proof that a first-row cell is a semantic header. */
    static Links relate(List<Occurrence> occurrences) {
        var byCell = new LinkedHashMap<CellKey, List<String>>();
        for (Occurrence item : occurrences) if (item.context != null && item.context.cell() != null)
            byCell.computeIfAbsent(new CellKey(item.partKey, item.context.cell()), ignored -> new ArrayList<>()).add(item.id);
        var byRow = new HashMap<TableKey, Map<Integer, List<CellKey>>>();
        var firstRows = new HashMap<TableKey, List<CellKey>>();
        var invalidTables = new HashSet<TableKey>();
        var coordinateCounts = new HashMap<TableKey, Integer>();
        for (CellKey key : byCell.keySet()) {
            Cell cell = key.cell;
            var table = new TableKey(key.part, cell.table);
            if (!cell.known() || cell.row + cell.rowSpan > 2049 || cell.column + cell.columnSpan > 257) {
                invalidTables.add(table); continue;
            }
            int count = coordinateCounts.merge(table, cell.rowSpan, Integer::sum);
            if (count > 40_000) { invalidTables.add(table); continue; }
            if (cell.row == 1) firstRows.computeIfAbsent(table, ignored -> new ArrayList<>()).add(key);
            for (int row = cell.row; row < cell.row + cell.rowSpan; row++)
                byRow.computeIfAbsent(table, ignored -> new HashMap<>()).computeIfAbsent(row, ignored -> new ArrayList<>()).add(key);
        }
        var result = new LinkedHashMap<String, List<String>>();
        var incomplete = new LinkedHashSet<String>();
        for (Occurrence item : occurrences) {
            Context context = item.context;
            if (context == null || context.cell() == null) continue;
            Cell cell = context.cell();
            var table = new TableKey(item.partKey, cell.table);
            if (invalidTables.contains(table) || context.cells.stream().anyMatch(c -> !c.known())) { incomplete.add(item.id); continue; }
            var keys = new LinkedHashSet<CellKey>();
            keys.add(new CellKey(item.partKey, cell));
            // A shared vertically merged label is a dependency of its rows, not a bridge
            // that makes every row in the table depend on every other row.
            if (cell.rowSpan == 1) keys.addAll(byRow.getOrDefault(table, Map.of()).getOrDefault(cell.row, List.of()));
            if (cell.row > 1) for (CellKey first : firstRows.getOrDefault(table, List.of()))
                if (cell.overlapsColumns(first.cell)) keys.add(first);
            for (Cell parent : context.cells) if (!parent.equals(cell)) keys.add(new CellKey(item.partKey, parent));
            var ids = new LinkedHashSet<String>();
            for (CellKey key : keys) ids.addAll(byCell.getOrDefault(key, List.of()));
            ids.remove(item.id);
            if (ids.size() > MAX_LINKS) incomplete.add(item.id);
            else result.put(item.id, List.copyOf(ids));
        }
        return new Links(Collections.unmodifiableMap(result), List.copyOf(incomplete));
    }

    private static Context context(ArrayDeque<Frame> stack) {
        var cells = new ArrayList<Cell>();
        var regions = new ArrayList<String>();
        var frames = stack.descendingIterator();
        while (frames.hasNext()) {
            Frame frame = frames.next();
            if (frame.cell != null) cells.add(frame.cell);
            if (REGIONS.contains(frame.kind)) regions.add(frame.kind);
        }
        return new Context(cells, regions);
    }
    private static int number(String text, int max) {
        if (text.equals("unknown")) return -1;
        int value = Integer.parseInt(text);
        if (value < 1 || value > max) throw new IllegalArgumentException();
        return value;
    }
}
