package org.jibro.collection.extraction;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.jibro.collection.extraction.ExtractionModels.Part;

class OcrDocumentParserTest {
    @TempDir
    Path root;

    @Test
    void nativeTableCoordinatesDoNotSuppressOcrForAnImageWithTooFewActualLetters() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            var image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            try (var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 10, 10, 20, 20);
                for (float x : new float[]{30, 100, 170}) {
                    content.moveTo(x, 70);
                    content.lineTo(x, 170);
                }
                for (float y : new float[]{70, 120, 170}) {
                    content.moveTo(30, y);
                    content.lineTo(170, y);
                }
                content.stroke();
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(40, 145);
                content.showText("Code");
                content.endText();
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        var nativeParts = new DocumentTextParser().parse(bytes, "application/pdf");
        assertTrue(nativeParts.get(0).text().contains("⟦PDF:TABLE"));
        var parser = new OcrDocumentParser(root, (file, timeout) -> "스캔 이미지에서 읽은 신청 기준");
        var parts = parser.parse(bytes, "application/pdf");
        assertTrue(parts.stream().anyMatch(p -> OcrProvenance.contains(p) && p.text().equals("스캔 이미지에서 읽은 신청 기준")));
    }

    @Test
    void mixedPdfKeepsNativeTextAndOpticallyReadsFullImagePagesIncludingNumericOnlyText() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var parser = new OcrDocumentParser(root, (file, timeout) -> {
            calls.incrementAndGet();
            var image = ImageIO.read(file.toFile());
            assertTrue((long) image.getWidth() * image.getHeight() <= OcrDocumentParser.MAX_IMAGE_PIXELS);
            // Two separate image tiles on the page must both survive the page render.
            assertTrue(new Color(image.getRGB(image.getWidth() / 4, image.getHeight() / 2)).getRed() < 50);
            assertTrue(new Color(image.getRGB(image.getWidth() * 3 / 4, image.getHeight() / 2)).getRed() < 50);
            assertTrue(timeout.compareTo(OcrDocumentParser.PAGE_TIMEOUT) <= 0);
            return "신청 기간 2026.11.01 / 숫자·표는 원문 대조";
        });
        var parts = parser.parse(mixedPdf(), "application/pdf");
        assertEquals(2, calls.get());
        assertTrue(parts.get(0).text().contains("Native official announcement"));
        assertFalse(OcrProvenance.contains(parts.get(0)));
        assertEquals(2, parts.stream().filter(OcrProvenance::contains).count());
        assertTrue(parts.stream().anyMatch(p -> p.location().equals(OcrProvenance.MARKER + " 공고문 2쪽")));
        assertTrue(parts.stream().anyMatch(p -> p.text().contains("123.45")));
    }

    @Test
    void hwpReadsCompressedBindataButNeverPretendsItsPositionIsAPageOrUsesPreviewThumbnail() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var parser = new OcrDocumentParser(root, (file, timeout) -> {
            calls.incrementAndGet();
            return "도면 84.97 / 판독은 확인 필요";
        });
        var parts = parser.parse(imageHwp(), "application/x-ole-storage");
        assertEquals(1, calls.get());
        assertTrue(parts.stream().anyMatch(p -> p.location().equals(OcrProvenance.MARKER + " HWP BinData/BIN0001.png (쪽 번호 확인 필요)")));
        assertTrue(parts.stream().noneMatch(p -> p.location().contains("PrvImage")));
        assertTrue(parts.stream().anyMatch(p -> p.text().contains("123")));
    }

    @Test
    void alternativeSegmentationIsASeparateReadingWithoutMergingText() throws Exception {
        var parser = new OcrDocumentParser(root, (file, timeout) -> "기본 판독", (file, timeout) -> "다른 지면 분할 판독", 6, Duration.ofSeconds(10));
        var parts = parser.parse(imagePdf(1, false), "application/pdf");
        assertEquals(2, parts.stream().filter(OcrProvenance::contains).count());
        assertTrue(parts.stream().anyMatch(p -> p.location().contains("보조 판독(PSM6)") && p.text().equals("다른 지면 분할 판독")));
        assertTrue(parts.stream().anyMatch(p -> p.text().equals("기본 판독")));
        assertFalse(OcrProvenance.incomplete(parts));
    }

    @Test
    void aSuccessfulAlternateReadingCanReplaceAnEmptyPrimaryWithoutInventingText() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var parser = new OcrDocumentParser(root,
                (file, timeout) -> {
                    calls.incrementAndGet();
                    return " \n";
                },
                (file, timeout) -> {
                    calls.incrementAndGet();
                    return "보조 판독으로 읽힌 원문";
                }, 6, Duration.ofSeconds(10));
        var parts = parser.parse(imagePdf(1, false), "application/pdf");
        assertEquals(2, calls.get());
        var optical = parts.stream().filter(OcrProvenance::contains).toList();
        assertEquals(1, optical.size());
        assertEquals("보조 판독으로 읽힌 원문", optical.get(0).text());
        assertTrue(optical.get(0).location().contains("보조 판독(PSM6)"));
        assertFalse(OcrProvenance.incomplete(parts));
    }

    @Test
    void anEmptySuccessfulAlternateDoesNotInvalidateThePrimaryReading() throws Exception {
        var parser = new OcrDocumentParser(root, (file, timeout) -> "기본 판독", (file, timeout) -> "", 6, Duration.ofSeconds(10));
        var parts = parser.parse(imagePdf(1, false), "application/pdf");
        var optical = parts.stream().filter(OcrProvenance::contains).toList();
        assertEquals(1, optical.size());
        assertEquals("기본 판독", optical.get(0).text());
        assertFalse(optical.get(0).location().contains("보조 판독"));
        assertFalse(OcrProvenance.incomplete(parts));
    }

    @Test
    void emptyReadingsRemainIncompleteWhetherOneOrTwoModesAreAvailable() throws Exception {
        for (var parser : List.of(new OcrDocumentParser(root, (file, timeout) -> ""),
                new OcrDocumentParser(root, (file, timeout) -> "", (file, timeout) -> " \n", 6, Duration.ofSeconds(10)))) {
            var parts = parser.parse(imagePdf(1, false), "application/pdf");
            assertTrue(OcrProvenance.incomplete(parts));
            assertTrue(parts.stream().filter(OcrProvenance::contains).allMatch(p -> p.text().isBlank()));
        }
    }

    @Test
    void aFailedAlternateKeepsThePrimaryAndExplicitIncompleteMarker() throws Exception {
        for (String primary : List.of("기본 판독", "")) {
            var parser = new OcrDocumentParser(root, (file, timeout) -> primary,
                    (file, timeout) -> {
                        throw new java.util.concurrent.TimeoutException();
                    }, 6, Duration.ofSeconds(10));
            var parts = parser.parse(imagePdf(1, false), "application/pdf");
            assertTrue(parts.stream().anyMatch(p -> OcrProvenance.contains(p) && p.text().equals(primary)));
            assertTrue(parts.stream().anyMatch(p -> p.location().contains("OCR_PAGE_TIMEOUT") && p.text().isBlank()));
            assertTrue(OcrProvenance.incomplete(parts));
        }
    }

    @Test
    void pageAndDocumentTimeBudgetsFailWithoutReturningATruncatedSuccessfulDocument() throws Exception {
        var parser = new OcrDocumentParser(root, (file, timeout) -> "읽은 내용");
        assertTrue(OcrProvenance.incomplete(parser.parse(imagePdf(41, false), "application/pdf")));
        var expired = new OcrDocumentParser(root, (file, timeout) -> {
            fail("deadline must be checked first");
            return "";
        }, Duration.ZERO);
        assertTrue(OcrProvenance.incomplete(expired.parse(imagePdf(1, false), "application/pdf")));
        var hugeText = new OcrDocumentParser(root, (file, timeout) -> "x".repeat(1_000_001));
        assertTrue(OcrProvenance.incomplete(hugeText.parse(imagePdf(1, false), "application/pdf")));
    }

    @Test
    void longTextNoticeUsesImageBudgetOnlyForItsScannedAppendix() throws Exception {
        byte[] bytes;
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < 45; i++) {
                var page = new PDPage(new PDRectangle(400, 400));
                doc.addPage(page);
                try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    stream.newLineAtOffset(10, 300);
                    stream.showText("Native official announcement with sufficient readable text. ".repeat(3));
                    stream.endText();
                }
            }
            imagePage(doc, false);
            doc.save(output);
            bytes = output.toByteArray();
        }
        AtomicInteger calls = new AtomicInteger();
        var parser = new OcrDocumentParser(root, (file, timeout) -> {
            calls.incrementAndGet();
            return "스캔된 부록을 읽은 내용";
        });
        var parts = parser.parse(bytes, "application/pdf");
        assertEquals(1, calls.get());
        assertFalse(OcrProvenance.incomplete(parts));
        assertEquals(45, parts.stream().filter(p -> p.text().contains("Native official announcement")).count());
        assertTrue(parts.stream().anyMatch(p -> p.location().equals(OcrProvenance.MARKER + " 공고문 46쪽")));
    }

    @Test
    void fallbackDetectsNumericOnlyAndMixedPagesButDoesNotApplyToSpreadsheetZip() {
        assertTrue(OcrDocumentParser.needsFallback(List.of(new Part("1쪽", "완전한 본문".repeat(30)), new Part("2쪽", "123.45 6789")), "application/pdf"));
        assertFalse(OcrDocumentParser.needsFallback(List.of(new Part("1쪽", "완전한 본문".repeat(30))), "application/pdf"));
        assertFalse(OcrDocumentParser.needsFallback(List.of(new Part("Sheet1", "123")), "application/zip"));
    }

    @Test
    void plainNativeTextAndRestrictedHwpDoNotInvokeImageRecognition() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var parser = new OcrDocumentParser(root, (file, timeout) -> {
            calls.incrementAndGet();
            return "not expected";
        });
        assertFalse(parser.parse(DocumentTextParserTest.pdf("Native short text"), "application/pdf").stream().anyMatch(OcrProvenance::contains));
        assertThrows(IOException.class, () -> parser.parse(DocumentTextParserTest.hwp("", 2), "application/x-ole-storage"));
        assertEquals(0, calls.get());
    }

    @Test
    void aLaterPageFailureKeepsEarlierReadingsAndAnExplicitIncompleteCode() throws Exception {
        AtomicInteger count = new AtomicInteger();
        var parser = new OcrDocumentParser(root, (file, timeout) -> {
            if (count.incrementAndGet() == 2) throw new IOException("OCR_PAGE_TIMEOUT");
            return "앞쪽에서 읽은 조건 안내";
        });
        var parts = parser.parse(mixedPdf(), "application/pdf");
        assertTrue(parts.stream().anyMatch(p -> p.text().equals("앞쪽에서 읽은 조건 안내")));
        assertTrue(parts.stream().anyMatch(p -> p.text().contains("Native official announcement")));
        assertTrue(parts.stream().anyMatch(p -> p.location().contains("OCR_PAGE_TIMEOUT") && p.text().isBlank()));
        assertTrue(OcrProvenance.incomplete(parts));
        assertEquals("OCR_WORKER_FAILED", OcrDocumentParser.failureCode(new IOException("private-file-path-and-key")));
    }

    @Test
    void aSingleFullPageScanIsNotUpscaledBeyondItsNativeResolution() throws Exception {
        byte[] bytes;
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(new PDRectangle(400, 600));
            doc.addPage(page);
            var image = new BufferedImage(1000, 1500, BufferedImage.TYPE_BYTE_GRAY);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.drawImage(LosslessFactory.createFromImage(doc, image), 0, 0, 400, 600);
            }
            doc.save(output);
            bytes = output.toByteArray();
        }
        var parser = new OcrDocumentParser(root, (file, timeout) -> {
            var image = ImageIO.read(file.toFile());
            assertTrue(image.getWidth() <= 1000);
            assertTrue(image.getHeight() <= 1500);
            return "원본 해상도에서 읽은 내용";
        });
        assertFalse(OcrProvenance.incomplete(parser.parse(bytes, "application/pdf")));
    }

    @Test
    void impracticallyLargePageDimensionsAreRejectedBeforeRendering() throws Exception {
        byte[] bytes;
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            imagePage(doc, false);
            doc.getPage(0).setMediaBox(new PDRectangle(100_000, 100_000));
            doc.save(output);
            bytes = output.toByteArray();
        }
        var parser = new OcrDocumentParser(root, (file, timeout) -> {
            fail("must not allocate the huge bitmap");
            return "";
        });
        var parts = parser.parse(bytes, "application/pdf");
        assertTrue(parts.stream().anyMatch(p -> p.location().contains("OCR_IMAGE_DIMENSIONS") && p.text().isBlank()));
    }

    static byte[] mixedPdf() throws Exception {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(new PDRectangle(400, 400));
            doc.addPage(page);
            try (var stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                stream.newLineAtOffset(10, 300);
                stream.showText("Native official announcement with sufficient readable text. ".repeat(3));
                stream.endText();
            }
            imagePage(doc, false);
            imagePage(doc, true);
            doc.save(output);
            return output.toByteArray();
        }
    }

    static byte[] imagePdf(int count, boolean numeric) throws Exception {
        try (var doc = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < count; i++) imagePage(doc, numeric);
            doc.save(output);
            return output.toByteArray();
        }
    }

    private static void imagePage(PDDocument doc, boolean numeric) throws Exception {
        var page = new PDPage(new PDRectangle(400, 400));
        doc.addPage(page);
        BufferedImage image = new BufferedImage(200, 300, BufferedImage.TYPE_BYTE_GRAY);
        var g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 200, 300);
        g.dispose();
        var tile = LosslessFactory.createFromImage(doc, image);
        try (var stream = new PDPageContentStream(doc, page)) {
            stream.drawImage(tile, 0, 50, 200, 300);
            stream.drawImage(tile, 200, 50, 200, 300);
            if (numeric) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                stream.newLineAtOffset(10, 380);
                stream.showText("123.45");
                stream.endText();
            }
        }
    }

    static byte[] imageHwp() throws Exception {
        var image = new BufferedImage(200, 200, BufferedImage.TYPE_BYTE_GRAY);
        var encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        var compressed = new ByteArrayOutputStream();
        var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (var stream = new DeflaterOutputStream(compressed, deflater)) {
            stream.write(encoded.toByteArray());
        } finally {
            deflater.end();
        }
        try (var fs = new POIFSFileSystem(new ByteArrayInputStream(DocumentTextParserTest.hwp("123", 1))); var output = new ByteArrayOutputStream()) {
            fs.getRoot().createDirectory("BinData").createDocument("BIN0001.png", new ByteArrayInputStream(compressed.toByteArray()));
            fs.getRoot().createDocument("PrvImage", new ByteArrayInputStream(encoded.toByteArray()));
            fs.writeFilesystem(output);
            return output.toByteArray();
        }
    }
}
