package org.jibro.collection.review;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.ApplicationTiming;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.ScheduleTemplate;
import org.jibro.notice.HousingModels.ReviewIssue;

/**
 * Additional visiting instructions never participate in the overall boundary calculation.
 */
final class VisitReception {
    private static final String VISIT = "(?:현장\\s*(?:방문\\s*)?|방문\\s*)(?:청약\\s*)?(?:신청|접수)";
    private static final String ADVANCE = "사전\\s*(?:전화|연락|예약)|(?:까지|미리|사전에).{0,45}?(?:연락|전화|예약)";
    private static final Pattern CONTACT = Pattern.compile(VISIT + ".{0,140}?(?:" + ADVANCE + ")|사전\\s*(?:전화|연락|예약).{0,45}?" + VISIT);
    private static final Pattern SENTENCE = Pattern.compile("(?<=다[.])\\s+|\\R(?=\\s*(?:다만[, ]|[※■▪•]))");
    private static final Pattern HOLIDAY_CLOSURE = Pattern.compile("공휴일(?:에는|은|에)?\\s*(?:신청\\s*)?접수\\s*(?:받지\\s*않|불가|제외)");
    private static final Pattern DEADLINE = Pattern.compile("(20\\d\\d)[.년/-]\\s*(\\d{1,2})[.월/-]\\s*(\\d{1,2})[.일]?\\s*(?:\\([월화수목금토일]\\))?\\s*까지");
    private static final Pattern BREAK = Pattern.compile("(?:점심(?:\\s*시간)?|중식(?:\\s*시간)?)\\s*[:：(]?\\s*"
            + "(\\d{1,2})(?:\\s*:\\s*(\\d{2})|시)?\\s*[~∼～–-]\\s*(\\d{1,2})(?:\\s*:\\s*(\\d{2})|시)?\\s*\\)?\\s*(?:제외|접수\\s*(?:제한|불가))?");
    private static final Pattern HEADING = Pattern.compile("현장\\s*(?:방문\\s*)?(?:신청|접수)\\s*(?:장소|운영시간|시간|일시)");
    private static final Pattern BEFORE = Pattern.compile("(?:변경|정정)\\s*전\\s*[:：)]|(?:^|\\R)\\s*(?:변경|정정)\\s*전\\s*(?:\\R|$)");
    private static final Pattern PARAGRAPH = Pattern.compile("(?:⟦[^⟧]+⟧|[■▪•]|\\R\\s*\\R)");

    private VisitReception() {
    }

    static List<ScheduleTemplate> enrich(List<ScheduleTemplate> schedules, Set<String> dailyScopes,
                                         List<Source> sources, LocalDate checked, LocalDate end, List<ReviewIssue> issues) {
        if (end == null || end.isBefore(checked)) return List.copyOf(schedules);
        var events = new ArrayList<>(schedules);
        var excludedHolidays = new java.util.HashSet<String>();
        for (Source source : sources) {
            if (source.context().stream().anyMatch(part -> HOLIDAY_CLOSURE.matcher(part.text()).find()))
                excludedHolidays.add(source.url());
        }
        for (String scope : dailyScopes) expand(events, scope, excludedHolidays);
        var contacts = new LinkedHashMap<String, ScheduleTemplate>();
        var breaks = new LinkedHashMap<String, ScheduleTemplate>();
        for (Source source : sources) {
            if (!"EXTRACTED".equals(source.status()) || source.sha256() == null || source.sha256().isBlank()
                    || source.url() == null || source.label() == null || !source.label().matches("(?i).*\\.(?:pdf|hwpx?)$")
                    || source.label().matches(".*(?:위임장|동의서|서식|양식|팸플릿|리플릿).*")
                    || org.jibro.collection.extraction.OcrProvenance.contains(source)) continue;
            for (var part : source.context()) {
                // Side-by-side before/after columns have no reliable reading order in flattened text.
                if (BEFORE.matcher(part.text()).find()) continue;
                for (String paragraph : PARAGRAPH.split(part.text())) {
                    String text = paragraph.replaceAll("\\s+", " ").strip();
                    if (text.isEmpty() || part.location() == null || part.location().isBlank()) continue;
                    for (String sentence : SENTENCE.split(paragraph))
                        readContact(source, part.location(), sentence.replaceAll("\\s+", " ").strip(), checked, contacts, issues);
                    readBreak(source, part.location(), text, checked, breaks, issues);
                }
            }
        }
        // Different signed instructions must not be merged into a single notice-wide rule.
        if (contacts.size() == 1 && events.size() < 100) events.add(contacts.values().iterator().next());
        if (breaks.size() == 1 && events.size() < 100) {
            String range = breaks.keySet().iterator().next();
            var visits = events.stream().filter(s -> s.title().contains("현장") || s.title().contains("방문")).toList();
            if (visits.stream().noneMatch(s -> s.title().contains("점심") || s.title().contains(range)))
                events.add(breaks.get(range));
        }
        return List.copyOf(events);
    }

    private static void readContact(Source source, String location, String sentence, LocalDate checked,
                                    Map<String, ScheduleTemplate> contacts, List<ReviewIssue> issues) {
        if (sentence.length() > 300 || !CONTACT.matcher(sentence).find()
                || sentence.matches(".*(?:계약|서류\\s*제출|주택\\s*열람).*")) return;
        var due = DEADLINE.matcher(sentence);
        String date = "";
        if (due.find()) {
            try {
                date = LocalDate.of(Integer.parseInt(due.group(1)), Integer.parseInt(due.group(2)),
                        Integer.parseInt(due.group(3))).toString();
            } catch (DateTimeException invalid) {
                unreadable("contact-date", "방문 접수 사전 연락 기한 확인 필요", source, location, sentence, checked, issues);
                return;
            }
            if (due.find()) return;
        }
        var note = new ScheduleTemplate("auto-visit-contact", ApplicationTiming.VISIT_CONTACT, date,
                evidence(source, location, sentence, checked), sentence);
        contacts.putIfAbsent(sentence.replaceAll("\\s", ""), note);
    }

    private static void readBreak(Source source, String location, String text, LocalDate checked,
                                  Map<String, ScheduleTemplate> breaks, List<ReviewIssue> issues) {
        var heading = HEADING.matcher(text);
        while (heading.find()) {
            String tail = text.substring(heading.end());
            var lunch = BREAK.matcher(tail);
            if (!lunch.find() || lunch.start() > 75 || tail.substring(0, lunch.start())
                    .matches(".*(?:서류|계약|우편|상담|온라인|인터넷|\\d순위|단지).*")) continue;
            String first;
            String last;
            try {
                first = clock(lunch.group(1), lunch.group(2));
                last = clock(lunch.group(3), lunch.group(4));
                if (first.compareTo(last) >= 0) throw new DateTimeException("LUNCH_INTERVAL_INVALID");
            } catch (DateTimeException invalid) {
                unreadable("lunch-time", "방문 접수 점심시간 확인 필요", source, location, lunch.group(), checked, issues);
                continue;
            }
            String rest = tail.substring(lunch.end()).stripLeading();
            if (rest.matches("^(?:하지|하지는|에도|에도\\s*정상|없이|가능|접수\\s*가능).*")) continue;
            String range = first + "~" + last;
            boolean excluded = lunch.group().matches(".*(?:제외|불가|제한)$");
            breaks.putIfAbsent(range, new ScheduleTemplate("auto-visit-break", ApplicationTiming.VISIT_NOTE, "",
                    evidence(source, location, lunch.group(), checked), "방문 접수 점심시간 " + range + (excluded ? " 제외" : "")));
        }
    }

    private static Evidence evidence(Source source, String location, String text, LocalDate checked) {
        return new Evidence(location, EvidenceLocationUrl.forExcerpt(source, location, text), checked.toString());
    }

    private static String clock(String hour, String minute) {
        return java.time.LocalTime.of(Integer.parseInt(hour), minute == null ? 0 : Integer.parseInt(minute)).toString();
    }

    private static void unreadable(String field, String title, Source source, String location, String text, LocalDate checked, List<ReviewIssue> issues) {
        String identity = source.url() + "\n" + location + "\n" + text;
        String id = "intake-review-auto-" + field + "-" + java.util.UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
        var issue = new ReviewIssue(id, "SCHEDULE", title, "원문의 날짜·시각을 유효한 값으로 읽을 수 없어요. 원문을 다시 확인해 주세요. · " + text,
                List.of(), List.of(), List.of(evidence(source, location, text, checked)));
        if (!issues.contains(issue)) issues.add(issue);
    }

    private static void expand(List<ScheduleTemplate> events, String scope, Set<String> excludedHolidays) {
        for (int i = 0; i + 1 < events.size(); i++) {
            var first = events.get(i);
            var last = events.get(i + 1);
            if (!first.title().replaceFirst("^접수 회차 · ", "").equals(scope + " 접수 시작")
                    || !last.id().equals(first.id().replaceFirst("-start$", "-end"))
                    || !first.evidence().equals(last.evidence()) || first.date().length() != 16 || last.date().length() != 16)
                continue;
            LocalDateTime from = LocalDateTime.parse(first.date()), to = LocalDateTime.parse(last.date());
            long days = ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate()) + 1;
            if (days < 2 || !from.toLocalTime().isBefore(to.toLocalTime())) continue;
            boolean holidayExclusion = excludedHolidays.contains(EvidenceLocationUrl.base(first.evidence().url()));
            String hours = from.toLocalTime() + "~" + to.toLocalTime();
            // Long or weekend-spanning periods stay ranges: the notice may exclude holidays.
            boolean enumerate = days <= 5 && from.getDayOfWeek().getValue() + days - 1 <= 5 && events.size() - 2 + days <= 100;
            var replacements = new ArrayList<ScheduleTemplate>();
            for (int day = 0; day < (enumerate ? days : 1); day++) {
                String date = from.toLocalDate().plusDays(day).toString();
                String label = scope + " " + hours + (enumerate ? "" : " · " + to.toLocalDate() + "까지 접수일마다")
                        + (holidayExclusion ? " (공휴일 제외)" : "");
                replacements.add(new ScheduleTemplate("auto-visit-" + first.id() + "-" + date,
                        "접수 회차 · " + label, date, first.evidence()));
            }
            events.subList(i, i + 2).clear();
            events.addAll(i, replacements);
            return;
        }
    }
}
