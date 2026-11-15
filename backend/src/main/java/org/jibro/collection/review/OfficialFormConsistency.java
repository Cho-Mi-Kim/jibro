package org.jibro.collection.review;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.Evidence;

/** Checks a form's explicit reference to this recruitment, not arbitrary historical/legal dates. */
public final class OfficialFormConsistency {
    private OfficialFormConsistency() {}
    private static final Pattern PUBLICATION = Pattern.compile("^(?:[（(]?(?:입주자모집공고일|공고일))[:：]?[（(]?(20\\d{2})[.년/-](\\d{1,2})[.월/-](\\d{1,2})(?:[.일）)].*)?$");
    private static final Pattern FORM_REFERENCE = Pattern.compile("공고[（(][‘’'`]?((?:20)?\\d{2})[.년/-](\\d{1,2})[.월/-](\\d{1,2})[.일]?[）)]에따른입주자로서");

    public static List<GuidanceFinding> inspect(Draft draft) {
        if (draft == null || draft.sources() == null || draft.checkedAt() == null) return List.of();
        var dates = new LinkedHashSet<LocalDate>();
        for (Source source : draft.sources()) {
            // EMPTY means no classified excerpt, not necessarily an unread listing. Only the
            // independently verified metadata grammar can supply anchors in that state.
            boolean verifiedEmptyListing = "EMPTY".equals(source.status()) && OfficialListingMetadata.onlyFields(source);
            if (!"EXTRACTED".equals(source.status()) && !verifiedEmptyListing) continue;
            // A legal/example date deep in an attachment is not the current recruitment date.
            // Listing field parts and standalone first-page labels are the only primary anchors.
            boolean listing = compact(source.label()).equals("공식상세본문");
            var anchors = listing ? source.context() : source.label().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                ? source.context().stream().filter(p -> p.location().matches("(?:공고문\\s*)?1\\s*쪽")).toList() : List.<Part>of();
            for (Part part : anchors) {
                for (String line : part.text().split("\\R")) {
                    var matcher = PUBLICATION.matcher(compact(line));
                    if (matcher.matches()) {
                        LocalDate date = date(matcher.group(1), matcher.group(2), matcher.group(3));
                        if (date != null) dates.add(date);
                    }
                }
            }
        }
        var findings = new ArrayList<GuidanceFinding>();
        String checked = draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        if (dates.size() > 1) {
            Source source = draft.sources().get(0);
            findings.add(new GuidanceFinding("PUBLICATION_DATE_CONFLICT", "본문의 모집공고일이 서로 다릅니다: " + dates,
                new Evidence(source.label(), source.url(), checked)));
        }
        if (dates.size() != 1) return List.copyOf(findings);
        LocalDate publication = dates.iterator().next();
        for (Source source : draft.sources()) {
            if (!"EXTRACTED".equals(source.status())) continue;
            for (Part part : source.context()) {
                String text = compact(part.text());
                if (!text.contains("확약서") && !text.contains("확인서")) continue;
                var matcher = FORM_REFERENCE.matcher(text);
                while (matcher.find()) {
                    LocalDate referenced = date(matcher.group(1), matcher.group(2), matcher.group(3));
                    if (referenced == null || !publication.equals(referenced))
                        findings.add(new GuidanceFinding("FORM_PUBLICATION_DATE_MISMATCH",
                            "모집공고일은 " + publication + "이지만 첨부 서식은 " + (referenced == null ? matcher.group() : referenced)
                                + " 공고에 따른 입주자라고 명시합니다. 날짜를 임의로 고치지 않고 기관 확인이 필요합니다.",
                            new Evidence(source.label() + " · " + part.location(), EvidenceLocationUrl.forSource(source, part.location(), part.text().lines()
                                .filter(line -> FORM_REFERENCE.matcher(compact(line)).find()).findFirst().orElse("")), checked)));
                }
            }
        }
        return List.copyOf(findings);
    }

    private static LocalDate date(String year, String month, String day) {
        try { return LocalDate.of(Integer.parseInt(year.length() == 2 ? "20" + year : year), Integer.parseInt(month), Integer.parseInt(day)); }
        catch (DateTimeException | NumberFormatException invalid) { return null; }
    }
    private static String compact(String text) { return text.replaceAll("[\\s\\p{Zs}]+", ""); }
}
