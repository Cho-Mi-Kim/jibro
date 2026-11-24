package org.jibro.collection.extraction;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * HWPX text with explicit table coordinates, never inferred from a flattened sentence.
 * Marker lines use one-based row/column addresses and retain nested table and note boundaries.
 * Missing source coordinates remain "unknown"; no empty or merged cell is filled with guessed text.
 */
public final class HwpxStructuredText {
    private static final String PREFIX = "⟦HWPX:";
    private static final Pattern MARKER = Pattern.compile("(?m)^⟦HWPX:[^⟦⟧\\r\\n]+⟧\\r?\\n?");
    private static final int MAX_DEPTH = 128;
    private static final int MAX_TABLE_DEPTH = 16;
    private static final int MAX_TABLES = 2048;
    private static final int MAX_CELLS = 10_000;
    private static final int MAX_ROWS = 2048;
    private static final int MAX_COLUMNS = 256;
    private static final int MAX_OCCUPIED_CELLS = 40_000;
    private static final int MAX_ALIGNED_EDGE_OFFSET_HWP_UNITS = 50;

    private HwpxStructuredText() {
    }

    /**
     * For legacy text comparison only. Consumers needing relationships must retain the markers.
     */
    public static String withoutStructure(String text) {
        return MARKER.matcher(text).replaceAll("");
    }

    static String read(byte[] bytes, int maxChars) throws XMLStreamException, IOException {
        return read(bytes, maxChars, HwpxStyles.empty(), null);
    }

    static String read(byte[] bytes, int maxChars, HwpxStyles paragraphStyles, ImageText images) throws XMLStreamException, IOException {
        var reader = xmlReader(bytes);
        try {
            return new Reading(maxChars, paragraphStyles, images).read(reader);
        } finally {
            reader.close();
        }
    }

    @FunctionalInterface
    interface ImageText {
        String read(String binaryId) throws IOException;
    }

    static XMLStreamReader xmlReader(byte[] bytes) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("외부 XML 금지");
        });
        return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }

    private static final class Reading {
        private final int limit;
        private HwpxStyles paragraphStyles;
        private final ImageText images;
        private HwpxPicture picture;
        private final ArrayDeque<Integer> memoFields = new ArrayDeque<>();
        private final ArrayDeque<Integer> memoStories = new ArrayDeque<>();
        private final ArrayDeque<HwpxStyles> parentStyles = new ArrayDeque<>();
        private final ArrayDeque<String> separateNumberRegions = new ArrayDeque<>();
        private final ArrayDeque<Paragraph> paragraphs = new ArrayDeque<>();
        private final StringBuilder output = new StringBuilder();
        private final ArrayDeque<Table> tables = new ArrayDeque<>();
        private int chars;
        private int tableCount;
        private int cellCount;
        private int depth;
        private int textDepth;

        Reading(int limit, HwpxStyles paragraphStyles, ImageText images) {
            this.limit = limit;
            this.paragraphStyles = paragraphStyles;
            this.images = images;
        }

        String read(XMLStreamReader reader) throws XMLStreamException, IOException {
            while (reader.hasNext()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("문서 추출 중단");
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DTD 금지");
                if (event == XMLStreamConstants.START_ELEMENT) start(reader);
                else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && textDepth > 0) {
                    String text = reader.getText();
                    // Reserved delimiters cannot be supplied by a document as trusted structural metadata.
                    if (text.indexOf('⟦') >= 0 || text.indexOf('⟧') >= 0) throw new IOException("HWPX 구조 구분 문자 확인 필요");
                    append(text);
                } else if (event == XMLStreamConstants.END_ELEMENT) end(reader.getLocalName());
            }
            if (!tables.isEmpty()) throw new IOException("HWPX 표 구조 확인 필요");
            return output.toString();
        }

        private void start(XMLStreamReader reader) throws IOException {
            if (++depth > MAX_DEPTH) throw new IOException("XML 중첩 제한 초과");
            String name = reader.getLocalName();
            if (name.equals("pic")) {
                if (picture != null) throw new IOException("HWPX 그림 중첩 확인 필요");
                picture = new HwpxPicture();
            }
            if (picture != null) picture.start(reader, depth);
            switch (name) {
                case "p" -> {
                    paragraphs.push(new Paragraph(depth, target()));
                    String prefix = paragraphStyles.prefix(reader.getAttributeValue(null, "paraPrIDRef"), separateNumberRegions.peek());
                    if (!prefix.isEmpty()) append(prefix + " ");
                }
                case "fieldBegin" -> {
                    if ("MEMO".equals(reader.getAttributeValue(null, "type"))) memoFields.push(depth);
                }
                case "subList" -> {
                    if (!memoFields.isEmpty() && memoFields.peek() == depth - 1) {
                        memoStories.push(depth);
                        parentStyles.push(paragraphStyles);
                        paragraphStyles = paragraphStyles.separateStory();
                        marker("MEMO");
                    }
                }
                case "t" -> textDepth++;
                case "lineBreak" -> append("\n");
                case "tab" -> append("\t");
                case "tbl" -> {
                    if (++tableCount > MAX_TABLES || tables.size() >= MAX_TABLE_DEPTH)
                        throw new IOException("HWPX 표 개수·중첩 제한 초과");
                    Table table = new Table(tableCount, number(reader, "rowCnt", MAX_ROWS, false), number(reader, "colCnt", MAX_COLUMNS, false));
                    table.depth = depth;
                    table.anchor = paragraphs.peek();
                    table.anchorOffset = target().length();
                    table.flowWrap = Set.of("TOP_AND_BOTTOM", "SQUARE").contains(String.valueOf(reader.getAttributeValue(null, "textWrap")));
                    tables.push(table);
                    marker("TABLE id=" + table.id + " rows=" + value(table.rows) + " cols=" + value(table.columns));
                }
                case "pos" -> {
                    if (!tables.isEmpty() && tables.peek().depth == depth - 1) {
                        Table table = tables.peek();
                        table.floatingParagraph = table.flowWrap
                            && "0".equals(reader.getAttributeValue(null, "treatAsChar"))
                            && "0".equals(reader.getAttributeValue(null, "allowOverlap"))
                            && "PARA".equals(reader.getAttributeValue(null, "vertRelTo"))
                            && "TOP".equals(reader.getAttributeValue(null, "vertAlign"));
                        table.verticalOffset = metric(reader, "vertOffset");
                    }
                }
                case "lineseg" -> {
                    if (!paragraphs.isEmpty() && paragraphs.peek().depth == depth - 2)
                        paragraphs.peek().line(metric(reader, "vertpos"), metric(reader, "vertsize"));
                }
                case "tc" -> {
                    if (++cellCount > MAX_CELLS || tables.isEmpty() || tables.peek().cell != null)
                        throw new IOException("HWPX 셀 구조·개수 확인 필요");
                    tables.peek().cell = new Cell();
                    tables.peek().cell.border = reader.getAttributeValue(null, "borderFillIDRef");
                }
                case "cellzone" -> {
                    if (!tables.isEmpty()) tables.peek().zones.add(new Zone(
                        number(reader, "startRowAddr", MAX_ROWS - 1, true), number(reader, "startColAddr", MAX_COLUMNS - 1, true),
                        number(reader, "endRowAddr", MAX_ROWS - 1, true), number(reader, "endColAddr", MAX_COLUMNS - 1, true)));
                }
                case "cellAddr" -> {
                    Cell cell = cell();
                    cell.row = number(reader, "rowAddr", MAX_ROWS - 1, true);
                    cell.column = number(reader, "colAddr", MAX_COLUMNS - 1, true);
                }
                case "cellSpan" -> {
                    Cell cell = cell();
                    cell.rowSpan = number(reader, "rowSpan", MAX_ROWS, false);
                    cell.columnSpan = number(reader, "colSpan", MAX_COLUMNS, false);
                }
                case "cellSz" -> cell().width = metric(reader, "width");
                case "footNote", "endNote", "header", "footer" -> {
                    separateNumberRegions.push(name);
                    marker(name.toUpperCase(java.util.Locale.ROOT));
                }
                default -> {
                }
            }
        }

        private void end(String name) throws IOException {
            if (picture != null) {
                picture.end(name);
                if (name.equals("pic")) {
                    String reference = picture.unmodifiedInlineImage();
                    if (reference != null && images != null) {
                        String text = images.read(reference);
                        if (text != null) append(text);
                    }
                    picture = null;
                }
            }
            switch (name) {
                case "t" -> textDepth--;
                case "p" -> {
                    append("\n");
                    finish(paragraphs.pop());
                }
                case "subList" -> {
                    if (!memoStories.isEmpty() && memoStories.peek() == depth) {
                        marker("/MEMO");
                        memoStories.pop();
                        paragraphStyles = parentStyles.pop();
                    }
                }
                case "fieldBegin" -> {
                    if (!memoFields.isEmpty() && memoFields.peek() == depth) memoFields.pop();
                }
                case "tc" -> {
                    Table table = tables.peek();
                    Cell cell = cell();
                    table.validate(cell);
                    table.cell = null;
                    marker("CELL table=" + table.id + " row=" + address(cell.row) + " col=" + address(cell.column)
                        + " rowspan=" + value(cell.rowSpan) + " colspan=" + value(cell.columnSpan)
                        + (table.hasZoneOverride(cell) ? "" : paragraphStyles.borders(cell.border).metadata()));
                    transfer(cell.text);
                    marker("/CELL");
                }
                case "tbl" -> {
                    if (tables.isEmpty() || tables.peek().cell != null) throw new IOException("HWPX 표 종료 확인 필요");
                    marker("/TABLE");
                    tables.peek().alignSubpointBoundaries();
                    Table table = tables.pop();
                    if (table.floatingParagraph && table.anchor != null && table.anchor.target == target())
                        table.anchor.floating.add(table);
                    else transfer(table.text);
                }
                case "footNote", "endNote", "header", "footer" -> {
                    marker("/" + name.toUpperCase(java.util.Locale.ROOT));
                    separateNumberRegions.pop();
                }
                default -> {
                }
            }
            depth--;
        }

        private Cell cell() throws IOException {
            if (tables.isEmpty() || tables.peek().cell == null) throw new IOException("HWPX 셀 위치 확인 필요");
            return tables.peek().cell;
        }

        private void finish(Paragraph paragraph) throws IOException {
            boolean oneBelow = paragraph.floating.size() == 1 && paragraph.floating.get(0).belowText();
            for (int index = paragraph.floating.size() - 1; index >= 0; index--) {
                Table table = paragraph.floating.get(index);
                int position = oneBelow ? paragraph.target.length() : table.anchorOffset;
                boolean separator = position > 0 && paragraph.target.charAt(position - 1) != '\n';
                if (separator) {
                    if (chars == limit) throw new IOException("XML 텍스트 길이 제한 초과");
                    chars++;
                }
                // Buffered table text was already counted. Only explicit non-overlapping placement
                // below this paragraph moves it; other anchors retain their original XML position.
                paragraph.target.insert(position, (separator ? "\n" : "") + table.text);
            }
        }

        private StringBuilder target() {
            if (tables.isEmpty()) return output;
            Table table = tables.peek();
            return table.cell == null ? table.text : table.cell.text;
        }

        private void marker(String contents) throws IOException {
            StringBuilder target = target();
            if (!target.isEmpty() && target.charAt(target.length() - 1) != '\n') append("\n");
            append(PREFIX + contents + "⟧\n");
        }

        private void append(String text) throws IOException {
            if (text.length() > limit - chars) throw new IOException("XML 텍스트 길이 제한 초과");
            chars += text.length();
            target().append(text);
        }

        private void transfer(StringBuilder text) throws IOException {
            StringBuilder target = target();
            if (!target.isEmpty() && target.charAt(target.length() - 1) != '\n' && text.indexOf(PREFIX) == 0)
                append("\n");
            // This text was already counted while reading; copying between nested buffers adds no source text.
            target.append(text);
        }
    }

    private static final class Table {
        final int id;
        final int rows;
        final int columns;
        final StringBuilder text = new StringBuilder();
        final Set<Long> occupied = new HashSet<>();
        final List<Zone> zones = new ArrayList<>();
        final List<Cell> cells = new ArrayList<>();
        Cell cell;
        int depth, anchorOffset;
        Paragraph anchor;
        boolean flowWrap, floatingParagraph;
        double verticalOffset = Double.NaN;

        Table(int id, int rows, int columns) {
            this.id = id;
            this.rows = rows;
            this.columns = columns;
        }

        void validate(Cell cell) throws IOException {
            cells.add(cell);
            if (cell.row < 0 || cell.column < 0 || cell.rowSpan < 0 || cell.columnSpan < 0) return;
            int bottom = cell.row + cell.rowSpan, right = cell.column + cell.columnSpan;
            if (bottom > MAX_ROWS || right > MAX_COLUMNS || rows >= 0 && bottom > rows || columns >= 0 && right > columns)
                throw new IOException("HWPX 표 범위를 벗어난 셀");
            if ((long) cell.rowSpan * cell.columnSpan > MAX_OCCUPIED_CELLS - occupied.size())
                throw new IOException("HWPX 표 범위 제한 초과");
            for (int row = cell.row; row < bottom; row++)
                for (int column = cell.column; column < right; column++) {
                    if (!occupied.add((long) row * MAX_COLUMNS + column)) throw new IOException("HWPX 표 셀 중복 확인 필요");
                }
        }

        /**
         * Collapse only a measured sub-point offset, never an actual narrow input/content cell.
         */
        void alignSubpointBoundaries() {
            if (columns < 2 || cells.isEmpty() || cells.stream().anyMatch(c -> c.column < 0 || c.columnSpan < 1
                || !Double.isFinite(c.width) || c.width <= 0)) return;
            var edges = new double[columns + 1];
            java.util.Arrays.fill(edges, Double.NaN);
            edges[0] = 0;
            for (int pass = 0; pass < columns; pass++) {
                boolean changed = false;
                for (var c : cells) {
                    int start = c.column, end = start + c.columnSpan;
                    if (end > columns) return;
                    if (Double.isFinite(edges[start]) && !Double.isFinite(edges[end])) {
                        edges[end] = edges[start] + c.width;
                        changed = true;
                    } else if (Double.isFinite(edges[end]) && !Double.isFinite(edges[start])) {
                        edges[start] = edges[end] - c.width;
                        changed = true;
                    } else if (Double.isFinite(edges[start]) && Double.isFinite(edges[end])
                        && Math.abs(edges[end] - edges[start] - c.width) > 1) return;
                }
                if (!changed) break;
            }
            var removed = new ArrayList<Integer>();
            // HWP units are 1/100 point. Match the PDF reader's half-point rule-joining precision.
            for (int column = 0; column < columns; column++) {
                double width = edges[column + 1] - edges[column];
                if (!Double.isFinite(width) || width <= 0) return;
                if (width > MAX_ALIGNED_EDGE_OFFSET_HWP_UNITS) continue;
                int at = column;
                if (cells.stream().anyMatch(c -> c.column == at && c.columnSpan == 1)) continue;
                removed.add(column + 1);
            }
            if (removed.isEmpty()) return;
            var pattern = java.util.regex.Pattern.compile("(⟦HWPX:CELL table=" + id + " row=\\d+ col=)(\\d+)( rowspan=\\d+ colspan=)(\\d+)");
            var matcher = pattern.matcher(text);
            var normalized = new StringBuilder();
            while (matcher.find()) {
                int start = Integer.parseInt(matcher.group(2)) - 1, end = start + Integer.parseInt(matcher.group(4));
                int from = start - (int) removed.stream().filter(edge -> edge <= start).count();
                int to = end - (int) removed.stream().filter(edge -> edge <= end).count();
                if (to <= from) return;
                matcher.appendReplacement(normalized, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + (from + 1) + matcher.group(3) + (to - from)));
            }
            matcher.appendTail(normalized);
            String header = "TABLE id=" + id + " rows=" + value(rows) + " cols=" + value(columns);
            String aligned = normalized.toString().replace(header + "⟧", "TABLE id=" + id + " rows=" + value(rows) + " cols=" + (columns - removed.size()) + "⟧");
            text.setLength(0);
            text.append(aligned);
        }

        boolean hasZoneOverride(Cell cell) {
            return zones.stream().anyMatch(zone -> zone.overlaps(cell));
        }

        boolean belowText() {
            return anchor != null && anchor.validLines && Double.isFinite(anchor.firstLine)
                && Double.isFinite(verticalOffset)
                // vertOffset locates the table itself. Outside spacing does not move its edge;
                // flowWithText only restricts its page area, not its position within this paragraph.
                && verticalOffset >= anchor.lastBottom - anchor.firstLine;
        }
    }

    private static final class Paragraph {
        final int depth;
        final StringBuilder target;
        final List<Table> floating = new ArrayList<>();
        double firstLine = Double.NaN, lastTop = Double.NaN, lastBottom = Double.NaN;
        boolean validLines = true;

        Paragraph(int depth, StringBuilder target) {
            this.depth = depth;
            this.target = target;
        }

        void line(double top, double height) {
            if (!Double.isFinite(top) || !Double.isFinite(height) || height <= 0 || Double.isFinite(lastTop) && top < lastTop) {
                validLines = false;
                return;
            }
            if (!Double.isFinite(firstLine)) firstLine = top;
            lastTop = top;
            lastBottom = Double.isFinite(lastBottom) ? Math.max(lastBottom, top + height) : top + height;
        }
    }

    private record Zone(int firstRow, int firstColumn, int lastRow, int lastColumn) {
        boolean overlaps(Cell cell) {
            if (firstRow < 0 || firstColumn < 0 || lastRow < firstRow || lastColumn < firstColumn
                || cell.row < 0 || cell.column < 0 || cell.rowSpan < 1 || cell.columnSpan < 1) return true;
            return firstRow < cell.row + cell.rowSpan && lastRow >= cell.row
                && firstColumn < cell.column + cell.columnSpan && lastColumn >= cell.column;
        }
    }

    private static final class Cell {
        final StringBuilder text = new StringBuilder();
        int row = -1;
        int column = -1;
        int rowSpan = -1;
        int columnSpan = -1;
        String border;
        double width = Double.NaN;
    }

    private static int number(XMLStreamReader reader, String name, int max, boolean zeroAllowed) throws IOException {
        String raw = reader.getAttributeValue(null, name);
        if (raw == null) return -1;
        try {
            int value = Integer.parseInt(raw);
            if (value < (zeroAllowed ? 0 : 1) || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IOException("HWPX 표 좌표·범위 확인 필요");
        }
    }

    private static double metric(XMLStreamReader reader, String name) {
        try {
            double value = Double.parseDouble(reader.getAttributeValue(null, name));
            return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
        } catch (NumberFormatException | NullPointerException unavailable) {
            return Double.NaN;
        }
    }

    private static String address(int coordinate) {
        return coordinate < 0 ? "unknown" : String.valueOf(coordinate + 1);
    }

    private static String value(int value) {
        return value < 0 ? "unknown" : String.valueOf(value);
    }
}
