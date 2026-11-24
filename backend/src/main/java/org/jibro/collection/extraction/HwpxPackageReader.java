package org.jibro.collection.extraction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.stream.XMLStreamConstants;
import javax.imageio.ImageIO;

import static org.jibro.collection.extraction.ExtractionModels.Part;

/**
 * Reads manifest-ordered HWPX sections and only bounded images referenced by supported inline pictures.
 */
final class HwpxPackageReader {
    private HwpxPackageReader() {
    }

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
                byte[] manifest = readEntry(zip, zip.getEntry("Contents/content.hpf"), budget[0]);
                budget[0] -= manifest.length;
                PackageManifest contents = manifest(manifest);
                List<String> sectionOrder = contents.sections();
                ZipEntry header = zip.getEntry("Contents/header.xml");
                HwpxStyles paragraphStyles = HwpxStyles.empty();
                if (header != null) {
                    byte[] xml = readEntry(zip, header, budget[0]);
                    budget[0] -= xml.length;
                    paragraphStyles = HwpxStyles.read(xml);
                }
                var parts = new ArrayList<Part>();
                var sections = new HashSet<String>();
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().matches("Contents/section\\d+\\.xml")) continue;
                    if (!sections.add(entry.getName()) || sections.size() > maxParts)
                        throw new IOException("HWPX 구역 수·중복 확인 필요");
                }
                if (sectionOrder.isEmpty() || !sections.equals(new HashSet<>(sectionOrder)))
                    throw new IOException("HWPX 본문 구역 순서 확인 필요");
                int chars = 0;
                var images = new InlineImages(zip, contents.files(), budget);
                for (String name : sectionOrder) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("문서 추출 중단");
                    ZipEntry entry = zip.getEntry(name);
                    byte[] xml = readEntry(zip, entry, budget[0]);
                    budget[0] -= xml.length;
                    String text = HwpxStructuredText.read(xml, maxText - chars, paragraphStyles, images::read);
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

    private static PackageManifest manifest(byte[] manifest) throws Exception {
        var reader = HwpxStructuredText.xmlReader(manifest);
        var files = new HashMap<String, String>();
        var references = new ArrayList<String>();
        boolean spine = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) throw new IOException("DTD 금지");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (name.equals("item")) {
                        String id = reader.getAttributeValue(null, "id"), path = reader.getAttributeValue(null, "href");
                        if (id == null || path == null || files.putIfAbsent(id, path) != null)
                            throw new IOException("HWPX 문서 항목 확인 필요");
                    } else if (name.equals("spine")) spine = true;
                    else if (name.equals("itemref") && spine) references.add(reader.getAttributeValue(null, "idref"));
                } else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("spine"))
                    spine = false;
            }
        } finally {
            reader.close();
        }
        var sections = new ArrayList<String>();
        for (String reference : references) {
            String file = files.get(reference);
            if (file == null) throw new IOException("HWPX 본문 구역 참조 확인 필요");
            if (file.matches("Contents/section\\d+\\.xml")) {
                if (sections.contains(file)) throw new IOException("HWPX 본문 구역 중복 확인 필요");
                sections.add(file);
            }
        }
        return new PackageManifest(List.copyOf(sections), Map.copyOf(files));
    }

    private record PackageManifest(List<String> sections, Map<String, String> files) {
    }

    private static final class InlineImages {
        private final ZipFile zip;
        private final Map<String, String> files;
        private final int[] budget;
        private final Map<String, String> recognized = new HashMap<>();
        private long pixels;
        private int count;

        InlineImages(ZipFile zip, Map<String, String> files, int[] budget) {
            this.zip = zip;
            this.files = files;
            this.budget = budget;
        }

        String read(String id) throws IOException {
            String path = files.get(id);
            if (path == null || !path.startsWith("BinData/") || path.contains("..")) return null;
            if (recognized.containsKey(path)) return recognized.get(path);
            recognized.put(path, null);
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.getSize() < 0 || entry.getSize() > budget[0] || count >= OcrDocumentParser.MAX_IMAGES)
                return null;
            count++;
            byte[] bytes = readEntry(zip, entry, budget[0]);
            budget[0] -= bytes.length;
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                if (input == null) return null;
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return null;
                var reader = readers.next();
                try {
                    reader.setInput(input, false, true);
                    if (reader.getNumImages(true) != 1) return null;
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    long area = (long) width * height;
                    if (width <= 0 || height <= 0 || area > OcrDocumentParser.MAX_IMAGE_PIXELS
                            || area > OcrDocumentParser.MAX_TOTAL_PIXELS - pixels) return null;
                    pixels += area;
                    var image = reader.read(0);
                    if (image == null) return null;
                    try {
                        var reading = RasterArrow.read(image);
                        String arrow = reading == null ? null : reading.direction();
                        recognized.put(path, arrow);
                        return arrow;
                    } finally {
                        image.flush();
                    }
                } finally {
                    reader.dispose();
                }
            } catch (javax.imageio.IIOException unsupportedImage) {
                // Unreadable pictures remain unrecognized; they never become inferred arrows.
                return null;
            }
        }
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        byte[] bytes = read(zip.getInputStream(entry), limit);
        var crc = new CRC32();
        crc.update(bytes);
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
