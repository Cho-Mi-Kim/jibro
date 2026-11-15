package org.jibro.collection.extraction;

import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Part;

/** Rejects text JSONB cannot represent; no source character is silently removed or replaced. */
final class ExtractionText {
    static final String FAILURE_MESSAGE = "원문 텍스트에 지원하지 않는 문자가 있어 발췌하지 못했습니다. 원문을 직접 확인해 주세요.";
    private static final int MAX_HTML = 2 * 1024 * 1024;
    private static final int MAX_PARTS = 1000;
    private static final int MAX_CHARS = 1_000_000;

    private ExtractionText() {}

    static final class Invalid extends IllegalArgumentException {
        Invalid(String reason) { super(reason); }
    }

    static void html(String text) {
        characters(text, MAX_HTML);
    }

    static void parts(List<Part> parts) {
        if (parts == null || parts.size() > MAX_PARTS) throw new Invalid("SOURCE_TEXT_LIMIT");
        int remaining = MAX_CHARS;
        for (Part part : parts) {
            if (part == null) throw new Invalid("SOURCE_TEXT_MISSING");
            characters(part.location(), 1024);
            characters(part.text(), remaining);
            remaining -= part.text().length();
        }
    }

    private static void characters(String text, int limit) {
        if (text == null) throw new Invalid("SOURCE_TEXT_MISSING");
        if (text.length() > limit) throw new Invalid("SOURCE_TEXT_LIMIT");
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == 0) throw new Invalid("SOURCE_TEXT_NUL");
            if (Character.isHighSurrogate(value)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new Invalid("SOURCE_TEXT_SURROGATE");
                }
                index++;
            } else if (Character.isLowSurrogate(value)) throw new Invalid("SOURCE_TEXT_SURROGATE");
        }
    }
}
