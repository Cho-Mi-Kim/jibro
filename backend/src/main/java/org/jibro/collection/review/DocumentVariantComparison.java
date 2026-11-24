package org.jibro.collection.review;

import java.text.Normalizer;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.HashMap;

import org.jibro.collection.review.local.DocumentContextReader;

import java.util.TreeMap;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;

/**
 * Accepts lossless layout differences only. Matching words, numbers or character counts cannot
 * establish that subjects, table columns, negations and exceptions still have the same meaning.
 */
final class DocumentVariantComparison {
    private static final Pattern PAGE = Pattern.compile("(?:공고문\\s*)?(\\d{1,3})\\s*쪽");
    private static final Pattern PAGE_COUNTER = Pattern.compile("(?m)^\\s*-\\s*\\d+\\s*-\\s*$");
    private static final Pattern SECTION_CAPTION = Pattern.compile(
        "입주대상주택|임대기간(?:및임대조건)?|임대조건|(?:신청|입주)자격|"
            + "신청방법(?:및입주자선정)?|입주자선정|신청일정|신청서류(?:등)?|기타유의사항|신청접수장소");
    private static final int MAX_PARTS = 200;
    private static final int MAX_CHARACTERS = 1_000_000;

    private DocumentVariantComparison() {
    }

    static boolean equivalent(Source first, Source second) {
        if (!complete(first.context()) || !complete(second.context())) return false;
        if (normalize(first.context()).equals(normalize(second.context()))) return true;
        // Physical pages may arrive in a different collection order. Only explicit, complete and
        // unique page numbers permit restoring their order; arbitrary paragraphs are never sorted.
        var firstPages = orderedPages(first.context());
        var secondPages = orderedPages(second.context());
        if (firstPages != null && secondPages != null && normalize(firstPages).equals(normalize(secondPages)))
            return true;
        var firstItems = items(first, firstPages == null ? first.context() : firstPages);
        var secondItems = items(second, secondPages == null ? second.context() : secondPages);
        if (sameItems(firstItems, secondItems)) return true;
        var firstRows = items(first, firstPages == null ? first.context() : firstPages, true);
        return sameItems(firstRows, items(second, secondPages == null ? second.context() : secondPages, true));
    }

    private record CellValue(int row, int column, int rowSpan, int columnSpan, List<Object> content,
                             DocumentContextReader.Boundary left, DocumentContextReader.Boundary right,
                             DocumentContextReader.Boundary top, DocumentContextReader.Boundary bottom,
                             Map<DocumentContextReader.Side, DocumentContextReader.Measurement> doubleBorders) {
    }

    private record TableValue(int rows, int columns, List<CellValue> cells, int rowGroup,
                              Map<Integer, DocumentContextReader.Measurement> rowBands,
                              Map<Integer, DocumentContextReader.Measurement> columnBands) {
    }

    private enum PageBoundary {NEXT, SAME_PAGE}

    private record TextRun(String text, int next) {
    }

    /**
     * The shared structure reader binds every value to its physical row/column and containing cell.
     * Document-specific table numbers and one-column layout boxes are not business meaning. Every
     * other cell, surrounding sentence, quantity and exception must still correspond exactly.
     */
    private static List<Object> items(Source source, List<Part> parts) {
        return items(source, parts, false);
    }

    private static List<Object> items(Source source, List<Part> parts, boolean alignVisibleRows) {
        var output = new ArrayList<Object>();
        boolean physicalPages = consecutivePhysicalPages(source, parts);
        for (Part part : parts) {
            if (physicalPages && !output.isEmpty()) output.add(PageBoundary.NEXT);
            var reading = DocumentContextReader.read(source, part);
            if (!reading.valid()) return null;
            var content = range(part.text(), reading, 0, part.text().length(), alignVisibleRows);
            if (content == null) return null;
            if (physicalPages) {
                for (var item : content) {
                    if (item instanceof TableValue && !output.isEmpty() && output.get(output.size() - 1) instanceof TableValue)
                        output.add(PageBoundary.SAME_PAGE);
                    append(output, List.of(item));
                }
            } else append(output, content);
        }
        return List.copyOf(output);
    }

    private static boolean consecutivePhysicalPages(Source source, List<Part> parts) {
        int previous = -1;
        for (var part : parts) {
            var page = PAGE.matcher(part.location().strip());
            if (!"PDF".equals(DocumentContextReader.format(source, part)) || !page.matches()) return false;
            int number = Integer.parseInt(page.group(1));
            if (number < 1 || previous >= 0 && number != previous + 1) return false;
            previous = number;
        }
        return true;
    }

    private static boolean sameItems(List<Object> first, List<Object> second) {
        if (first == null || second == null) return false;
        first = new ArrayList<>(first);
        second = new ArrayList<>(second);
        int left = 0, right = 0;
        while (true) {
            while (left < first.size() && first.get(left) instanceof PageBoundary) left++;
            while (right < second.size() && second.get(right) instanceof PageBoundary) right++;
            if (left == first.size() || right == second.size()) return left == first.size() && right == second.size();
            Object a = first.get(left), b = second.get(right);
            if (a instanceof String || b instanceof String) {
                var one = textRun(first, left);
                var two = textRun(second, right);
                if (one.next() == left || two.next() == right || !one.text().equals(two.text())) {
                    if (expandPadding(first, one.next()) | expandPadding(second, two.next())) continue;
                    return false;
                }
                left = one.next();
                right = two.next();
            } else if (a instanceof TableValue one && b instanceof TableValue two) {
                if (sameTable(one, two)) {
                    left++;
                    right++;
                } else {
                    int end = sideBySideTable(one, second, right);
                    if (end < 0) end = consentChoicePair(one, second, right);
                    if (end < 0) end = splitTable(one, second, right);
                    if (end > right) {
                        left++;
                        right = end;
                    } else {
                        end = sideBySideTable(two, first, left);
                        if (end < 0) end = consentChoicePair(two, first, left);
                        if (end < 0) end = splitTable(two, first, left);
                        if (end <= left) {
                            if (expandPadding(first, left) | expandPadding(second, right)) continue;
                            return false;
                        }
                        left = end;
                        right++;
                    }
                }
            } else return false;
        }
    }

    private static TextRun textRun(List<Object> values, int from) {
        var text = new StringBuilder();
        int next = from;
        while (next < values.size()) {
            Object value = values.get(next);
            if (value instanceof String part) text.append(part);
            else if (!(value instanceof PageBoundary)) break;
            next++;

        }
        return new TextRun(text.toString(), next);
    }

    private static boolean sameTable(TableValue first, TableValue second) {
        if ((first.rowGroup() > 0 || second.rowGroup() > 0)
            && (first.rows() != second.rows() || first.columns() != second.columns())) return false;
        if (first.rows() != second.rows() || first.columns() != second.columns())
            return sameSpacerGrid(first, second) || sameSpacerGrid(second, first)
                || first.rows() == second.rows()
                && (sameSignAmountColumn(first, second) || sameSignAmountColumn(second, first));
        if (first.cells().size() != second.cells().size())
            return false;
        for (int i = 0; i < first.cells().size(); i++) {
            var a = first.cells().get(i);
            var b = second.cells().get(i);
            if (a.row() != b.row() || a.column() != b.column() || a.rowSpan() != b.rowSpan() || a.columnSpan() != b.columnSpan()
                || !sameItems(a.content(), b.content())) return false;
        }
        return true;
    }

    /**
     * Only the parser's explicit neighboring row group can join detached columns. The complete
     * opposite table must witness every row, column, merged scope and cell, without dropping labels.
     */
    private static int sideBySideTable(TableValue whole, List<Object> values, int start) {
        int rightAt = start + 1;
        if (rightAt < values.size() && values.get(rightAt) == PageBoundary.SAME_PAGE) rightAt++;
        if (whole.rowGroup() != 0 || rightAt >= values.size()
            || !(values.get(start) instanceof TableValue left)
            || !(values.get(rightAt) instanceof TableValue right)
            || left.rowGroup() == 0 || left.rowGroup() != right.rowGroup()
            || left.rows() != whole.rows() || right.rows() != whole.rows()) return -1;
        int gap = whole.columns() - left.columns() - right.columns();
        if (gap < 0 || gap > 1) return -1;
        var cells = new ArrayList<>(left.cells());
        if (gap == 1) {
            var grid = grid(whole);
            if (grid == null) return -1;
            for (int row = 1; row <= whole.rows(); row++) {
                var space = grid[row - 1][left.columns()];
                // A detached pair physically witnesses this one join, not arbitrary blank columns.
                if (space.row() != row || space.rowSpan() != 1 || space.column() != left.columns() + 1
                    || space.columnSpan() != 1 || !space.content().isEmpty()) return -1;
                cells.add(space);
            }
        }
        for (var cell : right.cells())
            cells.add(new CellValue(cell.row(), left.columns() + gap + cell.column(), cell.rowSpan(), cell.columnSpan(),
                cell.content(), cell.left(), cell.right(), cell.top(), cell.bottom(), cell.doubleBorders()));
        cells.sort(Comparator.comparingInt(CellValue::row).thenComparingInt(CellValue::column));
        return sameTable(whole, new TableValue(whole.rows(), whole.columns(), List.copyOf(cells), 0, Map.of(), Map.of())) ? rightAt + 1 : -1;
    }

    /**
     * A blank consent choice has two labeled input boxes; no answer or intervening rule can vanish.
     */
    private static int consentChoicePair(TableValue whole, List<Object> values, int start) {
        int secondAt = start + 1;
        if (secondAt < values.size() && values.get(secondAt) == PageBoundary.SAME_PAGE) secondAt++;
        if (whole.rowGroup() != 0 || whole.rows() != 1 || whole.columns() != 5 || whole.cells().size() != 5
            || whole.cells().stream().anyMatch(cell -> cell.rowSpan() != 1 || cell.columnSpan() != 1)
            || !whole.cells().get(0).content().equals(List.of("동의"))
            || !(whole.cells().get(3).content().equals(List.of("미동의"))
            || whole.cells().get(3).content().equals(List.of("비동의")))
            || !whole.cells().get(1).content().isEmpty() || !whole.cells().get(2).content().isEmpty()
            || !whole.cells().get(4).content().isEmpty() || secondAt >= values.size()) return -1;
        for (int side = 0; side < 2; side++) {
            if (!(values.get(side == 0 ? start : secondAt) instanceof TableValue part) || part.rowGroup() != 0
                || part.rows() != 1 || part.columns() != 2 || part.cells().size() != 2
                || part.cells().stream().anyMatch(cell -> cell.rowSpan() != 1 || cell.columnSpan() != 1)
                || !part.cells().get(0).content().equals(whole.cells().get(side * 3).content())
                || !part.cells().get(1).content().isEmpty()) return -1;
        }
        return secondAt + 1;
    }

    /**
     * Contract only explicit unruled continuations or measured PDF bands witnessed by HWPX double rules.
     * Every remaining cell and merge must match the complete opposite grid.
     */
    private static boolean sameSpacerGrid(TableValue padded, TableValue witness) {
        int rowDifference = padded.rows() - witness.rows(), columnDifference = padded.columns() - witness.columns();
        if (rowDifference < 0 || columnDifference < 0 || rowDifference + columnDifference == 0) return false;
        var grid = grid(padded);
        if (grid == null) return false;
        var rows = new ArrayList<Integer>();
        for (int row = 1; row <= padded.rows(); row++) {
            int at = row;
            boolean empty = java.util.Arrays.stream(grid[row - 1]).allMatch(cell -> cell.row() < at || cell.content().isEmpty());
            if (empty && (padded.rowBands().containsKey(row) || unruledRow(grid, row) || outerBlankRow(grid, row)))
                rows.add(row);
        }
        var columns = new ArrayList<Integer>();
        for (int column : padded.columnBands().keySet()) {
            boolean empty = true;
            for (var row : grid)
                if (row[column - 1].column() == column && !row[column - 1].content().isEmpty()) empty = false;
            if (empty) columns.add(column);
        }
        if (rows.size() != rowDifference || columns.size() != columnDifference) return false;
        for (int row : rows)
            if (padded.rowBands().containsKey(row)
                && !doubleBoundary(witness, true, row - before(rows, row), padded.rowBands().get(row)))
                return false;
        for (int column : columns)
            if (!doubleBoundary(witness, false,
                column - before(columns, column), padded.columnBands().get(column))) return false;
        var cells = new ArrayList<CellValue>();
        for (var cell : padded.cells()) {
            int above = before(rows, cell.row()), left = before(columns, cell.column());
            int height = cell.rowSpan() - (before(rows, cell.row() + cell.rowSpan()) - above);
            int width = cell.columnSpan() - (before(columns, cell.column() + cell.columnSpan()) - left);
            if (height > 0 && width > 0)
                cells.add(new CellValue(cell.row() - above, cell.column() - left, height, width,
                    cell.content(), cell.left(), cell.right(), cell.top(), cell.bottom(), cell.doubleBorders()));
        }
        return sameTable(new TableValue(witness.rows(), witness.columns(), cells, 0, Map.of(), Map.of()), witness);
    }

    private static boolean unruledRow(CellValue[][] grid, int row) {
        for (int col = 0; col < grid[row - 1].length; col++) {
            var cell = grid[row - 1][col];
            boolean above = row > 1 && (cell == grid[row - 2][col]
                || cell.row() == row && cell.top() == DocumentContextReader.Boundary.NONE
                && grid[row - 2][col].row() + grid[row - 2][col].rowSpan() == row
                && grid[row - 2][col].bottom() == DocumentContextReader.Boundary.NONE);
            boolean below = row < grid.length && (cell == grid[row][col]
                || cell.row() + cell.rowSpan() == row + 1 && cell.bottom() == DocumentContextReader.Boundary.NONE
                && grid[row][col].row() == row + 1 && grid[row][col].top() == DocumentContextReader.Boundary.NONE);
            if (!above && !below) return false;
        }
        return true;
    }

    private static boolean outerBlankRow(CellValue[][] grid, int row) {
        if (row != 1 && row != grid.length) return false;
        var cell = grid[row - 1][0];
        return cell.row() == row && cell.rowSpan() == 1 && cell.column() == 1
            && cell.columnSpan() == grid[0].length && cell.content().isEmpty()
            && cell.left() == DocumentContextReader.Boundary.NONE && cell.right() == DocumentContextReader.Boundary.NONE
            && (row == 1 ? cell.top() : cell.bottom()) == DocumentContextReader.Boundary.NONE;
    }

    private static boolean doubleBoundary(TableValue table, boolean horizontal, int edge, DocumentContextReader.Measurement band) {
        var grid = grid(table);
        int size = horizontal ? table.rows() : table.columns();
        if (grid == null || edge <= 1 || edge > size) return false;
        for (int at = 0; at < (horizontal ? table.columns() : table.rows()); at++) {
            var previous = horizontal ? grid[edge - 2][at] : grid[at][edge - 2];
            var next = horizontal ? grid[edge - 1][at] : grid[at][edge - 1];
            if (previous == next) return false;
            var one = previous.doubleBorders().get(horizontal ? DocumentContextReader.Side.BOTTOM : DocumentContextReader.Side.RIGHT);
            var two = next.doubleBorders().get(horizontal ? DocumentContextReader.Side.TOP : DocumentContextReader.Side.LEFT);
            if (one == null && two == null || one != null && !one.sameWidth(band) || two != null && !two.sameWidth(band))
                return false;
        }
        return true;
    }

    private static int before(List<Integer> removed, int edge) {
        return (int) removed.stream().filter(position -> position < edge).count();
    }

    /**
     * An explicit unruled sign/amount split is one field only under its shared spanning header.
     */
    private static boolean sameSignAmountColumn(TableValue split, TableValue witness) {
        if (split.columns() != witness.columns() + 1) return false;
        var grid = grid(split);
        if (grid == null) return false;
        for (int column = 1; column < split.columns(); column++) {
            boolean valid = true;
            int firstValueRow = Integer.MAX_VALUE;
            var parents = new ArrayList<CellValue>();
            for (int row = 0; row < grid.length; row++) {
                var left = grid[row][column - 1];
                var right = grid[row][column];
                if (left == right) {
                    if (left.column() == column && left.columnSpan() == 2 && !left.content().isEmpty())
                        parents.add(left);
                    continue;
                }
                if (left.columnSpan() != 1 || right.columnSpan() != 1 || left.row() != right.row() || left.rowSpan() != right.rowSpan()
                    || left.right() != DocumentContextReader.Boundary.NONE || right.left() != DocumentContextReader.Boundary.NONE
                    || left.content().size() != 1 || right.content().size() != 1
                    || !(left.content().get(0) instanceof String sign) || !sign.matches("\\([+-]\\)")
                    || !(right.content().get(0) instanceof String amount) || !amount.matches("[0-9][0-9,]*(?:원)?")) {
                    valid = false;
                    break;
                }
                firstValueRow = Math.min(firstValueRow, left.row());
            }
            int valueRow = firstValueRow;
            if (!valid || valueRow == Integer.MAX_VALUE || parents.stream().noneMatch(p -> p.row() + p.rowSpan() - 1 < valueRow))
                continue;
            var cells = new ArrayList<CellValue>();
            for (var cell : split.cells()) {
                if (cell.column() == column + 1) continue;
                int span = cell.columnSpan();
                var body = cell.content();
                var rightBoundary = cell.right();
                if (cell.column() <= column && cell.column() + span - 1 >= column + 1) span--;
                else if (cell.column() == column) {
                    var right = grid[cell.row() - 1][column];
                    var merged = new ArrayList<Object>();
                    append(merged, cell.content());
                    append(merged, right.content());
                    body = List.copyOf(merged);
                    rightBoundary = right.right();
                }
                cells.add(new CellValue(cell.row(), cell.column() > column ? cell.column() - 1 : cell.column(),
                    cell.rowSpan(), span, body, cell.left(), rightBoundary, cell.top(), cell.bottom(), Map.of()));
            }
            if (sameTable(new TableValue(split.rows(), witness.columns(), cells, 0, Map.of(), Map.of()), witness))
                return true;
        }
        return false;
    }

    private record SplitState(int lastRow, Map<CellValue, List<Object>> contents) {
    }

    /**
     * Adjacent physical pages can split rows only as witnessed by the other document's full grid.
     */
    private static int splitTable(TableValue whole, List<Object> values, int start) {
        if (whole.rowGroup() > 0) return -1;
        final var completeWhole = whole;
        var witness = grid(whole);
        if (witness == null) return -1;
        var states = List.of(new SplitState(0, Map.<CellValue, List<Object>>of()));
        int pieces = 0, next = start;
        while (next < values.size() && values.get(next) instanceof TableValue rawPiece) {
            var piece = withoutHeaderRuleBand(whole, rawPiece);
            if (piece.rowGroup() > 0) return -1;
            var candidates = new HashMap<Integer, SplitState>();
            var ambiguous = new java.util.HashSet<Integer>();
            for (var state : states) {
                for (int firstRow : state.lastRow() == 0 ? new int[]{1}
                    : new int[]{state.lastRow() + 1, state.lastRow()}) {
                    var repeated = pieces == 0 ? null : withoutRepeatedHeader(whole, witness, piece, firstRow);
                    var readings = repeated == null ? List.of(piece) : List.of(piece, repeated);
                    for (var reading : readings) {
                        var candidate = splitPiece(whole, witness, reading, firstRow, state.contents());
                        if (candidate == null || ambiguous.contains(candidate.lastRow())) continue;
                        var previous = candidates.putIfAbsent(candidate.lastRow(), candidate);
                        if (previous != null && !previous.equals(candidate)) {
                            candidates.remove(candidate.lastRow());
                            ambiguous.add(candidate.lastRow());
                        }
                    }
                }
            }
            if (candidates.isEmpty()) return -1;
            states = List.copyOf(candidates.values());
            pieces++;
            next++;
            if (next < values.size() && values.get(next) instanceof String following) {
                var tail = completeWhole.cells().stream().filter(c -> c.row() == completeWhole.rows()
                    && c.rowSpan() == 1 && c.column() == 1 && c.columnSpan() == completeWhole.columns()).toList();
                if (tail.size() == 1 && tail.get(0).content().size() == 1 && tail.get(0).content().get(0) instanceof String paragraph
                    && following.startsWith(paragraph) && states.stream().anyMatch(state -> state.lastRow() == completeWhole.rows() - 1
                    && completeWhole.cells().stream().filter(c -> c != tail.get(0))
                    .allMatch(c -> sameItems(c.content(), state.contents().get(c))))) {
                    if (following.equals(paragraph)) return next + 1;
                    values.set(next, following.substring(paragraph.length()));
                    return next;
                }
            }
            if (pieces > 1 && states.stream().anyMatch(state -> state.lastRow() == completeWhole.rows()
                && completeWhole.cells().stream().allMatch(cell -> sameItems(cell.content(), state.contents().get(cell)))))
                return next;
            if (next >= values.size() || !(values.get(next) instanceof PageBoundary)) return -1;
            next++;
        }
        return -1;
    }

    private static TableValue withoutHeaderRuleBand(TableValue whole, TableValue piece) {
        // A PDF export repeats the double rule below a header on each physical page.
        // Its measured empty band is accounted for by that same full-grid HWPX border.
        if (piece.columns() != whole.columns() || piece.rows() < 3 || piece.rowBands().size() != 1
            || !piece.rowBands().containsKey(2) || !piece.columnBands().isEmpty()
            || !doubleBoundary(whole, true, 2, piece.rowBands().get(2))) return piece;
        var header = whole.cells().stream().filter(c -> c.row() == 1).toList();
        var repeated = piece.cells().stream().filter(c -> c.row() == 1).toList();
        if (header.size() < 2 || !sameTable(new TableValue(1, whole.columns(), header, 0, Map.of(), Map.of()),
            new TableValue(1, piece.columns(), repeated, 0, Map.of(), Map.of()))) return piece;
        var grid = grid(piece);
        if (grid == null || java.util.Arrays.stream(grid[1]).anyMatch(c -> c.row() != 2 || c.rowSpan() != 1 || !c.content().isEmpty()))
            return piece;
        return new TableValue(piece.rows() - 1, piece.columns(), piece.cells().stream().filter(c -> c.row() != 2)
            .map(c -> new CellValue(c.row() > 2 ? c.row() - 1 : c.row(), c.column(), c.rowSpan(), c.columnSpan(),
                c.content(), c.left(), c.right(), c.top(), c.bottom(), c.doubleBorders())).toList(), 0, Map.of(), Map.of());
    }

    private static TableValue withoutRepeatedHeader(TableValue whole, CellValue[][] witness, TableValue piece, int firstRow) {
        if (piece.rows() < 2 || piece.columns() > whole.columns()) return null;
        var edges = splitEdges(whole, witness, piece.columns(), firstRow, firstRow + piece.rows() - 2);
        if (edges == null) return null;
        var header = whole.cells().stream().filter(c -> c.row() == 1).toList();
        var repeated = piece.cells().stream().filter(c -> c.row() == 1).toList();
        if (header.size() < 2 || header.size() != repeated.size()) return null;
        for (int i = 0; i < header.size(); i++) {
            var a = header.get(i);
            var b = repeated.get(i);
            if (a.rowSpan() != 1 || b.rowSpan() != 1
                || a.column() != edges.get(b.column() - 1) + 1
                || a.column() + a.columnSpan() != edges.get(b.column() + b.columnSpan() - 1) + 1
                || a.content().size() != 1 || !(a.content().get(0) instanceof String label)
                || !label.matches("[가-힣A-Za-z·/()]{1,50}") || !sameItems(a.content(), b.content())) return null;
        }
        // The repeated labels and their body cells must both own the same original columns.
        return new TableValue(piece.rows() - 1, piece.columns(), piece.cells().stream().filter(c -> c.row() > 1)
            .map(c -> new CellValue(c.row() - 1, c.column(), c.rowSpan(), c.columnSpan(), c.content(),
                c.left(), c.right(), c.top(), c.bottom(), c.doubleBorders())).toList(), 0, Map.of(), Map.of());
    }

    private static List<Integer> splitEdges(TableValue whole, CellValue[][] witness, int columns, int firstRow, int lastRow) {
        if (firstRow < 1 || lastRow > whole.rows() || firstRow > lastRow || columns > whole.columns()) return null;
        var edges = new ArrayList<Integer>();
        edges.add(0);
        for (int column = 1; column < whole.columns(); column++) {
            boolean visible = columns == whole.columns();
            for (int row = firstRow - 1; !visible && row < lastRow; row++)
                visible = witness[row][column - 1] != witness[row][column];
            if (visible) edges.add(column);
        }
        edges.add(whole.columns());
        return edges.size() == columns + 1 ? edges : null;
    }

    private static SplitState splitPiece(TableValue whole, CellValue[][] witness, TableValue piece,
                                         int firstRow, Map<CellValue, List<Object>> previous) {
        int lastRow = firstRow + piece.rows() - 1;
        var edges = splitEdges(whole, witness, piece.columns(), firstRow, lastRow);
        if (edges == null) return null;
        var contents = new HashMap<>(previous);
        for (var cell : piece.cells()) {
            int row = firstRow + cell.row() - 1, last = row + cell.rowSpan() - 1;
            int column = edges.get(cell.column() - 1) + 1;
            int columnSpan = edges.get(cell.column() + cell.columnSpan() - 1) - column + 1;
            var target = witness[row - 1][column - 1];
            if (target.column() != column || target.columnSpan() != columnSpan
                || row != Math.max(target.row(), firstRow)
                || last != Math.min(target.row() + target.rowSpan() - 1, lastRow)) return null;
            var before = contents.getOrDefault(target, List.of());
            var joined = new ArrayList<Object>();
            append(joined, before);
            // A vertically merged label may repeat at the seam. Ordinary continued prose must
            // concatenate exactly, including its quantity, negation and exception.
            if (!(target.rowSpan() > 1 && sameItems(target.content(), before)
                && sameItems(target.content(), cell.content()))) append(joined, cell.content());
            if (!contentPrefix(target.content(), joined)) return null;
            contents.put(target, List.copyOf(joined));
        }
        return new SplitState(lastRow, Map.copyOf(contents));
    }

    private static boolean contentPrefix(List<Object> whole, List<Object> fragment) {
        if (fragment.isEmpty() || sameItems(whole, fragment)) return true;
        if (fragment.size() > whole.size()) return false;
        int last = fragment.size() - 1;
        if (!sameItems(whole.subList(0, last), fragment.subList(0, last))) return false;
        return whole.get(last) instanceof String text && fragment.get(last) instanceof String prefix
            ? text.startsWith(prefix) : sameItems(whole.subList(last, last + 1), fragment.subList(last, last + 1));
    }

    private static CellValue[][] grid(TableValue table) {
        if ((long) table.rows() * table.columns() > DocumentContextReader.MAX_GRID_POSITIONS) return null;
        var grid = new CellValue[table.rows()][table.columns()];
        for (var cell : table.cells())
            for (int row = cell.row() - 1; row < cell.row() + cell.rowSpan() - 1; row++)
                for (int col = cell.column() - 1; col < cell.column() + cell.columnSpan() - 1; col++)
                    grid[row][col] = cell;
        return grid;
    }

    private static List<Object> range(String text, DocumentContextReader.Reading reading, int start, int end, boolean alignVisibleRows) {
        var result = new ArrayList<Object>();
        int cursor = start;
        for (var table : reading.tables()) {
            if (table.start() < cursor || table.end() > end) continue;
            prose(result, text, reading, cursor, table.start());
            if (reading.memo(table.start(), table.end())) {
                cursor = table.end();
                continue;
            }
            if (table.grid() == null) return null;
            // Text outside cells in a purported table cannot disappear during normalization.
            for (var segment : reading.segments())
                if (segment.start() >= table.start() && segment.end() <= table.end()
                    && (segment.context() == null || segment.context().cells().stream().noneMatch(c -> c.table() == table.id()))
                    && !normalize(text.substring(segment.start(), segment.end())).isEmpty()) return null;
            var expanded = alignVisibleRows && table.rowBands().isEmpty() && table.columnBands().isEmpty()
                ? visibleRows(text, reading, table) : Map.<Integer, List<List<DocumentContextReader.Baseline>>>of();
            int[] rows = new int[table.rows() + 1];
            for (int row = 1; row <= table.rows(); row++)
                rows[row] = rows[row - 1] + (expanded.containsKey(row) ? expanded.get(row).get(0).size() : 1);
            var values = new ArrayList<CellValue>();
            // Keep multi-row blank fields and actual PDF gap regions. The pre-existing
            // one-cell HWPX wrapper contract remains text-only (including image containers).
            boolean preserveSingleColumn = table.columns() == 1
                && (table.rows() > 1 || text.startsWith("⟦PDF:TABLE", table.start()))
                && table.cells().stream().anyMatch(c -> reading.text(text, c.start(), c.end()).isBlank());
            for (var cell : table.cells().stream().sorted(Comparator
                .comparingInt((DocumentContextReader.BoundedCell c) -> c.position().row())
                .thenComparingInt(c -> c.position().column())).toList()) {
                var body = range(text, reading, cell.start(), cell.end(), alignVisibleRows);
                if (body == null) return null;
                var at = cell.position();
                if (table.columns() == 1 && table.rowGroup() == 0 && !preserveSingleColumn) append(result, body);
                else if (expanded.containsKey(at.row())) {
                    var lines = expanded.get(at.row()).get(at.column() - 1);
                    for (int line = 0; line < lines.size(); line++) {
                        var baseline = lines.get(line);
                        values.add(new CellValue(rows[at.row() - 1] + line + 1, at.column(), 1, 1,
                            List.of(normalize(reading.text(text, baseline.start(), baseline.end()))), cell.left(), cell.right(), cell.top(), cell.bottom(), cell.doubleBorders()));
                    }
                } else values.add(new CellValue(rows[at.row() - 1] + 1, at.column(),
                    rows[at.row() + at.rowSpan() - 1] - rows[at.row() - 1], at.columnSpan(), body, cell.left(), cell.right(), cell.top(), cell.bottom(), cell.doubleBorders()));
            }
            if (table.columns() > 1 || table.rowGroup() > 0 || values.stream().anyMatch(c -> c.content().isEmpty()))
                table(result, new TableValue(rows[table.rows()], table.columns(), values.stream()
                    .sorted(Comparator.comparingInt(CellValue::row).thenComparingInt(CellValue::column)).toList(), table.rowGroup(), table.rowBands(), table.columnBands()));
            cursor = table.end();
        }
        prose(result, text, reading, cursor, end);
        return List.copyOf(result);
    }

    /**
     * A final full-width note has the same scope immediately below its table in the same parent
     * cell. Keep its entire text and position there; blank input rows stay in the grid.
     */
    private static void table(List<Object> output, TableValue table) {
        if (table.rowGroup() > 0 || !table.rowBands().isEmpty() || !table.columnBands().isEmpty()) {
            output.add(table);
            return;
        }
        table = joinedDirectionColumns(table);
        var flow = explicitHorizontalFlow(table);
        if (flow != null) {
            append(output, flow);
            return;
        }
        int rows = table.rows();
        var cells = new ArrayList<>(table.cells());
        var notes = new ArrayList<Object>();
        boolean removedNotePadding = false;
        while (rows > 1) {
            var last = cells.get(cells.size() - 1);
            if (last.row() != rows || last.rowSpan() != 1 || last.column() != 1
                || last.columnSpan() != table.columns()) break;
            boolean note = last.content().size() == 1 && last.content().get(0) instanceof String text && text.startsWith("※");
            boolean notePadding = !removedNotePadding && !notes.isEmpty() && last.content().isEmpty()
                && last.left() == DocumentContextReader.Boundary.NONE && last.right() == DocumentContextReader.Boundary.NONE;
            if (!note && !notePadding) break;
            // A single open-sided blank row immediately before the full-width note is its
            // layout spacing, not a form input. The entire note remains in this parent.
            removedNotePadding |= notePadding;
            notes.addAll(0, last.content());
            cells.remove(cells.size() - 1);
            rows--;
        }
        // Retain the grid until comparison chooses its role. A one-value physical fragment may
        // continue a labeled row on the preceding page rather than being a decorative box.
        output.add(new TableValue(rows, table.columns(), List.copyOf(cells), 0, Map.of(), Map.of()));
        append(output, notes);
    }

    private static TableValue joinedDirectionColumns(TableValue table) {
        var grid = grid(table);
        if (grid == null) return table;
        for (int col = 1; col < table.columns(); col++) {
            int at = col;
            boolean direction = false, valid = true;
            for (var row : grid) {
                var a = row[col - 1];
                var b = row[col];
                if (a == b || a.columnSpan() != 1 || b.columnSpan() != 1 || a.row() != b.row() || a.rowSpan() != b.rowSpan()) {
                    valid = false;
                    break;
                }
                if (a.content().isEmpty() && b.content().isEmpty()) continue;
                if (a.content().size() != 1 || !a.content().equals(b.content()) || !(a.content().get(0) instanceof String s)
                    || !s.matches("[→←▶◀]")) {
                    valid = false;
                    break;
                }
                direction = true;
            }
            if (!valid || !direction) continue;
            var cells = new ArrayList<CellValue>();
            for (var c : table.cells()) {
                if (c.column() == col + 1) continue;
                if (c.column() == col) {
                    var b = grid[c.row() - 1][col];
                    var content = new ArrayList<Object>();
                    append(content, c.content());
                    append(content, b.content());
                    cells.add(new CellValue(c.row(), col, c.rowSpan(), 1, content, c.left(), b.right(), c.top(), c.bottom(), Map.of()));
                } else
                    cells.add(new CellValue(c.row(), c.column() > at ? c.column() - 1 : c.column(), c.rowSpan(), c.columnSpan(),
                        c.content(), c.left(), c.right(), c.top(), c.bottom(), c.doubleBorders()));
            }
            return joinedDirectionColumns(new TableValue(table.rows(), table.columns() - 1, cells, 0, Map.of(), Map.of()));
        }
        return table;
    }

    private static boolean expandPadding(List<Object> values, int at) {
        if (at >= values.size() || !(values.get(at) instanceof TableValue table)
            || !table.rowBands().isEmpty() || !table.columnBands().isEmpty()) return false;
        var body = paddingContent(table);
        if (body == null) return false;
        values.remove(at);
        values.addAll(at, body);
        return true;
    }

    private static List<Object> paddingContent(TableValue table) {
        if (table.rowGroup() > 0) return null;
        if (table.rows() == 1 && table.columns() == 3 && table.cells().size() == 3
            && table.cells().stream().allMatch(c -> c.rowSpan() == 1 && c.columnSpan() == 1)) {
            var number = table.cells().get(0).content();
            var caption = table.cells().get(2).content();
            if (table.cells().get(1).content().isEmpty() && number.size() == 1 && caption.size() == 1
                && number.get(0) instanceof String ordinal && ordinal.matches("[1-9][0-9]?")
                && caption.get(0) instanceof String title && SECTION_CAPTION.matcher(title).matches())
                return List.of(ordinal + title);
        }
        var occupied = table.cells().stream().filter(cell -> !cell.content().isEmpty()).toList();
        if (occupied.isEmpty()) return null;
        var first = occupied.get(0);
        if (table.columns() == 3 && occupied.size() == 2) {
            var body = occupied.get(1);
            // A centered caption above a single full-width body has no parallel data column.
            // Keep the complete body, including its nested tables and every following exception.
            if (first.row() == 1 && first.column() == 2 && first.columnSpan() == 1
                && first.rowSpan() == table.rows() - 1 && first.content().size() == 1
                && first.content().get(0) instanceof String
                && body.row() == table.rows() && body.rowSpan() == 1
                && body.column() == 1 && body.columnSpan() == table.columns()) {
                var content = new ArrayList<Object>();
                append(content, first.content());
                append(content, body.content());
                return List.copyOf(content);
            }
        }
        if (occupied.stream().anyMatch(cell -> cell.column() != first.column()
            || cell.columnSpan() != first.columnSpan())) return null;
        var content = new ArrayList<Object>();
        occupied.stream().sorted(Comparator.comparingInt(CellValue::row)).forEach(cell -> append(content, cell.content()));
        return List.copyOf(content);
    }

    /**
     * Full-height directional cells bind each intervening column as one ordered step.
     */
    private static List<Object> explicitHorizontalFlow(TableValue table) {
        var firstRow = table.cells().stream().filter(c -> c.row() == 1).toList();
        if (table.rows() > 1 && firstRow.size() == 1 && firstRow.get(0).rowSpan() == 1
            && firstRow.get(0).columnSpan() == table.columns()) {
            var body = new TableValue(table.rows() - 1, table.columns(), table.cells().stream()
                .filter(c -> c.row() > 1).map(c -> new CellValue(c.row() - 1, c.column(), c.rowSpan(),
                    c.columnSpan(), c.content(), c.left(), c.right(), c.top(), c.bottom(), c.doubleBorders()))
                .toList(), 0, Map.of(), Map.of());
            var flow = explicitHorizontalFlow(body);
            if (flow == null) return null;
            var result = new ArrayList<Object>();
            append(result, firstRow.get(0).content());
            append(result, flow);
            return List.copyOf(result);
        }
        var occupied = table.cells().stream().filter(cell -> !cell.content().isEmpty()).toList();
        int first = occupied.stream().mapToInt(CellValue::column).min().orElse(0);
        int last = occupied.stream().mapToInt(cell -> cell.column() + cell.columnSpan() - 1).max().orElse(0);
        int columns = last - first + 1;
        if (columns < 3 || columns % 2 != 1) return null;
        var result = new ArrayList<Object>();
        for (int column = first; column <= last; column++) {
            int at = column;
            var cells = table.cells().stream().filter(cell -> cell.column() == at)
                .sorted(Comparator.comparingInt(CellValue::row)).toList();
            if (cells.isEmpty()) return null;
            boolean direction = (column - first) % 2 == 1;
            if (direction && (cells.size() != 1 || cells.get(0).row() != 1 || cells.get(0).rowSpan() != table.rows()))
                return null;
            for (var cell : cells) {
                if (cell.columnSpan() != 1 || cell.content().size() != 1
                    || !(cell.content().get(0) instanceof String text) || text.isEmpty()
                    || direction != text.matches("[→←▶◀]")) return null;
                append(result, cell.content());
            }
        }
        return List.copyOf(result);
    }

    /**
     * A missing horizontal border is not a missing semantic row when every column has a complete
     * line at the same measured PDF baseline. Line counts alone cannot establish that relation.
     */
    private static Map<Integer, List<List<DocumentContextReader.Baseline>>> visibleRows(
        String text, DocumentContextReader.Reading reading, DocumentContextReader.Table table) {
        var result = new HashMap<Integer, List<List<DocumentContextReader.Baseline>>>();
        if (table.columns() < 2 || reading.baselines().isEmpty()) return result;
        var grid = table.grid();
        for (int row = 0; row < grid.length; row++) {
            var columns = new ArrayList<List<DocumentContextReader.Baseline>>();
            boolean valid = true;
            for (var cell : grid[row]) {
                if (cell.position().rowSpan() != 1 || cell.position().columnSpan() != 1
                    || reading.tables().stream().anyMatch(t -> t.start() >= cell.start() && t.end() <= cell.end())) {
                    valid = false;
                    break;
                }
                var lines = reading.baselines().stream().filter(b -> b.start() >= cell.start() && b.end() <= cell.end()).toList();
                if (lines.size() < 2 || !columns.isEmpty() && lines.size() != columns.get(0).size()
                    || lines.stream().anyMatch(b -> normalize(reading.text(text, b.start(), b.end())).isEmpty())) {
                    valid = false;
                    break;
                }
                var covered = new StringBuilder();
                lines.forEach(b -> covered.append(reading.text(text, b.start(), b.end())));
                if (!normalize(covered.toString()).equals(normalize(reading.text(text, cell.start(), cell.end())))) {
                    valid = false;
                    break;
                }
                columns.add(lines);
            }
            double previousBottom = -1;
            for (int line = 0; valid && line < columns.get(0).size(); line++) {
                double first = -1, last = Double.MAX_VALUE, top = Double.MAX_VALUE, bottom = -1;
                for (var column : columns) {
                    var baseline = column.get(line);
                    first = Math.max(first, baseline.minimum());
                    last = Math.min(last, baseline.maximum());
                    top = Math.min(top, baseline.minimum());
                    bottom = Math.max(bottom, baseline.maximum());
                }
                if (first > last || top <= previousBottom) valid = false;
                previousBottom = bottom;
            }
            if (valid) result.put(row + 1, List.copyOf(columns));
        }
        return result;
    }

    private static void prose(List<Object> output, String text, DocumentContextReader.Reading reading, int start, int end) {
        String normalized = normalize(reading.text(text, start, end));
        if (!normalized.isEmpty()) append(output, List.of(normalized));
    }

    private static void append(List<Object> output, List<Object> values) {
        for (Object value : values) {
            if (value instanceof String text && !output.isEmpty() && output.get(output.size() - 1) instanceof String previous)
                output.set(output.size() - 1, previous + text);
            else output.add(value);
        }
    }

    private static boolean complete(List<Part> parts) {
        return parts != null && !parts.isEmpty() && parts.size() <= MAX_PARTS
            && parts.stream().allMatch(part -> part != null && part.text() != null && part.location() != null)
            && parts.stream().mapToLong(part -> part.text().length()).sum() <= MAX_CHARACTERS;
    }

    private static List<Part> orderedPages(List<Part> parts) {
        var pages = new TreeMap<Integer, Part>();
        for (Part part : parts) {
            var number = PAGE.matcher(part.location().strip());
            if (!number.matches() || pages.putIfAbsent(Integer.parseInt(number.group(1)), part) != null) return null;
        }
        if (pages.firstKey() != 1 || pages.lastKey() != pages.size()) return null;
        return List.copyOf(pages.values());
    }

    private static String normalize(List<Part> parts) {
        var text = new StringBuilder();
        for (Part part : parts) text.append('\n').append(PAGE_COUNTER.matcher(part.text()).replaceAll(""));
        return normalize(text.toString());
    }

    private static String normalize(String text) {
        return Normalizer.normalize(PAGE_COUNTER.matcher(text).replaceAll(""), Normalizer.Form.NFC)
            .replaceAll("[\\s\\p{Zs}]+", "");
    }
}
