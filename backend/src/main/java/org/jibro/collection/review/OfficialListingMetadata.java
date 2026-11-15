package org.jibro.collection.review;

import java.util.Set;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.Source;

/** Recognizes only complete listing fields, never qualification prose or attachment filenames. */
final class OfficialListingMetadata {
    private static final String DATE = "20\\d{2}[.년/-]\\d{1,2}[.월/-]\\d{1,2}[.일]?";
    private static final String RANGE = "(?:" + DATE + ")?[~∼〜～–—-](?:" + DATE + ")?";
    private static final Pattern FIELD = Pattern.compile(
        "공고상태(?:공고중|접수중|접수마감|접수종료|접수예정|공고종료|정정공고|공고취소)"
        + "|유형(?:국민임대|영구임대|행복주택|공공임대|공공임대\\(50년\\)|매입임대|전세임대|통합공공임대)"
        + "|공고일:?" + DATE
        + "|(?:접수기간|서류접수기간|서류제출기간|계약기간):?(?:" + RANGE + "|" + DATE + ")"
        + "|(?:서류제출대상자발표일|당첨자발표일|결과발표일):?" + DATE);

    private OfficialListingMetadata() {}

    static boolean onlyFields(Source source) {
        if (!"공식상세본문".equals(compact(source.label()))
            || !Set.of("EXTRACTED", "EMPTY").contains(source.status())
            || source.sha256() == null || source.context().isEmpty()) return false;
        // An excerpt not accounted for by the field grammar must not disappear with its container.
        return source.context().stream().allMatch(part -> part != null && FIELD.matcher(compact(part.text())).matches())
            && source.excerpts().stream().allMatch(excerpt -> excerpt != null
                && FIELD.matcher(compact(excerpt.text())).matches());
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\p{Zs}]+", "");
    }
}
