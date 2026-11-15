package org.jibro.collection.extraction;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Restores only a physically blank advancing glyph incorrectly mapped to U+0000 by a PDF font. */
final class PdfLayoutTextStripper extends PDFTextStripper {
    PdfLayoutTextStripper() { setSortByPosition(true); }

    @Override protected void writeString(String text, List<TextPosition> positions) throws IOException {
        super.writeString(restoreBlankGlyphs(text, positions), positions);
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
        if (!(position.getFont() instanceof PDType0Font font) || !font.isEmbedded() || font.isDamaged() || font.isVertical()) return false;
        int code = position.getCharacterCodes()[0];
        try {
            // Missing/.notdef glyphs, visible outlines and zero-width controls are never treated as whitespace.
            return font.codeToGID(code) > 0 && font.hasGlyph(code) && font.getWidth(code) > 0
                && font.getPath(code).getPathIterator(null).isDone();
        } catch (IOException uncertain) { return false; }
    }
}
