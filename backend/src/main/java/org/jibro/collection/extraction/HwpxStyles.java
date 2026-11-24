package org.jibro.collection.extraction;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Header-defined paragraph markers, counters and explicit cell borders, read once per document.
 */
final class HwpxStyles {
    private static final int MAX_LEVELS = 10;
    private static final int MAX_DEPTH = 128;
    private static final Pattern REFERENCE = Pattern.compile("\\^(10|[1-9])");
    private final Map<String, Style> styles;
    private final Map<String, String> bullets;
    private final Map<String, Numbering> numberings;
    private final Map<String, Borders> borders;
    private final Map<String, int[]> counters = new HashMap<>();

    private HwpxStyles(Map<String, Style> styles, Map<String, String> bullets, Map<String, Numbering> numberings, Map<String, Borders> borders) {
        this.styles = Map.copyOf(styles);
        this.bullets = Map.copyOf(bullets);
        this.numberings = Map.copyOf(numberings);
        this.borders = Map.copyOf(borders);
    }

    static HwpxStyles empty() {
        return new HwpxStyles(Map.of(), Map.of(), Map.of(), Map.of());
    }

    HwpxStyles separateStory() {
        return new HwpxStyles(styles, bullets, numberings, borders);
    }

    static HwpxStyles read(byte[] header) throws XMLStreamException, IOException {
        var reader = HwpxStructuredText.xmlReader(header);
        var styles = new HashMap<String, Style>();
        var bullets = new HashMap<String, String>();
        var numberings = new HashMap<String, Numbering>();
        var borders = new HashMap<String, Borders>();
        String borderFill = null;
        String paragraph = null;
        Numbering numbering = null;
        int level = -1, start = -1, depth = 0;
        String format = null;
        StringBuilder template = null;
        try {
            while (reader.hasNext()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("문서 추출 중단");
                int event = reader.next();
                if (event == XMLStreamConstants.DTD) throw new XMLStreamException("DTD 금지");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > MAX_DEPTH) throw new IOException("XML 중첩 제한 초과");
                    switch (reader.getLocalName()) {
                        case "borderFill" -> {
                            borderFill = attribute(reader, "id");
                            put(borders, borderFill, new Borders("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", Map.of()));
                        }
                        case "leftBorder", "rightBorder", "topBorder", "bottomBorder" -> {
                            if (borderFill != null) {
                                Borders previous = borders.get(borderFill);
                                String side = reader.getLocalName().replace("Border", "");
                                String type = attribute(reader, "type"), edge = border(type);
                                var widths = new java.util.LinkedHashMap<>(previous.doubleWidths());
                                String width = attribute(reader, "width");
                                if ("DOUBLE_SLIM".equals(type) && width != null
                                        && width.replace(" ", "").matches("[0-9]+(?:\\.[0-9]+)?mm"))
                                    widths.put(side, width.replace(" ", ""));
                                borders.put(borderFill, new Borders(side.equals("left") ? edge : previous.left(),
                                        side.equals("right") ? edge : previous.right(), side.equals("top") ? edge : previous.top(),
                                        side.equals("bottom") ? edge : previous.bottom(), Map.copyOf(widths)));
                            }
                        }
                        case "bullet" -> {
                            String symbol = attribute(reader, "char");
                            boolean literal = "0".equals(attribute(reader, "useImage"))
                                    && symbol != null && symbol.codePointCount(0, symbol.length()) == 1
                                    && !Character.isWhitespace(symbol.codePointAt(0)) && !Character.isISOControl(symbol.codePointAt(0))
                                    && symbol.indexOf('⟦') < 0 && symbol.indexOf('⟧') < 0;
                            put(bullets, attribute(reader, "id"), literal ? symbol : "");
                        }
                        case "paraPr" -> {
                            paragraph = attribute(reader, "id");
                            put(styles, paragraph, new Style("UNKNOWN", "", -1));
                        }
                        case "heading" -> {
                            if (paragraph != null) styles.put(paragraph, new Style(attribute(reader, "type"),
                                    attribute(reader, "idRef"), integer(reader, "level")));
                        }
                        case "numbering" -> {
                            numbering = new Numbering(integer(reader, "start"), new HashMap<>());
                            put(numberings, attribute(reader, "id"), numbering);
                        }
                        case "paraHead" -> {
                            if (numbering != null) {
                                level = integer(reader, "level");
                                start = integer(reader, "start");
                                format = attribute(reader, "numFormat");
                                template = new StringBuilder();
                            }
                        }
                        default -> {
                        }
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && template != null) {
                    template.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "borderFill" -> borderFill = null;
                        case "paraPr" -> paragraph = null;
                        case "numbering" -> numbering = null;
                        case "paraHead" -> {
                            if (numbering != null && template != null) {
                                if (level < 1 || level > MAX_LEVELS || numbering.levels().putIfAbsent(level,
                                        new Level(start, format, template.toString())) != null)
                                    throw new IOException("HWPX 번호 수준 정의 확인 필요");
                                template = null;
                            }
                        }
                        default -> {
                        }
                    }
                    depth--;
                }
            }
        } finally {
            reader.close();
        }
        return new HwpxStyles(styles, bullets, numberings, borders);
    }

    Borders borders(String reference) {
        return reference == null ? new Borders("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", Map.of())
                : borders.getOrDefault(reference, new Borders("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", Map.of()));
    }

    private static String border(String type) {
        if (type == null) return "UNKNOWN";
        return switch (type) {
            case "NONE" -> "NONE";
            case "SOLID", "DASH", "DOT", "DASH_DOT", "DASH_DOT_DOT", "LONG_DASH", "CIRCLE",
                 "DOUBLE_SLIM", "SLIM_THICK", "THICK_SLIM", "SLIM_THICK_SLIM", "WAVE", "DOUBLE_WAVE" -> "DRAWN";
            default -> "UNKNOWN";
        };
    }

    record Borders(String left, String right, String top, String bottom, Map<String, String> doubleWidths) {
        String metadata() {
            String value = left.equals("NONE") || right.equals("NONE") ? " left=" + left + " right=" + right : "";
            if (top.equals("NONE") || bottom.equals("NONE")) value += " top=" + top + " bottom=" + bottom;
            for (String side : java.util.List.of("left", "right", "top", "bottom"))
                if (doubleWidths.containsKey(side)) value += " double" + side + "=" + doubleWidths.get(side);
            return value;
        }
    }

    String prefix(String reference, String separateRegion) throws IOException {
        if (reference == null) return "";
        Style style = styles.get(reference);
        if (style == null || style.type() == null) throw unresolved();
        return switch (style.type()) {
            case "NONE" -> "";
            case "BULLET" -> {
                String bullet = style.reference() == null ? null : bullets.get(style.reference());
                if (bullet == null || bullet.isEmpty()) throw unresolved();
                yield bullet;
            }
            case "NUMBER" -> {
                if (separateRegion != null) throw new IOException("HWPX 별도 영역 자동번호 확인 필요: " + separateRegion);
                yield numbered(style);
            }
            default -> throw unresolved();
        };
    }

    private String numbered(Style style) throws IOException {
        Numbering definition = style.reference() == null ? null : numberings.get(style.reference());
        int index = style.level();
        if (definition == null || index < 0 || index >= MAX_LEVELS || definition.start() <= 0) throw unresolved();
        Level level = definition.levels().get(index + 1);
        if (level == null || !"DIGIT".equals(level.format()) || level.start() <= 0
                || index == 0 && definition.start() != level.start()) throw unresolved();
        String template = level.template();
        if (template.isBlank() || template.indexOf('⟦') >= 0 || template.indexOf('⟧') >= 0) throw unresolved();
        var matcher = REFERENCE.matcher(template);
        int[] values = counters.computeIfAbsent(style.reference(), unused -> new int[MAX_LEVELS]);
        if (values[index] == Integer.MAX_VALUE) throw unresolved();
        values[index] = values[index] == 0 ? level.start() : values[index] + 1;
        Arrays.fill(values, index + 1, MAX_LEVELS, 0);
        StringBuilder result = new StringBuilder();
        boolean own = false;
        int previous = 0;
        while (matcher.find()) {
            int target = Integer.parseInt(matcher.group(1)) - 1;
            Level referenced = definition.levels().get(target + 1);
            if (target > index || values[target] <= 0 || referenced == null || !"DIGIT".equals(referenced.format()))
                throw unresolved();
            result.append(template, previous, matcher.start()).append(values[target]);
            own |= target == index;
            previous = matcher.end();
        }
        result.append(template.substring(previous));
        if (!own || result.indexOf("^") >= 0) throw unresolved();
        return result.toString();
    }

    private static <T> void put(Map<String, T> values, String id, T value) throws IOException {
        if (id == null || values.putIfAbsent(id, value) != null) throw new IOException("HWPX 목록 스타일 중복·식별자 확인 필요");
    }

    private static String attribute(XMLStreamReader reader, String name) {
        return reader.getAttributeValue(null, name);
    }

    private static int integer(XMLStreamReader reader, String name) throws IOException {
        String value = attribute(reader, name);
        if (value == null) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IOException("HWPX 번호 설정 확인 필요");
        }
    }

    private static IOException unresolved() {
        return new IOException("HWPX 문단 목록 스타일 확인 필요");
    }

    private record Style(String type, String reference, int level) {
    }

    private record Numbering(int start, Map<Integer, Level> levels) {
    }

    private record Level(int start, String format, String template) {
    }
}
