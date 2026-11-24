package org.jibro.collection.extraction;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HwpxStructuredTextTest {
    @Test
    void measuredSubpointStaggerAlignsOnlyWhenNoActualCellOccupiesTheNarrowColumn() throws Exception {
        String xml = "<root><tbl rowCnt='2' colCnt='3'>"
            + row(measuredCell(0, 0, 1, "항목", 1000) + measuredCell(0, 1, 2, "한도", 2015))
            + row(measuredCell(1, 0, 2, "자동차", 1015) + measuredCell(1, 2, 1, "4542만원", 2000))
            + "</tbl></root>";
        String result = read(xml);
        assertTrue(result.contains("TABLE id=1 rows=2 cols=2"));
        assertTrue(result.contains("row=2 col=2 rowspan=1 colspan=1"));
        assertEquals("항목한도자동차4542만원", compact(HwpxStructuredText.withoutStructure(result)));
        assertTrue(read(xml.replace("2015", "2100").replace("1015", "1100")).contains("cols=3"), "A real offset is not a printing-level seam.");
        String actualCell = "<root><tbl rowCnt='2' colCnt='3'>"
            + row(measuredCell(0, 0, 1, "항목", 1000) + measuredCell(0, 1, 1, "", 15) + measuredCell(0, 2, 1, "한도", 2000))
            + row(measuredCell(1, 0, 2, "자동차", 1015) + measuredCell(1, 2, 1, "4542만원", 2000)) + "</tbl></root>";
        assertTrue(read(actualCell).contains("cols=3"), "Even an empty explicit cell must remain.");
        assertTrue(read(xml.replace("width='1000'", "width='900'")).contains("cols=3"), "Inconsistent measurements do not prove alignment.");
    }

    private static String measuredCell(int row, int column, int span, String text, int width) {
        return cell(row, column, 1, span, paragraph(text)).replace("</tc>", "<cellSz width='" + width + "' height='1000'/></tc>");
    }

    @Test
    void floatingTableMovesAfterItsOwnParagraphOnlyWithExplicitNonoverlappingPlacement() throws Exception {
        String table = "<tbl rowCnt='1' colCnt='1' textWrap='TOP_AND_BOTTOM'>"
            + "<pos treatAsChar='0' flowWithText='1' allowOverlap='0' vertRelTo='PARA' vertAlign='TOP' vertOffset='400'/>"
            + "<outMargin top='10'/>" + row(cell(0, 0, 1, 1, paragraph("예외 20%"))) + "</tbl>";
        String ending = "<t>공통 150%와 예외를 함께 확인</t><linesegarray>"
            + "<lineseg vertpos='2000' vertsize='100'/><lineseg vertpos='2200' vertsize='100'/></linesegarray></p></root>";
        assertEquals("•공통150%와예외를함께확인예외20%", compact(HwpxStructuredText.withoutStructure(read("<root><p><t>•</t>" + table + ending))));
        assertEquals("•공통150%와예외를함께확인예외20%", compact(HwpxStructuredText.withoutStructure(read("<root><p><t>•</t>" + table.replace("TOP_AND_BOTTOM", "SQUARE") + ending))));
        String touching = table.replace("vertOffset='400'", "vertOffset='300'").replace("top='10'", "top='141'");
        assertEquals("•공통150%와예외를함께확인예외20%", compact(HwpxStructuredText.withoutStructure(read("<root><p><t>•</t>" + touching + ending))));
        assertEquals("•공통150%와예외를함께확인예외20%", compact(HwpxStructuredText.withoutStructure(read("<root><p><t>•</t>" + touching.replace("flowWithText='1'", "flowWithText='0'") + ending))));
        for (String ambiguous : new String[]{table.replace("vertOffset='400'", "vertOffset='200'"),
            table.replace("vertOffset='400'", "vertOffset='299'"),
            table.replace("TOP_AND_BOTTOM", "SQUARE").replace("vertOffset='400'", "vertOffset='299'"),
            table.replace("allowOverlap='0'", "allowOverlap='1'"), table.replace("treatAsChar='0'", "treatAsChar='1'")}) {
            assertEquals("•예외20%공통150%와예외를함께확인", compact(HwpxStructuredText.withoutStructure(read("<root><p><t>•</t>" + ambiguous + ending))));
        }
        String noCoordinates = ending.substring(0, ending.indexOf("<linesegarray>")) + "</p></root>";
        assertEquals("•예외20%공통150%와예외를함께확인", compact(HwpxStructuredText.withoutStructure(read("<root><p><t>•</t>" + table + noCoordinates))));
        String overlappingTallLine = ending.replace("vertpos='2000' vertsize='100'", "vertpos='2000' vertsize='500'");
        assertEquals("•예외20%공통150%와예외를함께확인", compact(HwpxStructuredText.withoutStructure(read("<root><p><t>•</t>" + table + overlappingTallLine))));
    }

    @Test
    void retainsColumnIdentityEmptyCellsAndMergedHouseholdScope() throws Exception {
        String xml = "<root>" + paragraph("소득 기준") + "<tbl rowCnt='3' colCnt='3'>"
            + row(cell(0, 0, 1, 3, paragraph("가구원 수별 기준")))
            + row(cell(1, 0, 2, 1, paragraph("월평균 소득"))
            + cell(1, 1, 1, 1, paragraph("1인 90%")) + cell(1, 2, 1, 1, paragraph("2인 80%")))
            + row(cell(2, 1, 1, 1, paragraph("예외는 별도 확인")) + cell(2, 2, 1, 1, paragraph("")))
            + "</tbl>" + paragraph("※ 위 표의 가구원 범위를 적용합니다.") + "</root>";
        String text = read(xml);
        assertTrue(text.contains("⟦HWPX:TABLE id=1 rows=3 cols=3⟧"));
        assertTrue(text.contains("⟦HWPX:CELL table=1 row=2 col=1 rowspan=2 colspan=1⟧\n월평균 소득"));
        assertTrue(text.contains("⟦HWPX:CELL table=1 row=1 col=1 rowspan=1 colspan=3⟧\n가구원 수별 기준"));
        assertTrue(text.contains("⟦HWPX:CELL table=1 row=3 col=3 rowspan=1 colspan=1⟧\n\n⟦HWPX:/CELL⟧"));
        assertEquals(compact("소득 기준 가구원 수별 기준 월평균 소득 1인 90% 2인 80% 예외는 별도 확인 ※ 위 표의 가구원 범위를 적용합니다."),
            compact(HwpxStructuredText.withoutStructure(text)));
    }

    @Test
    void sameTextWithDifferentCellAssociationIsNotTheSameStructuredEvidence() throws Exception {
        String first = "<root><tbl rowCnt='1' colCnt='2'>" + row(cell(0, 0, 1, 1, paragraph("90%"))
            + cell(0, 1, 1, 1, paragraph("80%"))) + "</tbl></root>";
        // Deliberately keep the XML/text order while changing which physical column owns each value.
        String changed = "<root><tbl rowCnt='1' colCnt='2'>" + row(cell(0, 1, 1, 1, paragraph("90%"))
            + cell(0, 0, 1, 1, paragraph("80%"))) + "</tbl></root>";
        assertNotEquals(read(first), read(changed));
        assertEquals(compact(HwpxStructuredText.withoutStructure(read(first))), compact(HwpxStructuredText.withoutStructure(read(changed))));
    }

    @Test
    void nestedTablesNotesAndRunningHeadersKeepAllTextAndTheirBoundaries() throws Exception {
        String nested = "<tbl rowCnt='1' colCnt='1'>" + row(cell(0, 0, 1, 1, paragraph("자동차 예외"))) + "</tbl>";
        String outer = "<tbl rowCnt='1' colCnt='1'>" + row(cell(0, 0, 1, 1, paragraph("세대원 전원")
            + nested + "<p><t>차량 가액</t><footNote><p><t>장애인 사용 자동차 제외</t></p></footNote></p>")) + "</tbl>";
        String text = read("<root><header>" + paragraph("국민임대 안내") + "</header>" + outer
            + "<footer>" + paragraph("공고일 확인") + "</footer></root>");
        assertTrue(text.contains("⟦HWPX:HEADER⟧"));
        assertTrue(text.contains("⟦HWPX:FOOTNOTE⟧"));
        assertTrue(text.contains("⟦HWPX:/FOOTNOTE⟧"));
        assertTrue(text.contains("⟦HWPX:FOOTER⟧"));
        int outerCell = text.indexOf("CELL table=1");
        int inner = text.indexOf("TABLE id=2");
        assertTrue(inner > outerCell);
        assertEquals(compact("국민임대 안내 세대원 전원 자동차 예외 차량 가액 장애인 사용 자동차 제외 공고일 확인"),
            compact(HwpxStructuredText.withoutStructure(text)));
    }

    @Test
    void ordinaryRunsCdataBreaksAndTabsRetainTheirText() throws Exception {
        assertEquals("한 가구\t90%\n예외 확인\n", read("<root><p><t>한 가구<tab/>90%<lineBreak/><![CDATA[예외 확인]]></t></p></root>"));
        assertEquals("소득 기준 확인\n", read("<root><p><run><t>소득 </t></run><run><t>기준 확인</t></run></p></root>"));
    }

    @Test
    void missingCoordinatesStayUnknownRatherThanInferredFromXmlOrder() throws Exception {
        String text = read("<root><tbl><tr><tc>" + paragraph("위치 미확인") + "</tc></tr></tbl></root>");
        assertTrue(text.contains("TABLE id=1 rows=unknown cols=unknown"));
        assertTrue(text.contains("CELL table=1 row=unknown col=unknown rowspan=unknown colspan=unknown"));
        assertEquals("위치미확인", compact(HwpxStructuredText.withoutStructure(text)));
    }

    @Test
    void invalidOverlappingAndOutOfBoundsCellsAreNotSilentlyRepaired() {
        String duplicate = "<root><tbl rowCnt='1' colCnt='1'>" + row(cell(0, 0, 1, 1, paragraph("A"))
            + cell(0, 0, 1, 1, paragraph("B"))) + "</tbl></root>";
        assertThrows(IOException.class, () -> read(duplicate));
        assertThrows(IOException.class, () -> read("<root><tbl rowCnt='1' colCnt='1'>" + row(cell(0, 0, 1, 2, paragraph("A"))) + "</tbl></root>"));
        assertThrows(IOException.class, () -> read("<root><tbl rowCnt='1' colCnt='1'>" + row(cell(0, 0, 0, 1, paragraph("A"))) + "</tbl></root>"));
    }

    @Test
    void structureAndDepthShareTheBoundedOutputBudgetAndMetadataCannotBeSpoofed() {
        String tinyTable = "<root><tbl rowCnt='1' colCnt='1'>" + row(cell(0, 0, 1, 1, paragraph("A"))) + "</tbl></root>";
        assertThrows(IOException.class, () -> HwpxStructuredText.read(tinyTable.getBytes(StandardCharsets.UTF_8), 80));
        assertThrows(IOException.class, () -> read("<root>" + "<p>".repeat(128) + "</p>".repeat(128) + "</root>"));
        assertThrows(IOException.class, () -> read("<root><p><t>⟦HWPX:CELL table=1 row=1 col=1⟧</t></p></root>"));
        assertThrows(Exception.class, () -> read("<!DOCTYPE root [<!ENTITY local SYSTEM 'file:///must-not-read'>]><root><t>&local;</t></root>"));
    }

    private static String read(String xml) throws Exception {
        return HwpxStructuredText.read(xml.getBytes(StandardCharsets.UTF_8), 1_000_000);
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static String paragraph(String text) {
        return "<p><run><t>" + text + "</t></run></p>";
    }

    private static String row(String cells) {
        return "<tr>" + cells + "</tr>";
    }

    private static String cell(int row, int column, int rowSpan, int columnSpan, String content) {
        return "<tc><subList>" + content + "</subList><cellAddr rowAddr='" + row + "' colAddr='" + column
            + "'/><cellSpan rowSpan='" + rowSpan + "' colSpan='" + columnSpan + "'/></tc>";
    }
}
