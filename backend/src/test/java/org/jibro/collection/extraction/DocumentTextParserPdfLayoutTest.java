package org.jibro.collection.extraction;

import java.io.ByteArrayOutputStream;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTextParserPdfLayoutTest {
    private final DocumentTextParser parser = new DocumentTextParser();

    @Test
    void actualTwoUpNoticeKeepsEachPrintedPageTogetherAndPreservesMergedAndNestedCells() throws Exception {
        byte[] pdf;
        try (var source = getClass().getResourceAsStream("/extraction/lh-multichild-two-up.pdf")) {
            assertNotNull(source);
            pdf = source.readAllBytes();
        }
        var parts = parser.parse(pdf, "application/pdf");
        assertEquals(7, parts.size());
        assertEquals("공고문 1쪽", parts.get(0).location());
        assertEquals("공고문 7쪽", parts.get(6).location());
        String text = withoutLinePositions(parts.get(0).text());
        String plain = text.replaceAll("\\s+", " ");
        assertTrue(plain.contains("19세 미만 미성년자로 한정)을 양육하는 가구 중 아래에 해당하는 사람"));
        assertTrue(text.indexOf("- 1 -") < text.indexOf("소득 · 자산 기준 및 자격 검증 대상"));
        assertTrue(Pattern.compile("row=3 col=1 rowspan=4 colspan=1⟧\\s*2순위\\s*⟦PDF:/CELL⟧").matcher(text).find());
        assertTrue(Pattern.compile("row=5 col=3 rowspan=1 colspan=1⟧\\s*「한부모가족지원법 시행규칙」 제3조에 따라 성평등가족부장관이 정하여\\s*고시하는 한부모가족\\s*⟦PDF:/CELL⟧").matcher(text).find());
        // The inner income table remains in the outer income row, rather than becoming unrelated numbers.
        assertTrue(Pattern.compile("row=2 col=1 rowspan=1 colspan=1⟧\\s*소득\\s*⟦PDF:/CELL⟧\\s*⟦PDF:CELL[^⟦]+col=2[^⟦]+⟧[^⟦]*⟦PDF:TABLE id=\\d+ rows=2 cols=7⟧").matcher(text).find());
        assertTrue(Pattern.compile("row=2 col=2 rowspan=1 colspan=1⟧\\s*5,717,900\\s*⟦PDF:/CELL⟧").matcher(text).find());
        assertTrue(Pattern.compile("row=2 col=7 rowspan=1 colspan=1⟧\\s*7,745,373\\s*⟦PDF:/CELL⟧").matcher(text).find());
        assertTrue(plain.contains("직계존속의 사망, 실종선고, 행방불명 등으로 형제자매를 부양해야 하는 미성년 세대주"));
    }

    @Test
    void ruledRowsKeepWrappedValuesInTheirOwnCellsInsteadOfBecomingReadingColumns() throws Exception {
        String positioned = parser.parse(table(false, false), "application/pdf").get(0).text();
        String text = withoutLinePositions(positioned);
        assertTrue(text.contains("⟦PDF:TABLE id=1 rows=3 cols=2⟧"));
        assertTrue(Pattern.compile("row=2 col=1 rowspan=1 colspan=1⟧\\s*Youth\\s*⟦PDF:/CELL⟧").matcher(text).find());
        assertTrue(Pattern.compile("row=2 col=2 rowspan=1 colspan=1⟧\\s*Age 19 to 39\\s*Unmarried\\s*⟦PDF:/CELL⟧").matcher(text).find());
        assertTrue(Pattern.compile("row=3 col=2 rowspan=1 colspan=1⟧\\s*Works in the named complex\\s*⟦PDF:/CELL⟧").matcher(text).find());
        assertTrue(positioned.contains("⟦PDF:LINE y0=110.00 y1=110.00⟧\nYouth"));
        assertTrue(positioned.contains("⟦PDF:LINE y0=110.00 y1=110.00⟧\nAge 19 to 39"));
        assertTrue(positioned.contains("⟦PDF:LINE y0=125.00 y1=125.00⟧\nUnmarried"));
    }

    @Test
    void incompleteBordersAndRotatedPagesKeepTextWithoutClaimingCellRelationships() throws Exception {
        for (byte[] pdf : new byte[][]{table(true, false), table(false, true)}) {
            String text = parser.parse(pdf, "application/pdf").get(0).text();
            assertFalse(text.contains("⟦PDF:TABLE"));
            assertTrue(text.contains("Youth"));
            assertTrue(text.contains("Age 19 to 39"));
            assertTrue(text.contains("Works in the named complex"));
        }
    }

    @Test
    void shortRegularDashesNeedACompletePatternBetweenActualTableWalls() throws Exception {
        for (boolean vertical : new boolean[]{false, true}) {
            String intact = parser.parse(dashedTable(false, vertical), "application/pdf").get(0).text();
            assertTrue(intact.contains("rows=2 cols=2"));
            assertTrue(withoutLinePositions(intact).matches("(?s).*row=2 col=2 rowspan=1 colspan=1⟧\\s*D\\s*⟦PDF:/CELL⟧.*"));
            String broken = parser.parse(dashedTable(true, vertical), "application/pdf").get(0).text();
            assertFalse(broken.contains("⟦PDF:TABLE"));
            assertTrue(broken.contains("D"));
        }
    }

    private static byte[] dashedTable(boolean broken, boolean vertical) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.setLineWidth(.36f);
                stream.addRect(40, 600, 200, 100);
                stream.moveTo(vertical ? 40 : 140, vertical ? 650 : 600);
                stream.lineTo(vertical ? 240 : 140, vertical ? 650 : 700);
                stream.stroke();
                float from = vertical ? 600 : 40, to = vertical ? 700 : 240;
                for (int i = 0; from + i * 1.2 < to; i++) {
                    if (broken && i == 50) continue;
                    float start = (float) (from + i * 1.2), end = Math.min(to, start + .48f);
                    stream.moveTo(vertical ? 140 : start, vertical ? start : 650);
                    stream.lineTo(vertical ? 140 : end, vertical ? end : 650);
                    stream.stroke();
                }
                text(stream, 60, 675, "A");
                text(stream, 160, 675, "B");
                text(stream, 60, 625, "C");
                text(stream, 160, 625, "D");
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] table(boolean incomplete, boolean rotated) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(new PDRectangle(600, 800));
            if (rotated) page.setRotation(90);
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.setLineWidth(0.3f);
                for (float y : new float[]{740, 710, 660, 620}) {
                    content.moveTo(40, y);
                    content.lineTo(550, y);
                }
                content.moveTo(40, 620);
                content.lineTo(40, 740);
                content.moveTo(220, 620);
                content.lineTo(220, incomplete ? 730 : 740);
                if (!incomplete) {
                    content.moveTo(550, 620);
                    content.lineTo(550, 740);
                }
                content.stroke();
                text(content, 50, 720, "Type");
                text(content, 230, 720, "Requirement");
                text(content, 50, 690, "Youth");
                text(content, 230, 690, "Age 19 to 39");
                text(content, 230, 675, "Unmarried");
                text(content, 50, 640, "Industrial worker");
                text(content, 230, 640, "Works in the named complex");
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static String withoutLinePositions(String text) {
        return text.replaceAll("⟦PDF:LINE [^⟧]+⟧\\R", "");
    }

    private static void text(PDPageContentStream content, float x, float y, String text) throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }
}
