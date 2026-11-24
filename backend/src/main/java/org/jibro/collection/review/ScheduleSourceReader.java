package org.jibro.collection.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Excerpt;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.collection.extraction.ExtractionModels.Part;

/**
 * Keeps label/value adjacency from source lines; it never reconstructs ambiguous PDF columns.
 */
public final class ScheduleSourceReader {
    static final String FULL_DATE = "(?<!\\d)20\\d{2}\\s*[.년/-]\\s*\\d{1,2}\\s*[.월/-]\\s*\\d{1,2}(?!\\d)(?:\\s*[.일])?";
    static final String WEEKDAY = "(?:\\s*\\([월화수목금토일](?:요일)?\\))?";
    private static final String TIME = "\\d{1,2}\\s*:\\s*\\d{2}";
    private static final String DAILY_HOURS = "\\s*\\(?\\s*(?:매일\\s*)?(" + TIME + ")\\s*[~∼～–-]\\s*(" + TIME + ")\\s*\\)?";
    private static final String BREAK_CLOCK = "(?:[01]?\\d|2[0-3])(?::[0-5]\\d|시)?";
    private static final Pattern LUNCH_BREAK = Pattern.compile("\\s*[*(\\-]?\\s*점심(?:시간)?\\s*\\(?\\s*(" + BREAK_CLOCK
        + ")\\s*[~∼～-]\\s*(" + BREAK_CLOCK + ")\\s*\\)?\\s*(?:접수불가|제외)?\\s*\\)?$");
    private static final Pattern EXCLUDED_HOURS = Pattern.compile("\\s*\\(\\s*(" + TIME + ")\\s*[~∼～-]\\s*(" + TIME + ")\\s*제외\\s*\\)$");
    private static final Pattern DATE_RANGE_HOURS = Pattern.compile("^(" + FULL_DATE + WEEKDAY + ")\\s*[~∼～–-]\\s*("
        + "(?:" + FULL_DATE + "|\\d{1,2}[.월/-]\\s*\\d{1,2}[.일]?)" + WEEKDAY + ")\\s+" + DAILY_HOURS + "$");
    private static final Pattern DAY_HOURS = Pattern.compile("^\\s*(" + FULL_DATE + ")" + WEEKDAY
        + "\\s*(?:당일\\s*)?\\(?(" + TIME + ")\\s*[~∼〜～–—-]\\s*(" + TIME + ")\\)?(?:시|\\s*까지)?\\s*$");
    private static final Pattern DATE_START = Pattern.compile("^[\\s:'’‘`]*\\d{2,4}\\s*[.년/-]\\s*\\d{1,2}");
    private static final Pattern SCHEDULE_HEADING = Pattern.compile("^\\s*(?:\\d{1,2}\\.\\s*|[■□]\\s*)?(?:모집|공급)\\s*일정(?:\\s.*)?$");
    private static final Pattern NEXT_HEADING = Pattern.compile("^\\s*\\d{1,2}\\.\\s*[가-힣].*");
    private static final Pattern RANK = Pattern.compile("^([1-9](?:\\s*[,~∼·]\\s*[1-9])*\\s*순위)$");
    private static final Pattern CHANNEL = Pattern.compile("^(?:[12]\\.\\s*)?(인터넷(?:\\s*[/·]\\s*모바일)?|온라인|현장)(?:\\s*(?:청약|신청|접수))*$");
    private static final Pattern FIELD = Pattern.compile("(?:접수|신청|청약)\\s*(?:기간|시작|개시|마감|종료)|(?:대상자|당첨자|결과)\\s*발표|서류\\s*(?:제출|접수)\\s*(?:기간|기한|마감|일)|서류\\s*(?:마감|기한)");
    private static final Pattern APPLICATION_RANGE = Pattern.compile("(?<!서류)(?:신청|청약|접수)\\s*(?:접수\\s*)?기간");
    private static final Pattern SINGLE_DAY = Pattern.compile("^(?:[■▪•◎]\\s*)?(?:신청|청약|접수)\\s*(?:접수\\s*)?(?:기간|일시)\\s*[:：]\\s*(.+)$");
    private static final Pattern ROLLING_START = Pattern.compile("^(" + FULL_DATE + WEEKDAY + "(?:\\s*" + TIME
        + ")?)\\s*(?:~|∼|부터)\\s*(?:충원|소진|마감)\\s*시\\s*까지[.。]?$");
    private static final Pattern JOINT_PERIOD = Pattern.compile("(?:신청|청약)\\s*(?:접수\\s*)?(?:및|·|/|과)\\s*서류\\s*(?:제출|접수)\\s*(?:접수\\s*)?기간");
    private static final Pattern DOCUMENT_LABEL = Pattern.compile("^[■□*\\s]*(?:제출대상자\\s*)?서류\\s*(?:제출|접수)(?:\\s*(?:기간|기한|마감|일))?\\s*[:：]?$");
    private static final Pattern DATED_DOCUMENT = Pattern.compile("^([■□*\\s]*)서류\\s*(?:제출|접수)\\s*[:：](?=\\s*[('’‘`]*\\d)");
    private static final Pattern ONLINE_BOUNDARIES = Pattern.compile("접수기간\\s*[:：]\\s*(" + FULL_DATE + WEEKDAY
        + "\\s*(?:\\d{1,2}시|" + TIME + "))\\s*부터\\s*접수\\s*마감일\\s*(" + FULL_DATE + WEEKDAY
        + "\\s*(?:\\d{1,2}시|" + TIME + "))(?:$|까지로\\s*접수기간\\s*중에는\\s*24시간)");
    private static final Pattern OFFICE_HOURS = Pattern.compile("(" + FULL_DATE + ")" + WEEKDAY
        + "\\s*업무시간\\s*\\((" + TIME + ")\\s*[~∼〜～–—-]\\s*(" + TIME + ")\\)");

    record ScopedField(String scope, Excerpt excerpt, boolean dailyVisit, String cohort) {
        ScopedField(String scope, Excerpt excerpt, boolean dailyVisit) {
            this(scope, excerpt, dailyVisit, null);
        }

        ScopedField(String scope, Excerpt excerpt) {
            this(scope, excerpt, false, null);
        }
    }

    private static final String METHOD = "(?:인터넷(?:\\s*[/·]\\s*모바일)?|온라인|현장(?:방문)?|방문|모바일)";
    private static final Pattern METHOD_LABEL = Pattern.compile("(?:[12]\\.\\s*)?\\[?(" + METHOD
        + ")\\]?\\s*(?:청약\\s*)?(?:신청|접수)?\\]?(?:\\s*및\\s*서류제출)?\\s*[:：]?\\s*(?=['‘’\u0060]?\\d)");
    private static final Pattern CELL_CHANNEL = Pattern.compile("^(" + FULL_DATE + WEEKDAY + "\\s*[~∼～–-]\\s*(?:" + FULL_DATE
        + "|\\d{1,2}[.월/-]\\s*\\d{1,2}[.일]?)" + WEEKDAY + ")\\s*\\[(" + METHOD
        + ")(?:접수|신청)?\\]\\s*(" + DAILY_HOURS + ")$");
    private static final Pattern INLINE_SCOPE = Pattern.compile("^(?:[•▪■]\\s*)?(?<scope>(?:[가-힣A-Za-z0-9·-]{1,32}단지\\s*)?"
        + "(?:[1-9](?:[,·~][1-9])*순위\\s*)?(?:" + METHOD + ")?)(?:\\s*(?:청약|신청|접수)){0,2}\\s*(?:기간|일시)?\\s*[:：]?\\s*(?<value>"
        + FULL_DATE + ".*)$");

    record Reading(List<Excerpt> fields, List<ScopedField> scoped, Set<String> unresolvedScopes) {
    }

    private ScheduleSourceReader() {
    }

    /**
     * Explicit fields and scoped table rows from one verified agency attachment.
     */
    public static List<Part> agencyFields(Source source) {
        source = org.jibro.collection.review.local.DocumentContextReader.normative(source);
        var result = new LinkedHashSet<Part>();
        var part = source.context().get(0);
        if (part.location().startsWith("Excel 워크시트")) {
            // Never join adjacent rows, hidden cells, or cells in a merged layout.
            var sheet = org.jibro.collection.review.local.SpreadsheetContextReader.read(part);
            if (!sheet.merges().isEmpty()) return List.of();
            var header = new java.util.LinkedHashMap<String, String>();
            for (var row : sheet.rows()) {
                if (!row.readable()) {
                    header.clear();
                    continue;
                }
                var values = row.cells();
                String at = part.location() + " · 행 " + row.number();
                if (!values.isEmpty()) result.add(new Part(at, String.join(" ", values.values())));
                if (!values.isEmpty() && values.values().stream().allMatch(v -> v.matches("(?:접수|신청)\\s*(?:방식|방법|기간|시작(?:일시|일|시각)?|마감(?:일시|일|시각)?)|순위|단지"))) {
                    header.clear();
                    header.putAll(values);
                } else if (!header.isEmpty() && header.keySet().equals(values.keySet())) {
                    String scope = header.entrySet().stream().filter(e -> e.getValue().matches(".*(?:방식|방법)|순위|단지"))
                        .map(e -> values.get(e.getKey())).collect(java.util.stream.Collectors.joining(" "));
                    header.forEach((col, label) -> {
                        if (!label.matches(".*(?:방식|방법)|순위|단지"))
                            result.add(new Part(at + " · " + col + row.number(), scope + " "
                                + label + ": " + values.get(col)));
                    });
                } else header.clear();
            }
        } else {
            var reading = read(source);
            reading.fields().forEach(e -> result.add(new Part(e.location(), e.text())));
            reading.scoped().forEach(s -> result.add(new Part(s.excerpt().location(), s.scope() + " " + s.excerpt().text())));
            // Clock-only and explicit old → new fields can be absent from the primary period reader.
            part.text().lines().map(String::strip).filter(s -> s.matches(".*(?:접수|신청)\\s*(?:시작|마감|개시|종료|일|기간|일시).*"))
                .forEach(s -> result.add(new Part(part.location(), s)));
        }
        return List.copyOf(result);
    }

    static Reading read(Source source) {
        if (source.context().isEmpty()) return new Reading(source.excerpts().stream()
            .filter(excerpt -> "SCHEDULE".equals(excerpt.category())).toList(), List.of(), Set.of());
        List<Excerpt> fields = new ArrayList<>(ApplicationTableReader.read(source));
        List<ScopedField> scoped = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();
        Set<String> years = explicitYears(source);
        for (var part : source.context()) {
            // An explicitly labelled online method in this same listing part owns its following
            // hours sentence. A visitor-only parenthesis is a separate scoped window.
            if (source.label().replaceAll("\\s+", "").equals("공식상세본문")) {
                boolean onlineMethod = part.text().lines().anyMatch(l -> l.replaceAll("\\s+", "").equals("■신청접수는인터넷및모바일접수로진행됩니다"));
                for (String raw : part.text().lines().toList()) {
                    String value = raw.replaceAll("\\s+", "");
                    var hours = Pattern.compile("^■접수시간은(20[0-9]{2}년[0-9]{1,2}월[0-9]{1,2}일)([0-9]{1,2}:[0-9]{2})[~∼～-](20[0-9]{2}년[0-9]{1,2}월[0-9]{1,2}일)([0-9]{1,2}:[0-9]{2})까지입니다[.]$").matcher(value);
                    if (onlineMethod && hours.matches())
                        readWindow(source, part.location(), "인터넷", hours.group(1) + " " + hours.group(2) + " ~ " + hours.group(3) + " " + hours.group(4), fields, scoped);
                    var visit = Pattern.compile("^\\(단,현장접수는(20[0-9]{2}[.][0-9]{1,2}[.][0-9]{1,2}\\([월화수목금토일]\\))([0-9]{1,2}:[0-9]{2})[~∼～-]([0-9]{1,2}:[0-9]{2})\\(점심시간([0-9]{1,2}:[0-9]{2})[~∼～-]([0-9]{1,2}:[0-9]{2})제외\\)만받습니다[.]\\)$").matcher(value);
                    if (visit.matches())
                        readWindow(source, part.location(), "현장", visit.group(1) + " " + visit.group(2) + " ~ " + visit.group(1) + " " + visit.group(3) + " (점심 " + visit.group(4) + "~" + visit.group(5) + " 제외)", fields, scoped);
                }
            }

            for (var cell : ApplicationTableReader.read(source, part)) {
                String text = explicitShortYears(cell.text(), years).replaceAll("\\s+", " ").strip();
                String cohort = source.url() + " · " + cell.cohort().replaceAll("\\s*([()·⋅])\\s*", "$1").replace('⋅', '·');
                if (cell.kind() == ScheduleCandidates.Kind.RESULT) {
                    // A quoted two-digit year states the year, unlike a date whose year is omitted.
                    // Only the century is taken from the document's explicit full years.
                    var centuries = years.stream().map(year -> year.substring(0, 2)).distinct().toList();
                    if (centuries.size() == 1)
                        text = text.replaceFirst("^['‘’`]\\s*(\\d{2})(?=[.년/-])", centuries.get(0) + "$1");
                    text = text.replaceFirst("\\s*\\[LH(?:청약플러스|홈페이지)\\]\\s*", " ")
                        .replaceFirst("\\(\\s*(\\d{1,2}:\\d{2}\\s*(?:이후)?)\\s*\\)$", "$1");
                    if (!DATE_START.matcher(text).find()) text = "확인 필요 · " + text;
                    var excerpt = new Excerpt("SCHEDULE", "결과 발표: " + text, cell.location(), source.url());
                    if (cell.scope().isBlank()) fields.add(excerpt);
                    else scoped.add(new ScopedField(cell.scope(), excerpt, false, cohort));
                    continue;
                }
                var cellFields = new ArrayList<ScopedField>();
                if (!readCell(source, cell.location(), cell.scope(), text, fields, cellFields))
                    unresolved.add(cell.location() + " 미해석");
                for (var field : cellFields)
                    scoped.add(new ScopedField(field.scope(), field.excerpt(), field.dailyVisit(), cohort));
            }
            String[] lines = explicitShortYears(part.text(), years).split("\\R");
            boolean timetable = false;
            boolean visitOnly = false;
            String rank = null;
            String methodContext = "";
            for (int i = 0; i < lines.length; i++) {
                String line = clean(lines[i]).replaceAll("접수기간은\\s*접수시작일", "접수기간:");
                line = DATED_DOCUMENT.matcher(line).replaceFirst("$1서류 제출 기간:");
                if (DOCUMENT_LABEL.matcher(line).matches()) line = "서류 제출 기간:";
                if (line.startsWith("■") || NEXT_HEADING.matcher(line).matches()) {
                    methodContext = line.matches(".*현장(?:방문)?\\s*신청방법.*") ? "현장" : "";
                }
                if (line.startsWith("⟦HWPX:") || line.startsWith("⟦PDF:")) {
                    timetable = false;
                    rank = null;
                    methodContext = "";
                    continue;
                }
                if (SCHEDULE_HEADING.matcher(line).matches()) {
                    timetable = true;
                    visitOnly = line.matches(".*(?:현장|방문)\\s*접수만\\s*가능.*");
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
                if (timetable && visitOnly && line.matches("신청기간\\s+신청장소\\s+(?:예비입주자\\s*발표\\s+)?계약안내")
                    && i + 1 < lines.length) {
                    readVisitTable(source, part.location(), lines, i + 1, fields, scoped);
                }
                var inline = INLINE_SCOPE.matcher(line);
                if (inline.matches() && !inline.group("scope").isBlank()
                    && (timetable || line.contains("접수") || line.contains("신청"))) {
                    String scope = inline.group("scope").strip();
                    String value = inline.group("value");
                    if (i + 1 < lines.length && clean(lines[i + 1]).matches(DAILY_HOURS))
                        value += " " + clean(lines[++i]);
                    if (i + 1 < lines.length && LUNCH_BREAK.matcher(clean(lines[i + 1])).matches())
                        value += " " + clean(lines[i + 1]);
                    if (!readWindow(source, part.location(), scope, value, fields, scoped))
                        unresolved.add(scope);
                    continue;
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
                boolean rollingHeading = line.matches("(?:입주자\\s*)?모집\\s*기간\\s*[:：]?");
                if (!FIELD.matcher(line).find() && !rollingHeading && !IntakeExplanation.matches(line)) continue;
                StringBuilder field = new StringBuilder(line);
                // A standalone label can own the immediately following date/range, never another column.
                if (!Pattern.compile("\\d{1,4}\\s*[.년/-]\\s*\\d").matcher(line).find()
                    && (rollingHeading || line.endsWith(":") || line.startsWith("*") || line.matches("(?:신청|접수|청약)\\s*(?:접수\\s*)?(?:기간|일시)"))) {
                    int next = i + 1;
                    while (next < lines.length && clean(lines[next]).isBlank()) next++;
                    if (next < lines.length && (DATE_START.matcher(clean(lines[next])).find()
                        || IntakeExplanation.matches(line + " " + clean(lines[next])))) {
                        field.append(' ').append(clean(lines[next++]));
                        for (int count = 0; next < lines.length && count < 3; next++, count++) {
                            String continuation = clean(lines[next]);
                            if (continuation.matches("^[~∼〜～–—-].*") || continuation.matches("^\\d{1,2}:\\d{2}.*")
                                || field.toString().matches(".*[~∼〜～–—-]$") && (DATE_START.matcher(continuation).find()
                                || IntakeExplanation.matches(field + " " + continuation))) {
                                field.append(' ').append(continuation);
                            } else break;
                        }
                    }
                }
                String fieldText = field.toString();
                if (IntakeExplanation.matches(fieldText) && (rank != null || !methodContext.isBlank())) {
                    unresolved.add(rank != null ? rank : methodContext);
                    continue;
                }
                var single = SINGLE_DAY.matcher(fieldText);
                if (single.matches() && methodContext.isBlank()) {
                    String value = single.group(1);
                    if (i + 1 < lines.length && clean(lines[i + 1]).matches(DAILY_HOURS))
                        value += " " + clean(lines[i + 1]);
                    if ((DAY_HOURS.matcher(value).matches() || DATE_RANGE_HOURS.matcher(value).matches())
                        && readWindow(source, part.location(), "", value, fields, scoped)) continue;
                    var rolling = ROLLING_START.matcher(single.group(1));
                    if (rolling.matches()) {
                        fields.add(new Excerpt("SCHEDULE", "접수 시작: " + rolling.group(1), part.location(), source.url()));
                        continue;
                    }
                }
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
                    var online = ONLINE_BOUNDARIES.matcher(fieldText);
                    if (method.equals("인터넷·모바일") && online.find())
                        fieldText = "접수 기간: " + online.group(1) + " ~ " + online.group(2);
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
        return new Reading(List.copyOf(fields), separateVisitDates(scoped), Set.copyOf(unresolved));
    }

    /**
     * A visit-only table can wrap the place and contract notification around a single intake clock.
     */
    private static void readVisitTable(Source source, String location, String[] lines, int start,
                                       List<Excerpt> fields, List<ScopedField> scoped) {
        String range = clean(lines[start]).replaceFirst("\\s+['‘’]?(?:20)?\\d{2}[.]\\d{1,2}[.]\\d{1,2}[.]?\\([월화수목금토일]\\)\\s+\\d{1,2}:\\d{2}\\s*이후$", "");
        String clock = "", lunch = "";
        boolean office = false;
        for (int i = start + 1; i < Math.min(lines.length, start + 7); i++) {
            String line = clean(lines[i]).replaceFirst("(\\))\\s*발표일\\s*변동\\s*가능$", "$1");
            if (line.isBlank()) continue;
            if (line.matches(DAILY_HOURS)) {
                if (!clock.isEmpty()) return;
                clock = line;
            } else if (LUNCH_BREAK.matcher(line).matches()) {
                if (!lunch.isEmpty()) return;
                lunch = line;
            } else if (line.matches(".*(?:행정복지센터|주민센터).*") && !CLOCK_OR_DATE.matcher(line).find()) {
                office = true;
            } else if (!line.matches(".*(?:거주지(?:\\s*관할)?|자격검증\\s*결과|개별통보).*") || CLOCK_OR_DATE.matcher(line).find())
                break;
        }
        if (office && !clock.isEmpty())
            readWindow(source, location, "현장", (range + " " + clock + " " + lunch).strip(), fields, scoped);
    }

    private static final Pattern CLOCK_OR_DATE = Pattern.compile("\\d\\s*[:.년월/-]\\s*\\d");

    private static List<ScopedField> separateVisitDates(List<ScopedField> fields) {
        // Dates in different files can be conflicting versions. Split only distinct rows in the same source.
        var single = Pattern.compile("^접수 기간: (" + FULL_DATE + ")\\s+" + TIME + "\\s*~\\s*\\1\\s+" + TIME + "$");
        var days = new java.util.LinkedHashMap<String, Set<String>>();
        for (var field : fields) {
            var match = single.matcher(field.excerpt().text());
            if ((field.scope().contains("현장") || field.scope().contains("방문")) && match.matches())
                days.computeIfAbsent(field.scope(), ignored -> new LinkedHashSet<>()).add(match.group(1));
        }
        return fields.stream().map(field -> {
            var match = single.matcher(field.excerpt().text());
            if (days.getOrDefault(field.scope(), Set.of()).size() > 1 && match.matches())
                return new ScopedField(field.scope() + " · " + match.group(1).strip(), field.excerpt(), false, field.cohort());
            return field;
        }).toList();
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

    private static boolean readCell(Source source, String location, String scope, String text,
                                    List<Excerpt> fields, List<ScopedField> scoped) {
        String packed = text.replaceAll("[\\s\\p{Zs}]+", "");
        var flowOnline = Pattern.compile("^※인터넷\\(PC[‧·]모바일\\)접수[’‘']?(20[0-9]{2}[.][0-9]{1,2}[.][0-9]{1,2}[.]?\\([월화수목금토일]\\))([0-9]{1,2}:[0-9]{2})[~∼～-]([0-9]{1,2}[.][0-9]{1,2}[.]?\\([월화수목금토일]\\))([0-9]{1,2}:[0-9]{2})\\(시작일과마감일을제외하고접수기간내24시간접수가능\\)$").matcher(packed);
        if (flowOnline.matches()) return readWindow(source, location, scope.isBlank() ? "인터넷" : scope + " · 인터넷",
            flowOnline.group(1) + " " + flowOnline.group(2) + " ~ " + flowOnline.group(3) + " " + flowOnline.group(4), fields, scoped);
        var flowVisit = Pattern.compile("^※현장접수[’‘']?(20[0-9]{2}[.][0-9]{1,2}[.][0-9]{1,2}[.]?\\([월화수목금토일]\\))([0-9]{1,2}:[0-9]{2})[~∼～-]([0-9]{1,2}:[0-9]{2})\\(점심시간([0-9]{1,2})시[~∼～-]([0-9]{1,2})시제외\\)-현장접수장소:LH[가-힣]+주거복지지사\\(주차불가\\)-65세이상고령자및장애인등에한하여현장청약접수$").matcher(packed);
        if (flowVisit.matches()) return readWindow(source, location, scope.isBlank() ? "현장" : scope + " · 현장",
            flowVisit.group(1) + " " + flowVisit.group(2) + " ~ " + flowVisit.group(1) + " " + flowVisit.group(3) + " (점심 " + flowVisit.group(4) + ":00~" + flowVisit.group(5) + ":00 제외)", fields, scoped);
        var shared = SHARED_VISIT_ONLINE.matcher(text);
        if (shared.matches()) {
            String window = shared.group(1) + " " + shared.group(3) + ":00 ~ " + shared.group(2) + " " + shared.group(4) + ":00";
            String prefix = scope.isBlank() ? "" : scope + " · ";
            return readWindow(source, location, prefix + "현장 (점심 " + shared.group(5) + ":00~" + shared.group(6) + ":00 제외)", window, fields, scoped)
                && readWindow(source, location, prefix + "인터넷", window, fields, scoped);
        }
        // A bracketed channel inside the intake cell belongs to its dates, not a neighbouring viewing/result column.
        var inlineChannel = CELL_CHANNEL.matcher(text);
        if (inlineChannel.matches()) {
            String channel = inlineChannel.group(2);
            return readWindow(source, location, scope.isBlank() ? channel : scope + " · " + channel,
                inlineChannel.group(1) + " " + inlineChannel.group(3), fields, scoped);
        }
        var labels = METHOD_LABEL.matcher(text);
        if (!labels.find()) return readWindow(source, location, scope, text, fields, scoped);
        if (!text.substring(0, labels.start()).isBlank()) return false;
        do {
            String channel = labels.group(1);
            int start = labels.end();
            boolean more = labels.find();
            String window = text.substring(start, more ? labels.start() : text.length()).replaceFirst("[/\\s]+$", "");
            String name = scope.isBlank() ? channel : scope + " · " + channel;
            if (!readWindow(source, location, name, window, fields, scoped)) return false;
            if (!more) break;
        } while (true);
        return true;
    }

    private static boolean readWindow(Source source, String location, String scope, String text,
                                      List<Excerpt> fields, List<ScopedField> scoped) {
        if (scope.isBlank() && IntakeExplanation.matches("접수 기간: " + text)) {
            fields.add(new Excerpt("SCHEDULE", "접수 기간: " + text, location, source.url()));
            return true;
        }
        String value = text.strip().replaceFirst("^['‘’](?=20\\d{2}[.년/-])", "").replaceFirst("\\s*\\*\\s*접수장소\\s*[:：].*$", "").strip();
        var lunch = LUNCH_BREAK.matcher(value);
        var exclusion = EXCLUDED_HOURS.matcher(value);
        if (lunch.find()) {
            scope = (scope + " (점심 " + breakClock(lunch.group(1)) + "~" + breakClock(lunch.group(2)) + " 제외)").strip();
            value = value.substring(0, lunch.start()).strip();
        } else if (exclusion.find()) {
            scope = (scope + " (" + exclusion.group(1) + "~" + exclusion.group(2) + " 제외)").strip();
            value = value.substring(0, exclusion.start()).strip();
        }
        var day = DAY_HOURS.matcher(value);
        var daily = DATE_RANGE_HOURS.matcher(value);
        boolean repeated = daily.matches();
        if (day.matches()) {
            String date = day.group(1);
            value = date + " " + day.group(2) + " ~ " + date + " " + day.group(3);
        } else if (repeated) {
            value = daily.group(1) + " " + daily.group(3) + " ~ " + daily.group(2) + " " + daily.group(4);
        } else if (!value.matches(FULL_DATE + WEEKDAY + "\\s*(?:" + TIME + ")?\\s*[~∼～–-]\\s*"
            + "(?:" + FULL_DATE + "|\\d{1,2}[.월/-]\\s*\\d{1,2}[.일]?)" + WEEKDAY + "\\s*(?:" + TIME + ")?(?:까지)?"))
            return false;
        var excerpt = new Excerpt("SCHEDULE", "접수 기간: " + value, location, source.url());
        if (scope.isBlank()) fields.add(excerpt);
        else scoped.add(new ScopedField(scope, excerpt, repeated && (scope.contains("현장") || scope.contains("방문"))));
        return true;
    }

    private static final Pattern SHARED_VISIT_ONLINE = Pattern.compile("\\(현장\\s*/\\s*인터넷\\)\\s*("
        + FULL_DATE + WEEKDAY + ")\\s*[~∼～]\\s*(" + FULL_DATE + WEEKDAY + ")\\s*(\\d{1,2})\\s*~\\s*(\\d{1,2})시"
        + "\\s*※?\\s*현장은\\s*점심시간\\s*(\\d{1,2})\\s*~\\s*(\\d{1,2})시\\s*제외\\s*인터넷은\\s*24시간\\s*가능");

    private static String clean(String value) {
        return value.replaceAll("[\\p{Zs}\\t]+", " ").strip();
    }

    private static String breakClock(String text) {
        return text.contains(":") ? text : text.replace("시", "") + ":00";
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
        var abbreviated = Pattern.compile("(?<![\\d.])(?:['‘’`]\\s*)?([0-9]{2})(\\s*[.년/-]\\s*\\d{1,2}\\s*[.월/-]\\s*\\d{1,2})(?!\\d)").matcher(text);
        StringBuilder expanded = new StringBuilder();
        while (abbreviated.find()) {
            String year = "20" + abbreviated.group(1);
            abbreviated.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(years.contains(year)
                ? year + abbreviated.group(2) : abbreviated.group()));
        }
        return abbreviated.appendTail(expanded).toString();
    }
}
