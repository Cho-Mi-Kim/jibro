package org.jibro.collection.extraction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HwpxPackageReaderTest {
    private static final int EXPANDED = 24 * 1024 * 1024;
    private final DocumentTextParser parser = new DocumentTextParser();

    @Test void verifiedHwpxReadsEveryBodySectionWithoutInflatingEmbeddedImages() throws Exception {
        var entries = container();
        entries.put("BinData/image.bmp", new byte[EXPANDED + 1]);
        entries.put("Contents/section0.xml", xml("본문 첫 구역"));
        entries.put("Contents/section1.xml", xml("두 번째 구역의 예외"));
        int[] budget = {EXPANDED, 300};
        var parts = HwpxPackageReader.readIfHwpx(zip(entries), budget, 200, 1_000_000);
        assertEquals(List.of("본문 첫 구역\n", "두 번째 구역의 예외\n"), parts.stream().map(ExtractionModels.Part::text).toList());
        assertTrue(parts.get(1).location().contains("section1.xml"));
        assertEquals(300 - entries.size(), budget[1]);
        assertEquals(EXPANDED - entries.get("mimetype").length - entries.get("Contents/section0.xml").length
            - entries.get("Contents/section1.xml").length, budget[0]);
    }

    @Test void identityAndManifestAreBothRequiredToSkipBinaryExpansion() throws Exception {
        var entries = container();
        entries.put("huge.bin", new byte[EXPANDED + 1]);
        entries.put("Contents/section0.xml", xml("본문"));
        entries.remove("mimetype");
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        entries.put("mimetype", bytes("application/zip"));
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        entries.put("mimetype", bytes("application/hwp+zip"));
        entries.remove("Contents/content.hpf");
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
    }

    @Test void bodyXmlEntryCountAndTotalTextKeepTheirOriginalLimits() throws Exception {
        var entries = container();
        entries.put("Contents/section0.xml", new byte[EXPANDED + 1]);
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        entries.put("Contents/section0.xml", xml("a".repeat(600_000)));
        entries.put("Contents/section1.xml", xml("b".repeat(400_001)));
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        entries.put("Contents/section0.xml", xml("본문")); entries.remove("Contents/section1.xml");
        for (int i = 0; i < 298; i++) entries.put("BinData/image" + i, new byte[0]);
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
    }

    @Test void nestedHwpxSharesOuterEntryAndExpandedByteBudget() throws Exception {
        var entries = container(); entries.put("Contents/section0.xml", xml("공통 예외 조건"));
        byte[] inner = zip(entries);
        var parts = parser.parse(zip(Map.of("notice.hwpx", inner)), "application/zip");
        assertEquals("notice.hwpx · HWPX Contents/section0.xml (쪽 번호 확인 필요)", parts.get(0).location());
        assertThrows(IOException.class, () -> HwpxPackageReader.readIfHwpx(inner, new int[]{1, 300}, 200, 1_000_000));
        assertThrows(IOException.class, () -> HwpxPackageReader.readIfHwpx(inner, new int[]{EXPANDED, 2}, 200, 1_000_000));
    }

    @Test void noBodyOrTooManyBodySectionsIsNotSuccessfulExtraction() throws Exception {
        var entries = container();
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
        for (int i = 0; i < 201; i++) entries.put("Contents/section" + i + ".xml", xml("구역"));
        assertThrows(IOException.class, () -> parser.parse(zip(entries), "application/zip"));
    }

    @Test void corruptSectionChecksumCannotBecomeExtractedText() throws Exception {
        var entries = container(); entries.put("Contents/section0.xml", xml("검토할 본문"));
        byte[] archive = zip(entries);
        var fields = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        boolean changed = false;
        for (int offset = 0; offset < archive.length - 46; offset++) {
            if (fields.getInt(offset) != 0x02014b50) continue;
            int length = Short.toUnsignedInt(fields.getShort(offset + 28));
            String name = new String(archive, offset + 46, length, StandardCharsets.UTF_8);
            if (name.equals("Contents/section0.xml")) {
                fields.putInt(offset + 16, fields.getInt(offset + 16) ^ 1);
                changed = true;
                break;
            }
        }
        assertTrue(changed);
        assertThrows(IOException.class, () -> parser.parse(archive, "application/zip"));
    }

    private static Map<String, byte[]> container() {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("mimetype", bytes("application/hwp+zip"));
        entries.put("Contents/content.hpf", bytes("<package/>"));
        return entries;
    }
    private static byte[] xml(String text) { return bytes("<root><p><t>" + text + "</t></p></root>"); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
