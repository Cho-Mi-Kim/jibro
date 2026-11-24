package org.jibro.collection.extraction;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.List;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PdfLayoutTextStripperTest {
    @Test
    void exactArrowOverprintIsOneButDifferentOverlappingOriginsAreBothRetained() throws Exception {
        for (float offset : new float[]{0, 3}) {
            try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
                var page = new PDPage();
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    var font = new PDType1Font(Standard14Fonts.FontName.SYMBOL);
                    for (float x : new float[]{100, 100 + offset}) {
                        stream.beginText();
                        stream.setFont(font, 12);
                        stream.newLineAtOffset(x, 600);
                        stream.showText("→");
                        stream.endText();
                    }
                }
                document.save(output);
                String text = new DocumentTextParser().parse(output.toByteArray(), "application/pdf").get(0).text();
                assertEquals(offset == 0 ? 1 : 2, text.chars().filter(c -> c == '→').count());
            }
        }
    }

    @Test
    void officialSuwonPanelsKeepEveryProcedureArrowAndItsOwnDates() throws Exception {
        try (var input = getClass().getResourceAsStream("/extraction/suwon-process.pdf")) {
            var parts = new DocumentTextParser().parse(input.readAllBytes(), "application/pdf");
            String text = parts.get(0).text();
            String plain = PdfLayoutTextStripper.withoutStructure(text).replaceAll("\\s+", "");
            assertTrue(plain.contains("청약신청(인터넷또는현장)→동호지정순번및순번별계약일정발표→→동호지정및계약체결(동호지정→서류제출→계약금납부→계약체결)자격검증→적격자발표및입주"));
            assertTrue(text.contains("rows=2 cols=4"));
            assertTrue(plain.contains("‘26.12.14.(월)~12.15(.화)10시~17시"));
            assertTrue(parts.get(1).text().contains("rows=3 cols=3")); // Physical page 6: foreign household table.
            assertTrue(parts.get(2).text().contains("rows=3 cols=7")); // Physical page 8: intake schedule.
            String procedure = parts.get(3).text(); // Physical page 15: detached procedure boxes.
            assertTrue(procedure.contains("rows=11 cols=5"));
            assertEquals(4, procedure.chars().filter(c -> c == '↓').count());
            String steps = PdfLayoutTextStripper.withoutStructure(procedure).replaceAll("\\s+", "");
            assertTrue(steps.indexOf("등록명부작성") < steps.indexOf("동호지정", steps.indexOf("↓")));
            assertTrue(steps.contains("계약금100만원입금"));
            assertTrue(steps.contains("계약당일17시이후계약불가"));
        }
    }

    @Test
    void officialDetachedHeaderRetainsItsObservedBlankBandBeforeIncomeRows() throws Exception {
        // LH file 69480894, physical page 16; the empty band is present in the PDF, not inferred from HWPX.
        try (var input = getClass().getResourceAsStream("/extraction/seoul-income-header.pdf")) {
            String text = new DocumentTextParser().parse(input.readAllBytes(), "application/pdf").get(0).text();
            var source = new org.jibro.collection.extraction.ExtractionModels.Source("공고.pdf", "https://apply.lh.or.kr/file.pdf",
                "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new org.jibro.collection.extraction.ExtractionModels.Part("공고문 16쪽", text)));
            var reading = org.jibro.collection.review.local.DocumentContextReader.read(source, source.context().get(0));
            assertTrue(reading.valid());
            assertTrue(reading.tables().stream().anyMatch(t -> t.rows() == 1 && t.columns() == 4
                && t.cells().stream().allMatch(c -> reading.text(text, c.start(), c.end()).isBlank())));
            assertTrue(text.contains("상시근로소득"));
        }
    }

    @Test
    void officialUnpartitionedProcedureCellKeepsMultilineStepsWithTheirArrows() throws Exception {
        // Official LH Seoul permanent-rental PDF 69480894, physical page 10; all page contents retained.
        try (var input = getClass().getResourceAsStream("/extraction/seoul-selection-flow.pdf")) {
            var text = new DocumentTextParser().parse(input.readAllBytes(), "application/pdf").get(0).text();
            String plain = PdfLayoutTextStripper.withoutStructure(text).replaceAll("\\s+", "");
            assertTrue(plain.contains("입주자선정순서배점합산순(아래배점기준표참조)→전입일자가오래된자→신청자연령이높은자→추첨"));
            assertTrue(plain.contains("동일점수일때선정순서"));
        }
    }

    @Test
    void clippedPaddedPictureUsesTheVisibleArrowPositionAndCannotRestoreAHiddenHead() throws Exception {
        String visible = new DocumentTextParser().parse(paddedArrowPdf(100), "application/pdf").get(0).text();
        assertEquals("A→B", PdfLayoutTextStripper.withoutStructure(visible).replaceAll("\\s+", ""));
        String hidden = new DocumentTextParser().parse(paddedArrowPdf(20), "application/pdf").get(0).text();
        assertFalse(hidden.contains("→"));
        assertEquals("AB", PdfLayoutTextStripper.withoutStructure(hidden).replaceAll("\\s+", ""));
    }

    private static byte[] paddedArrowPdf(int clipWidth) throws Exception {
        var image = new BufferedImage(300, 100, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 300, 100);
        graphics.setColor(Color.GRAY);
        graphics.fillPolygon(new int[]{10, 55, 55, 90, 55, 55, 10}, new int[]{35, 35, 10, 50, 90, 65, 65}, 7);
        graphics.dispose();
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.addRect(10, 600, 35, 100);
                stream.addRect(160, 600, 40, 100);
                stream.stroke();
                var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (int x : new int[]{20, 175}) {
                    stream.beginText();
                    stream.setFont(font, 10);
                    stream.newLineAtOffset(x, 650);
                    stream.showText(x == 20 ? "A" : "B");
                    stream.endText();
                }
                stream.saveGraphicsState();
                stream.addRect(50, 600, clipWidth, 100);
                stream.clip();
                stream.drawImage(LosslessFactory.createFromImage(document, image), 50, 600, 300, 100);
                stream.restoreGraphicsState();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    @Test
    void officialPictureArrowsStayBetweenTheirStepBoxesInsteadOfMixingAllRows() throws Exception {
        try (var input = getClass().getResourceAsStream("/extraction/lh-multichild-two-up.pdf")) {
            var parts = new DocumentTextParser().parse(input.readAllBytes(), "application/pdf");
            String text = PdfLayoutTextStripper.withoutStructure(parts.get(1).text()).replaceAll("[\\s\\p{Zs}]+", "");
            assertTrue(text.contains("입주신청(LH청약플러스)→자격확인(LH지역본부)→대상자선정(개별안내)→"
                + "전세주택물색(입주대상자)권리분석(LH지역본부)→전세및임대차계약체결(LH지역본부)→입주"));
            assertTrue(text.contains("2026.03.24.(화)10:00~2026.12.31.(목)18:00까지"));
        }
    }

    @Test
    void officialOpenSideTablesKeepHeadersAmountsAndStepDatesWhilePageCountersStayMetadata() throws Exception {
        try (var input = getClass().getResourceAsStream("/extraction/lh-sangok-69385666.pdf")) {
            var parts = new DocumentTextParser().parse(input.readAllBytes(), "application/pdf");
            String first = PdfLayoutTextStripper.withoutStructure(parts.get(0).text()).replaceAll("[\\s\\p{Zs}]+", "");
            assertTrue(first.contains("인터넷·현장청약접수’26.11.23.(월)~11.25.(수)→서류제출대상자발표’26.12.7.(월)→"
                + "서류제출대상자서류접수’26.12.8.(화)~12.11.(금)→"));
            String finalSteps = parts.get(0).text().replaceAll("⟦PDF:LINE[^⟧]*⟧", "");
            assertTrue(java.util.regex.Pattern.compile("row=2 col=1 rowspan=1 colspan=1⟧\\s*소명대상 개별통보\\s*⟦PDF:/CELL⟧").matcher(finalSteps).find());
            assertTrue(java.util.regex.Pattern.compile("row=2 col=3 rowspan=1 colspan=1⟧\\s*’27.4.15.\\(목\\)\\s*⟦PDF:/CELL⟧").matcher(finalSteps).find());
            String second = parts.get(1).text();
            assertTrue(second.contains("TABLE id=1 rows=4 cols=12"));
            assertTrue(second.contains("TABLE id=2 rows=6 cols=9"));
            assertTrue(second.contains("44.75"));
            assertTrue(second.contains("128,400,000"));
            assertTrue(second.contains("14,400,000"));
            String facility = parts.get(25).text().replaceAll("⟦PDF:LINE[^⟧]*⟧", "");
            // The PDF keeps an empty strip between the two physical header rules.
            assertTrue(facility.matches("(?s).*TABLE id=\\d+ rows=14 cols=3.*"));
            assertTrue(java.util.regex.Pattern.compile("row=4 col=1 rowspan=3 colspan=1⟧\\s*거실\\s*⟦PDF:/CELL⟧").matcher(facility).find());
            assertTrue(java.util.regex.Pattern.compile("row=5 col=2 rowspan=1 colspan=1⟧[^⟦]*600[^⟦]*900[^⟦]*⟦PDF:/CELL⟧").matcher(facility).find());
            assertTrue(java.util.regex.Pattern.compile("row=9 col=1 rowspan=4 colspan=1⟧\\s*욕실\\s*⟦PDF:/CELL⟧").matcher(facility).find());
            for (int page = 1; page <= 28; page++)
                assertTrue(parts.get(page - 1).text().contains("⟦PDF:PAGE_NUMBER value=" + page + "⟧"));
        }
    }

    @Test
    void onlyProvenEmptyAdvancingGlyphsBecomeSpacesWithoutChangingOtherText() throws Exception {
        TextPosition blank = blankGlyph();
        assertEquals("신청 서류 / 2026.12.31", restore("신청\0서류 / 2026.12.31", blank));
        assertEquals("1234", restore("1234", blank));
        assertEquals("\0\0", restore("\0\0", blank)); // An unexplained second NUL is not repaired.
        assertEquals("\0", PdfLayoutTextStripper.restoreBlankGlyphs("\0", List.of()));
    }

    @Test
    void visibleGlyphAndMissingGlyphAreNeverRemoved() throws Exception {
        TextPosition visible = blankGlyph();
        var path = new GeneralPath();
        path.moveTo(0, 0);
        path.lineTo(2, 3);
        when(((PDType0Font) visible.getFont()).getPath(1)).thenReturn(path);
        assertEquals("조건\0예외", restore("조건\0예외", visible));
        TextPosition missing = blankGlyph();
        when(((PDType0Font) missing.getFont()).codeToGID(1)).thenReturn(0);
        assertEquals("조건\0예외", restore("조건\0예외", missing));
        TextPosition absent = blankGlyph();
        when(((PDType0Font) absent.getFont()).hasGlyph(1)).thenReturn(false);
        assertFalse(PdfLayoutTextStripper.blankAdvancingGlyph(absent));
    }

    @Test
    void nonEmbeddedDamagedVerticalOrUnreadableFontIsUncertain() throws Exception {
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

    @Test
    void controlsMultiCodeAndMixedUncertainGlyphsRemainInvalid() throws Exception {
        TextPosition zeroWidth = blankGlyph();
        when(zeroWidth.getWidthDirAdj()).thenReturn(0f);
        TextPosition fontZeroWidth = blankGlyph();
        when(((PDType0Font) fontZeroWidth.getFont()).getWidth(1)).thenReturn(0f);
        TextPosition multipleCodes = blankGlyph();
        when(multipleCodes.getCharacterCodes()).thenReturn(new int[]{1, 2});
        TextPosition embeddedText = blankGlyph();
        when(embeddedText.getUnicode()).thenReturn("x\0");
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
