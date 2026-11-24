package org.jibro.collection.extraction;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Reads independent columns and proven ruled cells before PDFBox orders the glyphs within each region.
 * Restores only a physically blank advancing glyph incorrectly mapped to U+0000 by a PDF font.
 */
final class PdfLayoutTextStripper extends PDFTextStripper {
    private static final Pattern STRUCTURE = Pattern.compile("(?m)^⟦PDF:[^⟦⟧\\r\\n]+⟧\\r?\\n?");
    private PdfPageLayout layout;
    private int tableId;
    private int cellDepth;
    private StringWriter regionOutput;
    private List<TextPosition> linePositions;
    private List<LinePosition> lineLocations;
    private int lineStart;
    private float pageWidth;
    private float pageHeight;
    private final java.util.Set<ArrowOrigin> arrowOrigins = new java.util.HashSet<>();

    private record ArrowOrigin(String symbol, org.apache.pdfbox.pdmodel.font.PDFont font,
                               float x, float y, float width, float height, String matrix) {
    }

    PdfLayoutTextStripper() {
        setSortByPosition(true);
    }

    static String withoutStructure(String text) {
        return STRUCTURE.matcher(text).replaceAll("");
    }

    @Override
    protected void processTextPosition(TextPosition position) {
        boolean suppress = getSuppressDuplicateOverlappingText();
        if (List.of("→", "←", "▶", "◀").contains(position.getUnicode())) {
            if (!arrowOrigins.add(new ArrowOrigin(position.getUnicode(), position.getFont(), position.getXDirAdj(),
                position.getYDirAdj(), position.getWidthDirAdj(), position.getHeightDir(), position.getTextMatrix().toString())))
                return;
            setSuppressDuplicateOverlappingText(false);
        }
        try {
            super.processTextPosition(position);
        } finally {
            setSuppressDuplicateOverlappingText(suppress);
        }
    }

    @Override
    protected void startPage(PDPage page) throws IOException {
        super.startPage(page);
        arrowOrigins.clear();
        tableId = 0;
        pageWidth = page.getCropBox().getWidth();
        pageHeight = page.getCropBox().getHeight();
        // Direction-adjusted glyph coordinates and graphic coordinates agree only for an unrotated page.
        layout = page.getRotation() == 0 ? new PdfPageLayout(page) : null;
        if (layout != null) layout.processPage(page);
    }

    @Override
    protected void writePage() throws IOException {
        var original = charactersByArticle;
        var positions = new ArrayList<>(original.stream().flatMap(List::stream).toList());
        if (positions.stream().anyMatch(p -> p.getUnicode().contains("⟦") || p.getUnicode().contains("⟧")))
            throw new IOException("PDF 구조 구분 문자 확인 필요");
        if (layout == null || positions.stream().anyMatch(p -> p.getDir() != 0) || original.size() != 1) {
            super.writePage();
            return;
        }
        var pageNumber = physicalPageNumber(positions);
        positions.removeAll(pageNumber);
        var pictureText = layout.arrowPositions(positions);
        positions.addAll(pictureText);
        try {
            for (var column : layout.columns(positions))
                writeContent(column, layout.readingRegions(column, pictureText));
            if (!pageNumber.isEmpty()) marker("PAGE_NUMBER value=" + getCurrentPageNo());
        } finally {
            charactersByArticle = original;
        }
    }

    private List<TextPosition> physicalPageNumber(List<TextPosition> text) {
        var visible = text.stream().filter(p -> !p.getUnicode().isBlank()).toList();
        if (visible.isEmpty()) return List.of();
        var last = visible.stream().max(Comparator.comparingDouble(TextPosition::getYDirAdj)).orElseThrow();
        float height = last.getHeightDir();
        if (height <= 0 || last.getYDirAdj() - height < pageHeight * .95) return List.of();
        var line = visible.stream().filter(p -> Math.abs(p.getYDirAdj() - last.getYDirAdj()) <= height / 2)
            .sorted(Comparator.comparingDouble(TextPosition::getXDirAdj)).toList();
        if (!line.stream().map(TextPosition::getUnicode).collect(Collectors.joining()).equals(Integer.toString(getCurrentPageNo()))
            || line.stream().anyMatch(p -> p.getXDirAdj() < pageWidth * .4 || p.getXDirAdj() + p.getWidthDirAdj() > pageWidth * .6))
            return List.of();
        double preceding = visible.stream().filter(p -> !line.contains(p)).mapToDouble(TextPosition::getYDirAdj).max().orElse(0);
        if (last.getYDirAdj() - height - preceding < height * 2
            || layout.tables(text).stream().anyMatch(t -> line.stream().anyMatch(t::contains))) return List.of();
        return line;
    }

    private void writeContent(List<TextPosition> text, List<PdfPageLayout.Table> tables) throws IOException {
        var pending = new ArrayList<>(text);
        for (var table : tables) {
            var before = pending.stream().filter(p -> (p.getYDirAdj() <= table.ys().get(0) || table.inlineLead().contains(p))
                && tables.stream().noneMatch(t -> t.contains(p))).toList();
            writeRegion(before);
            pending.removeAll(before);
            boolean structured = table.xs().size() > 2 || table.rowGroup() > 0 || pending.stream().noneMatch(table::contains);
            int id = structured ? ++tableId : 0;
            if (structured)
                marker("TABLE id=" + id + " rows=" + (table.ys().size() - 1) + " cols=" + (table.xs().size() - 1)
                    + (table.rowGroup() > 0 ? " rowgroup=" + table.rowGroup() : "")
                    + bandMetadata(table, true) + bandMetadata(table, false));
            for (var cell : table.cells()) {
                if (structured) marker("CELL table=" + id + " row=" + (cell.row() + 1) + " col=" + (cell.column() + 1)
                    + " rowspan=" + cell.rowSpan() + " colspan=" + cell.colSpan());
                var contents = pending.stream().filter(p -> layout.contains(table, cell, p)).toList();
                if (structured) cellDepth++;
                try {
                    var children = table.children().stream().filter(cell::contains).toList();
                    boolean directionsOnly = children.stream().allMatch(child -> !child.enclosed()
                        && child.cells().size() == 1 && contents.stream().filter(child::contains).count() == 1
                        && contents.stream().filter(child::contains)
                        .allMatch(p -> List.of("→", "←", "▶", "◀").contains(p.getUnicode())));
                    if (directionsOnly) {
                        for (var step : PdfPageLayout.flowColumns(contents)) writeRegion(step);
                    } else writeContent(contents, children);
                } finally {
                    if (structured) cellDepth--;
                }
                pending.removeAll(contents);
                if (structured) marker("/CELL");
            }
            if (structured) marker("/TABLE");
        }
        writeRegion(pending);
    }

    private void writeRegion(List<TextPosition> text) throws IOException {
        if (text.isEmpty()) return;
        charactersByArticle = new ArrayList<>();
        charactersByArticle.add(new ArrayList<>(text));
        if (cellDepth == 0) {
            super.writePage();
            return;
        }
        var originalOutput = output;
        regionOutput = new StringWriter();
        output = regionOutput;
        linePositions = new ArrayList<>();
        lineLocations = new ArrayList<>();
        try {
            super.writePage();
            finishLine();
            String value = regionOutput.toString();
            int cursor = 0;
            for (var line : lineLocations) {
                originalOutput.write(value.substring(cursor, line.start));
                originalOutput.write(String.format(Locale.ROOT, "⟦PDF:LINE y0=%.2f y1=%.2f⟧\n", line.minimum, line.maximum));
                cursor = line.start;
            }
            originalOutput.write(value.substring(cursor));
        } finally {
            output = originalOutput;
            regionOutput = null;
            linePositions = null;
            lineLocations = null;
        }
    }

    private static String bandMetadata(PdfPageLayout.Table table, boolean horizontal) {
        String bands = table.ruleBands().stream().filter(b -> b.horizontal() == horizontal)
            .map(b -> String.format(Locale.ROOT, "%d:%.3f", b.index(), b.width())).collect(Collectors.joining(","));
        return bands.isEmpty() ? "" : (horizontal ? " rowbands=" : " colbands=") + bands;
    }

    private void marker(String value) throws IOException {
        getOutput().write("⟦PDF:" + value + "⟧\n");
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) throws IOException {
        if (regionOutput != null) {
            if (linePositions.isEmpty()) lineStart = regionOutput.getBuffer().length();
            positions.stream().filter(p -> !p.getUnicode().isBlank()).forEach(linePositions::add);
        }
        super.writeString(restoreBlankGlyphs(text, positions), positions);
    }

    @Override
    protected void writeLineSeparator() throws IOException {
        finishLine();
        super.writeLineSeparator();
    }

    @Override
    protected void writeParagraphEnd() throws IOException {
        finishLine();
        super.writeParagraphEnd();
    }

    private void finishLine() {
        if (linePositions == null || linePositions.isEmpty()) return;
        lineLocations.add(new LinePosition(lineStart, linePositions.stream().mapToDouble(TextPosition::getYDirAdj).min().orElseThrow(),
            linePositions.stream().mapToDouble(TextPosition::getYDirAdj).max().orElseThrow()));
        linePositions.clear();
    }

    private record LinePosition(int start, double minimum, double maximum) {
    }

    static String restoreBlankGlyphs(String text, List<TextPosition> positions) {
        if (text.indexOf(0) >= 0) {
            var nulGlyphs = positions.stream().filter(position -> position.getUnicode().indexOf(0) >= 0).toList();
            long nulCount = text.chars().filter(character -> character == 0).count();
            if (!nulGlyphs.isEmpty() && nulCount == nulGlyphs.size() && nulGlyphs.stream().allMatch(PdfLayoutTextStripper::blankAdvancingGlyph))
                text = text.replace('\0', ' ');
        }
        return text;
    }

    static boolean blankAdvancingGlyph(TextPosition position) {
        if (!position.getUnicode().equals("\0") || position.getCharacterCodes().length != 1
            || !Float.isFinite(position.getWidthDirAdj()) || position.getWidthDirAdj() <= 0) return false;
        if (!(position.getFont() instanceof PDType0Font font) || !font.isEmbedded() || font.isDamaged() || font.isVertical())
            return false;
        int code = position.getCharacterCodes()[0];
        try {
            // Missing/.notdef glyphs, visible outlines and zero-width controls are never treated as whitespace.
            return font.codeToGID(code) > 0 && font.hasGlyph(code) && font.getWidth(code) > 0
                && font.getPath(code).getPathIterator(null).isDone();
        } catch (IOException uncertain) {
            return false;
        }
    }
}
