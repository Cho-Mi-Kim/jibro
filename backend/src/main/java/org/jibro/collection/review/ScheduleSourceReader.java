package org.jibro.collection.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Excerpt;
import org.jibro.collection.extraction.ExtractionModels.Source;

/**
 * Keeps label/value adjacency from source lines; it never reconstructs ambiguous PDF columns.
 */
final class ScheduleSourceReader {
    static final String FULL_DATE = "(?<!\\d)20\\d{2}\\s*[.년/-]\\s*\\d{1,2}\\s*[.월/-]\\s*\\d{1,2}(?!\\d)(?:\\s*[.일])?";
    private static final String WEEKDAY = "(?:\\s*\\([월화수목금토일](?:요일)?\\))?";
    private static final String TIME = "\\d{1,2}\\s*:\\s*\\d{2}";
    private static final Pattern DAY_HOURS = Pattern.compile("^\\s*(" + FULL_DATE + ")" + WEEKDAY
        + "\\s*(?:당일\\s*)?\\(?(" + TIME + ")\\s*[~∼〜～–—-]\\s*(" + TIME + ")\\)?(?:시|\\s*까지)?\\s*$");
    private static final Pattern DATE_START = Pattern.compile("^[\\s:'’‘`]*\\d{2,4}\\s*[.년/-]\\s*\\d{1,2}");
    private static final Pattern SCHEDULE_HEADING = Pattern.compile("^\\s*(?:\\d{1,2}\\.\\s*|[■□]\\s*)?(?:모집|공급)\\s*일정(?:\\s.*)?$");
    private static final Pattern NEXT_HEADING = Pattern.compile("^\\s*\\d{1,2}\\.\\s*[가-힣].*");
    private static final Pattern RANK = Pattern.compile("^([1-9](?:\\s*[,~∼·]\\s*[1-9])*\\s*순위)$");
    private static final Pattern CHANNEL = Pattern.compile("^(?:[12]\\.\\s*)?(인터넷(?:\\s*[/·]\\s*모바일)?|온라인|현장)(?:\\s*(?:청약|신청|접수))*$");
    private static final Pattern FIELD = Pattern.compile("(?:접수|신청|청약)\\s*(?:기간|시작|개시|마감|종료)|(?:대상자|당첨자|결과)\\s*발표|서류\\s*(?:제출|접수)\\s*(?:기간|기한|마감|일)|서류\\s*(?:마감|기한)");
    private static final Pattern APPLICATION_RANGE = Pattern.compile("(?<!서류)(?:신청|청약|접수)\\s*(?:접수\\s*)?기간");
    private static final Pattern JOINT_PERIOD = Pattern.compile("(?:신청|청약)\\s*(?:접수\\s*)?(?:및|·|/|과)\\s*서류\\s*(?:제출|접수)\\s*(?:접수\\s*)?기간");
    private static final Pattern DOCUMENT_LABEL = Pattern.compile("^[■□*\\s]*(?:제출대상자\\s*)?서류\\s*(?:제출|접수)(?:\\s*(?:기간|기한|마감|일))?\\s*[:：]?$");
    private static final Pattern DATED_DOCUMENT = Pattern.compile("^([■□*\\s]*)서류\\s*(?:제출|접수)\\s*[:：](?=\\s*[('’‘`]*\\d)");
    private static final Pattern OFFICE_HOURS = Pattern.compile("(" + FULL_DATE + ")" + WEEKDAY
        + "\\s*업무시간\\s*\\((" + TIME + ")\\s*[~∼〜～–—-]\\s*(" + TIME + ")\\)");

    record ScopedField(String scope, Excerpt excerpt) {
    }

    record Reading(List<Excerpt> fields, List<ScopedField> scoped, Set<String> unresolvedScopes) {
    }

    private ScheduleSourceReader() {
    }

    static Reading read(Source source) {
        if (source.context().isEmpty()) return new Reading(source.excerpts().stream()
            .filter(excerpt -> "SCHEDULE".equals(excerpt.category())).toList(), List.of(), Set.of());
        List<Excerpt> fields = new ArrayList<>(ApplicationTableReader.read(source));
        List<ScopedField> scoped = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        Set<String> years = explicitYears(source);
        for (var part : source.context()) {
            String[] lines = explicitShortYears(part.text(), years).replace('\r', '\n').split("\\n");
            boolean timetable = false;
            String rank = null;
            String methodContext = "";
            for (int i = 0; i < lines.length; i++) {
                String line = clean(lines[i]).replaceAll("접수기간은\\s*접수시작일", "접수기간:");
                line = DATED_DOCUMENT.matcher(line).replaceFirst("$1서류 제출 기간:");
                if (DOCUMENT_LABEL.matcher(line).matches()) line = "서류 제출 기간:";
                if (line.startsWith("■") || NEXT_HEADING.matcher(line).matches()) {
                    methodContext = line.matches(".*현장(?:방문)?\\s*신청방법.*") ? "현장" : "";
                }
                if (SCHEDULE_HEADING.matcher(line).matches()) {
                    timetable = true;
                    rank = null;
                    continue;
                }
                if (timetable && (NEXT_HEADING.matcher(line).matches() && !line.matches("^[12]\\.\\s*(?:온라인|인터넷|현장).*"))) {
                    timetable = false;
                    rank = null;
                }
                if (timetable && line.matches("^[•▪].*")) {
                    timetable = false;
                    rank = null;
                }
                var rankMatch = RANK.matcher(line);
                if (timetable && rankMatch.matches()) {
                    rank = line.replaceAll("\\s", "");
                    unresolved.add(rank);
                    StringBuilder adjacent = new StringBuilder();
                    String channel = "";
                    for (int next = i + 1; next < Math.min(lines.length, i + 9); next++) {
                        String value = clean(lines[next]);
                        if (value.isBlank()) continue;
                        if (RANK.matcher(value).matches() || NEXT_HEADING.matcher(value).matches() || value.startsWith("•"))
                            break;
                        var channelMatch = CHANNEL.matcher(unbracketChannel(value));
                        if (channelMatch.matches() && adjacent.isEmpty()) {
                            String nextChannel = channelMatch.group(1).replaceAll("\\s", "");
                            // Multiple channel headers followed by a date are a flattened table, not proven shared hours.
                            if (!channel.isBlank() && !channel.equals(nextChannel)) {
                                rank = null;
                                break;
                            }
                            channel = nextChannel;
                            continue;
                        }
                        // A bounded parenthesized residency qualifier may stand between rank and date.
                        if (adjacent.isEmpty() && value.startsWith("(")) {
                            while (!value.contains(")") && next + 1 < Math.min(lines.length, i + 9))
                                value += clean(lines[++next]);
                            if (value.endsWith(")")) continue;
                            break;
                        }
                        if (adjacent.isEmpty() && !DATE_START.matcher(value).find()) break;
                        adjacent.append(' ').append(value);
                        if (addDay(scoped, source, part.location(), rank + (channel.isBlank() ? "" : " · " + channel), adjacent.toString())) {
                            unresolved.remove(rank);
                            break;
                        }
                        if (adjacent.length() > 110) break;
                    }
                    continue;
                }
                var channelMatch = CHANNEL.matcher(unbracketChannel(line));
                if (timetable && rank != null && channelMatch.matches()) {
                    StringBuilder adjacent = new StringBuilder();
                    for (int next = i + 1; next < Math.min(lines.length, i + 4); next++) {
                        String value = clean(lines[next]);
                        if (value.isBlank()) continue;
                        if (adjacent.isEmpty() && !DATE_START.matcher(value).find()) break;
                        adjacent.append(' ').append(value);
                        if (addDay(scoped, source, part.location(), rank + " · " + channelMatch.group(1).replaceAll("\\s", ""), adjacent.toString())) {
                            unresolved.remove(rank);
                            break;
                        }
                    }
                }
                if (!FIELD.matcher(line).find()) continue;
                StringBuilder field = new StringBuilder(line);
                // A standalone label can own the immediately following date/range, never another column.
                if (!Pattern.compile("\\d{1,4}\\s*[.년/-]\\s*\\d").matcher(line).find()
                    && (line.endsWith(":") || line.startsWith("*"))) {
                    int next = i + 1;
                    while (next < lines.length && clean(lines[next]).isBlank()) next++;
                    if (next < lines.length && DATE_START.matcher(clean(lines[next])).find()) {
                        field.append(' ').append(clean(lines[next++]));
                        for (int count = 0; next < lines.length && count < 3; next++, count++) {
                            String continuation = clean(lines[next]);
                            if (continuation.matches("^[~∼〜～–—-].*") || continuation.matches("^\\d{1,2}:\\d{2}.*")
                                || field.toString().matches(".*[~∼〜～–—-]$") && DATE_START.matcher(continuation).find()) {
                                field.append(' ').append(continuation);
                            } else break;
                        }
                    }
                }
                String fieldText = field.toString();
                var joint = JOINT_PERIOD.matcher(fieldText);
                if (joint.find()) {
                    // The source explicitly assigns both stages to this one period, not neighbouring table columns.
                    fields.add(new Excerpt("SCHEDULE", joint.replaceFirst("서류 제출 기간"), part.location(), source.url()));
                    fieldText = JOINT_PERIOD.matcher(fieldText).replaceFirst("신청 접수 기간");
                }
                String method = fieldText.matches(".*인터넷\\s*\\((?:PC|모바일|PC[^)]*모바일)[^)]*\\).*접수기간.*")
                    ? "인터넷·모바일" : methodContext;
                if (!method.isBlank() && APPLICATION_RANGE.matcher(fieldText).find() && !fieldText.contains("서류")
                    && Pattern.compile("\\d{1,4}\\s*[.년/-]\\s*\\d").matcher(fieldText).find()) {
                    var office = OFFICE_HOURS.matcher(fieldText);
                    if (office.find()) {
                        String suffix = "";
                        var lunch = Pattern.compile("점심시간\\s*:\\s*(" + TIME + ")\\s*[~∼〜～–—-]\\s*(" + TIME + ")\\s*제외").matcher(fieldText);
                        if (lunch.find()) suffix = " (" + lunch.group(1) + "~" + lunch.group(2) + " 제외)";
                        addDay(scoped, source, part.location(), method + suffix, office.group(1) + " " + office.group(2) + "~" + office.group(3));
                    } else {
                        scoped.add(new ScopedField(method, new Excerpt("SCHEDULE", fieldText, part.location() + " · " + method + " 접수", source.url())));
                    }
                } else fields.add(new Excerpt("SCHEDULE", fieldText, part.location(), source.url()));
            }
        }
        return new Reading(List.copyOf(fields), List.copyOf(scoped), Set.copyOf(unresolved));
    }

    private static boolean addDay(List<ScopedField> fields, Source source, String location, String scope, String text) {
        var match = DAY_HOURS.matcher(text);
        if (!match.matches()) return false;
        String date = match.group(1).strip();
        String value = "접수 기간: " + date + " " + match.group(2).replaceAll("\\s", "") + " ~ "
            + date + " " + match.group(3).replaceAll("\\s", "");
        var excerpt = new Excerpt("SCHEDULE", value, location + " · " + scope + " 접수", source.url());
        fields.add(new ScopedField(scope, excerpt));
        return true;
    }

    private static String clean(String value) {
        return value.replaceAll("[\\p{Zs}\\t]+", " ").strip();
    }

    private static String unbracketChannel(String value) {
        return value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1).strip() : value;
    }

    private static Set<String> explicitYears(Source source) {
        Set<String> years = new LinkedHashSet<>();
        for (var part : source.context()) {
            var full = Pattern.compile("(?<!\\d)(20\\d{2})\\s*[.년/-]\\s*\\d{1,2}\\s*[.월/-]\\s*\\d{1,2}").matcher(part.text());
            while (full.find()) years.add(full.group(1));
        }
        return years;
    }

    private static String explicitShortYears(String text, Set<String> years) {
        var abbreviated = Pattern.compile("(?<![\\d.])['‘’`]?([0-9]{2})(\\s*[.년/-]\\s*\\d{1,2}\\s*[.월/-]\\s*\\d{1,2})(?!\\d)").matcher(text);
        StringBuilder expanded = new StringBuilder();
        while (abbreviated.find()) {
            String year = "20" + abbreviated.group(1);
            abbreviated.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(years.contains(year)
                ? year + abbreviated.group(2) : abbreviated.group()));
        }
        return abbreviated.appendTail(expanded).toString();
    }
}
