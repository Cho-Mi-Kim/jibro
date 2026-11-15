package org.jibro.collection.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static org.jibro.collection.extraction.ExtractionModels.Part;

/** Reads stored workbook values and their positions. No formula evaluation, macros or external links are run. */
final class SpreadsheetTextParser {
    static final int MAX_ROWS = 10_000;
    static final int MAX_COLUMNS = 256;
    static final int MAX_CELLS = 50_000;
    static final int MAX_MERGES = 2000;
    private static final int MAX_EXPANDED = 24 * 1024 * 1024;

    private SpreadsheetTextParser() {}

    static List<Part> xls(POIFSFileSystem file, int maxParts, int maxText) throws IOException {
        try (var workbook = new HSSFWorkbook(file)) {
            return read(workbook, maxParts, maxText);
        }
    }

    static List<Part> xlsxIfPresent(byte[] bytes, int[] budget, int maxParts, int maxText) throws Exception {
        return xlsxIfPresent(bytes, budget, maxParts, maxText, StandardCharsets.UTF_8);
    }

    static List<Part> xlsxIfPresent(byte[] bytes, int[] budget, int maxParts, int maxText, Charset entryCharset) throws Exception {
        Path temporary = Files.createTempFile("jibro-xlsx-", ".zip");
        try {
            Files.write(temporary, bytes);
            if (!verifyPackage(temporary, budget, entryCharset)) return null;
            // The explicit archive budget and POI's compression-ratio protection apply before cell objects are built.
            ZipSecureFile.setMaxEntrySize(Math.min(ZipSecureFile.getMaxEntrySize(), MAX_EXPANDED));
            ZipSecureFile.setMaxTextSize(Math.min(ZipSecureFile.getMaxTextSize(), maxText));
            try (var packageFile = OPCPackage.open(temporary.toFile(), PackageAccess.READ);
                 var workbook = new XSSFWorkbook(packageFile)) {
                return read(workbook, maxParts, maxText);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Verify the complete archive, including ignored images/objects, without extracting anything to disk. */
    private static boolean verifyPackage(Path temporary, int[] budget, Charset entryCharset) throws IOException {
        try (var zip = new ZipFile(temporary.toFile(), entryCharset)) {
            if (zip.getEntry("[Content_Types].xml") == null || zip.getEntry("xl/workbook.xml") == null) return false;
            if (zip.size() > budget[1]) throw new IOException("압축 파일 항목 제한 초과");
            budget[1] -= zip.size();
            var names = new HashSet<String>();
            var entries = zip.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                interrupted();
                var entry = entries.nextElement();
                String name = entry.getName();
                if (!names.add(name) || name.startsWith("/") || name.contains("\\")
                    || List.of(name.split("/")).contains("..")) throw new IOException("스프레드시트 압축 경로 확인 필요");
                if (entry.isDirectory()) continue;
                if (entry.getSize() < 0 || entry.getSize() > budget[0]) throw new IOException("문서 압축 해제 크기 제한 초과");
                var crc = new CRC32();
                long actual = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        interrupted();
                        if (count > budget[0]) throw new IOException("문서 압축 해제 크기 제한 초과");
                        budget[0] -= count;
                        actual += count;
                        crc.update(buffer, 0, count);
                    }
                }
                if (entry.getSize() != actual || entry.getCrc() != crc.getValue())
                    throw new IOException("스프레드시트 압축 무결성 확인 필요");
            }
            return true;
        }
    }

    private static List<Part> read(Workbook workbook, int maxParts, int maxText) throws IOException {
        if (workbook.getNumberOfSheets() > maxParts) throw new IOException("스프레드시트 시트 수 제한 초과");
        var formatter = new DataFormatter(Locale.KOREA);
        formatter.setUseCachedValuesForFormulaCells(true);
        var parts = new ArrayList<Part>();
        int characters = 0, cells = 0, merges = 0;
        boolean content = false;
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            interrupted();
            var sheet = workbook.getSheetAt(sheetIndex);
            if (sheet.getLastRowNum() >= MAX_ROWS) throw new IOException("스프레드시트 행 수 제한 초과");
            String visibility = workbook.isSheetVeryHidden(sheetIndex) ? " [매우 숨김]" : workbook.isSheetHidden(sheetIndex) ? " [숨김]" : "";
            var text = new StringBuilder("워크시트: " + sheet.getSheetName() + visibility + "\n");
            merges += sheet.getNumMergedRegions();
            if (merges > MAX_MERGES) throw new IOException("스프레드시트 병합 영역 제한 초과");
            for (var region : sheet.getMergedRegions()) {
                if (region.getLastRow() >= MAX_ROWS || region.getLastColumn() >= MAX_COLUMNS)
                    throw new IOException("스프레드시트 병합 영역 범위 확인 필요");
                text.append("병합 범위: ").append(region.formatAsString()).append("\n");
                checkText(text, characters, maxText);
            }
            for (var row : sheet) {
                interrupted();
                if (row.getLastCellNum() > MAX_COLUMNS) throw new IOException("스프레드시트 열 수 제한 초과");
                var rowText = new StringBuilder();
                for (Cell cell : row) {
                    if (++cells > MAX_CELLS) throw new IOException("스프레드시트 셀 수 제한 초과");
                    String value = value(cell, formatter);
                    if (value.isBlank()) continue;
                    content = true;
                    rowText.append(cell.getAddress().formatAsString());
                    if (sheet.isColumnHidden(cell.getColumnIndex())) rowText.append(" [숨김 열]");
                    rowText.append(": ").append(value).append('\n');
                    checkText(rowText, characters + text.length(), maxText);
                }
                if (!rowText.isEmpty()) {
                    text.append("행 ").append(row.getRowNum() + 1);
                    if (row.getZeroHeight()) text.append(" [숨김]");
                    text.append('\n').append(rowText);
                    checkText(text, characters, maxText);
                }
            }
            characters += text.length();
            if (characters > maxText) throw new IOException("텍스트 길이 제한 초과");
            parts.add(new Part("Excel 워크시트 " + sheet.getSheetName() + visibility + " (행·열 위치)", text.toString()));
        }
        if (!content) throw new IOException("스프레드시트 셀 텍스트 확인 필요");
        return List.copyOf(parts);
    }

    private static String value(Cell cell, DataFormatter formatter) {
        if (cell.getCellType() != CellType.FORMULA) return formatter.formatCellValue(cell);
        String formula = "=" + cell.getCellFormula();
        if (cell instanceof XSSFCell xssf && xssf.getRawValue() == null)
            return "[수식 미계산: " + formula + "]";
        if (!java.util.Set.of(CellType.NUMERIC, CellType.STRING, CellType.BOOLEAN, CellType.ERROR)
            .contains(cell.getCachedFormulaResultType())) return "[수식 저장값 확인 필요: " + formula + "]";
        return "[저장된 계산값(재계산 안 함): " + formatter.formatCellValue(cell) + "; 수식: " + formula + "]";
    }

    private static void checkText(StringBuilder text, int previous, int maxText) throws IOException {
        if (text.length() > maxText - previous) throw new IOException("텍스트 길이 제한 초과");
    }

    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("문서 추출 중단");
    }
}
