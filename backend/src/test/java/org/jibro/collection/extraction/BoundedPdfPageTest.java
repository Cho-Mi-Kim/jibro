package org.jibro.collection.extraction;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedPdfPageTest {
    @TempDir
    Path root;

    private byte[] pdf() throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (Color color : List.of(Color.RED, Color.BLUE)) {
                var page = new PDPage(new PDRectangle(300, 400));
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.setNonStrokingColor(color);
                    content.addRect(0, 0, 300, 400);
                    content.fill();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void actualPackagedWorkerReturnsTheSelectedPageAndPreservesNotFound() throws Exception {
        var parser = new BoundedDocumentParser();
        byte[] pdf = pdf();
        var first = ImageIO.read(new ByteArrayInputStream(parser.renderPdfPage(pdf, 1)));
        var second = ImageIO.read(new ByteArrayInputStream(parser.renderPdfPage(pdf, 2)));
        assertEquals(450, first.getWidth());
        assertEquals(600, first.getHeight());
        assertEquals(Color.RED.getRGB(), first.getRGB(100, 100));
        assertEquals(Color.BLUE.getRGB(), second.getRGB(100, 100));
        assertTrue(assertThrows(PdfPageRenderer.PageException.class, () -> parser.renderPdfPage(pdf, 3)).missing());
        assertThrows(IOException.class, () -> parser.renderPdfPage(new byte[]{1, 2, 3}, 1));
        assertNotNull(ImageIO.read(new ByteArrayInputStream(parser.renderPdfPage(pdf, 1))));
    }

    @Test
    void existingOfficialPdfFixtureStillRendersWithTheSameWorkerBudget() throws Exception {
        try (var input = getClass().getResourceAsStream("/extraction/lh-multichild-two-up.pdf")) {
            assertNotNull(input);
            var png = new BoundedDocumentParser().renderPdfPage(input.readAllBytes(), 1);
            var image = ImageIO.read(new ByteArrayInputStream(png));
            assertNotNull(image);
            assertTrue(image.getWidth() <= 2400 && image.getHeight() <= 2400);
        }
    }

    @Test
    void timeAndHeapLimitsKillTheChildAndLeaveTheParentAbleToRender() throws Exception {
        for (String mode : List.of("hang", "memory", "flood")) {
            Path pid = root.resolve(mode + ".pid");
            var parser = new BoundedDocumentParser(Duration.ofSeconds(mode.equals("hang") ? 2 : 5), root, (directory, input, type) -> {
                var command = BoundedDocumentParser.javaCommand(directory);
                command.addAll(List.of("-cp", System.getProperty("jibro.parser.test-classpath"), ParserTestWorker.class.getName(), mode, pid.toString()));
                return command;
            });
            if (mode.equals("hang")) assertThrows(TimeoutException.class, () -> parser.renderPdfPage(pdf(), 1));
            else assertThrows(IOException.class, () -> parser.renderPdfPage(pdf(), 1));
            assertTrue(Files.isRegularFile(pid));
            long child = Long.parseLong(Files.readString(pid));
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            try (var files = Files.list(root)) {
                assertTrue(files.noneMatch(Files::isDirectory));
            }
        }
        assertNotNull(ImageIO.read(new ByteArrayInputStream(new BoundedDocumentParser().renderPdfPage(pdf(), 1))));
    }

    @Test
    void invalidOrExcessiveIpcOutputIsNeverSentAsAnImage() {
        assertThrows(IOException.class, () -> PdfPageRenderer.image(new byte[]{0, 1, 2}));
        assertThrows(IOException.class, () -> PdfPageRenderer.image(new byte[PdfPageRenderer.MAX_OUTPUT + 1]));
        assertThrows(IOException.class, () -> new BoundedDocumentParser().renderPdfPage(new byte[20 * 1024 * 1024 + 1], 1));
    }
}
