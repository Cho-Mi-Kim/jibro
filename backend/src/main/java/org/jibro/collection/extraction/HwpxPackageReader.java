package org.jibro.collection.extraction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.jibro.collection.extraction.ExtractionModels.Part;

/** Reads a verified HWPX container without inflating embedded screenshots or executable objects. */
final class HwpxPackageReader {
    private HwpxPackageReader() {}

    static List<Part> readIfHwpx(byte[] bytes, int[] budget, int maxParts, int maxText) throws Exception {
        return readIfHwpx(bytes, budget, maxParts, maxText, StandardCharsets.UTF_8);
    }

    static List<Part> readIfHwpx(byte[] bytes, int[] budget, int maxParts, int maxText, Charset entryCharset) throws Exception {
        Path temporary = Files.createTempFile("jibro-hwpx-", ".zip");
        try {
            Files.write(temporary, bytes);
            try (var zip = new ZipFile(temporary.toFile(), entryCharset)) {
                ZipEntry mime = zip.getEntry("mimetype");
                if (mime == null || zip.getEntry("Contents/content.hpf") == null) return null;
                byte[] identity = readEntry(zip, mime, 128);
                if (!new String(identity, StandardCharsets.US_ASCII).equals("application/hwp+zip")) return null;
                if (zip.size() > budget[1]) throw new IOException("압축 파일 항목 제한 초과");
                budget[1] -= zip.size();
                budget[0] -= identity.length;
                if (budget[0] < 0) throw new IOException("문서 압축 해제 크기 제한 초과");
                var parts = new ArrayList<Part>();
                var sections = new HashSet<String>();
                var entries = zip.entries();
                int chars = 0;
                while (entries.hasMoreElements()) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("문서 추출 중단");
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().matches("Contents/section\\d+\\.xml")) continue;
                    if (!sections.add(entry.getName()) || parts.size() >= maxParts) throw new IOException("HWPX 구역 수·중복 확인 필요");
                    byte[] xml = readEntry(zip, entry, budget[0]);
                    budget[0] -= xml.length;
                    String text = HwpxStructuredText.read(xml, maxText - chars);
                    chars += text.length();
                    parts.add(new Part("HWPX " + entry.getName() + " (쪽 번호 확인 필요)", text));
                }
                if (parts.isEmpty()) throw new IOException("HWPX 본문 구역 확인 필요");
                return parts;
            } catch (ZipException malformed) {
                // The ordinary archive reader still owns non-HWPX ZIP format validation.
                throw new IOException("압축 문서 구조 확인 필요", malformed);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        byte[] bytes = read(zip.getInputStream(entry), limit);
        var crc = new CRC32(); crc.update(bytes);
        if (entry.getSize() != bytes.length || entry.getCrc() != crc.getValue())
            throw new IOException("HWPX 본문 무결성 확인 필요");
        return bytes;
    }

    private static byte[] read(InputStream input, int limit) throws IOException {
        try (input; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("문서 추출 중단");
                if (count > limit - output.size()) throw new IOException("문서 압축 해제 크기 제한 초과");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
