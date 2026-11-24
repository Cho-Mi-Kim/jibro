package org.jibro.collection.review.followup;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jibro.collection.review.IntakeChannelDates.AgencyField;
import org.jibro.collection.review.IntakeChannelDates.Office;
import org.jibro.collection.review.IntakeChannelDates.Window;
import org.jsoup.nodes.Element;

final class ReceptionDeskFields {
    private static final String DESK = "[가-힣][가-힣0-9]{1,15}(?:동|읍|면)\\s*(?:행정복지센터|주민센터)";
    private static final String DATE = "(?:(20\\d{2})\\s*[.년/-]\\s*)?(\\d{1,2})\\s*[.월/-]\\s*(\\d{1,2})[.일]?\\s*(?:\\([월화수목금토일]\\))?";
    private static final Pattern PERIOD = Pattern.compile("(?m)^[\\h\\d.)*-]*(?:방문\\s*)?(?:신청|접수)\\h*기간\\s*[:：*-]?\\s*"
        + DATE + "\\h*[~∼～]\\h*" + DATE + "\\h*$");
    private static final Pattern BREAK = Pattern.compile("(?m)^\\h*[-*]?\\h*(?:(" + DESK + ")\\h*[:：·]?\\h*)?점심(?:시간)?\\h*[:(]?\\h*"
        + "(\\d{1,2}:\\d{2})\\h*[~∼～-]\\h*(\\d{1,2}:\\d{2})\\h*\\)?\\h*(?:제외|접수\\h*불가)\\h*$");
    private static final Pattern COUNTER = Pattern.compile("(?m)^\\h*(?:\\d+[.)]?\\h*)?(?:신청\\h*)?접수\\h*장소\\h*[:：]\\h*([^\\r\\n:：]{1,55}?(?:관리사무소|행정복지센터|주민센터))\\h*$");
    private static final Pattern CONTACT = Pattern.compile("(?m)^\\h*(?:문의|접수처)\\h*[:：]\\h*(" + DESK + ")(?:\\h*\\(?[\\d -]+\\)?)?\\h*$");
    private static final Pattern COMPLEX = Pattern.compile("([가-힣]{2,}\\d+)(?:단지)?");
    private ReceptionDeskFields() {}

    static boolean namesComplex(String text, String complex) {
        return Pattern.compile(Pattern.quote(complex) + "(?!\\d)").matcher(text.replaceAll("\\s+","")).find();
    }

    static Office counter(String text, LocalDate edition) {
        var names = COUNTER.matcher(text).results().map(m -> m.group(1).strip()).distinct().toList();
        return names.size() == 1 ? new Office(names.get(0), edition, "") : null;
    }

    static List<Window> read(Element article, String text, String title, String url, LocalDate issued, LocalDate edition) {
        if ((!text.matches("(?s).*(?:방문\\s*(?:신청|접수)|현장\\s*(?:신청|접수)).*") && counter(text, edition) == null) || text.matches("(?s).*변경\\s*전\\s*[:：].*")) return List.of();
        var dates = PERIOD.matcher(text);
        if (!dates.find() || dates.group(1) == null) return List.of();
        var contact = CONTACT.matcher(text);
        String desk = "";
        if (contact.find()) {
            String name = contact.group(1);
            var dept = article.selectFirst("dt:matchesOwn(^작성부서$) + dd");
            if (dept != null && name.replaceAll("\\s", "").equals(dept.text().strip() + "행정복지센터") && !contact.find()) desk = name;
        }
        var sites = COMPLEX.matcher(title).results().map(m -> m.group(1)).distinct().toList();
        if (sites.size() > 1) return List.of();
        var matches = new ArrayList<Window>();
        try {
            int year = Integer.parseInt(dates.group(1));
            LocalDate start = LocalDate.of(year,Integer.parseInt(dates.group(2)),Integer.parseInt(dates.group(3)));
            LocalDate end = LocalDate.of(dates.group(4) == null ? year : Integer.parseInt(dates.group(4)),Integer.parseInt(dates.group(5)),Integer.parseInt(dates.group(6)));
            if (end.isBefore(start) || dates.find()) return List.of();
            var office = counter(text, edition);
            var lastClose = Pattern.compile("(?m)^\\h*[-*]?\\h*(?:(" + DESK + ")\\h*[:：·]?\\h*)?(?:접수\\h*)?(?:마지막\\h*날|마감일|최종일)\\h*(?:방문\\h*)?(?:접수\\h*)?(?:마감\\h*(?:시각|시간)?\\h*[:：]?\\h*(\\d{1,2}:\\d{2})|(\\d{1,2}:\\d{2})\\h*(?:까지|마감))\\h*$").matcher(text);
            while (lastClose.find()) {
                String name = lastClose.group(1) != null ? lastClose.group(1) : !desk.isBlank() ? desk : office == null ? "" : office.name();
                if (name.isBlank()) continue;
                String clock = lastClose.group(2) != null ? lastClose.group(2) : lastClose.group(3);
                matches.add(new Window("현장","",start,end,url,null,LocalTime.parse(clock.replaceFirst("^(\\d):","0$1:")),
                    issued,null,null,AgencyField.OFFICE_CLOSE,null,null,new Office(name,edition,sites.isEmpty() ? "" : sites.get(0))));
            }
            var breaks = BREAK.matcher(text);
            while (breaks.find()) {
                String name = breaks.group(1) == null ? desk : breaks.group(1);
                if (name.isBlank()) continue;
                var from = LocalTime.parse(breaks.group(2).replaceFirst("^(\\d):","0$1:"));
                var until = LocalTime.parse(breaks.group(3).replaceFirst("^(\\d):","0$1:"));
                if (from.isBefore(until)) matches.add(new Window("현장","",start,end,url,null,null,issued,null,null,
                    AgencyField.OFFICE_BREAK,from,until,new Office(name,edition,sites.isEmpty() ? "" : sites.get(0))));
            }
        } catch (DateTimeException invalid) { return List.of(); }
        return List.copyOf(matches);
    }
}
