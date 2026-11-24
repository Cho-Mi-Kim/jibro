package org.jibro.collection.extraction;

import java.util.HashSet;
import java.util.Set;
import javax.xml.stream.XMLStreamReader;

/**
 * Validates that an inline picture shows its original pixels without changing their meaning.
 */
final class HwpxPicture {
    private final Set<String> seen = new HashSet<>();
    private boolean valid = true;
    private int effectsDepth = -1;
    private double width, height;
    private double clipLeft, clipRight, clipTop, clipBottom, imageWidth, imageHeight;
    private final double[][] rectangle = new double[4][2];
    private String reference;

    void start(XMLStreamReader reader, int depth) {
        String name = reader.getLocalName();
        if (effectsDepth >= 0 && depth > effectsDepth) valid = false;
        switch (name) {
            case "pic" -> valid &= is(reader, "reverse", "0") && is(reader, "groupLevel", "0");
            case "orgSz" -> {
                unique(name);
                width = number(reader, "width");
                height = number(reader, "height");
            }
            case "flip" -> {
                unique(name);
                valid &= is(reader, "horizontal", "0") && is(reader, "vertical", "0");
            }
            case "rotationInfo" -> {
                unique(name);
                valid &= number(reader, "angle") == 0;
            }
            case "pos" -> {
                unique(name);
                valid &= is(reader, "treatAsChar", "1");
            }
            case "img" -> {
                unique(name);
                reference = reader.getAttributeValue(null, "binaryItemIDRef");
                valid &= is(reader, "bright", "0") && is(reader, "contrast", "0")
                        && is(reader, "effect", "REAL_PIC") && is(reader, "alpha", "0");
            }
            case "imgClip" -> {
                unique(name);
                clipLeft = number(reader, "left");
                clipRight = number(reader, "right");
                clipTop = number(reader, "top");
                clipBottom = number(reader, "bottom");
            }
            case "imgDim" -> {
                unique(name);
                imageWidth = number(reader, "dimwidth");
                imageHeight = number(reader, "dimheight");
            }
            case "pt0", "pt1", "pt2", "pt3" -> {
                unique(name);
                int index = name.charAt(2) - '0';
                rectangle[index][0] = number(reader, "x");
                rectangle[index][1] = number(reader, "y");
            }
            case "transMatrix", "scaMatrix", "rotMatrix" -> {
                unique(name);
                valid &= number(reader, "e2") == 0 && number(reader, "e4") == 0;
                double x = number(reader, "e1"), y = number(reader, "e5");
                valid &= name.equals("scaMatrix") ? x > 0 && y > 0 : x == 1 && y == 1;
            }
            case "effects" -> {
                unique(name);
                effectsDepth = depth;
            }
            default -> {
            }
        }
    }

    void end(String name) {
        if (name.equals("effects")) effectsDepth = -1;
    }

    String unmodifiedInlineImage() {
        if (!valid || reference == null || !seen.containsAll(Set.of("orgSz", "flip", "rotationInfo", "pos", "img",
                "imgClip", "imgDim", "pt0", "pt1", "pt2", "pt3", "transMatrix", "scaMatrix", "rotMatrix"))) return null;
        // Clipping uses the source image dimensions; imgRect/orgSz use the placed shape coordinates.
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0
                || clipLeft != 0 || clipTop != 0 || clipRight != imageWidth || clipBottom != imageHeight) return null;
        if (rectangle[0][0] != 0 || rectangle[0][1] != 0 || rectangle[1][0] != width || rectangle[1][1] != 0
                || rectangle[2][0] != width || rectangle[2][1] != height || rectangle[3][0] != 0 || rectangle[3][1] != height)
            return null;
        return reference;
    }

    private void unique(String name) {
        valid &= seen.add(name);
    }

    private static boolean is(XMLStreamReader reader, String attribute, String expected) {
        return expected.equals(reader.getAttributeValue(null, attribute));
    }

    private double number(XMLStreamReader reader, String attribute) {
        try {
            double value = Double.parseDouble(reader.getAttributeValue(null, attribute));
            if (!Double.isFinite(value)) valid = false;
            return value;
        } catch (NumberFormatException | NullPointerException missing) {
            valid = false;
            return Double.NaN;
        }
    }
}
