package org.jibro.collection.extraction;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.poifs.filesystem.*;

import static org.jibro.collection.extraction.ExtractionModels.Part;

/**
 * Bounded optical reading of full PDF pages and HWP BinData images. No eligibility inference or text correction.
 */
final class OcrDocumentParser {
    static final Duration DOCUMENT_TIMEOUT = Duration.ofSeconds(300);
    static final Duration PAGE_TIMEOUT = Duration.ofSeconds(45);
    static final int MAX_IMAGES = 40;
    static final long MAX_IMAGE_PIXELS = 10_000_000;
    static final long MAX_TOTAL_PIXELS = 250_000_000;
    static final int MAX_TEXT = 1_000_000;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_EXPANDED = 48 * 1024 * 1024;
    private final Path directory;
    private final TesseractOcr.Recognizer recognizer;
    private final TesseractOcr.Recognizer alternate;
    private final int alternateMode;
    private final long deadline;
    private long pixels;
    private int images, textChars;
    private final List<Part> completed = new ArrayList<>();

    OcrDocumentParser(Path directory, TesseractOcr.Recognizer recognizer) {
        this(directory, recognizer, DOCUMENT_TIMEOUT.minusSeconds(5));
    }

    OcrDocumentParser(Path directory, TesseractOcr.Recognizer recognizer, Duration timeout) {
        this(directory, recognizer, null, 0, timeout);
    }

    OcrDocumentParser(Path directory, TesseractOcr.Recognizer recognizer, TesseractOcr.Recognizer alternate, int alternateMode, Duration timeout) {
        this.directory = directory;
        this.recognizer = recognizer;
        this.alternate = alternate;
        this.alternateMode = alternateMode;
        this.deadline = System.nanoTime() + timeout.toNanos();
    }

    static boolean needsFallback(List<Part> nativeParts, String mediaType) {
        if (!Set.of("application/pdf", "application/x-ole-storage").contains(mediaType)) return false;
        return nativeParts.isEmpty() || nativeParts.stream().anyMatch(part -> letters(part.text()) < 80);
    }

    List<Part> parse(byte[] bytes, String mediaType) throws Exception {
        var nativeParts = new DocumentTextParser().parse(bytes, mediaType);
        textChars = nativeParts.stream().mapToInt(p -> p.text().length()).sum();
        if (textChars > MAX_TEXT) throw new IOException("OCR_TEXT_LIMIT");
        try {
            return switch (mediaType) {
                case "application/pdf" -> pdf(bytes, nativeParts);
                case "application/x-ole-storage" -> hwp(bytes, nativeParts);
                default -> nativeParts;
            };
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            var partial = new ArrayList<>(nativeParts);
            partial.addAll(completed);
            partial.add(new Part(OcrProvenance.MARKER + " 일부 미판독 (" + failureCode(failure) + ")", ""));
            return List.copyOf(partial);
        }
    }

    private List<Part> pdf(byte[] bytes, List<Part> nativeParts) throws Exception {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent())
                throw new IOException("OCR_PDF_RESTRICTED");
            // Count pages that actually need optical reading in budget(), not text-only pages.
            // A long native-text notice with one scanned appendix still has only one OCR image.
            if (document.getNumberOfPages() > ParserProtocol.MAX_PARTS) throw new IOException("OCR_PAGE_LIMIT");
            var renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            var output = new ArrayList<Part>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                checkTime();
                var part = nativeParts.get(index);
                output.add(part);
                var page = document.getPage(index);
                if (letters(part.text()) >= 80 || !page.hasContents()) continue;
                if (!hasImage(page.getResources(), new HashSet<>()) && letters(part.text()) > 0) continue;
                var box = page.getCropBox();
                double width = box.getWidth(), height = box.getHeight();
                if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0)
                    throw new IOException("OCR_IMAGE_DIMENSIONS");
                double scale = Math.min(300d / 72, Math.sqrt(MAX_IMAGE_PIXELS / (width * height)));
                scale = Math.min(scale, singleImageScale(page.getResources(), width, height));
                if (scale < 0.5) throw new IOException("OCR_IMAGE_DIMENSIONS");
                long planned = (long) Math.ceil(width * scale) * (long) Math.ceil(height * scale);
                // Rounding at the target bound must not allocate a larger bitmap.
                if (planned > MAX_IMAGE_PIXELS) scale *= 0.999;
                claimPixels((long) Math.ceil(width * scale) * (long) Math.ceil(height * scale));
                BufferedImage image = renderer.renderImage(index, (float) scale, ImageType.GRAY);
                try {
                    readImages(image, "공고문 " + (index + 1) + "쪽", output);
                } finally {
                    image.flush();
                }
            }
            return List.copyOf(output);
        }
    }

    private boolean hasImage(PDResources resources, Set<Object> seen) throws IOException {
        if (resources == null || !seen.add(resources.getCOSObject())) return false;
        if (seen.size() > 256) throw new IOException("OCR_IMAGE_OBJECT_LIMIT");
        for (var name : resources.getXObjectNames()) {
            var object = resources.getXObject(name);
            if (object instanceof PDImageXObject) return true;
            if (object instanceof PDFormXObject form && hasImage(form.getResources(), seen)) return true;
        }
        return false;
    }

    private double singleImageScale(PDResources resources, double width, double height) throws IOException {
        if (resources == null) return Double.POSITIVE_INFINITY;
        PDImageXObject only = null;
        for (var name : resources.getXObjectNames()) {
            var object = resources.getXObject(name);
            if (!(object instanceof PDImageXObject image) || only != null) return Double.POSITIVE_INFINITY;
            only = image;
        }
        if (only == null || Math.abs(((double) only.getWidth() / only.getHeight()) / (width / height) - 1) > 0.02)
            return Double.POSITIVE_INFINITY;
        return Math.min(only.getWidth() / width, only.getHeight() / height);
    }

    private List<Part> hwp(byte[] bytes, List<Part> nativeParts) throws Exception {
        try (var fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            if (!fs.getRoot().hasEntry("FileHeader")) return nativeParts; // XLS is not an image document.
            byte[] header = readBounded(fs.createDocumentInputStream("FileHeader"), 1024);
            if (header.length < 40 || !new String(header, 0, 17, StandardCharsets.US_ASCII).startsWith("HWP Document File"))
                throw new IOException("OCR_HWP_HEADER");
            int flags = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(36);
            if ((flags & 6) != 0) throw new IOException("OCR_HWP_RESTRICTED");
            if (!fs.getRoot().hasEntry("BinData")) return nativeParts;
            var data = fs.getRoot().getEntry("BinData");
            if (!(data instanceof DirectoryEntry binData)) throw new IOException("OCR_HWP_IMAGES_INVALID");
            var entries = new ArrayList<DocumentEntry>();
            for (var entry : binData) if (entry instanceof DocumentEntry file) entries.add(file);
            entries.sort(Comparator.comparing(DocumentEntry::getName));
            if (entries.size() > MAX_IMAGES) throw new IOException("OCR_IMAGE_LIMIT");
            int expanded = 0;
            var output = new ArrayList<>(nativeParts);
            for (var entry : entries) {
                checkTime();
                String name = entry.getName();
                if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(?:jpe?g|png|bmp|tiff?|gif)$")) {
                    output.add(new Part(OcrProvenance.MARKER + " HWP BinData/" + name + " (지원하지 않는 내포 형식·쪽 번호 확인 필요)", ""));
                    continue;
                }
                byte[] imageBytes = readBounded(new DocumentInputStream(entry), Math.min(MAX_IMAGE_BYTES, MAX_EXPANDED - expanded));
                if (!imageHeader(imageBytes) && (flags & 1) != 0) {
                    var inflater = new Inflater(true);
                    try {
                        imageBytes = readBounded(new InflaterInputStream(new ByteArrayInputStream(imageBytes), inflater), Math.min(MAX_IMAGE_BYTES, MAX_EXPANDED - expanded));
                    } finally {
                        inflater.end();
                    }
                }
                expanded += imageBytes.length;
                BufferedImage image = decodeImage(imageBytes);
                try {
                    readImages(image, "HWP BinData/" + name + " (쪽 번호 확인 필요)", output);
                } finally {
                    image.flush();
                }
            }
            return List.copyOf(output);
        }
    }

    private BufferedImage decodeImage(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("OCR_IMAGE_UNSUPPORTED");
            var reader = readers.next();
            try {
                reader.setInput(input, false, true);
                if (reader.getNumImages(true) != 1) throw new IOException("OCR_MULTIPAGE_IMAGE_UNSUPPORTED");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                long original = (long) width * height;
                if (width <= 0 || height <= 0 || original > 100_000_000) throw new IOException("OCR_IMAGE_DIMENSIONS");
                int sample = Math.max(1, (int) Math.ceil(Math.sqrt((double) original / MAX_IMAGE_PIXELS)));
                claimPixels((long) ((width + sample - 1) / sample) * ((height + sample - 1) / sample));
                var parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                return reader.read(0, parameters);
            } finally {
                reader.dispose();
            }
        }
    }

    private void readImages(BufferedImage image, String location, List<Part> output) throws Exception {
        Part primary = readImage(image, location, recognizer);
        output.add(primary);
        completed.add(primary);
        if (alternate != null) {
            // Alternative page segmentation is a separate reading, never merged into invented table/prose relations.
            Part secondary = readImage(image, location + " · 보조 판독(PSM" + alternateMode + ")", alternate);
            // Only after both calls finish successfully can one empty reading be omitted.
            // A failed alternative still leaves the primary and the explicit failure marker intact.
            if (primary.text().isBlank() && !secondary.text().isBlank()) {
                output.remove(output.size() - 1);
                completed.remove(completed.size() - 1);
            }
            if (!secondary.text().isBlank() || primary.text().isBlank()) {
                output.add(secondary);
                completed.add(secondary);
            }
        }
    }

    private Part readImage(BufferedImage image, String location, TesseractOcr.Recognizer reader) throws Exception {
        checkTime();
        if ((long) image.getWidth() * image.getHeight() > MAX_IMAGE_PIXELS) throw new IOException("OCR_PIXEL_LIMIT");
        Path file = directory.resolve("ocr-page.png");
        try {
            if (!ImageIO.write(image, "png", file.toFile()) || Files.size(file) > MAX_IMAGE_BYTES)
                throw new IOException("OCR_IMAGE_LIMIT");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("OCR_DOCUMENT_TIMEOUT");
            String text = reader.read(file, Duration.ofNanos(Math.min(PAGE_TIMEOUT.toNanos(), remaining)));
            checkTime();
            if (text == null || (textChars += text.length()) > MAX_TEXT) throw new IOException("OCR_TEXT_LIMIT");
            return new Part(OcrProvenance.MARKER + " " + location, text);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void claimPixels(long value) throws IOException {
        if (value <= 0 || value > MAX_IMAGE_PIXELS || ++images > MAX_IMAGES || value > MAX_TOTAL_PIXELS - pixels)
            throw new IOException("OCR_PIXEL_LIMIT");
        pixels += value;
    }

    private void checkTime() throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new IOException("OCR_DOCUMENT_TIMEOUT");
    }

    static String failureCode(Throwable failure) {
        if (failure instanceof java.util.concurrent.TimeoutException) return "OCR_PAGE_TIMEOUT";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.matches("OCR_[A-Z_]{1,48}")) return message;
        }
        return "OCR_WORKER_FAILED";
    }

    private static long letters(String text) {
        return PdfLayoutTextStripper.withoutStructure(HwpxStructuredText.withoutStructure(text))
                .codePoints().filter(Character::isLetter).limit(80).count();
    }

    private static boolean imageHeader(byte[] bytes) {
        return bytes.length > 4 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8)
                || (bytes[0] == (byte) 0x89 && bytes[1] == 'P') || (bytes[0] == 'B' && bytes[1] == 'M')
                || (bytes[0] == 'G' && bytes[1] == 'I') || (bytes[0] == 'I' && bytes[1] == 'I') || (bytes[0] == 'M' && bytes[1] == 'M'));
    }

    private byte[] readBounded(InputStream stream, int limit) throws IOException {
        try (stream; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                checkTime();
                if (count > limit - output.size()) throw new IOException("OCR_IMAGE_BYTES_LIMIT");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
