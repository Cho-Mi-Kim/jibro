package org.jibro.collection.review.local;

import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Part;

/**
 * Reads the cell coordinates and merge/visibility records emitted by SpreadsheetTextParser.
 */
public final class SpreadsheetContextReader {
    private SpreadsheetContextReader() {
    }

    public record Row(int number, Map<String, String> cells, boolean readable) {
    }

    public record Sheet(String name, List<String> merges, List<Row> rows, boolean complete) {
    }

    public static Sheet read(Part part) {
        var lines = part.text().lines().toList();
        if (lines.isEmpty()) return new Sheet("", List.of(), List.of(), false);
        boolean wholeSheet = lines.get(0).startsWith("워크시트: ");
        if (!wholeSheet && !lines.get(0).matches("행 [1-9][0-9]*(?: \\[숨김])?"))
            return new Sheet("", List.of(), List.of(), false);
        String name = wholeSheet ? lines.get(0).substring("워크시트: ".length()) : "";
        var merges = new ArrayList<String>();
        var rows = new ArrayList<Row>();
        var cells = new LinkedHashMap<String, String>();
        int row = -1;
        boolean visible = !name.contains("[숨김]") && !name.contains("[매우 숨김]");
        boolean validRow = true, complete = wholeSheet && visible;
        var rowPattern = Pattern.compile("^행 ([1-9][0-9]*)( \\[숨김])?$");
        var cellPattern = Pattern.compile("^([A-Z]+)([1-9][0-9]*)( \\[숨김 열])?: (.+)$");
        int previous = 0;
        // Scoped row excerpts can supply fields; only a full packet proves whole-sheet coverage.
        for (String line : lines.subList(wholeSheet ? 1 : 0, lines.size())) {
            if (line.isBlank()) continue;
            if (row < 0 && line.startsWith("병합 범위: ")) {
                String range = line.substring("병합 범위: ".length());
                if (!range.matches("[A-Z]+[1-9][0-9]*:[A-Z]+[1-9][0-9]*") || merges.contains(range)) complete = false;
                merges.add(range);
                continue;
            }
            var heading = rowPattern.matcher(line);
            if (heading.matches()) {
                if (row > 0)
                    rows.add(new Row(row, Collections.unmodifiableMap(new LinkedHashMap<>(cells)), validRow && !cells.isEmpty()));
                row = Integer.parseInt(heading.group(1));
                validRow = visible && row > previous && heading.group(2) == null;
                previous = row;
                complete &= validRow;
                cells.clear();
                continue;
            }
            var cell = cellPattern.matcher(line);
            if (row < 0 || !cell.matches() || Integer.parseInt(cell.group(2)) != row || cell.group(3) != null) {
                complete = false;
                validRow = false;
                continue;
            }
            if (cells.putIfAbsent(cell.group(1), cell.group(4)) != null) {
                complete = false;
                validRow = false;
            }
        }
        if (row > 0)
            rows.add(new Row(row, Collections.unmodifiableMap(new LinkedHashMap<>(cells)), validRow && !cells.isEmpty()));
        return new Sheet(name, List.copyOf(merges), List.copyOf(rows), complete && !rows.isEmpty() && rows.stream().allMatch(Row::readable));
    }
}
