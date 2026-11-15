package org.jibro.collection.extraction;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PdfLayoutTextStripperTest {
    @Test void onlyProvenEmptyAdvancingGlyphsBecomeSpacesWithoutChangingOtherText() throws Exception {
        TextPosition blank = blankGlyph();
        assertEquals("신청 서류 / 2026.12.31", restore("신청\0서류 / 2026.12.31", blank));
        assertEquals("1234", restore("1234", blank));
        assertEquals("\0\0", restore("\0\0", blank)); // An unexplained second NUL is not repaired.
        assertEquals("\0", PdfLayoutTextStripper.restoreBlankGlyphs("\0", List.of()));
    }

    @Test void visibleGlyphAndMissingGlyphAreNeverRemoved() throws Exception {
        TextPosition visible = blankGlyph();
        var path = new GeneralPath(); path.moveTo(0, 0); path.lineTo(2, 3);
        when(((PDType0Font) visible.getFont()).getPath(1)).thenReturn(path);
        assertEquals("조건\0예외", restore("조건\0예외", visible));
        TextPosition missing = blankGlyph();
        when(((PDType0Font) missing.getFont()).codeToGID(1)).thenReturn(0);
        assertEquals("조건\0예외", restore("조건\0예외", missing));
        TextPosition absent = blankGlyph();
        when(((PDType0Font) absent.getFont()).hasGlyph(1)).thenReturn(false);
        assertFalse(PdfLayoutTextStripper.blankAdvancingGlyph(absent));
    }

    @Test void nonEmbeddedDamagedVerticalOrUnreadableFontIsUncertain() throws Exception {
        TextPosition nonEmbedded = blankGlyph();
        when(((PDType0Font) nonEmbedded.getFont()).isEmbedded()).thenReturn(false);
        TextPosition damaged = blankGlyph();
        when(((PDType0Font) damaged.getFont()).isDamaged()).thenReturn(true);
        TextPosition vertical = blankGlyph();
        when(((PDType0Font) vertical.getFont()).isVertical()).thenReturn(true);
        TextPosition unreadable = blankGlyph();
        when(((PDType0Font) unreadable.getFont()).getPath(1)).thenThrow(new IOException("unavailable"));
        for (TextPosition position : List.of(nonEmbedded, damaged, vertical, unreadable))
            assertEquals("조건\0예외", restore("조건\0예외", position));
    }

    @Test void controlsMultiCodeAndMixedUncertainGlyphsRemainInvalid() throws Exception {
        TextPosition zeroWidth = blankGlyph(); when(zeroWidth.getWidthDirAdj()).thenReturn(0f);
        TextPosition fontZeroWidth = blankGlyph(); when(((PDType0Font) fontZeroWidth.getFont()).getWidth(1)).thenReturn(0f);
        TextPosition multipleCodes = blankGlyph(); when(multipleCodes.getCharacterCodes()).thenReturn(new int[]{1, 2});
        TextPosition embeddedText = blankGlyph(); when(embeddedText.getUnicode()).thenReturn("x\0");
        for (TextPosition position : List.of(zeroWidth, fontZeroWidth, multipleCodes, embeddedText))
            assertEquals("조건\0예외", restore("조건\0예외", position));
        assertEquals("조건\0예외\0", PdfLayoutTextStripper.restoreBlankGlyphs("조건\0예외\0", List.of(blankGlyph(), zeroWidth)));
    }

    private static String restore(String text, TextPosition position) {
        return PdfLayoutTextStripper.restoreBlankGlyphs(text, List.of(position));
    }

    private static TextPosition blankGlyph() throws Exception {
        var font = mock(PDType0Font.class);
        when(font.isEmbedded()).thenReturn(true);
        when(font.codeToGID(1)).thenReturn(1);
        when(font.hasGlyph(1)).thenReturn(true);
        when(font.getWidth(1)).thenReturn(280f);
        when(font.getPath(1)).thenReturn(new GeneralPath());
        var position = mock(TextPosition.class);
        when(position.getUnicode()).thenReturn("\0");
        when(position.getCharacterCodes()).thenReturn(new int[]{1});
        when(position.getWidthDirAdj()).thenReturn(4.2f);
        when(position.getFont()).thenReturn(font);
        return position;
    }
}
