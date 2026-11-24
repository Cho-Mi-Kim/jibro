package org.jibro.collection.extraction;

import java.awt.geom.GeneralPath;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.color.ColorSpace;
import java.awt.geom.NoninvertibleTransformException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

/**
 * Geometric reading regions and closed ruled cells; never infers a heading or a cell's meaning.
 */
final class PdfPageLayout extends PDFGraphicsStreamEngine {
    // Half a PDF point joins the two sides of an ordinary printed rule, not text-sized gaps.
    private static final double RULE_TOLERANCE = 0.5;
    private static final int MAX_RULES = 1600;
    private static final int MAX_DRAWN_RULES = 100_000;
    private static final int MAX_GRID_TILES = 16000;
    private static final int MIN_COLUMN_WIDTH_EM = 8;
    private final GeneralPath path = new GeneralPath();
    private final List<Line> lines = new ArrayList<>();
    private final List<List<Line>> filledRuleGroups = new ArrayList<>();
    private final List<Arrow> arrows = new ArrayList<>();
    private List<TextPosition> flowDirections = List.of();
    private final List<PaintedArea> paintedAreas = new ArrayList<>();
    private int rowGroupId;
    private int decodedImages;
    private long decodedPixels;
    private final double left;
    private final double top;
    private boolean limited;
    private Integer clippingRule;

    PdfPageLayout(PDPage page) {
        super(page);
        left = page.getCropBox().getLowerLeftX();
        top = page.getCropBox().getUpperRightY();
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        super.processPage(page);
        var rectangles = paintedAreas.stream().filter(p -> p.solidFill && p.area.isRectangular())
            .map(p -> {
                var box = p.area.getBounds2D();
                boolean horizontal = box.getWidth() > box.getHeight();
                if (box.getWidth() == box.getHeight()) return null;
                return new Line(horizontal, horizontal ? box.getCenterY() : box.getCenterX(),
                    horizontal ? box.getMinX() : box.getMinY(), horizontal ? box.getMaxX() : box.getMaxY(),
                    (horizontal ? box.getHeight() : box.getWidth()) / 2, p.paint);
            }).filter(java.util.Objects::nonNull).toList();
        if (rectangles.size() > MAX_RULES) return;
        // Spreadsheet exports may paint each rule as a solid strip rather than a stroked path.
        // A fill is a rule only as part of an intersecting grid of equally wide, equally painted
        // strips in both axes. A background, isolated bar or icon does not establish such a grid.
        var connected = rectangles.stream().filter(a -> rectangles.stream().filter(b -> filledJoint(a, b)).count() >= 2).toList();
        var confirmed = connected.stream().filter(rule -> connected.stream().filter(other -> filledJoint(rule, other)).count() >= 2).toList();
        for (var group : connectedRules(confirmed)) {
            var frame = grid(group);
            if (frame != null && frame.enclosed) filledRuleGroups.add(group);
        }
    }

    private static boolean filledJoint(Line a, Line b) {
        double rounding = java.util.stream.DoubleStream.of(a.at, a.from, a.to, b.at, b.from, b.to)
            .map(v -> Math.ulp((float) v)).max().orElseThrow();
        if (a.horizontal == b.horizontal || !a.paint.equals(b.paint)
            || Math.abs(a.halfWidth - b.halfWidth) > rounding) return false;
        return a.at + a.halfWidth >= b.from - rounding && a.at - a.halfWidth <= b.to + rounding
            && b.at + b.halfWidth >= a.from - rounding && b.at - b.halfWidth <= a.to + rounding;
    }

    List<TextPosition> arrowPositions(List<TextPosition> text) {
        if (text.isEmpty()) return List.of();
        var font = text.get(0).getFont();
        return arrows.stream().map(a -> {
            float height = (float) Math.min(a.height, text.stream().mapToDouble(TextPosition::getHeightDir).max().orElse(a.height));
            float baseline = (float) (getPage().getCropBox().getHeight() - a.y - (a.height + height) / 2);
            float x = (float) a.x, width = (float) a.width;
            return new TextPosition(0, getPage().getCropBox().getWidth(), getPage().getCropBox().getHeight(),
                Matrix.getTranslateInstance(x, baseline), x + width, baseline, height, width, height / 2,
                a.direction, new int[0], font, height, Math.max(1, Math.round(height)));
        }).toList();
    }

    List<Table> readingRegions(List<TextPosition> text, List<TextPosition> pictureText) {
        var tables = tables(text.stream().filter(p -> !pictureText.contains(p)).toList());
        var directions = new ArrayList<>(pictureText.stream().filter(text::contains).toList());
        directions.addAll(flowGlyphs(tables, text));
        flowDirections = List.copyOf(directions);
        return arrangeRegions(detachedRowGroups(headerGaps(alignedPanels(coloredColumns(tables, text), text), text), text), directions);
    }

    private List<Table> coloredColumns(List<Table> input, List<TextPosition> text) {
        return input.stream().map(t -> {
            var children = coloredColumns(t.children, text);
            if (t.xs.size() != 2 || !children.isEmpty())
                return new Table(t.xs, t.ys, t.cells, children, t.enclosed, t.rowGroup, t.inlineLead, t.ruleBands);
            for (var row : t.cells) {
                var fills = paintedAreas.stream().filter(p -> p.solidFill && p.area.isRectangular()).map(p -> p.area.getBounds2D())
                    .filter(b -> Math.abs(b.getMinY() - row.top) <= RULE_TOLERANCE && Math.abs(b.getMaxY() - row.bottom) <= RULE_TOLERANCE
                        && b.getMinX() >= row.left - RULE_TOLERANCE && b.getMaxX() <= row.right + RULE_TOLERANCE
                        && b.getWidth() < row.right - row.left - RULE_TOLERANCE).distinct()
                    .sorted(Comparator.comparingDouble(Rectangle2D::getMinX)).toList();
                if (fills.size() < 2 || Math.abs(fills.get(0).getMinX() - row.left) > RULE_TOLERANCE
                    || Math.abs(fills.get(fills.size() - 1).getMaxX() - row.right) > RULE_TOLERANCE) continue;
                boolean complete = true;
                var xs = new ArrayList<Double>();
                xs.add(row.left);
                for (int i = 1; i < fills.size(); i++) {
                    double end = fills.get(i - 1).getMaxX(), start = fills.get(i).getMinX();
                    if (Math.abs(end - start) > RULE_TOLERANCE) {
                        complete = false;
                        break;
                    }
                    xs.add((end + start) / 2);
                }
                if (!complete) continue;
                xs.add(row.right);
                var cells = new ArrayList<Cell>();
                for (var cell : t.cells) {
                    if (cell == row) for (int col = 0; col < xs.size() - 1; col++)
                        cells.add(new Cell(cell.row, col, cell.rowSpan, 1, xs.get(col), cell.top, xs.get(col + 1), cell.bottom));
                    else
                        cells.add(new Cell(cell.row, 0, cell.rowSpan, xs.size() - 1, cell.left, cell.top, cell.right, cell.bottom));
                }
                if (text.stream().filter(t::contains).anyMatch(p -> !p.getUnicode().isBlank() && cells.stream().filter(c -> c.contains(p)).count() != 1))
                    continue;
                return new Table(xs, t.ys, cells, List.of(), t.enclosed, t.rowGroup, t.inlineLead, t.ruleBands);
            }
            return t;
        }).toList();
    }

    boolean contains(Table table, Cell cell, TextPosition position) {
        if (table.xs.size() < 3 || !flowDirections.contains(position)) return cell.contains(position);
        // Proven gutter directions may have a glyph advance wider than the printed gap.
        // Use the observed origin and the existing border precision, not the advance centre.
        double x = position.getXDirAdj() - RULE_TOLERANCE;
        return x >= cell.left && x < cell.right && centerY(position) >= cell.top && centerY(position) < cell.bottom;
    }

    private List<Table> alignedPanels(List<Table> input, List<TextPosition> text) {
        var tables = input.stream().map(t -> new Table(t.xs, t.ys, t.cells, alignedPanels(t.children, text),
            t.enclosed, t.rowGroup, t.inlineLead, t.ruleBands)).toList();
        var used = new java.util.HashSet<Table>();
        var result = new ArrayList<Table>();
        for (var table : tables) {
            if (used.contains(table)) continue;
            var peers = tables.stream().filter(t -> t.enclosed && t.children.isEmpty() && t.ruleBands.isEmpty()
                    && Math.abs(t.ys.get(0) - table.ys.get(0)) <= RULE_TOLERANCE
                    && Math.abs(t.ys.get(t.ys.size() - 1) - table.ys.get(table.ys.size() - 1)) <= RULE_TOLERANCE)
                .sorted(Comparator.comparingDouble(t -> t.xs.get(0))).toList();
            boolean valid = peers.size() >= 3 && peers.contains(table);
            boolean compactGaps = true;
            for (int i = 1; valid && i < peers.size(); i++) {
                var left = peers.get(i - 1);
                var right = peers.get(i);
                double x0 = left.xs.get(left.xs.size() - 1), x1 = right.xs.get(0);
                double advanceWidth = text.stream().filter(p -> !p.getUnicode().isBlank()
                        && (left.contains(p) || right.contains(p))).mapToDouble(TextPosition::getWidthDirAdj).filter(w -> w > 0)
                    .sorted().skip(text.stream().filter(p -> !p.getUnicode().isBlank()
                        && (left.contains(p) || right.contains(p)) && p.getWidthDirAdj() > 0).count() / 2).findFirst().orElse(0);
                compactGaps &= x1 - x0 <= advanceWidth;
                valid = x1 > x0 && text.stream().noneMatch(p -> !p.getUnicode().isBlank()
                    && !List.of("→", "←", "▶", "◀").contains(p.getUnicode())
                    && left.cells.stream().noneMatch(c -> c.contains(p)) && right.cells.stream().noneMatch(c -> c.contains(p))
                    && p.getXDirAdj() < x1 && p.getXDirAdj() + p.getWidthDirAdj() > x0
                    && p.getYDirAdj() > left.ys.get(0) && p.getYDirAdj() - p.getHeightDir() < left.ys.get(left.ys.size() - 1));
            }
            if (!valid) {
                result.add(table);
                continue;
            }
            var panels = new ArrayList<>(peers);
            if (peers.stream().allMatch(t -> t.xs.size() == 2 && t.ys.size() == 2)) {
                var columns = new ArrayList<List<Table>>();
                for (var head : peers) {
                    var body = tables.stream().filter(t -> t.enclosed && t.children.isEmpty() && t.ruleBands.isEmpty()
                            && t.xs.equals(head.xs) && t.ys.size() == 2 && t.ys.get(0) > head.ys.get(1))
                        .sorted(Comparator.comparingDouble(t -> t.ys.get(0))).toList();
                    double prior = head.ys.get(1);
                    var chain = new ArrayList<Table>();
                    for (var item : body) {
                        if (item.ys.get(0) - prior > head.ys.get(1) - head.ys.get(0)) break;
                        chain.add(item);
                        prior = item.ys.get(1);
                    }
                    columns.add(chain);
                }
                if (columns.stream().allMatch(c -> !c.isEmpty())) {
                    double begin = columns.get(0).get(0).ys.get(0);
                    var firstColumn = columns.get(0);
                    double end = firstColumn.get(firstColumn.size() - 1).ys.get(1);
                    if (columns.stream().allMatch(c -> c.get(0).ys.get(0) == begin && c.get(c.size() - 1).ys.get(1) == end))
                        columns.forEach(panels::addAll);
                }
            }
            if (!compactGaps && panels.size() == peers.size()) {
                result.add(table);
                continue;
            }
            var xs = panels.stream().flatMap(t -> t.xs.stream()).distinct().sorted().toList();
            var ys = panels.stream().flatMap(t -> t.ys.stream()).distinct().sorted().toList();
            if ((long) (xs.size() - 1) * (ys.size() - 1) > MAX_GRID_TILES) {
                result.add(table);
                continue;
            }
            var cells = new ArrayList<Cell>();
            for (var peer : panels)
                for (var c : peer.cells) {
                    int row = ys.indexOf(c.top), col = xs.indexOf(c.left);
                    cells.add(new Cell(row, col, ys.indexOf(c.bottom) - row, xs.indexOf(c.right) - col,
                        c.left, c.top, c.right, c.bottom));
                }
            if (panels.size() == peers.size()) for (int i = 1; i < peers.size(); i++) {
                double x0 = peers.get(i - 1).xs.get(peers.get(i - 1).xs.size() - 1), x1 = peers.get(i).xs.get(0);
                cells.add(new Cell(0, xs.indexOf(x0), ys.size() - 1, 1, x0, ys.get(0), x1, ys.get(ys.size() - 1)));
            }
            for (int row = 0; row < ys.size() - 1; row++)
                for (int col = 0; col < xs.size() - 1; col++) {
                    final int r = row, c = col;
                    if (cells.stream().anyMatch(cell -> cell.row <= r && cell.row + cell.rowSpan > r
                        && cell.column <= c && cell.column + cell.colSpan > c)) continue;
                    double x0 = xs.get(col), x1 = xs.get(col + 1), y0 = ys.get(row), y1 = ys.get(row + 1);
                    var empty = new Rectangle2D.Double(x0 + RULE_TOLERANCE, y0 + RULE_TOLERANCE,
                        x1 - x0 - 2 * RULE_TOLERANCE, y1 - y0 - 2 * RULE_TOLERANCE);
                    if (paintedAreas.stream().anyMatch(paint -> paint.area.intersects(empty))
                        || text.stream().anyMatch(p -> !p.getUnicode().isBlank() && !flowDirections.contains(p)
                        && empty.intersects(p.getXDirAdj(), p.getYDirAdj() - p.getHeightDir(), p.getWidthDirAdj(), p.getHeightDir()))) {
                        valid = false;
                        break;
                    }
                    cells.add(new Cell(row, col, 1, 1, x0, y0, x1, y1));
                }
            if (!valid) {
                result.add(table);
                continue;
            }
            used.addAll(panels);
            result.add(new Table(xs, ys, cells.stream().sorted(Comparator.comparingInt(Cell::row).thenComparingInt(Cell::column)).toList(),
                List.of(), false, 0, List.of(), List.of()));
        }
        return result.stream().sorted(Comparator.comparingDouble((Table t) -> t.ys.get(0))).toList();
    }

    private List<Table> headerGaps(List<Table> tables, List<TextPosition> text) {
        tables = tables.stream().map(t -> new Table(t.xs, t.ys, t.cells, headerGaps(t.children, text),
            t.enclosed, t.rowGroup, t.inlineLead, t.ruleBands)).toList();
        var result = new ArrayList<Table>(tables);
        for (int i = 1; i < tables.size(); i++) {
            var head = tables.get(i - 1);
            var body = tables.get(i);
            boolean paragraphBelowColumns = head.ys.size() > 2 && body.xs.size() == 2;
            double top = head.ys.get(head.ys.size() - 1), bottom = body.ys.get(0);
            if ((!paragraphBelowColumns && head.ys.size() != 2) || head.xs.size() < 3
                || !head.children.isEmpty() || !body.children.isEmpty() || bottom <= top
                || Math.abs(head.xs.get(0) - body.xs.get(0)) > RULE_TOLERANCE
                || Math.abs(head.xs.get(head.xs.size() - 1) - body.xs.get(body.xs.size() - 1)) > RULE_TOLERANCE
                || !paragraphBelowColumns && head.xs.stream().anyMatch(x -> body.xs.stream().noneMatch(y -> Math.abs(x - y) <= RULE_TOLERANCE))
                || bottom - top > top - head.ys.get(head.ys.size() - 2)
                || head.cells.stream().filter(c -> c.bottom == top && text.stream().anyMatch(p -> !p.getUnicode().isBlank() && c.contains(p)))
                .anyMatch(c -> lines.stream().noneMatch(l -> l.horizontal && Math.abs(l.at - top) <= RULE_TOLERANCE
                    && l.from <= c.left + RULE_TOLERANCE && l.to >= c.right - RULE_TOLERANCE))
                || body.cells.stream().filter(c -> c.row == 0 && text.stream().anyMatch(p -> !p.getUnicode().isBlank() && c.contains(p)))
                .anyMatch(c -> lines.stream().noneMatch(l -> l.horizontal && Math.abs(l.at - bottom) <= RULE_TOLERANCE
                    && l.from <= c.left + RULE_TOLERANCE && l.to >= c.right - RULE_TOLERANCE))) continue;
            double topInk = lines.stream().filter(l -> l.horizontal && Math.abs(l.at - top) <= RULE_TOLERANCE)
                .mapToDouble(l -> l.at + l.halfWidth).max().orElse(top);
            double bottomInk = lines.stream().filter(l -> l.horizontal && Math.abs(l.at - bottom) <= RULE_TOLERANCE)
                .mapToDouble(l -> l.at - l.halfWidth).min().orElse(bottom);
            double clearTop = Math.nextUp((float) topInk), clearBottom = Math.nextDown((float) bottomInk);
            var gap = new Rectangle2D.Double(head.xs.get(0), clearTop,
                head.xs.get(head.xs.size() - 1) - head.xs.get(0), clearBottom - clearTop);
            var clearGap = new Area(gap);
            for (var line : lines)
                if (!line.horizontal && line.from <= top && line.to >= bottom) {
                    double low = Math.nextDown((float) (line.at - line.halfWidth)), high = Math.nextUp((float) (line.at + line.halfWidth));
                    clearGap.subtract(new Area(new Rectangle2D.Double(low, line.from, high - low, line.to - line.from)));
                }
            if (gap.isEmpty() || paintedAreas.stream().anyMatch(p -> {
                var overlap = new Area(p.area);
                overlap.intersect(clearGap);
                return !overlap.isEmpty();
            })
                || text.stream().anyMatch(p -> !p.getUnicode().isBlank() && gap.intersects(p.getXDirAdj(),
                p.getYDirAdj() - p.getHeightDir(), p.getWidthDirAdj(), p.getHeightDir()))) continue;
            // Preserve the physically observed empty band; never delete blank fields from the other source.
            var xs = paragraphBelowColumns ? body.xs : head.xs;
            var cells = paragraphBelowColumns ? List.of(new Cell(0, 0, 1, 1, xs.get(0), top, xs.get(1), bottom))
                : head.cells.stream().map(c -> new Cell(0, c.column, 1, c.colSpan, c.left, top, c.right, bottom)).toList();
            result.add(new Table(xs, List.of(top, bottom), cells, List.of(), false, 0, List.of(), List.of()));
        }
        return result.stream().sorted(Comparator.comparingDouble((Table t) -> t.ys.get(0))).toList();
    }

    private List<Table> detachedRowGroups(List<Table> tables, List<TextPosition> text) {
        var pairs = new ArrayList<List<Table>>();
        for (Table label : tables)
            for (Table values : tables)
                if (alignedDetachedRows(label, values, text)) pairs.add(List.of(label, values));
        var groups = new HashMap<Table, Integer>();
        for (var pair : pairs) {
            // A geometric relation is useful only when both regions have exactly one counterpart.
            if (pair.stream().anyMatch(t -> pairs.stream().filter(p -> p.contains(t)).count() != 1)) continue;
            int group = ++rowGroupId;
            pair.forEach(t -> groups.put(t, group));
        }
        return tables.stream().map(t -> new Table(t.xs, t.ys, t.cells, t.children, t.enclosed,
            groups.getOrDefault(t, 0), t.inlineLead, t.ruleBands)).toList();
    }

    private boolean alignedDetachedRows(Table label, Table values, List<TextPosition> text) {
        if (!label.enclosed || !values.enclosed || label.xs.size() != 2 || values.xs.size() < 3
            || label.ys.size() < 3 || label.ys.size() != values.ys.size()
            || !label.children.isEmpty() || !values.children.isEmpty()
            || label.cells.stream().anyMatch(c -> c.rowSpan != 1)
            || values.cells.stream().anyMatch(c -> c.rowSpan != 1)) return false;
        for (int i = 0; i < label.ys.size(); i++)
            if (Math.abs(label.ys.get(i) - values.ys.get(i)) > RULE_TOLERANCE) return false;
        double gapStart = label.xs.get(1), gapEnd = values.xs.get(0);
        double gap = gapEnd - gapStart;
        // The maximum separation is one local printed character height; it is not a page-wide proximity rule.
        double em = Math.min(characterHeight(label, text), characterHeight(values, text));
        if (gap <= RULE_TOLERANCE || !Double.isFinite(em) || gap > em) return false;
        double y0 = Math.min(label.ys.get(0), values.ys.get(0));
        double y1 = Math.max(label.ys.get(label.ys.size() - 1), values.ys.get(values.ys.size() - 1));
        var gutter = new Rectangle2D.Double(gapStart + RULE_TOLERANCE, y0,
            gap - 2 * RULE_TOLERANCE, y1 - y0);
        if (gutter.isEmpty() || paintedAreas.stream().anyMatch(p -> p.area.intersects(gutter))) return false;
        return text.stream().noneMatch(p -> !p.getUnicode().isBlank()
            && gutter.intersects(p.getXDirAdj(), p.getYDirAdj() - p.getHeightDir(),
            p.getWidthDirAdj(), p.getHeightDir()));
    }

    private static double characterHeight(Table table, List<TextPosition> text) {
        var heights = text.stream().filter(table::contains).filter(p -> !p.getUnicode().isBlank())
            .mapToDouble(TextPosition::getHeightDir).filter(h -> h > 0 && Double.isFinite(h)).sorted().toArray();
        return heights.length == 0 ? Double.NaN : heights[heights.length / 2];
    }

    private List<TextPosition> flowGlyphs(List<Table> input, List<TextPosition> text) {
        var tables = new ArrayList<Table>(input);
        for (int i = 0; i < tables.size(); i++)
            for (var child : tables.get(i).children)
                if (!tables.contains(child)) tables.add(child);
        return text.stream().filter(p -> List.of("▶", "→", "◀", "←", "↓", "↑").contains(p.getUnicode()))
            .filter(p -> {
                double x0 = p.getXDirAdj(), x1 = x0 + p.getWidthDirAdj();
                double y0 = p.getYDirAdj() - p.getHeightDir(), y1 = p.getYDirAdj();
                if (List.of("↓", "↑").contains(p.getUnicode())) {
                    var above = tables.stream().filter(t -> t.enclosed && t.xs.size() == 2
                            && t.ys.get(t.ys.size() - 1) <= y0 && t.xs.get(0) <= x0 && t.xs.get(1) >= x1)
                        .max(Comparator.comparingDouble(t -> t.ys.get(t.ys.size() - 1))).orElse(null);
                    var below = tables.stream().filter(t -> t.enclosed && t.xs.size() == 2
                            && t.ys.get(0) >= y1 && t.xs.get(0) <= x0 && t.xs.get(1) >= x1)
                        .min(Comparator.comparingDouble(t -> t.ys.get(0))).orElse(null);
                    if (above == null || below == null || !above.xs.equals(below.xs)) return false;
                    double start = above.ys.get(above.ys.size() - 1), end = below.ys.get(0);
                    return text.stream().noneMatch(other -> other != p && !other.getUnicode().isBlank()
                        && other.getXDirAdj() < x1 && other.getXDirAdj() + other.getWidthDirAdj() > x0
                        && other.getYDirAdj() > start && other.getYDirAdj() - other.getHeightDir() < end);
                }
                var before = tables.stream().filter(t -> t.enclosed && t.xs.size() >= 2
                        && t.xs.get(t.xs.size() - 1) <= x0 && t.ys.get(0) <= y0 && t.ys.get(t.ys.size() - 1) >= y1)
                    .max(Comparator.comparingDouble(t -> t.xs.get(t.xs.size() - 1))).orElse(null);
                var after = tables.stream().filter(t -> t.enclosed && t.xs.size() >= 2
                        && t.xs.get(0) >= x0 - RULE_TOLERANCE && t.ys.get(0) <= y0 && t.ys.get(t.ys.size() - 1) >= y1)
                    .min(Comparator.comparingDouble(t -> t.xs.get(0))).orElse(null);
                if (before == null || after == null || before.ys.size() != after.ys.size()) return false;
                for (int i = 0; i < before.ys.size(); i++)
                    if (Math.abs(before.ys.get(i) - after.ys.get(i)) > RULE_TOLERANCE) return false;
                var gutter = new Rectangle2D.Double(before.xs.get(before.xs.size() - 1) + RULE_TOLERANCE, y0,
                    after.xs.get(0) - before.xs.get(before.xs.size() - 1) - 2 * RULE_TOLERANCE,
                    y1 - y0);
                var clear = new Area(gutter);
                for (var line : lines) {
                    if (!line.horizontal && line.from <= gutter.getMinY() && line.to >= gutter.getMaxY()) {
                        double low = Math.nextDown((float) (line.at - line.halfWidth));
                        double high = Math.nextUp((float) (line.at + line.halfWidth));
                        clear.subtract(new Area(new Rectangle2D.Double(low, line.from, high - low, line.to - line.from)));
                    }
                }
                if (gutter.isEmpty() || paintedAreas.stream().anyMatch(paint -> {
                    var overlap = new Area(paint.area);
                    overlap.intersect(clear);
                    return !overlap.isEmpty();
                }))
                    return false;
                // Only a standalone direction between two matching physical boxes is a
                // reading region. An arrow in a sentence, label or cell remains ordinary text.
                return text.stream().noneMatch(other -> other != p && !other.getUnicode().isBlank()
                    && !List.of("▶", "→", "◀", "←").contains(other.getUnicode())
                    && before.cells.stream().noneMatch(c -> c.contains(other))
                    && after.cells.stream().noneMatch(c -> c.contains(other))
                    && other.getXDirAdj() < after.xs.get(0)
                    && other.getXDirAdj() + other.getWidthDirAdj() > before.xs.get(before.xs.size() - 1)
                    && other.getYDirAdj() > before.ys.get(0)
                    && other.getYDirAdj() - other.getHeightDir() < before.ys.get(before.ys.size() - 1));
            }).toList();
    }

    private static HashMap<Table, List<TextPosition>> inlineLeads(List<Table> tables, List<TextPosition> text) {
        var result = new HashMap<Table, List<TextPosition>>();
        for (Table table : tables) {
            if (!table.enclosed || table.ys.size() != 2 || table.xs.size() != 3 || result.containsKey(table)) continue;
            var peers = tables.stream().filter(t -> t.enclosed && t.ys.size() == 2 && t.xs.size() == 3
                    && Math.abs(t.ys.get(0) - table.ys.get(0)) <= RULE_TOLERANCE
                    && Math.abs(t.ys.get(1) - table.ys.get(1)) <= RULE_TOLERANCE
                    && tables.stream().allMatch(parent -> parent.contains(t) == parent.contains(table)))
                .sorted(Comparator.comparingDouble(t -> t.xs.get(0))).toList();
            if (peers.size() != 2 || peers.get(0).overlaps(peers.get(1))) continue;
            var lead = text.stream().filter(table::inVerticalBand)
                .filter(p -> tables.stream().filter(parent -> parent.contains(table)).allMatch(parent -> parent.contains(p)))
                .filter(p -> peers.stream().noneMatch(t -> t.contains(p))).toList();
            var visible = lead.stream().filter(p -> !p.getUnicode().isBlank()).toList();
            if (visible.isEmpty()) continue;
            double baseline = visible.get(0).getYDirAdj();
            // Two physical one-row boxes may follow a single line of prose on their left.
            // Preserve that observed lead, without guessing a logical spacer cell or a form role.
            if (visible.stream().anyMatch(p -> p.getXDirAdj() + p.getWidthDirAdj() > peers.get(0).xs.get(0)
                || Math.abs(p.getYDirAdj() - baseline) > RULE_TOLERANCE
                || p.getYDirAdj() - p.getHeightDir() < table.ys.get(0) - RULE_TOLERANCE
                || p.getYDirAdj() > table.ys.get(1) + RULE_TOLERANCE
                || tables.stream().anyMatch(other -> !peers.contains(other) && !other.contains(table) && other.contains(p))))
                continue;
            peers.forEach(t -> result.put(t, lead));
        }
        return result;
    }

    private List<RuleBand> ruleBands(Table table, List<TextPosition> text, List<Line> rules) {
        var result = new ArrayList<RuleBand>();
        for (boolean horizontal : new boolean[]{false, true}) {
            var axis = horizontal ? table.ys : table.xs;
            var cross = horizontal ? table.xs : table.ys;
            for (int i = 1; i < axis.size() - 2; i++) {
                final int index = i;
                var cells = table.cells.stream().filter(c -> horizontal
                    ? c.row <= index && c.row + c.rowSpan > index : c.column <= index && c.column + c.colSpan > index).toList();
                if (cells.stream().anyMatch(c -> (horizontal ? c.rowSpan : c.colSpan) != 1
                    || text.stream().anyMatch(p -> !p.getUnicode().isBlank() && c.contains(p)))) continue;
                double from = axis.get(i), to = axis.get(i + 1);
                var first = rules.stream().filter(l -> l.horizontal == horizontal && Math.abs(l.at - from) <= RULE_TOLERANCE
                    && l.from < cross.get(cross.size() - 1) && l.to > cross.get(0)).toList();
                var second = rules.stream().filter(l -> l.horizontal == horizontal && Math.abs(l.at - to) <= RULE_TOLERANCE
                    && l.from < cross.get(cross.size() - 1) && l.to > cross.get(0)).toList();
                if (first.isEmpty() || second.isEmpty()) continue;
                Line pen = first.get(0);
                if (pen.halfWidth <= 0 || java.util.stream.Stream.concat(first.stream(), second.stream())
                    .anyMatch(l -> Math.abs(l.halfWidth - pen.halfWidth) > .01 || !l.paint.equals(pen.paint)))
                    continue;
                boolean complete = true;
                for (int c = 0; c < cross.size() - 1; c++)
                    complete &= wall(rules, horizontal, from, cross.get(c), cross.get(c + 1)) == 1
                        && wall(rules, horizontal, to, cross.get(c), cross.get(c + 1)) == 1;
                double clearWidth = to - from - 2 * pen.halfWidth;
                if (!complete || clearWidth <= 0) continue;
                // PDF path/pen coordinates have float precision. Stay strictly inside the
                // observed ink edges rather than mistaking their rounding residue for content.
                double clearFrom = Math.nextUp((float) (from + pen.halfWidth));
                double clearTo = Math.nextDown((float) (to - pen.halfWidth));
                var empty = new Area(horizontal
                    ? new Rectangle2D.Double(cross.get(0), clearFrom, cross.get(cross.size() - 1) - cross.get(0), clearTo - clearFrom)
                    : new Rectangle2D.Double(clearFrom, cross.get(0), clearTo - clearFrom, cross.get(cross.size() - 1) - cross.get(0)));
                // Grid intersections are physical ink, not cell contents. Every other painted
                // mark in the observed band prevents it from serving as an empty border witness.
                for (Line line : lines) {
                    if (line.horizontal == horizontal || line.halfWidth <= 0
                        || cross.stream().noneMatch(at -> Math.abs(at - line.at) <= Math.ulp(at.floatValue())))
                        continue;
                    double low = Math.nextDown((float) (line.at - line.halfWidth));
                    double high = Math.nextUp((float) (line.at + line.halfWidth));
                    double start = Math.nextDown((float) line.from), end = Math.nextUp((float) line.to);
                    empty.subtract(new Area(line.horizontal
                        ? new Rectangle2D.Double(start, low, end - start, high - low)
                        : new Rectangle2D.Double(low, start, high - low, end - start)));
                }
                boolean marked = paintedAreas.stream().anyMatch(paint -> {
                    if (!paint.area.intersects(empty.getBounds2D())) return false;
                    if (paint.solidFill && paint.area.isRectangular()) {
                        var bounds = paint.area.getBounds2D();
                        boolean background = bounds.getMinX() >= table.xs.get(0) - RULE_TOLERANCE
                            && bounds.getMaxX() <= table.xs.get(table.xs.size() - 1) + RULE_TOLERANCE
                            && bounds.getMinY() >= table.ys.get(0) - RULE_TOLERANCE
                            && bounds.getMaxY() <= table.ys.get(table.ys.size() - 1) + RULE_TOLERANCE
                            && table.cells.stream().anyMatch(c -> (horizontal
                            ? c.row + c.rowSpan == index && bounds.getMaxY() <= to + RULE_TOLERANCE
                            || c.row == index + 1 && bounds.getMinY() >= from - RULE_TOLERANCE
                            : c.column + c.colSpan == index && bounds.getMaxX() <= to + RULE_TOLERANCE
                            || c.column == index + 1 && bounds.getMinX() >= from - RULE_TOLERANCE)
                            && bounds.getMinX() <= c.left + RULE_TOLERANCE && bounds.getMaxX() >= c.right - RULE_TOLERANCE
                            && bounds.getMinY() <= c.top + RULE_TOLERANCE && bounds.getMaxY() >= c.bottom - RULE_TOLERANCE
                            && text.stream().anyMatch(p -> !p.getUnicode().isBlank() && c.contains(p)));
                        if (background) return false;
                    }
                    var overlap = new Area(paint.area);
                    overlap.intersect(empty);
                    return !overlap.isEmpty();
                });
                if (!marked) result.add(new RuleBand(horizontal, i + 1, to - from + 2 * pen.halfWidth));
            }
        }
        return result;
    }

    private static List<Table> arrangeRegions(List<Table> input, List<TextPosition> pictureText) {
        var regions = new ArrayList<Table>();
        var directionRegions = new java.util.HashSet<Table>();
        for (Table table : input)
            regions.add(new Table(table.xs, table.ys, table.cells,
                arrangeRegions(table.children, pictureText.stream().filter(table::contains).toList()),
                table.enclosed, table.rowGroup, table.inlineLead, table.ruleBands));
        for (TextPosition picture : pictureText) {
            if (regions.stream().anyMatch(t -> t.xs.size() > 2 && t.cells.stream().anyMatch(c ->
                picture.getXDirAdj() - RULE_TOLERANCE >= c.left && picture.getXDirAdj() - RULE_TOLERANCE < c.right
                    && centerY(picture) >= c.top && centerY(picture) < c.bottom))) continue;
            if (regions.stream().anyMatch(t -> picture.getXDirAdj() > t.xs.get(0) + RULE_TOLERANCE
                && t.cells.stream().anyMatch(c -> c.contains(picture)))) continue;
            double x0 = picture.getXDirAdj(), x1 = x0 + picture.getWidthDirAdj();
            double y1 = picture.getYDirAdj(), y0 = y1 - picture.getHeightDir();
            var region = new Table(List.of(x0, x1), List.of(y0, y1),
                List.of(new Cell(0, 0, 1, 1, x0, y0, x1, y1)), List.of(), false, 0, List.of(), List.of());
            regions.add(region);
            directionRegions.add(region);
        }
        regions.sort(Comparator.comparingDouble((Table t) -> t.ys.get(0)).thenComparingDouble(t -> t.xs.get(0)));
        var result = new ArrayList<Table>();
        var row = new ArrayList<Table>();
        for (Table region : regions) {
            // Separate boxes and their arrow pictures form a left-to-right row only when every
            // region overlaps vertically and their horizontal spans are disjoint.
            if (!row.isEmpty() && row.stream().anyMatch(t -> t.ys.get(t.ys.size() - 1) <= region.ys.get(0)
                || !directionRegions.contains(t) && !directionRegions.contains(region)
                && t.xs.get(0) < region.xs.get(region.xs.size() - 1) && region.xs.get(0) < t.xs.get(t.xs.size() - 1))) {
                row.sort(Comparator.comparingDouble(t -> t.xs.get(0)));
                result.addAll(row);
                row.clear();
            }
            row.add(region);
        }
        row.sort(Comparator.comparingDouble(t -> t.xs.get(0)));
        result.addAll(row);
        return result;
    }

    List<List<TextPosition>> columns(List<TextPosition> text) {
        if (text.isEmpty() || limited) return List.of(text);
        double em = text.stream().mapToDouble(TextPosition::getHeightDir).filter(h -> h > 0).sorted()
            .skip(text.size() / 2).findFirst().orElse(10);
        var occupied = text.stream().filter(p -> !p.getUnicode().isBlank())
            .map(p -> new Interval(p.getXDirAdj(), p.getXDirAdj() + p.getWidthDirAdj()))
            .sorted(Comparator.comparingDouble(Interval::from)).toList();
        var cuts = new ArrayList<Double>();
        if (!occupied.isEmpty()) {
            double end = occupied.get(0).to;
            for (Interval range : occupied) {
                double cut = (end + range.from) / 2;
                // A gutter must cross the whole page's text, be wider than two ems, and not cut a ruled table.
                if (range.from - end >= 2 * em && end - occupied.get(0).from >= MIN_COLUMN_WIDTH_EM * em
                    && occupied.get(occupied.size() - 1).to - range.from >= MIN_COLUMN_WIDTH_EM * em
                    && java.util.stream.Stream.concat(lines.stream(), filledRuleGroups.stream().flatMap(List::stream))
                    .noneMatch(l -> l.horizontal && l.from < cut && l.to > cut))
                    cuts.add(cut);
                end = Math.max(end, range.to);
            }
        }
        var result = new ArrayList<List<TextPosition>>();
        for (int i = 0; i <= cuts.size(); i++) result.add(new ArrayList<>());
        for (TextPosition position : text) {
            int column = 0;
            double center = position.getXDirAdj() + position.getWidthDirAdj() / 2;
            while (column < cuts.size() && center > cuts.get(column)) column++;
            result.get(column).add(position);
        }
        return result;
    }

    /**
     * Standalone directions in empty vertical gutters order a multi-line procedure within one cell.
     */
    static List<List<TextPosition>> flowColumns(List<TextPosition> text) {
        var arrows = text.stream().filter(p -> List.of("→", "←", "▶", "◀").contains(p.getUnicode()))
            .sorted(Comparator.comparingDouble(TextPosition::getXDirAdj)).toList();
        if (arrows.size() < 2 || arrows.stream().map(TextPosition::getUnicode).distinct().count() != 1)
            return List.of(text);
        double top = arrows.stream().mapToDouble(p -> p.getYDirAdj() - p.getHeightDir()).max().orElse(0);
        double bottom = arrows.stream().mapToDouble(TextPosition::getYDirAdj).min().orElse(0);
        if (top >= bottom) return List.of(text);
        var result = new ArrayList<List<TextPosition>>();
        double left = Double.NEGATIVE_INFINITY;
        for (var arrow : arrows) {
            double x0 = arrow.getXDirAdj(), x1 = x0 + arrow.getWidthDirAdj();
            if (x0 <= left || text.stream().anyMatch(p -> p != arrow && !p.getUnicode().isBlank()
                && p.getXDirAdj() < x1 && p.getXDirAdj() + p.getWidthDirAdj() > x0)) return List.of(text);
            double edge = left;
            var step = text.stream().filter(p -> !p.getUnicode().isBlank() && p.getXDirAdj() >= edge
                && p.getXDirAdj() + p.getWidthDirAdj() <= x0).toList();
            if (step.isEmpty()) return List.of(text);
            result.add(step);
            result.add(List.of(arrow));
            left = x1;
        }
        double edge = left;
        var finalStep = text.stream().filter(p -> !p.getUnicode().isBlank() && p.getXDirAdj() >= edge).toList();
        if (finalStep.isEmpty()) return List.of(text);
        result.add(finalStep);
        return List.copyOf(result);
    }

    List<Table> tables(List<TextPosition> text) {
        if (text.isEmpty() || limited) return List.of();
        var rules = merge(lines, text);
        if (rules.size() > MAX_RULES) return List.of();
        var components = new ArrayList<>(connectedRules(rules));
        var tables = new ArrayList<Table>();
        for (var fill : filledRuleGroups) {
            var frame = grid(fill);
            var contained = components.stream().filter(group -> group.stream().allMatch(line -> insideFrame(line, frame))).toList();
            var enclosedRules = new ArrayList<>(fill);
            contained.forEach(enclosedRules::addAll);
            Table table = grid(merge(enclosedRules, text));
            if (table != null && text.stream().anyMatch(table::contains)) {
                tables.add(table);
                components.removeAll(contained);
            }
        }
        for (var component : components) {
            Table table = grid(component);
            if (table != null && text.stream().anyMatch(table::contains)) tables.add(table);
        }
        var standaloneDirections = flowGlyphs(tables, text);
        // Nested tables are retained only when wholly inside one confirmed parent cell.
        var valid = tables.stream().filter(t -> tables.stream().noneMatch(o -> o != t && t.overlaps(o)
                && !t.contains(o) && !o.contains(t) && text.stream().anyMatch(p -> !p.getUnicode().isBlank() && t.contains(p) && o.contains(p))))
            .filter(t -> text.stream().filter(t::contains).filter(p -> !standaloneDirections.contains(p))
                .allMatch(p -> t.cells.stream().filter(c -> c.contains(p)).count() == 1))
            .toList();
        var directions = flowGlyphs(valid, text);
        var leads = inlineLeads(valid, text);
        valid = valid.stream().map(t -> new Table(t.xs, t.ys, t.cells, t.children, t.enclosed,
            t.rowGroup, leads.getOrDefault(t, List.of()), ruleBands(t, text, rules))).toList();
        var regions = valid;
        return regions.stream().filter(t -> regions.stream().noneMatch(o -> o != t && o.contains(t)))
            .filter(t -> text.stream().noneMatch(p -> !p.getUnicode().isBlank() && !directions.contains(p) && !t.inlineLead.contains(p)
                && t.inVerticalBand(p) && !t.contains(p)
                && regions.stream().noneMatch(o -> !t.overlaps(o) && o.contains(p))))
            .map(t -> nested(t, regions))
            .sorted(Comparator.comparingDouble((Table t) -> t.ys.get(0)).thenComparingDouble(t -> t.xs.get(0))).toList();
    }

    private static boolean insideFrame(Line line, Table frame) {
        var axis = line.horizontal ? frame.ys : frame.xs;
        var cross = line.horizontal ? frame.xs : frame.ys;
        return line.at >= axis.get(0) - RULE_TOLERANCE && line.at <= axis.get(axis.size() - 1) + RULE_TOLERANCE
            && line.from >= cross.get(0) - RULE_TOLERANCE && line.to <= cross.get(cross.size() - 1) + RULE_TOLERANCE;
    }

    private static List<List<Line>> connectedRules(List<Line> rules) {
        int[] group = new int[rules.size()];
        for (int i = 0; i < group.length; i++) group[i] = i;
        for (int i = 0; i < rules.size(); i++)
            for (int j = i + 1; j < rules.size(); j++) {
                Line a = rules.get(i), b = rules.get(j);
                if (a.horizontal != b.horizontal && a.at >= b.from - RULE_TOLERANCE && a.at <= b.to + RULE_TOLERANCE
                    && b.at >= a.from - RULE_TOLERANCE && b.at <= a.to + RULE_TOLERANCE) join(group, i, j);
            }
        var components = new HashMap<Integer, List<Line>>();
        for (int i = 0; i < rules.size(); i++)
            components.computeIfAbsent(root(group, i), k -> new ArrayList<>()).add(rules.get(i));
        return List.copyOf(components.values());
    }

    private static Table nested(Table parent, List<Table> all) {
        var children = all.stream().filter(parent::contains)
            .filter(t -> all.stream().noneMatch(o -> parent.contains(o) && o.contains(t)))
            .map(t -> nested(t, all)).sorted(Comparator.comparingDouble(t -> t.ys.get(0))).toList();
        return new Table(parent.xs, parent.ys, parent.cells, children, parent.enclosed, parent.rowGroup, parent.inlineLead, parent.ruleBands);
    }

    private static List<Line> dashedRules(List<Line> input, List<TextPosition> text) {
        var sorted = input.stream().sorted(Comparator.comparing(Line::horizontal).thenComparingDouble(Line::at)
            .thenComparingDouble(Line::from)).distinct().toList();
        var result = new ArrayList<Line>();
        for (int start = 0; start < sorted.size(); ) {
            Line first = sorted.get(start);
            if (start + 2 >= sorted.size()) {
                result.add(first);
                start++;
                continue;
            }
            Line next = sorted.get(start + 1);
            double length = first.to - first.from, gap = next.from - first.to;
            int end = start + 1;
            while (gap > .01 && end < sorted.size()) {
                Line prior = sorted.get(end - 1), current = sorted.get(end);
                if (!sameStroke(first, current) || Math.abs(current.from - prior.to - gap) > .01
                    || current.to - current.from > length + .01) break;
                end++;
                // The end of the drawn path may clip its final repeated dash.
                if (Math.abs(current.to - current.from - length) > .01) break;
            }
            if (end - start < 3) {
                result.add(first);
                start++;
                continue;
            }
            Line last = sorted.get(end - 1);
            double period = length + gap;
            var perpendicular = sorted.stream().filter(l -> l.horizontal != first.horizontal
                && l.from <= first.at && l.to >= first.at).toList();
            if (perpendicular.size() > MAX_RULES) return input;
            double leftEdge = perpendicular.stream().filter(l -> l.at <= first.from + l.halfWidth + first.halfWidth + .01 && first.from - l.at <= period)
                .mapToDouble(Line::at).max().orElse(Double.NaN);
            double rightEdge = perpendicular.stream().filter(l -> l.at >= last.to - l.halfWidth - first.halfWidth - .01 && l.at - last.to <= period)
                .mapToDouble(Line::at).min().orElse(Double.NaN);
            boolean clear = text.stream().noneMatch(p -> !p.getUnicode().isBlank()
                && (first.horizontal
                ? p.getXDirAdj() < last.to && p.getXDirAdj() + p.getWidthDirAdj() > first.from
                && p.getYDirAdj() - p.getHeightDir() < first.at && p.getYDirAdj() > first.at
                : p.getYDirAdj() - p.getHeightDir() < last.to && p.getYDirAdj() > first.from
                && p.getXDirAdj() < first.at && p.getXDirAdj() + p.getWidthDirAdj() > first.at)
                && glyphCrossesRule(p, first, last));
            // Three equal strokes establish two equal gaps. Snap to existing walls only;
            // otherwise retain this observed run as a partial rule. Discarding its tiny
            // fragments would falsely turn a broken divider into a merged cell.
            if (clear) {
                result.add(new Line(first.horizontal, first.at, Double.isFinite(leftEdge) ? leftEdge : first.from,
                    Double.isFinite(rightEdge) ? rightEdge : last.to, first.halfWidth, first.paint));
                start = end;
            } else {
                result.addAll(sorted.subList(start, end));
                start = end;
            }
        }
        return result;
    }

    private static boolean glyphCrossesRule(TextPosition position, Line first, Line last) {
        // A glyph advance can extend past a cell wall while the printed letter stays inside.
        // Only a readable embedded outline can disprove that apparent text/rule intersection.
        var font = position.getFont();
        var matrix = position.getTextMatrix();
        if (!(font instanceof org.apache.pdfbox.pdmodel.font.PDVectorFont vector) || !font.isEmbedded()
            || font.isDamaged() || font.isVertical() || position.getCharacterCodes().length != 1
            || position.getDir() != 0 || matrix.getShearX() != 0 || matrix.getShearY() != 0
            || matrix.getScaleX() <= 0 || matrix.getScaleY() <= 0) return true;
        try {
            int code = position.getCharacterCodes()[0];
            if (!vector.hasGlyph(code)) return true;
            var outline = vector.getNormalizedPath(code);
            if (outline == null || outline.getBounds2D().isEmpty()) return true;
            var transform = new AffineTransform(matrix.getScaleX(), 0, 0, -matrix.getScaleY(),
                position.getXDirAdj(), position.getYDirAdj());
            transform.concatenate(font.getFontMatrix().createAffineTransform());
            var ink = transform.createTransformedShape(outline);
            double width = Math.max(first.halfWidth, .01);
            return first.horizontal
                ? ink.intersects(first.from, first.at - width, last.to - first.from, width * 2)
                : ink.intersects(first.at - width, first.from, width * 2, last.to - first.from);
        } catch (IOException unreadable) {
            return true;
        }
    }

    private static boolean sameStroke(Line first, Line second) {
        return first.horizontal == second.horizontal && Math.abs(first.at - second.at) <= .01
            && Math.abs(first.halfWidth - second.halfWidth) <= .01 && first.paint.equals(second.paint);
    }

    private static List<Line> merge(List<Line> input, List<TextPosition> text) {
        var result = new ArrayList<Line>();
        var sorted = dashedRules(input, text).stream().sorted(Comparator.comparing(Line::horizontal).thenComparingDouble(Line::at)
            .thenComparingDouble(Line::from)).toList();
        for (Line line : sorted) {
            int index = -1;
            for (int i = result.size() - 1; i >= 0; i--) {
                Line previous = result.get(i);
                if (previous.horizontal != line.horizontal || line.at - previous.at > RULE_TOLERANCE) break;
                if (line.from <= previous.to + RULE_TOLERANCE && line.to >= previous.from - RULE_TOLERANCE) {
                    index = i;
                    break;
                }
            }
            if (index < 0) result.add(line);
            else {
                Line previous = result.get(index);
                result.set(index, new Line(line.horizontal, previous.at,
                    Math.min(line.from, previous.from), Math.max(line.to, previous.to),
                    Math.max(line.halfWidth, previous.halfWidth), line.paint));
            }
        }
        // Finish joining the cell-by-cell pieces before comparing complete parallel borders.
        var borders = new ArrayList<Line>();
        for (Line line : result) {
            int match = -1;
            for (int i = borders.size() - 1; i >= 0; i--)
                if (doubleRule(borders.get(i), line, input, text)) {
                    match = i;
                    break;
                }
            if (match < 0) borders.add(line);
            else {
                Line previous = borders.get(match);
                double low = Math.min(previous.at - previous.halfWidth, line.at - line.halfWidth);
                double high = Math.max(previous.at + previous.halfWidth, line.at + line.halfWidth);
                borders.set(match, new Line(true, (low + high) / 2, Math.min(previous.from, line.from),
                    Math.max(previous.to, line.to), (high - low) / 2, line.paint));
            }
        }
        return borders;
    }

    private static boolean doubleRule(Line first, Line second, List<Line> all, List<TextPosition> text) {
        if (!first.horizontal || !second.horizontal || second.at <= first.at
            || second.at - first.at > 2 * (first.halfWidth + second.halfWidth) + .01
            || Math.abs(first.from - second.from) > RULE_TOLERANCE || Math.abs(first.to - second.to) > RULE_TOLERANCE)
            return false;
        // A double border is narrower than the combined stroke widths, contains no text and
        // has no vertical divider beginning/ending between its two parallel strokes.
        return all.stream().noneMatch(l -> !l.horizontal && l.at >= first.from && l.at <= first.to
            && (l.from > first.at + .01 && l.from < second.at - .01 || l.to > first.at + .01 && l.to < second.at - .01))
            && text.stream().noneMatch(p -> !p.getUnicode().isBlank()
            && p.getXDirAdj() < first.to && p.getXDirAdj() + p.getWidthDirAdj() > first.from
            && p.getYDirAdj() > first.at && p.getYDirAdj() - p.getHeightDir() < second.at);
    }

    private static Table grid(List<Line> rules) {
        List<Double> xs = coordinates(rules.stream().filter(l -> !l.horizontal).map(Line::at).toList());
        List<Double> ys = coordinates(rules.stream().filter(Line::horizontal).map(Line::at).toList());
        boolean boundedOpenSides = false;
        if (!xs.isEmpty() && ys.size() >= 2) {
            double start = rules.stream().filter(Line::horizontal).mapToDouble(Line::from).min().orElseThrow();
            double end = rules.stream().filter(Line::horizontal).mapToDouble(Line::to).max().orElseThrow();
            // Some official tables omit the outside vertical strokes. Their two complete
            // horizontal edges and interior dividers still establish an explicit finite grid.
            boundedOpenSides = xs.get(0) >= start - RULE_TOLERANCE && xs.get(xs.size() - 1) <= end + RULE_TOLERANCE
                && wall(rules, true, ys.get(0), start, end) == 1
                && wall(rules, true, ys.get(ys.size() - 1), start, end) == 1;
            if (boundedOpenSides) {
                var boundaries = new ArrayList<>(xs);
                // A horizontal endpoint inside the existing outside stroke is a joint,
                // not a second empty column beyond the wall's centreline.
                double leftRadius = Math.min(jointRadius(rules, true, xs.get(0), ys.get(0)),
                    jointRadius(rules, true, xs.get(0), ys.get(ys.size() - 1)));
                double rightRadius = Math.min(jointRadius(rules, true, xs.get(xs.size() - 1), ys.get(0)),
                    jointRadius(rules, true, xs.get(xs.size() - 1), ys.get(ys.size() - 1)));
                if (xs.get(0) - start > RULE_TOLERANCE + leftRadius) boundaries.add(start);
                if (end - xs.get(xs.size() - 1) > RULE_TOLERANCE + rightRadius) boundaries.add(end);
                xs = coordinates(boundaries);
            }
        }
        int cols = xs.size() - 1, rows = ys.size() - 1;
        // Single-cell frames also establish a paragraph's reading region, but never become table metadata.
        if (cols < 1 || rows < 1 || cols > 256 || rows > 2048 || cols * rows > MAX_GRID_TILES) return null;
        boolean enclosed = true;
        for (int y = 0; y < rows; y++) {
            int leftWall = wall(rules, false, xs.get(0), ys.get(y), ys.get(y + 1));
            int rightWall = wall(rules, false, xs.get(cols), ys.get(y), ys.get(y + 1));
            if (leftWall < 0 || rightWall < 0 || !boundedOpenSides && (leftWall != 1 || rightWall != 1)) return null;
            enclosed &= leftWall == 1 && rightWall == 1;
        }
        for (int x = 0; x < cols; x++) {
            if (wall(rules, true, ys.get(0), xs.get(x), xs.get(x + 1)) != 1
                || wall(rules, true, ys.get(rows), xs.get(x), xs.get(x + 1)) != 1) return null;
        }
        int[] group = new int[cols * rows];
        for (int i = 0; i < group.length; i++) group[i] = i;
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++) {
                if (x + 1 < cols) {
                    int wall = wall(rules, false, xs.get(x + 1), ys.get(y), ys.get(y + 1));
                    if (wall < 0) return null;
                    if (wall == 0) join(group, y * cols + x, y * cols + x + 1);
                }
                if (y + 1 < rows) {
                    int wall = wall(rules, true, ys.get(y + 1), xs.get(x), xs.get(x + 1));
                    if (wall < 0) return null;
                    if (wall == 0) join(group, y * cols + x, (y + 1) * cols + x);
                }
            }
        var tiles = new HashMap<Integer, List<Integer>>();
        for (int i = 0; i < group.length; i++) tiles.computeIfAbsent(root(group, i), k -> new ArrayList<>()).add(i);
        var cells = new ArrayList<Cell>();
        for (List<Integer> members : tiles.values()) {
            int x0 = cols, x1 = 0, y0 = rows, y1 = 0;
            for (int tile : members) {
                x0 = Math.min(x0, tile % cols);
                x1 = Math.max(x1, tile % cols + 1);
                y0 = Math.min(y0, tile / cols);
                y1 = Math.max(y1, tile / cols + 1);
            }
            if ((x1 - x0) * (y1 - y0) != members.size()) return null;
            cells.add(new Cell(y0, x0, y1 - y0, x1 - x0, xs.get(x0), ys.get(y0), xs.get(x1), ys.get(y1)));
        }
        cells.sort(Comparator.comparingInt(Cell::row).thenComparingInt(Cell::column));
        return new Table(xs, ys, cells, List.of(), enclosed, 0, List.of(), List.of());
    }

    private static List<Double> coordinates(List<Double> values) {
        var result = new ArrayList<Double>();
        for (double value : values.stream().sorted().toList())
            if (result.isEmpty() || value - result.get(result.size() - 1) > RULE_TOLERANCE) result.add(value);
        return result;
    }

    private static int wall(List<Line> rules, boolean horizontal, double at, double from, double to) {
        boolean partial = false;
        for (Line line : rules)
            if (line.horizontal == horizontal && Math.abs(line.at - at) <= RULE_TOLERANCE) {
                double startTolerance = RULE_TOLERANCE + line.halfWidth + jointRadius(rules, horizontal, from, at);
                double endTolerance = RULE_TOLERANCE + line.halfWidth + jointRadius(rules, horizontal, to, at);
                if (line.from <= from + startTolerance && line.to >= to - endTolerance) return 1;
                if (line.from < to - endTolerance && line.to > from + startTolerance) partial = true;
            }
        return partial ? -1 : 0;
    }

    private static double jointRadius(List<Line> rules, boolean horizontal, double endpoint, double at) {
        return rules.stream().filter(l -> l.horizontal != horizontal && Math.abs(l.at - endpoint) <= RULE_TOLERANCE
                && at >= l.from - RULE_TOLERANCE && at <= l.to + RULE_TOLERANCE)
            .mapToDouble(Line::halfWidth).max().orElse(0);
    }

    private static int root(int[] groups, int i) {
        while (groups[i] != i) {
            groups[i] = groups[groups[i]];
            i = groups[i];
        }
        return i;
    }

    private static void join(int[] groups, int a, int b) {
        groups[root(groups, a)] = root(groups, b);
    }

    record Table(List<Double> xs, List<Double> ys, List<Cell> cells, List<Table> children, boolean enclosed,
                 int rowGroup,
                 List<TextPosition> inlineLead, List<RuleBand> ruleBands) {
        boolean contains(TextPosition p) {
            return centerX(p) > xs.get(0) && centerX(p) < xs.get(xs.size() - 1) && inVerticalBand(p);
        }

        boolean inVerticalBand(TextPosition p) {
            return centerY(p) > ys.get(0) && centerY(p) < ys.get(ys.size() - 1);
        }

        boolean contains(Table other) {
            return this != other && cells.stream().anyMatch(c -> c.contains(other));
        }

        boolean overlaps(Table other) {
            return xs.get(0) < other.xs.get(other.xs.size() - 1) && xs.get(xs.size() - 1) > other.xs.get(0)
                && ys.get(0) < other.ys.get(other.ys.size() - 1) && ys.get(ys.size() - 1) > other.ys.get(0);
        }
    }

    record RuleBand(boolean horizontal, int index, double width) {
    }

    record Cell(int row, int column, int rowSpan, int colSpan, double left, double top, double right, double bottom) {
        boolean contains(TextPosition p) {
            if (p.getUnicode().isBlank())
                return centerX(p) >= left && centerX(p) < right && centerY(p) >= top && centerY(p) < bottom;
            return p.getXDirAdj() >= left - RULE_TOLERANCE && p.getXDirAdj() + p.getWidthDirAdj() <= right + RULE_TOLERANCE
                && p.getYDirAdj() - p.getHeightDir() >= top - RULE_TOLERANCE && p.getYDirAdj() <= bottom + RULE_TOLERANCE;
        }

        boolean contains(Table t) {
            return t.xs.get(0) > left + RULE_TOLERANCE && t.xs.get(t.xs.size() - 1) < right - RULE_TOLERANCE
                && t.ys.get(0) > top + RULE_TOLERANCE && t.ys.get(t.ys.size() - 1) < bottom - RULE_TOLERANCE;
        }
    }


    private static double centerX(TextPosition p) {
        return p.getXDirAdj() + p.getWidthDirAdj() / 2;
    }

    private static double centerY(TextPosition p) {
        return p.getYDirAdj() - p.getHeightDir() / 2;
    }

    private record Interval(double from, double to) {
    }

    private record Line(boolean horizontal, double at, double from, double to, double halfWidth, String paint) {
    }

    private void recordPath() {
        var points = new float[6];
        Point2D start = null, previous = null;
        for (var cursor = path.getPathIterator(null); !cursor.isDone(); cursor.next()) {
            int kind = cursor.currentSegment(points);
            Point2D point = new Point2D.Double(points[0], points[1]);
            if (kind == PathIterator.SEG_MOVETO) start = point;
            else if (kind == PathIterator.SEG_LINETO && previous != null) line(previous, point);
            else if (kind == PathIterator.SEG_CLOSE && previous != null && start != null) line(previous, start);
            previous = kind == PathIterator.SEG_CUBICTO || kind == PathIterator.SEG_QUADTO ? null : point;
        }
        endPath();
    }

    private void recordPaintedArea(Shape shape, boolean solidFill) {
        if (paintedAreas.size() >= MAX_DRAWN_RULES) {
            limited = true;
            return;
        }
        var area = new Area(shape);
        area.intersect(getGraphicsState().getCurrentClippingPath());
        if (!area.isEmpty()) {
            area.transform(new AffineTransform(1, 0, 0, -1, -left, top));
            var color = getGraphicsState().getNonStrokingColor();
            String paint = solidFill ? color.getColorSpace().getName() + java.util.Arrays.toString(color.getComponents()) : "";
            paintedAreas.add(new PaintedArea(area, solidFill, paint));
        }
    }

    private void recordStrokeArea() {
        var state = getGraphicsState();
        var transform = state.getCurrentTransformationMatrix().createAffineTransform();
        try {
            // Paths already contain the CTM. Apply the pen in its own coordinates before
            // transforming back, so a compound table path does not fill the blank gutter.
            // A dash gap is not evidence of empty space: retain the complete stroke envelope.
            if (state.getLineWidth() == 0) {
                // Device hairlines have no fixed point width; reserve the existing rule tolerance.
                recordPaintedArea(new BasicStroke((float) (2 * RULE_TOLERANCE)).createStrokedShape(path), false);
            } else {
                var pen = new BasicStroke(state.getLineWidth(), state.getLineCap(), state.getLineJoin(), state.getMiterLimit());
                recordPaintedArea(transform.createTransformedShape(pen.createStrokedShape(
                    transform.createInverse().createTransformedShape(path))), false);
            }
        } catch (NoninvertibleTransformException e) {
            // A degenerate transform cannot establish an empty part of its clipping region.
            recordPaintedArea(state.getCurrentClippingPath(), false);
        }
    }

    private void line(Point2D a, Point2D b) {
        if (lines.size() > MAX_DRAWN_RULES) {
            limited = true;
            return;
        }
        var clip = getGraphicsState().getCurrentClippingPath();
        if (!clip.intersects(a.getX() - 0.01, a.getY() - 0.01, 0.02, 0.02)
            || !clip.intersects(b.getX() - 0.01, b.getY() - 0.01, 0.02, 0.02)
            || !clip.contains((a.getX() + b.getX()) / 2, (a.getY() + b.getY()) / 2)) return;
        double x0 = a.getX() - left, x1 = b.getX() - left, y0 = top - a.getY(), y1 = top - b.getY();
        var transform = getGraphicsState().getCurrentTransformationMatrix();
        var color = getGraphicsState().getStrokingColor();
        String paint = color.getColorSpace().getName() + java.util.Arrays.toString(color.getComponents()) + color.getPatternName();
        if (Math.abs(y0 - y1) < 0.01 && Math.abs(x0 - x1) > 0.01)
            lines.add(new Line(true, y0, Math.min(x0, x1), Math.max(x0, x1),
                getGraphicsState().getLineWidth() * Math.hypot(transform.getShearX(), transform.getScaleY()) / 2, paint));
        else if (Math.abs(x0 - x1) < 0.01 && Math.abs(y0 - y1) > 0.01)
            lines.add(new Line(false, x0, Math.min(y0, y1), Math.max(y0, y1),
                getGraphicsState().getLineWidth() * Math.hypot(transform.getScaleX(), transform.getShearY()) / 2, paint));
    }

    @Override
    public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) {
        path.moveTo(a.getX(), a.getY());
        path.lineTo(b.getX(), b.getY());
        path.lineTo(c.getX(), c.getY());
        path.lineTo(d.getX(), d.getY());
        path.closePath();
    }

    @Override
    public void drawImage(PDImage image) throws IOException {
        // Even an unreadable/rotated picture blocks a supposedly empty gap between two tables.
        recordPaintedArea(getGraphicsState().getCurrentTransformationMatrix().createAffineTransform()
            .createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1)), false);
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= 0 || pixels > OcrDocumentParser.MAX_IMAGE_PIXELS || decodedImages >= OcrDocumentParser.MAX_IMAGES
            || pixels > OcrDocumentParser.MAX_TOTAL_PIXELS - decodedPixels) return;
        var matrix = getGraphicsState().getCurrentTransformationMatrix();
        // Skewed, mirrored or rotated pictures need their transformed shape read, not a guessed direction.
        if (matrix.getShearX() != 0 || matrix.getShearY() != 0 || matrix.getScaleX() <= 0 || matrix.getScaleY() <= 0)
            return;
        var origin = matrix.transformPoint(0, 0);
        var extent = matrix.transformPoint(1, 1);
        decodedImages++;
        decodedPixels += pixels;
        var picture = image.getImage();
        var clip = getGraphicsState().getCurrentClippingPath();
        if (!clip.contains(origin.x, origin.y, extent.x - origin.x, extent.y - origin.y)) {
            // Read the visible picture in its native pixel coordinates. Do not infer a
            // direction from the complete resource when the PDF clips its visible contents.
            long visiblePixels = pixels;
            if (visiblePixels > OcrDocumentParser.MAX_IMAGE_PIXELS
                || visiblePixels > OcrDocumentParser.MAX_TOTAL_PIXELS - decodedPixels) return;
            decodedPixels += visiblePixels;
            int colorType = picture.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY
                ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB;
            var visible = new BufferedImage(picture.getWidth(), picture.getHeight(), colorType);
            var graphics = visible.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, visible.getWidth(), visible.getHeight());
            double sx = visible.getWidth() / (double) (extent.x - origin.x), sy = visible.getHeight() / (double) (extent.y - origin.y);
            graphics.setClip(new AffineTransform(sx, 0, 0, -sy, -origin.x * sx, extent.y * sy).createTransformedShape(clip));
            graphics.drawImage(picture, 0, 0, visible.getWidth(), visible.getHeight(), null);
            graphics.dispose();
            picture = visible;
        }
        var reading = RasterArrow.read(picture);
        if (reading != null) {
            double sx = (extent.x - origin.x) / picture.getWidth(), sy = (extent.y - origin.y) / picture.getHeight();
            arrows.add(new Arrow(origin.x + reading.left() * sx - left, top - extent.y + reading.top() * sy,
                reading.width() * sx, reading.height() * sy, reading.direction()));
        }
    }

    private record Arrow(double x, double y, double width, double height, String direction) {
    }

    private record PaintedArea(Area area, boolean solidFill, String paint) {
    }

    @Override
    public void clip(int windingRule) {
        clippingRule = windingRule;
    }

    @Override
    public void moveTo(float x, float y) {
        path.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        path.lineTo(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        path.curveTo(x1, y1, x2, y2, x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
        return path.getCurrentPoint();
    }

    @Override
    public void closePath() {
        path.closePath();
    }

    @Override
    public void endPath() {
        if (clippingRule != null) {
            path.setWindingRule(clippingRule);
            getGraphicsState().intersectClippingPath(path);
            clippingRule = null;
        }
        path.reset();
    }

    @Override
    public void strokePath() {
        recordStrokeArea();
        recordPath();
    }

    @Override
    public void fillPath(int windingRule) {
        path.setWindingRule(windingRule);
        recordPaintedArea(path, getGraphicsState().getNonStrokingColor().getPatternName() == null);
        endPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        path.setWindingRule(windingRule);
        recordPaintedArea(path, getGraphicsState().getNonStrokingColor().getPatternName() == null);
        recordStrokeArea();
        recordPath();
    }

    @Override
    public void shadingFill(COSName name) {
        recordPaintedArea(getGraphicsState().getCurrentClippingPath(), false);
    }
}
