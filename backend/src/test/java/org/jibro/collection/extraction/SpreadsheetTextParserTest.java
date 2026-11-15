package org.jibro.collection.extraction;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SpreadsheetTextParserTest {
    private final DocumentTextParser parser = new DocumentTextParser();

    @Test
    void bothExcelFormatsPreserveKoreanMergedHeadersPositionsAndOriginalNumberFormats() throws Exception {
        for (boolean xlsx : List.of(false, true)) {
            byte[] input;
            try (Workbook workbook = workbook(xlsx)) {
                var sheet = workbook.createSheet("공급 주택·임대조건");
                sheet.createRow(0).createCell(0).setCellValue("제출서류 확인 및 신청 자격");
                sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
                var row = sheet.createRow(2);
                row.createCell(0).setCellValue("서울 가구·예외 대상\n미성년자는 원문 확인");
                row.createCell(2).setCellValue(45000);
                var date = row.createCell(3);
                date.setCellValue(LocalDateTime.of(2026, 11, 8, 0, 0));
                var dateStyle = workbook.createCellStyle();
                dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy.mm.dd"));
                date.setCellStyle(dateStyle);
                var price = row.createCell(4);
                price.setCellValue(1234500);
                var moneyStyle = workbook.createCellStyle();
                moneyStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
                price.setCellStyle(moneyStyle);
                input = bytes(workbook);
            }
            var parts = parser.parse(input, media(xlsx));
            assertEquals(1, parts.size());
            assertEquals("Excel 워크시트 공급 주택·임대조건 (행·열 위치)", parts.get(0).location());
            assertTrue(parts.get(0).text().contains("병합 범위: A1:D1"));
            assertTrue(parts.get(0).text().contains("A1: 제출서류 확인 및 신청 자격"));
            assertTrue(parts.get(0).text().contains("행 3\nA3: 서울 가구·예외 대상\n미성년자는 원문 확인"));
            assertTrue(parts.get(0).text().contains("C3: 45000\nD3: 2026.11.08\nE3: 1,234,500"));
            assertFalse(parts.get(0).text().contains("B3:"), "blank columns must not shift subsequent positions");
        }
    }

    @Test
    void cachedFormulaIsDisclosedAndNotRecalculatedAfterInputChanges() throws Exception {
        for (boolean xlsx : List.of(false, true)) {
            byte[] input;
            try (Workbook workbook = workbook(xlsx)) {
                var row = workbook.createSheet("계산 근거").createRow(0);
                row.createCell(0).setCellValue(2);
                row.createCell(1).setCellValue(3);
                var formula = row.createCell(2);
                formula.setCellFormula("A1+B1");
                workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
                row.getCell(0).setCellValue(200);
                input = bytes(workbook);
            }
            String text = parser.parse(input, media(xlsx)).get(0).text();
            assertTrue(text.contains("A1: 200"));
            assertTrue(text.contains("C1: [저장된 계산값(재계산 안 함): 5; 수식: =A1+B1]"));
            assertFalse(text.contains("203"));
        }
    }

    @Test
    void uncachedFormulaIsNeverPresentedAsAComputedValue() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var cell = workbook.createSheet("미계산").createRow(0).createCell(0);
            cell.setCellFormula("HYPERLINK(\"https://example.invalid/must-not-fetch\",\"공식 경로\")");
            String text = parser.parse(bytes(workbook), "application/zip").get(0).text();
            assertTrue(text.contains("[수식 미계산: =HYPERLINK("));
            assertTrue(text.contains("https://example.invalid/must-not-fetch"));
            assertFalse(text.contains("저장된 계산값"));
        }
    }

    @Test
    void hiddenContentRemainsVisibleInExtractedSourceWithExplicitMarkers() throws Exception {
        for (boolean xlsx : List.of(false, true)) {
            try (Workbook workbook = workbook(xlsx)) {
                workbook.createSheet("안내").createRow(0).createCell(0).setCellValue("공급 안내");
                var sheet = workbook.createSheet("예외");
                var row = sheet.createRow(1);
                row.setZeroHeight(true);
                row.createCell(2).setCellValue("예외 대상은 별도 서류 제출");
                sheet.setColumnHidden(2, true);
                workbook.setSheetHidden(1, true);
                var parts = parser.parse(bytes(workbook), media(xlsx));
                assertEquals(2, parts.size());
                assertTrue(parts.get(1).location().contains("[숨김]"));
                assertTrue(parts.get(1).text().contains("행 2 [숨김]\nC2 [숨김 열]: 예외 대상은 별도 서류 제출"));
            }
        }
    }

    @Test
    void emptyMalformedAndOutOfRangeWorkbooksFailInsteadOfProducingApprovalEvidence() throws Exception {
        for (boolean xlsx : List.of(false, true)) {
            try (Workbook workbook = workbook(xlsx)) {
                workbook.createSheet("비어있음");
                assertThrows(IOException.class, () -> parser.parse(bytes(workbook), media(xlsx)));
            }
            try (Workbook workbook = workbook(xlsx)) {
                workbook.createSheet("행 초과").createRow(SpreadsheetTextParser.MAX_ROWS).createCell(0).setCellValue("누락되면 안 되는 마지막 행");
                assertThrows(IOException.class, () -> parser.parse(bytes(workbook), media(xlsx)));
            }
        }
        try (var workbook = new XSSFWorkbook()) {
            workbook.createSheet("열 초과").createRow(0).createCell(SpreadsheetTextParser.MAX_COLUMNS).setCellValue("마지막 열");
            assertThrows(IOException.class, () -> parser.parse(bytes(workbook), "application/zip"));
        }
        assertThrows(Exception.class, () -> parser.parse(zip(Map.of("[Content_Types].xml", new byte[0], "xl/workbook.xml", new byte[0])), "application/zip"));
    }

    @Test
    void workbookPackageGuardsIncludeUnselectedEntriesAndShareOuterArchiveBudget() throws Exception {
        byte[] input = simpleWorkbook(true);
        assertThrows(IOException.class, () -> SpreadsheetTextParser.xlsxIfPresent(input, new int[]{10, 300}, 200, 1_000_000));
        assertThrows(IOException.class, () -> SpreadsheetTextParser.xlsxIfPresent(input, new int[]{24 * 1024 * 1024, 1}, 200, 1_000_000));
        assertThrows(IOException.class, () -> SpreadsheetTextParser.xlsxIfPresent(input, new int[]{24 * 1024 * 1024, 300}, 200, 8));
        var entries = unzip(input);
        entries.put("xl/media/ignored.bin", new byte[24 * 1024 * 1024]);
        byte[] oversized = zip(entries);
        assertThrows(IOException.class, () -> parser.parse(oversized, "application/zip"));
        entries = unzip(input);
        entries.put("../outside.xml", "must not extract".getBytes(StandardCharsets.UTF_8));
        byte[] unsafe = zip(entries);
        assertThrows(IOException.class, () -> parser.parse(unsafe, "application/zip"));
    }

    @Test
    void zippedExcelAttachmentsKeepNamesAndCannotBypassCombinedEntryLimit() throws Exception {
        var parts = parser.parse(zip(Map.of("주택목록.xlsx", simpleWorkbook(true), "임대조건.xls", simpleWorkbook(false))), "application/zip");
        assertEquals(2, parts.size());
        assertTrue(parts.stream().anyMatch(part -> part.location().startsWith("주택목록.xlsx · Excel 워크시트")));
        assertTrue(parts.stream().anyMatch(part -> part.location().startsWith("임대조건.xls · Excel 워크시트")));
        var entries = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < 299; i++) entries.put("ignored-" + i, new byte[0]);
        entries.put("주택목록.xlsx", simpleWorkbook(true));
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
    }

    @Test
    void standaloneWorkerIncludesSpreadsheetClassesAndTransitiveLibrariesWithoutBreakingHwp() throws Exception {
        var worker = new BoundedDocumentParser();
        for (boolean xlsx : List.of(false, true)) {
            var parts = worker.parse(simpleWorkbook(xlsx), media(xlsx));
            assertEquals(1, parts.size());
            assertTrue(parts.get(0).text().contains("A1: 제출서류 및 임대조건 확인"));
        }
        assertTrue(worker.parse(DocumentTextParserTest.hwp("HWP 본문 확인", 1), "application/x-ole-storage")
            .get(0).text().contains("HWP 본문 확인"));
    }

    private static Workbook workbook(boolean xlsx) { return xlsx ? new XSSFWorkbook() : new HSSFWorkbook(); }
    private static String media(boolean xlsx) { return xlsx ? "application/zip" : "application/x-ole-storage"; }

    private static byte[] simpleWorkbook(boolean xlsx) throws IOException {
        try (Workbook workbook = workbook(xlsx)) {
            workbook.createSheet("공급표").createRow(0).createCell(0).setCellValue("제출서류 및 임대조건 확인");
            return bytes(workbook);
        }
    }

    private static byte[] bytes(Workbook workbook) throws IOException {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) entries.put(entry.getName(), input.readAllBytes());
        }
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
