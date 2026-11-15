package org.jibro.collection.extraction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.*;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

class DocumentTextParserTest {
    final DocumentTextParser parser = new DocumentTextParser();

    @Test void pdfKeepsPagePositionsAndRejectsTooManyPages() throws Exception {
        var parts = parser.parse(pdf("First page", "Second page"), "application/pdf");
        assertEquals(2, parts.size());
        assertEquals("공고문 1쪽", parts.get(0).location());
        assertTrue(parts.get(0).text().contains("First page"));
        assertEquals("공고문 2쪽", parts.get(1).location());
        assertTrue(parts.get(1).text().contains("Second page"));
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < 201; i++) doc.addPage(new PDPage());
            doc.save(output);
            assertThrows(IOException.class, () -> parser.parse(output.toByteArray(), "application/pdf"));
        }
    }

    @Test void compressedHwpReadsUtf16ParagraphRecordAndDoesNotInventPageNumber() throws Exception {
        String text = "신청 자격은 원문을 확인하세요.\r제출 서류: 증명서";
        var parts = parser.parse(hwp(text, 1), "application/x-ole-storage");
        assertEquals(1, parts.size());
        assertTrue(parts.get(0).text().contains("신청 자격은 원문을 확인하세요."));
        assertTrue(parts.get(0).text().contains("제출 서류: 증명서"));
        assertEquals("HWP Section0 (쪽 번호 확인 필요)", parts.get(0).location());
        assertThrows(IOException.class, () -> parser.parse(hwp(text, 2), "application/x-ole-storage"));
        assertThrows(IOException.class, () -> parser.parse(hwp(text, 4), "application/x-ole-storage"));
    }

    @Test void hwpxExtractsTextWithoutEntitiesAndZipDocumentsRetainSourceLocations() throws Exception {
        byte[] xml = "<root xmlns:hp='urn:test'><hp:p><hp:t>소득 기준 확인</hp:t></hp:p></root>".getBytes(StandardCharsets.UTF_8);
        var parts = parser.parse(zip(Map.of("Contents/section0.xml", xml)), "application/zip");
        assertEquals("소득 기준 확인\n", parts.get(0).text());
        assertTrue(parts.get(0).location().contains("쪽 번호 확인 필요"));
        var documents = parser.parse(zip(Map.of("notice.pdf", pdf("Attachment text"), "notice.hwp", hwp("제출 서류 안내", 1))), "application/zip");
        assertTrue(documents.stream().anyMatch(p -> p.location().equals("notice.pdf · 공고문 1쪽")));
        assertTrue(documents.stream().anyMatch(p -> p.location().startsWith("notice.hwp · HWP Section0")));
        byte[] dtd = "<!DOCTYPE root [<!ENTITY external SYSTEM 'file:///must-not-read'>]><root><t>&external;</t></root>".getBytes(StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> parser.parse(zip(Map.of("Contents/section0.xml", dtd)), "application/zip"));
    }

    @Test void archiveLimitsBoundEntriesExpandedBytesAndText() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 301; i++) entries.put("entry-" + i, new byte[0]);
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        assertThrows(IOException.class, () -> parser.parse(zip(Map.of("huge.bin", new byte[24 * 1024 * 1024 + 1])), "application/zip"));
        byte[] xml = ("<root><t>" + "a".repeat(1_000_001) + "</t></root>").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> parser.parse(zip(Map.of("Contents/section0.xml", xml)), "application/zip"));
    }

    @Test void zipCanContainHwpxWithoutLosingItsLocationOrSharedExpansionBudget() throws Exception {
        byte[] xml = "<root><p><t>압축 첨부 속 제출 서류 안내</t></p></root>".getBytes(StandardCharsets.UTF_8);
        byte[] hwpx = zip(Map.of("Contents/section0.xml", xml));
        var parts = parser.parse(zip(Map.of("notice.hwpx", hwpx)), "application/zip");
        assertEquals(1, parts.size());
        assertEquals("notice.hwpx · HWPX Contents/section0.xml (쪽 번호 확인 필요)", parts.get(0).location());
        assertEquals("압축 첨부 속 제출 서류 안내\n", parts.get(0).text());
        byte[] largeNested = zip(Map.of("ignored.bin", new byte[24 * 1024 * 1024]));
        assertThrows(IOException.class, () -> parser.parse(zip(Map.of("large.hwpx", largeNested)), "application/zip"));
        Map<String, byte[]> nestedEntries = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) nestedEntries.put("nested-" + i, new byte[0]);
        assertThrows(IOException.class, () -> parser.parse(zip(Map.of("many.hwpx", zip(nestedEntries))), "application/zip"));
    }

    @Test void legacyKoreanZipNamesPreserveDocumentLocationsAndLimits() throws Exception {
        var cp949 = Charset.forName("MS949");
        byte[] archive = zip(Map.of("[양식1]확인서.hwp", hwp("공식 제출 서식 본문", 1),
            "[양식2]공고문.pdf", pdf("Official document")), cp949);
        var parts = parser.parse(archive, "application/zip");
        assertTrue(parts.stream().anyMatch(p -> p.location().startsWith("[양식1]확인서.hwp · HWP")
            && p.text().contains("공식 제출 서식 본문")));
        assertTrue(parts.stream().anyMatch(p -> p.location().equals("[양식2]공고문.pdf · 공고문 1쪽")));
        assertThrows(IOException.class, () -> parser.parse(zip(Map.of("대용량.bin",
            new byte[24 * 1024 * 1024 + 1]), cp949), "application/zip"));
        var entries = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < 301; i++) entries.put("서류" + i, new byte[0]);
        assertThrows(IOException.class, () -> parser.parse(zip(entries, cp949), "application/zip"));
    }

    @Test void legacyOuterZipKeepsUtf8NestedHwpxAndXlsxContainers() throws Exception {
        byte[] xml = "<root><p><t>한글 구역 본문</t></p></root>".getBytes(StandardCharsets.UTF_8);
        byte[] hwpx = zip(Map.of("mimetype", "application/hwp+zip".getBytes(StandardCharsets.US_ASCII),
            "Contents/content.hpf", new byte[0], "Contents/section0.xml", xml));
        byte[] xlsx;
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("공급 주택").createRow(0).createCell(0).setCellValue("공식 목록");
            workbook.write(output); xlsx = output.toByteArray();
        }
        var parts = parser.parse(zip(Map.of("공고.hwpx", hwpx, "공급목록.xlsx", xlsx), Charset.forName("MS949")), "application/zip");
        assertTrue(parts.stream().anyMatch(p -> p.location().startsWith("공고.hwpx") && p.text().contains("한글 구역 본문")));
        assertTrue(parts.stream().anyMatch(p -> p.location().startsWith("공급목록.xlsx") && p.text().contains("공식 목록")));
        var utf8Parts = parser.parse(zip(Map.of("서식😀.hwp", hwp("UTF8 그대로", 1))), "application/zip");
        assertTrue(utf8Parts.get(0).location().startsWith("서식😀.hwp"));
    }

    @Test void explicitlyUtf8NamesAreNeverReinterpretedAsLegacyKorean() throws Exception {
        byte[] archive = zip(Map.of("양식.hwp", hwp("검토 문서", 1)), Charset.forName("MS949"));
        // An invalid archive claiming its CP949 bytes are UTF-8 must remain invalid.
        var buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + 10 <= archive.length; i++) {
            int signature = buffer.getInt(i);
            if (signature == 0x04034b50 || signature == 0x02014b50) {
                int flagOffset = i + (signature == 0x04034b50 ? 6 : 8);
                buffer.putShort(flagOffset, (short)(buffer.getShort(flagOffset) | 0x800));
            }
        }
        assertThrows(IOException.class, () -> parser.parse(archive, "application/zip"));
    }

    static byte[] pdf(String... texts) throws IOException {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (String text : texts) {
                var page = new PDPage(); doc.addPage(page);
                try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(20, 700); stream.showText(text); stream.endText();
                }
            }
            doc.save(output); return output.toByteArray();
        }
    }

    static byte[] hwp(String text, int flags) throws IOException {
        byte[] paragraph = text.getBytes(StandardCharsets.UTF_16LE);
        var records = ByteBuffer.allocate(4 + paragraph.length).order(ByteOrder.LITTLE_ENDIAN);
        records.putInt(67 | (paragraph.length << 20)).put(paragraph);
        byte[] body = records.array();
        if ((flags & 1) != 0) {
            var output = new ByteArrayOutputStream(); var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            try (var stream = new DeflaterOutputStream(output, deflater)) { stream.write(body); }
            finally { deflater.end(); }
            body = output.toByteArray();
        }
        byte[] header = new byte[256];
        System.arraycopy("HWP Document File".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 17);
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(36, flags);
        try (var fs = new POIFSFileSystem(); var output = new ByteArrayOutputStream()) {
            fs.createDocument(new ByteArrayInputStream(header), "FileHeader");
            fs.getRoot().createDirectory("BodyText").createDocument("Section0", new ByteArrayInputStream(body));
            fs.writeFilesystem(output); return output.toByteArray();
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        return zip(entries, StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> entries, Charset entryCharset) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, entryCharset)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
