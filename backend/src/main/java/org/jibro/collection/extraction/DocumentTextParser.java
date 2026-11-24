package org.jibro.collection.extraction;

import java.io.*;
import java.nio.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

import org.apache.pdfbox.Loader;
import org.apache.poi.poifs.filesystem.*;

import static org.jibro.collection.extraction.ExtractionModels.*;

/**
 * Text only: no scripts, embedded objects, macros, OCR, or external XML entities are executed.
 * 이 제품은 한글과컴퓨터의 한글 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다.
 */
public final class DocumentTextParser {
    private static final int MAX_EXPANDED = 24 * 1024 * 1024;
    private static final int MAX_TEXT = 1_000_000;
    private static final int MAX_PARTS = 200;

    public List<Part> parse(byte[] bytes, String mediaType) throws Exception {
        return switch (mediaType) {
            case "application/pdf" -> pdf(bytes);
            case "application/x-ole-storage" -> hwp(bytes);
            case "application/zip" -> zip(bytes);
            default -> throw new IOException("지원하지 않는 문서 형식");
        };
    }

    private List<Part> pdf(byte[] bytes) throws IOException {
        try (var pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted() || !pdf.getCurrentAccessPermission().canExtractContent())
                throw new IOException("암호화·추출 제한 문서");
            if (pdf.getNumberOfPages() > MAX_PARTS) throw new IOException("쪽 수 제한 초과");
            var parts = new ArrayList<Part>();
            var stripper = new PdfLayoutTextStripper();
            int chars = 0;
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                interrupted();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf);
                chars += text.length();
                if (chars > MAX_TEXT) throw new IOException("텍스트 길이 제한 초과");
                parts.add(new Part("공고문 " + page + "쪽", text));
            }
            return parts;
        }
    }

    private List<Part> hwp(byte[] bytes) throws IOException {
        try (var fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            if (!fs.getRoot().hasEntry("FileHeader")
                    && (fs.getRoot().hasEntry("Workbook") || fs.getRoot().hasEntry("Book")))
                return SpreadsheetTextParser.xls(fs, MAX_PARTS, MAX_TEXT);
            byte[] header = read(fs.createDocumentInputStream("FileHeader"), 1024);
            if (header.length < 40 || !new String(header, 0, 17, StandardCharsets.US_ASCII).startsWith("HWP Document File"))
                throw new IOException("HWP 헤더 확인 필요");
            int flags = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(36);
            if ((flags & 6) != 0) throw new IOException("암호화·배포용 HWP는 수동 확인 필요");
            DirectoryEntry body = (DirectoryEntry) fs.getRoot().getEntry("BodyText");
            List<DocumentEntry> sections = new ArrayList<>();
            body.forEach(e -> {
                if (e instanceof DocumentEntry d && e.getName().matches("Section\\d+")) sections.add(d);
            });
            sections.sort(Comparator.comparingInt(e -> Integer.parseInt(e.getName().substring(7))));
            if (sections.size() > MAX_PARTS) throw new IOException("구역 수 제한 초과");
            List<Part> parts = new ArrayList<>();
            int chars = 0, expanded = 0;
            for (var entry : sections) {
                interrupted();
                byte[] raw = read(new DocumentInputStream(entry), MAX_EXPANDED - expanded);
                if ((flags & 1) != 0) {
                    var inflater = new Inflater(true);
                    try {
                        raw = read(new InflaterInputStream(new ByteArrayInputStream(raw), inflater), MAX_EXPANDED - expanded);
                    } finally {
                        inflater.end();
                    }
                }
                expanded += raw.length;
                ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                StringBuilder text = new StringBuilder();
                while (b.remaining() >= 4) {
                    int record = b.getInt(), tag = record & 0x3ff, length = record >>> 20;
                    if (length == 0xfff) {
                        if (b.remaining() < 4) throw new IOException("HWP 레코드 손상");
                        length = b.getInt();
                    }
                    if (length < 0 || length > b.remaining()) throw new IOException("HWP 레코드 길이 확인 필요");
                    if (tag == 67) {
                        byte[] paragraph = new byte[length];
                        b.get(paragraph);
                        String value = new String(paragraph, StandardCharsets.UTF_16LE);
                        for (int i = 0; i < value.length(); i++) {
                            char c = value.charAt(i);
                            if (c >= 32) text.append(c);
                            else if (c == 10 || c == 13) text.append('\n');
                            else if (Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23).contains((int) c)) {
                                text.append(' ');
                                i += 7;
                            }
                        }
                        text.append('\n');
                        if (text.length() + chars > MAX_TEXT) throw new IOException("텍스트 길이 제한 초과");
                    } else b.position(b.position() + length);
                }
                chars += text.length();
                parts.add(new Part("HWP " + entry.getName() + " (쪽 번호 확인 필요)", text.toString()));
            }
            return parts;
        }
    }

    private List<Part> zip(byte[] bytes) throws Exception {
        return zip(bytes, 0, new int[]{MAX_EXPANDED, 300});
    }

    private List<Part> zip(byte[] bytes, int depth, int[] budget) throws Exception {
        int[] attemptBudget = budget.clone();
        List<Part> result;
        try {
            result = zip(bytes, depth, attemptBudget, StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException failure) {
            if (!zipNameEncodingFailure(failure)) throw failure;
            // Legacy Korean archives omit the UTF-8 flag. JDK ZIP readers still honor that flag
            // when present, so malformed UTF-8 entries cannot be reinterpreted as CP949.
            attemptBudget = budget.clone();
            result = zip(bytes, depth, attemptBudget, Charset.forName("MS949"));
        }
        System.arraycopy(attemptBudget, 0, budget, 0, budget.length);
        return result;
    }

    private static boolean zipNameEncodingFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = Objects.toString(cause.getMessage(), "").toLowerCase(Locale.ROOT);
            if ((cause instanceof ZipException || cause instanceof IllegalArgumentException)
                    && (message.contains("bad entry name") || message.contains("malformed input"))) return true;
        }
        return false;
    }

    private List<Part> zip(byte[] bytes, int depth, int[] budget, Charset entryCharset) throws Exception {
        List<Part> hwpx = HwpxPackageReader.readIfHwpx(bytes, budget, MAX_PARTS, MAX_TEXT, entryCharset);
        if (hwpx != null) return hwpx;
        List<Part> spreadsheet = SpreadsheetTextParser.xlsxIfPresent(bytes, budget, MAX_PARTS, MAX_TEXT, entryCharset);
        if (spreadsheet != null) return spreadsheet;
        List<Part> parts = new ArrayList<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), entryCharset)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                interrupted();
                if (--budget[1] < 0) throw new IOException("압축 파일 항목 제한 초과");
                if (entry.isDirectory()) continue;
                byte[] data = readUnclosed(zip, budget[0]);
                budget[0] -= data.length;
                String name = entry.getName();
                if (name.matches("Contents/section\\d+\\.xml"))
                    parts.add(new Part("HWPX " + name + " (쪽 번호 확인 필요)", HwpxStructuredText.read(data, MAX_TEXT)));
                else if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    for (Part part : pdf(data)) parts.add(new Part(name + " · " + part.location(), part.text()));
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".hwp") || name.toLowerCase(Locale.ROOT).endsWith(".xls")) {
                    for (Part part : hwp(data)) parts.add(new Part(name + " · " + part.location(), part.text()));
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                    var nested = SpreadsheetTextParser.xlsxIfPresent(data, budget, MAX_PARTS, MAX_TEXT);
                    if (nested == null) throw new IOException("스프레드시트 구조 확인 필요");
                    for (Part part : nested) parts.add(new Part(name + " · " + part.location(), part.text()));
                } else if (depth == 0 && name.toLowerCase(Locale.ROOT).endsWith(".hwpx")) {
                    for (Part part : zip(data, 1, budget))
                        parts.add(new Part(name + " · " + part.location(), part.text()));
                }
                if (parts.size() > MAX_PARTS || parts.stream().mapToInt(p -> p.text().length()).sum() > MAX_TEXT)
                    throw new IOException("압축 문서 내용 제한 초과");
            }
        }
        return parts;
    }

    private static byte[] read(InputStream in, int limit) throws IOException {
        try (in) {
            return readUnclosed(in, limit);
        }
    }

    private static byte[] readUnclosed(InputStream in, int limit) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            interrupted();
            if (n > limit - out.size()) throw new IOException("문서 압축 해제 크기 제한 초과");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("문서 추출 중단");
    }
}
