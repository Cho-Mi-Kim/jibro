package org.jibro.collection.review.followup;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.jibro.collection.review.IntakeChannelDates.AgencyField;
import org.jibro.collection.review.IntakeChannelDates.Window;

/** Explicit visit-day intervals in a post already tied to this recruitment. */
final class OfficeVisitFields {
    private static final String DATE = "(?:(20\\d{2})\\s*[./년-]\\s*)?(\\d{1,2})\\s*[./월-]\\s*(\\d{1,2})[.일]?\\s*(?:\\([월화수목금토일](?:요일)?\\))?";
    private static final String TIME = "(\\d{1,2}):(\\d{2})";
    private static final String RANGE = "\\s*[~∼～–-]\\s*";
    private static final Pattern INTERVAL = Pattern.compile("^(?:[•▪■*-]|\\d+[.)])?\\s*(?:\\[([^\\]]{1,60})\\]\\s*)?"
        + "(현장(?:방문)?|방문)?\\s*(?:신청|접수)(?:\\s*접수)?\\s*(?:기간|일시|일정|일)\\s*[:：]?\\s*"
        + DATE + "(?:" + RANGE + DATE + ")?\\s*[,·]?\\s*(?:\\d+일간\\s*)?(?:매일\\s*)?\\(?\\s*"
        + TIME + RANGE + TIME + "\\s*\\)?\\s*(?:\\(?\\s*점심(?:시간)?\\s*[:(]?\\s*" + TIME + RANGE + TIME
        + "\\s*\\)?\\s*(?:제외|접수불가)\\s*\\)?)?\\s*[.]?$");
    private static final Pattern OFFICE = Pattern.compile("(?m)^\\s*(?:\\d+[.)]\\s*)?(?:신청|접수)\\s*장소\\s*[:：]\\s*[^\\r\\n]*(?:거주지|주소지|관할)[^\\r\\n]*(?:행정복지센터|주민센터)\\s*$");

    static boolean sameEditionFile(String filename, OfficialFollowupModels.Target target) {
        String name = Normalizer.normalize(filename, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        if (!name.matches("(?i).*(?:공고문|모집공고).*\\.(?:pdf|hwpx?)$")
            || AgencyIntakePost.separate(name, target.title()) || AgencyIntakePost.separate(target.title(), name)) return false;
        var site = OfficialFollowupRelationship.identity(target.title());
        return !site.scope().isEmpty() && site.scope().stream().anyMatch(s -> s.length() >= 4)
            && site.scope().stream().allMatch(name::contains) && !site.housing().isEmpty() && site.housing().stream().allMatch(name::contains);
    }

    static List<Window> read(String body, String url, LocalDate issued) {
        String text = Normalizer.normalize(body, Normalizer.Form.NFKC);
        boolean office = OFFICE.matcher(text).find();
        var locations = Pattern.compile("(?m)^\\s*(?:\\d+[.)]\\s*)?(?:신청|접수)\\s*장소\\s*[:：]\\s*(.+)$").matcher(text);
        var named = new LinkedHashSet<String>();
        while (locations.find()) {
            String place = locations.group(1).strip();
            if (!OFFICE.matcher("신청장소: " + place).matches()) named.add(place);
        }
        var result = new LinkedHashSet<Window>();
        if (text.matches("(?s).*(?:정정|변경)\\s*전\\s*[:：\\n].*")) return List.of();
        for (String line : text.lines().toList()) {
            var m = INTERVAL.matcher(line.strip());
            if (!m.matches() || m.group(3) == null || m.group(2) == null && !office) continue;
            try {
                int year = Integer.parseInt(m.group(3));
                LocalDate start = LocalDate.of(year, Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
                LocalDate end = m.group(7) == null ? start : LocalDate.of(m.group(6) == null ? year : Integer.parseInt(m.group(6)),
                    Integer.parseInt(m.group(7)), Integer.parseInt(m.group(8)));
                var open = time(m, 9); var close = time(m, 11);
                var pause = time(m, 13); var resume = time(m, 15);
                if (end.isBefore(start) || !open.isBefore(close) || pause != null && (!pause.isBefore(resume)
                    || pause.isBefore(open) || resume.isAfter(close))) continue;
                String qualifier = m.group(1) == null ? "" : m.group(1);
                if (!named.isEmpty()) {
                    if (named.size() != 1 || office) continue;
                    String place = named.iterator().next();
                    if (!qualifier.contains(place)) qualifier = (qualifier + " " + place).strip();
                }
                result.add(new Window("현장", qualifier, start, end, url, open, close,
                    issued, null, null, AgencyField.VISIT_DAILY, pause, resume));
            } catch (DateTimeException invalid) { /* An unverified clock is not an opening interval. */ }
        }
        return List.copyOf(result);
    }

    private static LocalTime time(java.util.regex.Matcher m, int index) {
        return m.group(index) == null ? null : LocalTime.of(Integer.parseInt(m.group(index)), Integer.parseInt(m.group(index + 1)));
    }
    private OfficeVisitFields() {}
}
