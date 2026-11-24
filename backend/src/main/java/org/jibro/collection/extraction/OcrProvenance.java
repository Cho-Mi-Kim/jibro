package org.jibro.collection.extraction;

import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;

/**
 * OCR is a reading aid; its confidence never certifies a qualification rule or a date.
 */
public final class OcrProvenance {
    public static final String MARKER = "[OCR · 원문 대조 필요]";
    public static final String REVIEW_REQUIRED = "OCR_SOURCE_REQUIRES_REVIEW";

    private OcrProvenance() {
    }

    public static boolean contains(Part part) {
        return part != null && part.location() != null && part.location().contains(MARKER);
    }

    public static boolean contains(Source source) {
        return source != null && source.context().stream().anyMatch(OcrProvenance::contains);
    }

    public static boolean incomplete(List<Part> parts) {
        return parts.stream().anyMatch(part -> contains(part) && (part.text() == null || part.text().isBlank()));
    }
}
