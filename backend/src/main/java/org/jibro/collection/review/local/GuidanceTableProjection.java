package org.jibro.collection.review.local;

import java.util.ArrayList;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;

/**
 * Complete, explicitly headed PDF/HWPX cells are projected through the same guidance vocabulary.
 */
public final class GuidanceTableProjection {
    private GuidanceTableProjection() {
    }

    public static boolean structuredLabel(String label) {
        if (label == null) return false;
        String name = label.toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".hwpx") || name.endsWith(".owpml");
    }

    @FunctionalInterface
    public interface RowRenderer {
        String render(List<String> headers, List<String> values, String sectionHeading);
    }

    public static String project(Source source, Part part, RowRenderer render) {
        var reading = DocumentContextReader.read(source, part);
        if (reading.valid() && !reading.memos().isEmpty()) {
            part = new Part(part.location(), reading.withoutMemos(part.text()));
            reading = DocumentContextReader.read(source, part);
        }
        if (!reading.valid() || reading.tables().isEmpty()) return part.text();
        var output = new StringBuilder(part.text());
        // Descending offsets keep the untouched extraction as the sole location reference.
        for (int i = reading.tables().size() - 1; i >= 0; i--) {
            var table = reading.tables().get(i);
            if (table.rows() < 2 || table.columns() < 2 || table.columns() > 6) continue;
            if (reading.tables().stream().anyMatch(t -> t.start() > table.start() && t.end() < table.end())) continue;
            var grid = table.grid();
            if (grid == null || reading.segments().stream().anyMatch(s -> s.start() >= table.start() && s.end() <= table.end()
                    && s.context() != null && !s.context().regions().isEmpty())) continue;
            var continuation = continuedHeader(source, part, reading, table);
            var headers = new ArrayList<String>();
            boolean valid = true;
            if (continuation == null) {
                for (var cell : grid[0]) {
                    if (cell.position().rowSpan() != 1) valid = false;
                    headers.add(value(part, cell, reading));
                }
            } else headers.addAll(continuation.headers());
            var rows = new ArrayList<String>();
            boolean recognized = false;
            String heading = continuation == null ? sectionHeading(reading.text(part.text(), 0, table.start())) : continuation.heading();
            for (int row = continuation == null ? 1 : 0; valid && row < grid.length; row++) {
                var values = new ArrayList<String>();
                for (var cell : grid[row]) values.add(value(part, cell, reading));
                String statement = render.render(headers, values, heading);
                if (statement == null) {
                    var fields = new ArrayList<String>();
                    for (int col = 0; col < table.columns(); col++)
                        fields.add(headers.get(col) + " : " + values.get(col));
                    statement = "표 " + table.id() + " 행 " + (row + 1) + " · " + String.join(" / ", fields);
                } else recognized = true;
                rows.add(statement);
            }
            if (valid && recognized) output.replace(table.start(), table.end(), String.join("\n", rows) + "\n");
        }
        return output.toString();
    }

    private record ContinuedHeader(List<String> headers, String heading) {
    }

    private static ContinuedHeader continuedHeader(Source source, Part part, DocumentContextReader.Reading reading,
                                                   DocumentContextReader.Table table) {
        if (source.label() == null || !source.label().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                || !table.equals(reading.tables().get(0)) || !reading.text(part.text(), 0, table.start()).isBlank())
            return null;
        int index = source.context().indexOf(part);
        if (index <= 0) return null;
        var previous = source.context().get(index - 1);
        var page = java.util.regex.Pattern.compile("공고문 ([1-9][0-9]*)쪽");
        var before = page.matcher(previous.location());
        var after = page.matcher(part.location());
        if (!before.matches() || !after.matches() || Integer.parseInt(after.group(1)) != Integer.parseInt(before.group(1)) + 1)
            return null;
        var prior = DocumentContextReader.read(source, previous);
        if (!prior.valid() || prior.tables().isEmpty()) return null;
        var last = prior.tables().get(prior.tables().size() - 1);
        var oldGrid = last.grid();
        var grid = table.grid();
        if (oldGrid == null || grid == null || oldGrid.length < 2 || last.columns() != table.columns()
                || !prior.text(previous.text(), last.end(), previous.text().length()).isBlank()) return null;
        int lastNumber = mandatoryNumber(value(previous, oldGrid[oldGrid.length - 1][0], prior));
        int firstNumber = mandatoryNumber(value(part, grid[0][0], reading));
        if (lastNumber < 1 || firstNumber != lastNumber + 1) return null;
        var headers = new ArrayList<String>();
        for (var cell : oldGrid[0]) {
            if (cell.position().rowSpan() != 1 || cell.position().columnSpan() != 1) return null;
            headers.add(value(previous, cell, prior));
        }
        // A continuous numbered list is the evidence for carrying its own columns and heading.
        // An unrelated table with the same column count or a missing page does not qualify.
        if (mandatoryNumber(headers.get(0)) > 0) return null;
        return new ContinuedHeader(List.copyOf(headers), sectionHeading(prior.text(previous.text(), 0, last.start())));
    }

    private static int mandatoryNumber(String text) {
        var marker = java.util.regex.Pattern.compile("^\\[필수제출\\s*([1-9][0-9]*)].*").matcher(text);
        return marker.matches() ? Integer.parseInt(marker.group(1)) : -1;
    }

    public static String sectionHeading(String text) {
        String result = null;
        for (String line : text.lines().toList()) {
            var heading = java.util.regex.Pattern.compile("^(?:[■□◆●▪]\\s*|\\d{1,2}\\.\\s*)(.{1,120})$").matcher(line.strip());
            if (heading.matches()) result = heading.group(1).strip();
        }
        return result;
    }

    public record Explanation(String text, boolean complete) {
    }

    /**
     * Human-readable source text; only a complete grid can establish a displayed row relationship.
     */
    public static Explanation explanation(Source source, Part part, java.util.function.Predicate<List<String>> header) {
        var reading = DocumentContextReader.read(source, part);
        if (!reading.valid()) return new Explanation("", false);
        if (DocumentContextReader.format(source, part) == null && part.text().contains("⟦"))
            return new Explanation("", false);
        String text = explainRange(part, reading, 0, part.text().length(), header);
        return new Explanation(text == null ? reading.text(part.text(), 0, part.text().length()) : text, text != null);
    }

    private static String explainRange(Part part, DocumentContextReader.Reading reading, int start, int end,
                                       java.util.function.Predicate<List<String>> header) {
        var output = new StringBuilder();
        int cursor = start;
        for (var table : reading.tables()) {
            if (table.start() < cursor || table.end() > end) continue;
            if (reading.memo(table.start(), table.end())) {
                output.append(reading.text(part.text(), cursor, table.start()));
                cursor = table.end();
                continue;
            }
            var grid = table.grid();
            if (grid == null) return null;
            output.append(reading.text(part.text(), cursor, table.start()));
            if (table.columns() == 1) {
                for (var cell : table.cells().stream().sorted(java.util.Comparator.comparingInt(c -> c.position().row())).toList()) {
                    String body = explainRange(part, reading, cell.start(), cell.end(), header);
                    if (body == null) return null;
                    output.append(body.strip()).append('\n');
                }
            } else {
                output.append('\n');
                var labels = java.util.Arrays.stream(grid[0]).map(cell -> value(part, cell, reading)).toList();
                boolean labelled = grid.length > 1 && header.test(labels)
                        && java.util.Arrays.stream(grid[0]).allMatch(cell -> cell.position().rowSpan() == 1 && cell.position().columnSpan() == 1
                        && reading.tables().stream().noneMatch(t -> t.start() >= cell.start() && t.end() <= cell.end()));
                for (int row = labelled ? 1 : 0; row < grid.length; row++) {
                    output.append("표 안내 · ");
                    for (int col = 0; col < table.columns(); col++) {
                        if (col > 0) output.append(" / ");
                        if (labelled && !labels.get(col).isEmpty()) output.append(labels.get(col)).append(": ");
                        var cell = grid[row][col];
                        String body = explainRange(part, reading, cell.start(), cell.end(), header);
                        if (body == null) return null;
                        boolean nested = reading.tables().stream().anyMatch(t -> t.start() >= cell.start() && t.end() <= cell.end());
                        // Delimit the containing cell explicitly; a child row must not become a sibling of its parent row.
                        if (nested) output.append("［\n").append(body.strip()).append("\n］");
                        else output.append(body.strip().replaceAll("\\s+", " "));
                    }
                    output.append('\n');
                }
                output.append('\n');
            }
            cursor = table.end();
        }
        output.append(reading.text(part.text(), cursor, end));
        return output.toString();
    }

    private static String value(Part part, DocumentContextReader.BoundedCell cell, DocumentContextReader.Reading reading) {
        return reading.text(part.text(), cell.start(), cell.end()).strip().replaceAll("\\s+", " ");
    }
}
