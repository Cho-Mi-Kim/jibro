package org.jibro.collection.extraction;

import java.awt.image.BufferedImage;
import java.awt.color.ColorSpace;
import java.util.Arrays;

/**
 * Reads only a plain, unlabelled, grayscale shaft-and-triangle arrow, not arbitrary artwork.
 */
final class RasterArrow {
    private static final int WHITE_THRESHOLD = 240;
    // JPEG resampling produces a few levels of noise in a flat/gradient fill. A label produces edges.
    private static final int MAX_FILL_VARIATION = 32;
    private static final int MIN_SIDE_PIXELS = 20;

    private RasterArrow() {
    }

    record Reading(String direction, int left, int top, int width, int height) {
    }

    static Reading read(BufferedImage image) {
        if (image == null || image.getWidth() < MIN_SIDE_PIXELS || image.getHeight() < MIN_SIDE_PIXELS
            || (long) image.getWidth() * image.getHeight() > OcrDocumentParser.MAX_IMAGE_PIXELS) return null;
        int width = image.getWidth(), height = image.getHeight();
        boolean nativeGray = image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY
            && image.getRaster().getNumBands() == 1 && image.getSampleModel().getSampleSize(0) == 8;
        int[][] pixels = new int[height][width];
        int left = width, right = -1, top = height, bottom = -1;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y), alpha = argb >>> 24;
                int red = composite(argb >> 16 & 255, alpha), green = composite(argb >> 8 & 255, alpha), blue = composite(argb & 255, alpha);
                if (Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > 12) return null;
                // ImageIO's built-in gray-to-sRGB conversion brightens a grayscale JPEG's gradient.
                // Use its native 8-bit intensity, as with the grayscale samples exported into a PDF.
                int value = nativeGray ? image.getRaster().getSample(x, y, 0) : (red + green + blue) / 3;
                pixels[y][x] = value;
                if (value < WHITE_THRESHOLD) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        if (right - left + 1 < MIN_SIDE_PIXELS || bottom - top + 1 < MIN_SIDE_PIXELS) return null;
        int[][] cropped = new int[bottom - top + 1][right - left + 1];
        for (int y = top; y <= bottom; y++)
            System.arraycopy(pixels[y], left, cropped[y - top], 0, right - left + 1);
        String found = null;
        String[] directions = {"→", "↓", "←", "↑"};
        for (int turn = 0; turn < directions.length; turn++) {
            if (rightArrow(cropped)) {
                if (found != null) return null;
                found = directions[turn];
            }
            cropped = counterclockwise(cropped);
        }
        return found == null ? null : new Reading(found, left, top, right - left + 1, bottom - top + 1);
    }

    private static int composite(int color, int alpha) {
        return (color * alpha + 255 * (255 - alpha) + 127) / 255;
    }

    private static boolean rightArrow(int[][] pixels) {
        int height = pixels.length, width = pixels[0].length;
        int[] left = new int[height], right = new int[height], rawRight = new int[height];
        Arrays.fill(left, width);
        Arrays.fill(right, -1);
        Arrays.fill(rawRight, -1);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                if (pixels[y][x] < WHITE_THRESHOLD) {
                    rawRight[y] = x;
                    if (!contourInk(pixels, x, y)) continue;
                    left[y] = Math.min(left[y], x);
                    right[y] = x;
                }
        if (Arrays.stream(right).anyMatch(x -> x < 0)) return false;
        double shoulder = (right[0] + right[height - 1]) / 2.0;
        // Contours and PDF clipping are quantized to whole pixels; a fractional pixel limit
        // must not change the reading when one edge pixel is clipped from the same picture.
        double tolerance = Math.max(3, Math.ceil(width * .06));
        if (shoulder < width * .25 || shoulder > width * .75 || Math.abs(right[0] - right[height - 1]) > tolerance)
            return false;
        double center = (height - 1) / 2.0;
        for (int y = 0; y < height; y++) {
            double edge = shoulder + (width - 1 - shoulder) * (1 - Math.abs(y - center) / center);
            // One-pixel JPEG ringing at the tip is not a second shape. Keep both the raw bound
            // and the three-row median so a broad protrusion cannot be smoothed away.
            int[] neighborhood = {right[Math.max(0, y - 1)], right[y], right[Math.min(height - 1, y + 1)]};
            Arrays.sort(neighborhood);
            if (Math.abs(rawRight[y] - edge) > tolerance + 2 || Math.abs(neighborhood[1] - edge) > tolerance)
                return false;
        }
        // Outside the horizontal shaft, the arrowhead has one straight vertical rear edge.
        int upper = 0, lower = height - 1;
        while (upper < height && left[upper] >= shoulder - tolerance) upper++;
        while (lower >= 0 && left[lower] >= shoulder - tolerance) lower--;
        if (upper < height * .10 || upper > height * .40 || lower < height * .60 || lower > height * .90
            || Math.abs(upper - (height - 1 - lower)) > Math.max(3, height * .06)) return false;
        for (int y = 0; y < upper; y++) if (Math.abs(left[y] - shoulder) > tolerance) return false;
        for (int y = lower + 1; y < height; y++) if (Math.abs(left[y] - shoulder) > tolerance) return false;
        int middleFrom = (int) (height * .35), middleTo = (int) (height * .65);
        int shaftLeft = Arrays.stream(left, middleFrom, middleTo).sorted().skip((middleTo - middleFrom) / 2).findFirst().orElse(width);
        if (shoulder - shaftLeft < width * .20) return false;
        for (int y = middleFrom; y < middleTo; y++) if (Math.abs(left[y] - shaftLeft) > tolerance) return false;
        int borderMinimum = 255, interiorMinimum = 255;
        for (int y = 0; y < height; y++) {
            // The outer antialiased sample can be lighter than the outline directly inside it.
            for (int x : new int[]{left[y], Math.min(left[y] + 1, right[y]), Math.max(left[y], right[y] - 1), right[y]})
                borderMinimum = Math.min(borderMinimum, pixels[y][x]);
            for (int x = left[y] + 2; x < right[y] - 1; x++) interiorMinimum = Math.min(interiorMinimum, pixels[y][x]);
        }
        // A darker internal label cannot become the arrow's outline merely because it occurs
        // before the fill in a column with no printed outline.
        if (borderMinimum - interiorMinimum > MAX_FILL_VARIATION) return false;
        // The supported fill is flat or fades from its tail towards its head. Read the entire
        // centre, including both ends; no fixed inset may hide an inscription near the shaft.
        int darkest = 255;
        for (int x = 0; x < width; x++) {
            int fill = (pixels[(height - 1) / 2][x] + pixels[height / 2][x]) / 2;
            // The single tip boundary sample mixes foreground and white; every interior
            // sample, including the shaft ends, must still follow the supported fill.
            if (x < width - 1 && fill - darkest > MAX_FILL_VARIATION) return false;
            darkest = Math.min(darkest, fill);
            // Away from the two antialiased boundary pixels, the same horizontal fill must
            // continue through the shaft and triangle. An outline may darken it, not whiten it.
            double first = x < shoulder ? upper : (x - shoulder) / (width - 1 - shoulder) * center;
            double last = x < shoulder ? lower : height - 1 - first;
            for (int y = (int) Math.ceil(first + 2); y <= last - 2; y++)
                if (pixels[y][x] - fill > MAX_FILL_VARIATION) return false;
        }
        // From either outside edge towards the centre, a plain outline has one dark edge,
        // then a flat/gradient fill. A second dark stroke is text, even near the arrowhead.
        for (int x = 0; x < width; x++) {
            if (!plainFill(pixels, x, 0, (height + 1) / 2, 1)
                || !plainFill(pixels, x, height - 1, height / 2 - 1, -1)) return false;
        }
        return true;
    }

    private static boolean contourInk(int[][] pixels, int x, int y) {
        if (pixels[y][x] < WHITE_THRESHOLD - MAX_FILL_VARIATION) return true;
        int adjacent = 0, neighborX = 0, neighborY = 0;
        for (int row = Math.max(0, y - 1); row <= Math.min(pixels.length - 1, y + 1); row++)
            for (int col = Math.max(0, x - 1); col <= Math.min(pixels[0].length - 1, x + 1); col++)
                if ((col != x || row != y) && pixels[row][col] < WHITE_THRESHOLD) {
                    adjacent++;
                    neighborX = col;
                    neighborY = row;
                }
        if (adjacent > 1) return true;
        if (adjacent == 0) return false;
        if (pixels[neighborY][neighborX] < WHITE_THRESHOLD - MAX_FILL_VARIATION) return true;
        // Only a disconnected one/two-pixel faint JPEG component is ignored for the
        // contour. Raw extents and every original pixel still enter the shape/fill checks.
        for (int row = Math.max(0, neighborY - 1); row <= Math.min(pixels.length - 1, neighborY + 1); row++)
            for (int col = Math.max(0, neighborX - 1); col <= Math.min(pixels[0].length - 1, neighborX + 1); col++)
                if ((col != x || row != y) && (col != neighborX || row != neighborY) && pixels[row][col] < WHITE_THRESHOLD)
                    return true;
        return false;
    }

    private static boolean plainFill(int[][] pixels, int x, int from, int until, int step) {
        int minimum = 255, recoveredFill = -1;
        int fill = (pixels[(pixels.length - 1) / 2][x] + pixels[pixels.length / 2][x]) / 2;
        boolean started = false, recovered = false;
        for (int y = from; y != until; y += step) {
            int value = pixels[y][x];
            if (!started) {
                if (value >= WHITE_THRESHOLD) continue;
                started = true;
            }
            if (!recovered) {
                minimum = Math.min(minimum, value);
                if (value - minimum > MAX_FILL_VARIATION) {
                    recovered = true;
                    recoveredFill = Math.min(fill, value);
                }
            } else if (recoveredFill - value > MAX_FILL_VARIATION) {
                // Compare with the actual centre fill, not a JPEG ringing peak at the edge.
                // Otherwise a bright overshoot followed by a small undershoot looks like text.
                return false;
            } else {
                recoveredFill = Math.min(fill, Math.max(recoveredFill, value));
            }
        }
        return true;
    }

    private static int[][] counterclockwise(int[][] pixels) {
        int height = pixels.length, width = pixels[0].length;
        int[][] rotated = new int[width][height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) rotated[width - 1 - x][y] = pixels[y][x];
        return rotated;
    }
}
