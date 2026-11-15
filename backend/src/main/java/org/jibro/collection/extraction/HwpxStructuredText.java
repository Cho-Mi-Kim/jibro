package org.jibro.collection.extraction;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** HWPX text with explicit table coordinates, never inferred from a flattened sentence.
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

    private HwpxStructuredText() {}

    /** For legacy text comparison only. Consumers needing relationships must retain the markers. */
    public static String withoutStructure(String text) {
        return MARKER.matcher(text).replaceAll("");
    }

    static String read(byte[] bytes, int maxChars) throws XMLStreamException, IOException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("외부 XML 금지"); });
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
        try {
            return new Reading(maxChars).read(reader);
        } finally {
            reader.close();
        }
    }

    private static final class Reading {
        private final int limit;
        private final StringBuilder output = new StringBuilder();
        private final ArrayDeque<Table> tables = new ArrayDeque<>();
        private int chars;
        private int tableCount;
        private int cellCount;
        private int depth;
        private int textDepth;

        Reading(int limit) { this.limit = limit; }

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
            switch (name) {
                case "t" -> textDepth++;
                case "lineBreak" -> append("\n");
                case "tab" -> append("\t");
                case "tbl" -> {
                    if (++tableCount > MAX_TABLES || tables.size() >= MAX_TABLE_DEPTH) throw new IOException("HWPX 표 개수·중첩 제한 초과");
                    Table table = new Table(tableCount, number(reader, "rowCnt", MAX_ROWS, false), number(reader, "colCnt", MAX_COLUMNS, false));
                    tables.push(table);
                    marker("TABLE id=" + table.id + " rows=" + value(table.rows) + " cols=" + value(table.columns));
                }
                case "tc" -> {
                    if (++cellCount > MAX_CELLS || tables.isEmpty() || tables.peek().cell != null) throw new IOException("HWPX 셀 구조·개수 확인 필요");
                    tables.peek().cell = new Cell();
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
                case "footNote", "endNote", "header", "footer" -> marker(name.toUpperCase(java.util.Locale.ROOT));
                default -> { }
            }
        }

        private void end(String name) throws IOException {
            switch (name) {
                case "t" -> textDepth--;
                case "p" -> append("\n");
                case "tc" -> {
                    Table table = tables.peek();
                    Cell cell = cell();
                    table.validate(cell);
                    table.cell = null;
                    marker("CELL table=" + table.id + " row=" + address(cell.row) + " col=" + address(cell.column)
                        + " rowspan=" + value(cell.rowSpan) + " colspan=" + value(cell.columnSpan));
                    transfer(cell.text);
                    marker("/CELL");
                }
                case "tbl" -> {
                    if (tables.isEmpty() || tables.peek().cell != null) throw new IOException("HWPX 표 종료 확인 필요");
                    marker("/TABLE");
                    Table table = tables.pop();
                    transfer(table.text);
                }
                case "footNote", "endNote", "header", "footer" -> marker("/" + name.toUpperCase(java.util.Locale.ROOT));
                default -> { }
            }
            depth--;
        }

        private Cell cell() throws IOException {
            if (tables.isEmpty() || tables.peek().cell == null) throw new IOException("HWPX 셀 위치 확인 필요");
            return tables.peek().cell;
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
            if (!target.isEmpty() && target.charAt(target.length() - 1) != '\n' && text.indexOf(PREFIX) == 0) append("\n");
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
        Cell cell;

        Table(int id, int rows, int columns) {
            this.id = id;
            this.rows = rows;
            this.columns = columns;
        }

        void validate(Cell cell) throws IOException {
            if (cell.row < 0 || cell.column < 0 || cell.rowSpan < 0 || cell.columnSpan < 0) return;
            int bottom = cell.row + cell.rowSpan, right = cell.column + cell.columnSpan;
            if (bottom > MAX_ROWS || right > MAX_COLUMNS || rows >= 0 && bottom > rows || columns >= 0 && right > columns)
                throw new IOException("HWPX 표 범위를 벗어난 셀");
            if ((long) cell.rowSpan * cell.columnSpan > MAX_OCCUPIED_CELLS - occupied.size()) throw new IOException("HWPX 표 범위 제한 초과");
            for (int row = cell.row; row < bottom; row++) for (int column = cell.column; column < right; column++) {
                if (!occupied.add((long) row * MAX_COLUMNS + column)) throw new IOException("HWPX 표 셀 중복 확인 필요");
            }
        }
    }

    private static final class Cell {
        final StringBuilder text = new StringBuilder();
        int row = -1;
        int column = -1;
        int rowSpan = -1;
        int columnSpan = -1;
    }

    private static int number(XMLStreamReader reader, String name, int max, boolean zeroAllowed) throws IOException {
        String raw = reader.getAttributeValue(null, name);
        if (raw == null) return -1;
        try {
            int value = Integer.parseInt(raw);
            if (value < (zeroAllowed ? 0 : 1) || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) { throw new IOException("HWPX 표 좌표·범위 확인 필요"); }
    }

    private static String address(int coordinate) { return coordinate < 0 ? "unknown" : String.valueOf(coordinate + 1); }
    private static String value(int value) { return value < 0 ? "unknown" : String.valueOf(value); }
}
