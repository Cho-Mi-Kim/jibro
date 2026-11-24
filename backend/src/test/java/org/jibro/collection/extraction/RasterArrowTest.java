package org.jibro.collection.extraction;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RasterArrowTest {
    @Test
    void oneClippedEdgePixelDoesNotHideTheOfficialProcedureDirection() throws Exception {
        try (var input = getClass().getResourceAsStream("/extraction/seoul-clipped-flow-arrow.png")) {
            var image = ImageIO.read(input);
            assertEquals("→", RasterArrow.read(image).direction());
            assertNull(RasterArrow.read(image.getSubimage(0, 0, image.getWidth() - 18, image.getHeight())),
                "A genuinely missing arrowhead cannot be reconstructed from a presumed direction.");
        }
    }

    @Test
    void sameOfficialArrowSurvivesPdfResamplingWithoutDiscardingItsDirection() throws Exception {
        BufferedImage hwpx = officialArrow();
        assertEquals("→", RasterArrow.read(hwpx).direction());
        try (var input = getClass().getResourceAsStream("/extraction/lh-multichild-two-up.pdf");
             var pdf = Loader.loadPDF(input.readAllBytes())) {
            var picture = (PDImageXObject) pdf.getPage(1).getResources().getXObject(COSName.getPDFName("Im2"));
            assertEquals("→", RasterArrow.read(picture.getImage()).direction());
        }
        var flipped = new BufferedImage(hwpx.getWidth(), hwpx.getHeight(), hwpx.getType());
        for (int y = 0; y < hwpx.getHeight(); y++)
            for (int x = 0; x < hwpx.getWidth(); x++)
                flipped.getRaster().setDataElements(hwpx.getWidth() - 1 - x, y, hwpx.getRaster().getDataElements(x, y, null));
        assertEquals("←", RasterArrow.read(flipped).direction());
        var clockwise = new BufferedImage(hwpx.getHeight(), hwpx.getWidth(), hwpx.getType());
        for (int y = 0; y < hwpx.getHeight(); y++)
            for (int x = 0; x < hwpx.getWidth(); x++)
                clockwise.getRaster().setDataElements(hwpx.getHeight() - 1 - y, x, hwpx.getRaster().getDataElements(x, y, null));
        assertEquals("↓", RasterArrow.read(clockwise).direction());
        try (var zip = new ZipInputStream(getClass().getResourceAsStream("/extraction/lh-sangok-69385652.hwpx"))) {
            BufferedImage small = null;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                if (entry.getName().equals("BinData/image3.jpg")) {
                    small = ImageIO.read(zip);
                    break;
                }
            assertNotNull(small);
            assertEquals("→", RasterArrow.read(small).direction());
        }
        try (var input = getClass().getResourceAsStream("/extraction/lh-sangok-69385666.pdf");
             var pdf = Loader.loadPDF(input.readAllBytes())) {
            var picture = (PDImageXObject) pdf.getPage(9).getResources().getXObject(COSName.getPDFName("Im5"));
            assertEquals("→", RasterArrow.read(picture.getImage()).direction());
        }
    }

    @Test
    void labelsCropAndOtherShapesAreNotSilentlyTurnedIntoAnUnlabelledArrow() throws Exception {
        BufferedImage labelled = officialArrow();
        var graphics = labelled.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(labelled.getWidth() / 2, labelled.getHeight() / 2, 4, 12);
        graphics.dispose();
        assertNull(RasterArrow.read(labelled));
        BufferedImage original = officialArrow();
        assertNull(RasterArrow.read(original.getSubimage(0, 0, original.getWidth() / 2, original.getHeight())));
        var triangle = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        graphics = triangle.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 100, 100);
        graphics.setColor(Color.GRAY);
        graphics.fillPolygon(new int[]{10, 90, 10}, new int[]{10, 50, 90}, 3);
        graphics.dispose();
        assertNull(RasterArrow.read(triangle));
    }

    @Test
    void numbersInsideTheUpperAndLowerArrowheadAreNotLost() {
        var arrow = new BufferedImage(101, 101, BufferedImage.TYPE_INT_RGB);
        var graphics = arrow.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 101, 101);
        graphics.setColor(Color.GRAY);
        graphics.fillPolygon(new int[]{10, 55, 55, 90, 55, 55, 10}, new int[]{35, 35, 10, 50, 90, 65, 65}, 7);
        graphics.dispose();
        assertEquals("→", RasterArrow.read(arrow).direction());
        for (int y : new int[]{22, 68}) {
            var labelled = new BufferedImage(101, 101, BufferedImage.TYPE_INT_RGB);
            graphics = labelled.createGraphics();
            graphics.drawImage(arrow, 0, 0, null);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(58, y, 2, 10);
            graphics.fillRect(57, y, 3, 2);
            graphics.dispose();
            assertNull(RasterArrow.read(labelled));
        }
        for (int x : new int[]{15, 40, 80}) {
            var labelled = new BufferedImage(101, 101, BufferedImage.TYPE_INT_RGB);
            graphics = labelled.createGraphics();
            graphics.drawImage(arrow, 0, 0, null);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(x, 40, 3, 21);
            graphics.dispose();
            assertNull(RasterArrow.read(labelled));
        }
    }

    private BufferedImage officialArrow() throws Exception {
        try (var zip = new ZipInputStream(getClass().getResourceAsStream("/extraction/lh-multichild-2026.hwpx"))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                if (entry.getName().equals("BinData/image1.jpg")) return ImageIO.read(zip);
        }
        throw new AssertionError("Official arrow missing");
    }
}
