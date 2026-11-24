package org.jibro.collection.extraction;

import java.io.*;
import java.util.Arrays;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Worker-only PDF execution; the parent reads bounded status/PNG bytes without parsing a PDF.
 */
public final class PdfPageRenderer {
    static final int MAX_OUTPUT = 20 * 1024 * 1024 + 1;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private PdfPageRenderer() {
    }

    public static final class PageException extends IOException {
        private final boolean missing;

        PageException(boolean missing) {
            super(missing ? "PDF_PAGE_NOT_FOUND" : "PDF_PAGE_INVALID");
            this.missing = missing;
        }

        public boolean missing() {
            return missing;
        }
    }

    static void write(byte[] bytes, int page, OutputStream output) throws IOException {
        if (page < 1 || page > 1000) throw new IOException("PDF_PAGE_INVALID");
        try (var document = Loader.loadPDF(bytes)) {
            if (page > document.getNumberOfPages()) {
                output.write(4);
                output.flush();
                return;
            }
            var box = document.getPage(page - 1).getCropBox();
            float width = box.getWidth(), height = box.getHeight();
            if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) {
                output.write(2);
                output.flush();
                return;
            }
            float scale = Math.min(1.5f, 2400f / Math.max(width, height));
            var image = new PDFRenderer(document).renderImage(page - 1, scale);
            output.write(0);
            if (!ImageIO.write(image, "png", output)) throw new IOException("PDF_IMAGE_UNAVAILABLE");
            output.flush();
        }
    }

    static byte[] image(byte[] output) throws IOException {
        if (output.length == 1 && (output[0] == 4 || output[0] == 2)) throw new PageException(output[0] == 4);
        if (output.length < PNG.length + 1 || output.length > MAX_OUTPUT || output[0] != 0
                || !Arrays.equals(PNG, Arrays.copyOfRange(output, 1, PNG.length + 1)))
            throw new IOException("PDF_IMAGE_PROTOCOL_INVALID");
        return Arrays.copyOfRange(output, 1, output.length);
    }
}
