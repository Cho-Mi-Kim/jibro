package org.jibro.collection.review.local;

import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;

/**
 * Reads only parser-generated PDF/HWPX structure, retaining offsets into the unchanged Part.text.
 */
public final class DocumentContextReader {
    public static final int MAX_GRID_POSITIONS = 40_000;
    private static final Pattern LINE = Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)");
    private static final Pattern TABLE = Pattern.compile("TABLE id=(\\d+) rows=(\\d+|unknown) cols=(\\d+|unknown)(?: rowgroup=(\\d+))?(?: rowbands=([0-9:.,]+))?(?: colbands=([0-9:.,]+))?");
    private static final Pattern CELL = Pattern.compile("CELL table=(\\d+) row=(\\d+|unknown) col=(\\d+|unknown) rowspan=(\\d+|unknown) colspan=(\\d+|unknown)"
            + "(?: left=(NONE|DRAWN|UNKNOWN) right=(NONE|DRAWN|UNKNOWN))?"
            + "(?: top=(NONE|DRAWN|UNKNOWN) bottom=(NONE|DRAWN|UNKNOWN))?"
            + "(?: doubleleft=([0-9.]+mm))?(?: doubleright=([0-9.]+mm))?"
            + "(?: doubletop=([0-9.]+mm))?(?: doublebottom=([0-9.]+mm))?");
    private static final Pattern BASELINE = Pattern.compile("LINE y0=([0-9]{1,6}(?:\\.[0-9]{1,2})?) y1=([0-9]{1,6}(?:\\.[0-9]{1,2})?)");
    private static final Pattern PAGE_NUMBER = Pattern.compile("PAGE_NUMBER value=([1-9][0-9]{0,2})");
    private static final Set<String> REGIONS = Set.of("HEADER", "FOOTER", "FOOTNOTE", "ENDNOTE", "MEMO");
    private static final int MAX_LINKS = 64;

    private DocumentContextReader() {
    }

    public record Cell(int table, int row, int column, int rowSpan, int columnSpan) {
        public boolean known() {
            return row > 0 && column > 0 && rowSpan > 0 && columnSpan > 0;
        }

        boolean overlapsColumns(Cell other) {
            return column < other.column + other.columnSpan && other.column < column + columnSpan;
        }
    }

    public record Context(List<Cell> cells, List<String> regions) {
        public Context {
            cells = List.copyOf(cells);
            regions = List.copyOf(regions);
        }

        public Cell cell() {
            return cells.isEmpty() ? null : cells.get(cells.size() - 1);
        }

        String description() {
            var values = new ArrayList<String>();
            for (Cell cell : cells)
                values.add("표 " + cell.table + " 행 " + display(cell.row) + " 열 " + display(cell.column)
                        + " 병합 " + display(cell.rowSpan) + "×" + display(cell.columnSpan));
            values.addAll(regions);
            return values.isEmpty() ? "" : "문서 " + String.join(" / ", values);
        }

        private static String display(int number) {
            return number < 0 ? "미상" : String.valueOf(number);
        }
    }

    public record Segment(int start, int end, Context context) {
    }

    public enum Boundary {NONE, DRAWN, UNKNOWN}

    public enum Side {LEFT, RIGHT, TOP, BOTTOM}

    /**
     * A displayed source measurement, including its last decimal place's rounding interval.
     */
    public record Measurement(double value, double uncertainty) {
        public boolean sameWidth(Measurement other) {
            return other != null && Math.abs(value - other.value) <= uncertainty + other.uncertainty;
        }
    }

    public record BoundedCell(Cell position, int start, int end, Boundary left, Boundary right,
                              Boundary top, Boundary bottom, Map<Side, Measurement> doubleBorders) {
    }

    public record Table(int id, int rows, int columns, int start, int end, List<BoundedCell> cells, int rowGroup,
                        Map<Integer, Measurement> rowBands, Map<Integer, Measurement> columnBands) {
        public Table {
            cells = List.copyOf(cells);
            rowBands = Map.copyOf(rowBands);
            columnBands = Map.copyOf(columnBands);
        }

        /**
         * A complete grid proves that no overlapping or absent cell is being silently dropped.
         */
        public BoundedCell[][] grid() {
            if (rows < 1 || columns < 1 || (long) rows * columns > MAX_GRID_POSITIONS) return null;
            var grid = new BoundedCell[rows][columns];
            for (var entry : cells) {
                var c = entry.position();
                if (!c.known() || c.row() + c.rowSpan() - 1 > rows || c.column() + c.columnSpan() - 1 > columns)
                    return null;
                for (int r = c.row() - 1; r < c.row() + c.rowSpan() - 1; r++)
                    for (int col = c.column() - 1; col < c.column() + c.columnSpan() - 1; col++) {
                        if (grid[r][col] != null) return null;
                        grid[r][col] = entry;
                    }
            }
            return Arrays.stream(grid).flatMap(Arrays::stream).anyMatch(Objects::isNull) ? null : grid;
        }
    }

    public record Baseline(Context context, int start, int end, double minimum, double maximum) {
    }

    public record MemoRange(int start, int end) {
    }

    public record Reading(boolean valid, List<Segment> segments, List<Table> tables, List<Baseline> baselines,
                          List<MemoRange> memos) {
        public Reading {
            segments = List.copyOf(segments);
            tables = List.copyOf(tables);
            baselines = List.copyOf(baselines);
            memos = List.copyOf(memos);
        }

        public String text(String original, int start, int end) {
            var value = new StringBuilder();
            for (var segment : segments) {
                if (segment.context() != null && segment.context().regions().contains("MEMO")) continue;
                int from = Math.max(start, segment.start()), to = Math.min(end, segment.end());
                if (from < to) value.append(original, from, to);
            }
            return value.toString();
        }

        public boolean memo(int start, int end) {
            return memos.stream().anyMatch(m -> m.start() <= start && m.end() >= end);
        }

        public String withoutMemos(String original) {
            if (memos.isEmpty()) return original;
            var value = new StringBuilder();
            int cursor = 0;
            for (var memo : memos.stream().sorted(Comparator.comparingInt(MemoRange::start)).toList()) {
                if (memo.start() < cursor) continue;
                value.append(original, cursor, memo.start());
                cursor = memo.end();
            }
            return value.append(original, cursor, original.length()).toString();
        }

        /**
         * Row alignment is physical context; each consumer must still establish the column's role.
         */
        public Table rowCompanion(Table table) {
            if (table.rowGroup() == 0) return null;
            return tables.stream().filter(t -> t.id() != table.id() && t.rowGroup() == table.rowGroup())
                    .findFirst().orElse(null);
        }
    }

    /**
     * Retain raw extraction elsewhere; editor annotations never become public rules or dates.
     */
    public static Source normative(Source source) {
        if (source.context().stream().noneMatch(p -> p.text().contains("⟦HWPX:MEMO⟧"))) return source;
        var parts = new ArrayList<Part>();
        boolean changed = false;
        for (var part : source.context()) {
            var reading = read(source, part);
            if (!reading.valid()) return source;
            parts.add(new Part(part.location(), reading.withoutMemos(part.text())));
            changed |= !reading.memos().isEmpty();
        }
        if (!changed) return source;
        return new Source(source.label(), source.url(), source.sha256(), source.status(), source.message(),
                org.jibro.collection.extraction.ExcerptClassifier.extract(parts, source.url()), parts);
    }

    record Occurrence(String id, String partKey, Context context) {
    }

    record Links(Map<String, List<String>> related, List<String> incomplete) {
    }

    private static final class Frame {
        final String kind;
        final int table, start, rows, columns;
        int rowGroup;
        Map<Integer, Measurement> rowBands = Map.of(), columnBands = Map.of();
        final Cell cell;
        Boundary left = Boundary.UNKNOWN, right = Boundary.UNKNOWN, top = Boundary.UNKNOWN, bottom = Boundary.UNKNOWN;
        final Map<Side, Measurement> doubleBorders = new EnumMap<>(Side.class);
        final List<BoundedCell> cells = new ArrayList<>();

        Frame(String kind, int table, Cell cell, int start, int rows, int columns) {
            this.kind = kind;
            this.table = table;
            this.cell = cell;
            this.start = start;
            this.rows = rows;
            this.columns = columns;
        }
    }

    private record CellKey(String part, Cell cell) {
    }

    private record TableKey(String part, int table) {
    }

    public static Reading read(Source source, Part part) {
        String text = part.text();
        String format = format(source, part);
        if (format == null || !text.contains("⟦" + format + ":"))
            return new Reading(true, List.of(new Segment(0, text.length(), null)), List.of(), List.of(), List.of());
        var stack = new ArrayDeque<Frame>();
        var tableIds = new HashSet<Integer>();
        var segments = new ArrayList<Segment>();
        var tables = new ArrayList<Table>();
        var baselines = new ArrayList<Baseline>();
        var memos = new ArrayList<MemoRange>();
        Baseline pending = null;
        var lines = LINE.matcher(text);
        int start = 0;
        try {
            while (lines.find()) {
                if (lines.start() == lines.end()) break;
                String value = lines.group().stripTrailing();
                if (!value.startsWith("⟦" + format + ":")) continue;
                if (!value.endsWith("⟧")) return new Reading(false, List.of(), List.of(), List.of(), List.of());
                if (start < lines.start()) {
                    if (!stack.isEmpty() && stack.peek().kind.equals("TABLE") && !text.substring(start, lines.start()).isBlank())
                        return new Reading(false, List.of(), List.of(), List.of(), List.of());
                    segments.add(new Segment(start, lines.start(), context(stack)));
                }
                if (pending != null) {
                    baselines.add(new Baseline(pending.context(), pending.start(), lines.start(), pending.minimum(), pending.maximum()));
                    pending = null;
                }
                String marker = value.substring(format.length() + 2, value.length() - 1);
                var table = TABLE.matcher(marker);
                var cell = CELL.matcher(marker);
                var baseline = BASELINE.matcher(marker);
                var pageNumber = PAGE_NUMBER.matcher(marker);
                if (pageNumber.matches() && format.equals("PDF")) {
                    if (!stack.isEmpty() || !part.location().matches("(?i)(?:.+\\.pdf · )?공고문 " + pageNumber.group(1) + "쪽(?: · 공식 표)?"))
                        return new Reading(false, List.of(), List.of(), List.of(), List.of());
                    // The raw marker retains the printed value; it is a page role, not a rule quantity.
                } else if (baseline.matches() && format.equals("PDF")) {
                    if (stack.isEmpty() || !stack.peek().kind.equals("CELL"))
                        return new Reading(false, List.of(), List.of(), List.of(), List.of());
                    double minimum = Double.parseDouble(baseline.group(1)), maximum = Double.parseDouble(baseline.group(2));
                    if (minimum > maximum) return new Reading(false, List.of(), List.of(), List.of(), List.of());
                    pending = new Baseline(context(stack), lines.end(), -1, minimum, maximum);
                } else if (table.matches()) {
                    int id = number(table.group(1), 2048);
                    int rows = number(table.group(2), 2048), columns = number(table.group(3), 256);
                    if (!tableIds.add(id) || stack.size() >= 128)
                        return new Reading(false, List.of(), List.of(), List.of(), List.of());
                    var frame = new Frame("TABLE", id, null, lines.start(), rows, columns);
                    if (table.group(4) != null) {
                        if (!format.equals("PDF") || !stack.isEmpty())
                            return new Reading(false, List.of(), List.of(), List.of(), List.of());
                        frame.rowGroup = number(table.group(4), 2048);
                    }
                    if (table.group(5) != null || table.group(6) != null) {
                        if (!format.equals("PDF")) throw new IllegalArgumentException("PDF rule band required");
                        frame.rowBands = bands(table.group(5), rows);
                        frame.columnBands = bands(table.group(6), columns);
                    }
                    stack.push(frame);
                } else if (cell.matches()) {
                    int id = number(cell.group(1), 2048);
                    if (stack.isEmpty() || !stack.peek().kind.equals("TABLE") || stack.peek().table != id)
                        return new Reading(false, List.of(), List.of(), List.of(), List.of());
                    var frame = new Frame("CELL", id, new Cell(id, number(cell.group(2), 2048), number(cell.group(3), 256),
                            number(cell.group(4), 2048), number(cell.group(5), 256)), lines.end(), 0, 0);
                    if (cell.group(6) != null) {
                        if (!format.equals("HWPX"))
                            return new Reading(false, List.of(), List.of(), List.of(), List.of());
                        frame.left = Boundary.valueOf(cell.group(6));
                        frame.right = Boundary.valueOf(cell.group(7));
                    }
                    if (cell.group(8) != null) {
                        if (!format.equals("HWPX")) throw new IllegalArgumentException("HWPX border required");
                        frame.top = Boundary.valueOf(cell.group(8));
                        frame.bottom = Boundary.valueOf(cell.group(9));
                    }
                    for (int edge = 0; edge < Side.values().length; edge++) {
                        String width = cell.group(10 + edge);
                        if (width == null) continue;
                        if (!format.equals("HWPX")) throw new IllegalArgumentException("HWPX double border required");
                        frame.doubleBorders.put(Side.values()[edge], measurement(width.substring(0, width.length() - 2), 72 / 25.4));
                    }
                    stack.push(frame);
                } else if (REGIONS.contains(marker)) stack.push(new Frame(marker, -1, null, lines.start(), 0, 0));
                else if (marker.startsWith("/") && !stack.isEmpty() && stack.peek().kind.equals(marker.substring(1))) {
                    var closed = stack.pop();
                    if (closed.kind.equals("MEMO")) memos.add(new MemoRange(closed.start, lines.end()));
                    else if (closed.kind.equals("CELL"))
                        stack.peek().cells.add(new BoundedCell(closed.cell, closed.start, lines.start(), closed.left, closed.right, closed.top, closed.bottom, Map.copyOf(closed.doubleBorders)));
                    else if (closed.kind.equals("TABLE"))
                        tables.add(new Table(closed.table, closed.rows, closed.columns, closed.start, lines.end(), closed.cells, closed.rowGroup, closed.rowBands, closed.columnBands));
                } else return new Reading(false, List.of(), List.of(), List.of(), List.of());
                start = lines.end();
            }
            if (!stack.isEmpty()) return new Reading(false, List.of(), List.of(), List.of(), List.of());
            if (start < text.length()) segments.add(new Segment(start, text.length(), context(stack)));
            tables.sort(Comparator.comparingInt(Table::start));
            if (!validRowGroups(tables, text))
                return new Reading(false, List.of(), List.of(), List.of(), List.of());
            return new Reading(true, segments, tables, baselines, memos);
        } catch (IllegalArgumentException invalid) {
            return new Reading(false, List.of(), List.of(), List.of(), List.of());
        }
    }

    private static Measurement measurement(String text, double factor) {
        if (!text.matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException("Invalid measurement");
        double value = Double.parseDouble(text) * factor;
        int dot = text.indexOf('.'), decimals = dot < 0 ? 0 : text.length() - dot - 1;
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid measurement");
        return new Measurement(value, Math.pow(10, -decimals) * factor / 2);
    }

    private static Map<Integer, Measurement> bands(String value, int size) {
        if (value == null) return Map.of();
        var result = new LinkedHashMap<Integer, Measurement>();
        for (String entry : value.split(",")) {
            String[] parts = entry.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid rule band");
            int position = number(parts[0], size);
            if (position <= 1 || position >= size || result.put(position, measurement(parts[1], 1)) != null)
                throw new IllegalArgumentException("Invalid rule band");
        }
        return Map.copyOf(result);
    }

    private static boolean validRowGroups(List<Table> tables, String text) {
        var groups = new HashMap<Integer, List<Table>>();
        for (var table : tables)
            if (table.rowGroup() > 0)
                groups.computeIfAbsent(table.rowGroup(), ignored -> new ArrayList<>()).add(table);
        for (var pair : groups.values()) {
            if (pair.size() != 2) return false;
            var first = pair.get(0);
            var second = pair.get(1);
            if (first.rows() < 2 || first.rows() != second.rows()
                    || (first.columns() == 1) == (second.columns() == 1)
                    || first.grid() == null || second.grid() == null
                    || first.end() > second.start() || !text.substring(first.end(), second.start()).isBlank())
                return false;
        }
        return true;
    }

    public static String format(Source source, Part part) {
        String label = source.label() == null ? "" : source.label().toLowerCase(Locale.ROOT);
        if (label.endsWith(".hwpx") || label.endsWith(".owpml")
                || part.location().matches(".*(?:^| · )HWPX Contents/section\\d+\\.xml \\(쪽 번호 확인 필요\\)$"))
            return "HWPX";
        if (label.endsWith(".pdf") || label.endsWith(".zip")
                && part.location().matches("(?i).+\\.pdf · 공고문 [1-9][0-9]*쪽")) return "PDF";
        return null;
    }

    static boolean sameFlow(Context left, Context right) {
        return Objects.equals(left, right);
    }

    /**
     * Physical neighbours are context, never proof that a first-row cell is a semantic header.
     */
    static Links relate(List<Occurrence> occurrences) {
        var byCell = new LinkedHashMap<CellKey, List<String>>();
        for (Occurrence item : occurrences)
            if (item.context != null && item.context.cell() != null)
                byCell.computeIfAbsent(new CellKey(item.partKey, item.context.cell()), ignored -> new ArrayList<>()).add(item.id);
        var byRow = new HashMap<TableKey, Map<Integer, List<CellKey>>>();
        var firstRows = new HashMap<TableKey, List<CellKey>>();
        var invalidTables = new HashSet<TableKey>();
        var coordinateCounts = new HashMap<TableKey, Integer>();
        for (CellKey key : byCell.keySet()) {
            Cell cell = key.cell;
            var table = new TableKey(key.part, cell.table);
            if (!cell.known() || cell.row + cell.rowSpan > 2049 || cell.column + cell.columnSpan > 257) {
                invalidTables.add(table);
                continue;
            }
            int count = coordinateCounts.merge(table, cell.rowSpan, Integer::sum);
            if (count > MAX_GRID_POSITIONS) {
                invalidTables.add(table);
                continue;
            }
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
            if (invalidTables.contains(table) || context.cells.stream().anyMatch(c -> !c.known())) {
                incomplete.add(item.id);
                continue;
            }
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
