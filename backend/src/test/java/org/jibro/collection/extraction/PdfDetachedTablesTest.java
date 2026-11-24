package org.jibro.collection.extraction;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PdfDetachedTablesTest {
    private static final String OFFICIAL_FLOW = "주택열람입주희망주택직접방문▶동·호별접수및서류제출입주희망자→LH(방문접수)"
            + "▶입주자격조회LH▶자격검증및소명LH→소명대상자(개별통보)▶입주자선정결과발표LH홈페이지게시▶계약체결LH↔계약대상자";

    @Test
    void officialDetachedRankColumnsRetainTheirOwnCellsAndExactScheduleRows() throws Exception {
        try (var input = getClass().getResourceAsStream("/extraction/lh-bukgu-69296405.pdf")) {
            assertNotNull(input);
            var parts = new DocumentTextParser().parse(input.readAllBytes(), "application/pdf");
            assertEquals(17, parts.size());
            assertEquals("공고문 6쪽", parts.get(5).location());
            String page = parts.get(5).text();
            assertTrue(page.contains("TABLE id=1 rows=2 cols=1 rowgroup=1"));
            assertTrue(page.contains("TABLE id=2 rows=2 cols=5 rowgroup=1"));
            assertTrue(page.contains("TABLE id=3 rows=2 cols=1 rowgroup=2"));
            assertTrue(page.contains("TABLE id=4 rows=2 cols=5 rowgroup=2"));
            assertEquals("신청자격", cell(page, 1, 1, 1));
            assertEquals("1순위(우선)", cell(page, 1, 2, 1));
            assertEquals("‘26.11.23(월)~11.24(화)[방문접수](10:00~16:00)", cell(page, 2, 2, 3));
            assertEquals("‘26.12.22(화)[LH청약플러스](17:00이후)", cell(page, 2, 2, 4));
            assertEquals("1순위(일반)⋅2순위", cell(page, 3, 2, 1));
            assertEquals("‘26.12.14(월)~12.15(화)[방문접수](10:00~16:00)", cell(page, 4, 2, 3));
            assertEquals("‘27.1.27(수)[LH청약플러스](17:00이후)", cell(page, 4, 2, 4));
            assertEquals("별도안내", cell(page, 4, 2, 5));
            for (int i = 0; i < parts.size(); i++)
                if (i != 5) assertFalse(parts.get(i).text().contains("rowgroup="));
            assertTrue(PdfLayoutTextStripper.withoutStructure(parts.get(4).text()).replaceAll("\\s+", "").contains(OFFICIAL_FLOW));
            String form = parts.get(14).text();
            assertTrue(form.contains("TABLE id=1 rows=19 cols=12"));
            assertTrue(cell(form, 1, 13, 3, 10).contains("☐장애인(월평균소득70%이하자)"));
            assertEquals("(임대조건)☐무보증금월세제도–생계·주거급여동시수급자만해당", cell(form, 1, 14, 3, 10));
            assertEquals("☐월평균소득100%이하자", cell(form, 1, 15, 3, 10));
            String consent = parts.get(15).text();
            assertTrue(consent.contains("TABLE id=1 rows=6 cols=9 rowbands=2:1.438 colbands=5:1.440"));
            assertEquals("기본", cell(consent, 2, 2, 1));
            assertEquals("소득", cell(consent, 2, 3, 1));
            assertEquals("자산", cell(consent, 2, 4, 1));
            assertEquals("기타", cell(consent, 2, 5, 1));
            assertTrue(consent.indexOf("동의하십니까?") < consent.indexOf("TABLE id=3 rows=1 cols=2"));
            assertEquals("동의", cell(consent, 3, 1, 1));
            assertEquals("", cell(consent, 3, 1, 2));
            assertEquals("미동의", cell(consent, 4, 1, 1));
            assertEquals("", cell(consent, 4, 1, 2));
        }
    }

    @Test
    void directionalGlyphsBesideOtherTextCannotReorderAnApparentFlow() throws Exception {
        try (var input = getClass().getResourceAsStream("/extraction/lh-bukgu-69296405.pdf")) {
            assertNotNull(input);
            try (var document = Loader.loadPDF(input.readAllBytes()); var output = new ByteArrayOutputStream()) {
                // The original arrow between the first two boxes is no longer a standalone
                // direction when a label occupies the same gap. Keep all text unclaimed.
                try (var stream = new PDPageContentStream(document, document.getPage(4),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 4);
                    stream.newLineAtOffset(110, 285);
                    stream.showText("stop");
                    stream.endText();
                }
                document.save(output);
                String text = PdfLayoutTextStripper.withoutStructure(new DocumentTextParser()
                        .parse(output.toByteArray(), "application/pdf").get(4).text()).replaceAll("\\s+", "");
                assertFalse(text.contains(OFFICIAL_FLOW));
                assertTrue(text.contains("stop"));
                assertTrue(text.contains("주택열람"));
                assertTrue(text.contains("계약체결"));
            }
        }
    }

    @Test
    void oneLineLeadPrecedesSeparateClosedBoxesWhileMultilineNeighboursRemainUnclaimed() throws Exception {
        String inline = inlineBoxes(false);
        assertTrue(inline.indexOf("Do you agree?") < inline.indexOf("TABLE id=1 rows=1 cols=2"));
        assertEquals("Agree", cell(inline, 1, 1, 1));
        assertEquals("", cell(inline, 1, 1, 2));
        assertEquals("No", cell(inline, 2, 1, 1));
        assertFalse(inline.contains("rowgroup="));
        String multiline = inlineBoxes(true);
        assertFalse(multiline.contains("⟦PDF:TABLE"));
        assertTrue(multiline.contains("Another line"));
        assertTrue(multiline.contains("Agree"));
    }

    @Test
    void filledRuleGridKeepsEveryOfficialInventoryCellAndIndependentCallout() throws Exception {
        try (var input = getClass().getResourceAsStream("/extraction/lh-bukgu-69296407.pdf")) {
            assertNotNull(input);
            var parts = new DocumentTextParser().parse(input.readAllBytes(), "application/pdf");
            assertEquals(2, parts.size());
            assertTrue(parts.get(0).text().contains("TABLE id=1 rows=21 cols=20"));
            assertTrue(parts.get(1).text().contains("TABLE id=1 rows=12 cols=20"));
            assertEquals("1", cell(parts.get(0).text(), 1, 3, 1));
            assertEquals("201", cell(parts.get(0).text(), 1, 3, 8));
            assertEquals("4,554,000", cell(parts.get(0).text(), 1, 3, 17));
            assertEquals("104,740", cell(parts.get(0).text(), 1, 3, 18));
            assertEquals("29", cell(parts.get(1).text(), 1, 12, 1));
            assertEquals("304", cell(parts.get(1).text(), 1, 12, 8));
            assertEquals("80,490", cell(parts.get(1).text(), 1, 12, 20));
            for (var part : parts) {
                assertTrue(part.text().contains("거실포함 방갯수"));
                assertTrue(part.text().contains("######### ######## 3,118,230"));
            }
        }
        for (boolean grid : new boolean[]{false, true}) {
            try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
                var page = new PDPage();
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.setNonStrokingColor(Color.LIGHT_GRAY);
                    stream.addRect(40, 600, 100, 100);
                    stream.fill();
                    stream.setNonStrokingColor(Color.BLACK);
                    for (float y : new float[]{600, 650, 700}) {
                        stream.addRect(40, y, 100, .6f);
                        stream.fill();
                    }
                    if (grid) for (float x : new float[]{40, 90, 140}) {
                        stream.addRect(x, 600, .6f, 100);
                        stream.fill();
                    }
                    stream.addRect(180, 630, 20, .6f);
                    stream.fill();
                    text(stream, 50, 675, "A");
                    text(stream, 100, 675, "B");
                    text(stream, 50, 625, "C");
                    text(stream, 100, 625, "D");
                }
                document.save(output);
                String text = new DocumentTextParser().parse(output.toByteArray(), "application/pdf").get(0).text();
                if (grid) {
                    assertEquals("A", cell(text, 1, 1, 1));
                    assertEquals("D", cell(text, 1, 2, 2));
                } else {
                    assertFalse(text.contains("⟦PDF:TABLE"));
                    assertTrue(text.contains("A"));
                    assertTrue(text.contains("D"));
                }
            }
        }
    }

    @Test
    void graphicsInsideAnObservedBorderBandCannotBecomeEmptyBorderEvidence() throws Exception {
        for (String mark : new String[]{"fill", "stroke", "image", "near-rule"}) {
            try (var input = getClass().getResourceAsStream("/extraction/lh-bukgu-69296405.pdf")) {
                assertNotNull(input);
                try (var source = Loader.loadPDF(input.readAllBytes()); var document = new PDDocument();
                     var output = new ByteArrayOutputStream()) {
                    var page = document.importPage(source.getPage(15));
                    float y = page.getCropBox().getUpperRightY() - 172;
                    try (var stream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        stream.setNonStrokingColor(Color.BLACK);
                        if (mark.equals("near-rule")) {
                            stream.addRect(308.1f, page.getCropBox().getUpperRightY() - 175.4f, .3f, .1f);
                            stream.fill();
                        } else if (mark.equals("image")) {
                            var image = new BufferedImage(3, 20, BufferedImage.TYPE_BYTE_GRAY);
                            stream.drawImage(LosslessFactory.createFromImage(document, image), 308.1f, y, .3f, 2);
                        } else if (mark.equals("fill")) {
                            stream.moveTo(308.1f, y);
                            stream.lineTo(308.4f, y + 1);
                            stream.lineTo(308.1f, y + 2);
                            stream.closePath();
                            stream.fill();
                        } else {
                            stream.setLineWidth(.08f);
                            stream.moveTo(308.1f, y);
                            stream.curveTo(308.4f, y + .7f, 308.1f, y + 1.3f, 308.35f, y + 2);
                            stream.stroke();
                        }
                    }
                    document.save(output);
                    String text = new DocumentTextParser().parse(output.toByteArray(), "application/pdf").get(0).text();
                    assertTrue(text.contains("rows=6 cols=9 rowbands=2:1.438"), mark);
                    assertFalse(text.contains("colbands=5:"), mark);
                }
            }
        }
    }

    private static String inlineBoxes(boolean multiline) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.setLineWidth(.3f);
                for (float x : new float[]{110, 230}) {
                    stream.addRect(x, 640, 100, 20);
                    stream.moveTo(x + 50, 640);
                    stream.lineTo(x + 50, 660);
                }
                stream.stroke();
                text(stream, 40, 651, "Do you agree?");
                if (multiline) text(stream, 40, 644, "Another line");
                text(stream, 116, 648, "Agree");
                text(stream, 236, 648, "No");
            }
            document.save(output);
            return new DocumentTextParser().parse(output.toByteArray(), "application/pdf").get(0).text();
        }
    }

    @Test
    void geometricRelationPreservesSeparateTablesRatherThanInventingAJoinedGrid() throws Exception {
        for (var layout : new Layout[]{Layout.ALIGNED, Layout.CLIPPED_VECTOR}) {
            String text = parse(layout);
            assertTrue(text.contains("TABLE id=1 rows=2 cols=1 rowgroup=1"), layout.toString());
            assertTrue(text.contains("TABLE id=2 rows=2 cols=2 rowgroup=1"), layout.toString());
            assertEquals("Priority", cell(text, 1, 2, 1));
            assertEquals("11/2310:00", cell(text, 2, 2, 1));
            assertFalse(text.contains("cols=3"));
        }
    }

    @Test
    void distanceMisalignedRowsInterveningContentAndAmbiguousFramesCannotClaimRelatedRows() throws Exception {
        for (var layout : Layout.values()) {
            if (layout == Layout.ALIGNED || layout == Layout.CLIPPED_VECTOR) continue;
            String text = parse(layout);
            assertFalse(text.contains("rowgroup="), layout.toString());
            String plain = PdfLayoutTextStripper.withoutStructure(text).replaceAll("\\s+", "");
            assertTrue(plain.contains("Priority"), layout.toString());
            assertTrue(plain.contains("11/2310:00"), layout.toString());
            assertTrue(plain.contains("12/2217:00"), layout.toString());
            if (layout == Layout.TEXT_IN_GAP) assertTrue(plain.contains("!"));
        }
    }

    private static String cell(String page, int table, int row, int column) {
        return cell(page, table, row, column, 1);
    }

    private static String cell(String page, int table, int row, int column, int columnSpan) {
        String plain = page.replaceAll("⟦PDF:LINE[^⟧]*⟧", "");
        var match = Pattern.compile("⟦PDF:CELL table=" + table + " row=" + row + " col=" + column
                + " rowspan=1 colspan=" + columnSpan + "⟧(.*?)⟦PDF:/CELL⟧", Pattern.DOTALL).matcher(plain);
        assertTrue(match.find(), "Missing physical cell " + table + "/" + row + "/" + column);
        return match.group(1).replaceAll("\\s+", "");
    }

    private enum Layout {
        ALIGNED, FAR, SHIFTED_ROW, TEXT_IN_GAP, IMAGE_IN_GAP,
        STROKED_VECTOR_IN_GAP, FILLED_VECTOR_IN_GAP, CLIPPED_VECTOR, OVERLAPPING_LABELS, OPEN_SIDE
    }

    private static String parse(Layout layout) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.setLineWidth(.3f);
                float right = layout == Layout.FAR ? 140 : 104;
                grid(stream, 40, 100, 0, 650, layout != Layout.OPEN_SIDE);
                grid(stream, right, right + 200, right + 100, layout == Layout.SHIFTED_ROW ? 645 : 650, true);
                // Both frames share one compound painting command. Its bounding box covers
                // the gutter, but the actual painted line regions do not.
                stream.stroke();
                text(stream, 50, 675, "Scope");
                text(stream, 50, 625, "Priority");
                text(stream, right + 10, 675, "Apply");
                text(stream, right + 110, 675, "Result");
                text(stream, right + 10, 625, "11/23 10:00");
                text(stream, right + 110, 625, "12/22 17:00");
                if (layout == Layout.TEXT_IN_GAP) text(stream, 100.7f, 625, "!");
                if (layout == Layout.OVERLAPPING_LABELS) {
                    grid(stream, 80, 102, 0, 650, true);
                    stream.stroke();
                }
                if (layout == Layout.STROKED_VECTOR_IN_GAP || layout == Layout.FILLED_VECTOR_IN_GAP
                        || layout == Layout.CLIPPED_VECTOR) {
                    stream.saveGraphicsState();
                    if (layout == Layout.CLIPPED_VECTOR) {
                        stream.addRect(40, 600, 60, 100);
                        stream.clip();
                    }
                    stream.moveTo(101, 620);
                    stream.lineTo(103, 624);
                    stream.lineTo(101, 628);
                    if (layout == Layout.FILLED_VECTOR_IN_GAP) stream.fill();
                    else stream.stroke();
                    stream.restoreGraphicsState();
                }
                if (layout == Layout.IMAGE_IN_GAP) {
                    var image = new BufferedImage(5, 10, BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics();
                    graphics.setColor(Color.BLACK);
                    graphics.fillRect(0, 0, 5, 10);
                    graphics.dispose();
                    stream.drawImage(LosslessFactory.createFromImage(document, image), 101, 620, 2, 8);
                }
            }
            document.save(output);
            return new DocumentTextParser().parse(output.toByteArray(), "application/pdf").get(0).text();
        }
    }

    private static void grid(PDPageContentStream stream, float left, float right, float divider, float row, boolean closeRight) throws Exception {
        for (float y : new float[]{600, row, 700}) {
            stream.moveTo(left, y);
            stream.lineTo(right, y);
        }
        stream.moveTo(left, 600);
        stream.lineTo(left, 700);
        if (closeRight) {
            stream.moveTo(right, 600);
            stream.lineTo(right, 700);
        }
        if (divider > 0) {
            stream.moveTo(divider, 600);
            stream.lineTo(divider, 700);
        }
    }

    private static void text(PDPageContentStream stream, float x, float y, String value) throws Exception {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }
}
