package org.jibro.collection.review;

import static org.jibro.collection.review.ScheduleCandidates.*;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Excerpt;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.ReviewIssue;
import org.jibro.notice.HousingModels.ScheduleTemplate;

/** Compiles explicit dates and evidence-bound individual results; never invents an application deadline. */
public final class AutomaticScheduleCompiler {
    private static final Pattern LABEL = Pattern.compile(
        "(?<documentRange>서류\\s*(?:제출|접수)\\s*(?:기간|일))"
            + "|(?<documents>서류\\s*(?:(?:제출|접수)\\s*)?(?:마감|기한)(?:일)?)"
            + "|(?<selection>(?:서류\\s*제출\\s*)?대상자\\s*발표(?:일)?)"
            + "|(?<result>(?:당첨자|당첨|결과)\\s*발표(?:일)?)"
            + "|(?<range>(?:신청\\s*(?:접수\\s*)?|청약\\s*(?:접수\\s*)?|접수\\s*)기간)"
            + "|(?<start>(?:신청\\s*(?:접수\\s*)?|청약\\s*(?:접수\\s*)?|접수\\s*)(?:시작|개시)(?:일)?)"
            + "|(?<end>(?:신청\\s*(?:접수\\s*)?|청약\\s*(?:접수\\s*)?|접수\\s*)(?:마감|종료)(?:일)?)");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(20\\d{2})\\s*[.년/-]\\s*(\\d{1,2})\\s*[.월/-]\\s*(\\d{1,2})(?!\\d)(?:\\s*[.일])?");
    private static final Pattern DATE_HINT = Pattern.compile("(?<!\\d)\\d{1,4}\\s*[.년/-]\\s*\\d{1,2}");
    private static final Pattern CLOCK = Pattern.compile("^\\s*(?:\\([월화수목금토일](?:요일)?\\))?\\s*,?\\s*(?:(오전|오후)\\s*)?(\\d{1,2})(?::(\\d{2})|시(?:\\s*(\\d{1,2})분)?)(?!\\d)");
    private static final Pattern CLOCK_HINT = Pattern.compile("\\d{1,2}:|\\d{1,2}시");
    private static final Pattern RANGE = Pattern.compile("[~∼〜～–—-]|부터");
    private static final Pattern UNCERTAIN = Pattern.compile("지역별|순위별|유형별|세대별|별도|추후|미정|잠정|예정|변경|정정|취소|이후|이전|이내|상이|다름|발췌 길이 제한");
    private static final Pattern NON_APPLICATION = Pattern.compile("서류|계약|이의");
    private static final Pattern EARLY = Pattern.compile("조기\\s*(?:중단|마감|종료)\\s*가능");

    private AutomaticScheduleCompiler() {
    }

    public record Result(LocalDate startDate, LocalDate endDate, List<ScheduleTemplate> schedules, List<String> issues) {
        public boolean complete() { return startDate != null && endDate != null && issues.isEmpty(); }
    }

    /** Issues are publication blockers; reviewIssues identify optional events omitted from the safe schedule. */
    public record ScopedResult(LocalDate startDate, LocalDate endDate, List<ScheduleTemplate> schedules,
                               List<String> issues, List<ReviewIssue> reviewIssues) {
        public boolean complete() { return startDate != null && endDate != null && issues.isEmpty(); }
    }

    private record Label(int start, int end, Kind kind, boolean range) {}
    private record Dated(int start, int end, LocalDate day) {}
    private record Compilation(Map<Kind, List<Candidate>> candidates, Map<Kind, Candidate> resolved,
                               Map<String, Map<Kind, Candidate>> scopes, Set<String> issues,
                               Set<String> scopeIssues, Map<Kind, List<Unresolved>> unresolved,
                               List<ScheduleTemplate> individualResults) {}

    public static Result compile(List<Source> sources, LocalDate checkedDay) {
        var result = collect(sources, checkedDay, false);
        if (!result.issues().isEmpty()) return new Result(null, null, List.of(), List.copyOf(result.issues()));
        return new Result(result.resolved().get(Kind.START).day(), result.resolved().get(Kind.END).day(),
            schedules(result), List.of());
    }

    public static ScopedResult compileScoped(List<Source> sources, LocalDate checkedDay) {
        var result = collect(sources, checkedDay, true);
        var reviewIssues = ScheduleReviewScope.isolate(result.candidates(), result.resolved(), result.unresolved(), result.issues());
        // A missing/conflicting cohort window must never become an invented aggregate application boundary.
        result.issues().addAll(result.scopeIssues());
        if (!result.issues().isEmpty()) return new ScopedResult(null, null, List.of(), List.copyOf(result.issues()), reviewIssues);
        return new ScopedResult(result.resolved().get(Kind.START).day(), result.resolved().get(Kind.END).day(),
            schedules(result), List.of(), reviewIssues);
    }

    private static Compilation collect(List<Source> sources, LocalDate checkedDay, boolean scopedReview) {
        var candidates = new EnumMap<Kind, List<Candidate>>(Kind.class);
        var issues = new LinkedHashSet<String>();
        var scopeIssues = new LinkedHashSet<String>();
        var unresolved = new EnumMap<Kind, List<Unresolved>>(Kind.class);
        var scoped = new LinkedHashMap<String, Map<Kind, List<Candidate>>>();
        var unresolvedScopes = new LinkedHashSet<String>();
        for (Source source : sources) {
            if (org.jibro.collection.extraction.OcrProvenance.contains(source)) {
                issues.add(org.jibro.collection.extraction.OcrProvenance.REVIEW_REQUIRED);
                continue;
            }
            if (!"EXTRACTED".equals(source.status()) && !(scopedReview && OfficialListingMetadata.onlyFields(source))) {
                if (scopedReview) issues.add("SCHEDULE_SOURCE_UNAVAILABLE");
                continue;
            }
            var reading = ScheduleSourceReader.read(source);
            unresolvedScopes.addAll(reading.unresolvedScopes());
            for (Excerpt excerpt : reading.fields()) {
                // Excerpts must remain bound to the fetched source, not to a link supplied by its text.
                if (source.sha256() == null || source.sha256().isBlank() || source.url() == null
                    || !source.url().equals(excerpt.url()) || excerpt.location() == null || excerpt.location().isBlank()) {
                    issues.add("SCHEDULE_EVIDENCE_MISSING");
                    continue;
                }
                compileExcerpt(source, excerpt, checkedDay, candidates, issues, scopedReview ? unresolved : null);
            }
            for (var field : reading.scoped()) {
                if (source.sha256() == null || source.sha256().isBlank() || source.url() == null) {
                    issues.add("SCHEDULE_EVIDENCE_MISSING"); continue;
                }
                compileExcerpt(source, field.excerpt(), checkedDay, scoped.computeIfAbsent(field.scope(), ignored -> new EnumMap<>(Kind.class)), scopeIssues, null);
            }
        }
        var resolvedScopes = new LinkedHashMap<String, Map<Kind, Candidate>>();
        for (var entry : scoped.entrySet()) resolvedScopes.put(entry.getKey(), resolve(entry.getValue(), scopeIssues));
        for (String scope : unresolvedScopes) {
            if (scoped.keySet().stream().noneMatch(key -> key.equals(scope) || key.startsWith(scope + " · "))) {
                scopeIssues.add("SCHEDULE_SCOPED_PERIOD_MISSING:" + scope);
            }
        }
        addScopedBoundaries(resolvedScopes, candidates, scopeIssues);
        ConcurrentDocumentSubmission.add(sources, candidates, unresolved, issues, resolvedScopes.size() > 1 || !unresolvedScopes.isEmpty());
        issues.addAll(scopeIssues);
        var resolved = resolve(candidates, issues);
        Candidate start = resolved.get(Kind.START), end = resolved.get(Kind.END);
        if (start == null || end == null) issues.add("APPLICATION_PERIOD_MISSING");
        validatePeriod(resolved, issues);
        validateAnnouncementOrder(resolved, !resolvedScopes.isEmpty() || !unresolvedScopes.isEmpty(), issues);
        List<ScheduleTemplate> individual = end != null && end.day().isBefore(checkedDay) ? List.of()
            : IndividualAnnouncementCompiler.read(sources, checkedDay);
        var descriptions = individual.stream().map(event -> event.timingDescription().replaceAll("\\s+", "")).distinct().toList();
        if (descriptions.size() > 1 || !individual.isEmpty()
            && (candidates.containsKey(Kind.RESULT) || unresolved.containsKey(Kind.RESULT))) {
            // Do not silently replace a dated final result or choose between contradictory official clauses.
            issues.add("SCHEDULE_UNRESOLVED:RESULT");
            individual.forEach(event -> unresolved.computeIfAbsent(Kind.RESULT, ignored -> new ArrayList<>())
                .add(new Unresolved(event.timingDescription(), event.evidence())));
            individual = List.of();
        }
        return new Compilation(candidates, resolved, resolvedScopes, issues, scopeIssues, unresolved,
            individual.isEmpty() ? List.of() : List.of(individual.get(0)));
    }

    private static List<ScheduleTemplate> schedules(Compilation result) {
        List<ScheduleTemplate> schedules = new ArrayList<>();
        for (var entry : result.resolved().entrySet()) {
            Kind kind = entry.getKey(); Candidate c = entry.getValue();
            schedules.add(schedule(kind.id, kind.title, c));
        }
        int index = 0;
        for (var entry : result.scopes().entrySet()) {
            String id = "auto-scoped-" + ++index;
            // Scoped titles deliberately differ from the aggregate application boundary contract.
            schedules.add(schedule(id + "-start", entry.getKey() + " 접수 시작", entry.getValue().get(Kind.START)));
            schedules.add(schedule(id + "-end", entry.getKey() + " 접수 종료", entry.getValue().get(Kind.END)));
        }
        schedules.addAll(result.individualResults());
        return List.copyOf(schedules);
    }

    private static Map<Kind, Candidate> resolve(Map<Kind, List<Candidate>> candidates, Set<String> issues) {
        var resolved = new EnumMap<Kind, Candidate>(Kind.class);
        for (var entry : candidates.entrySet()) {
            var dates = entry.getValue().stream().map(Candidate::day).distinct().toList();
            var times = entry.getValue().stream().map(Candidate::time).filter(java.util.Objects::nonNull).distinct().toList();
            if (dates.size() != 1 || times.size() > 1) {
                issues.add("SCHEDULE_CONFLICT:" + entry.getKey());
                continue;
            }
            Candidate first = entry.getValue().get(0);
            Candidate precise = entry.getValue().stream().filter(c -> c.time() != null).findFirst().orElse(first);
            resolved.put(entry.getKey(), new Candidate(first.day(), precise.time(), precise.evidence(),
                entry.getValue().stream().anyMatch(Candidate::early)));
        }
        return resolved;
    }

    private static void validatePeriod(Map<Kind, Candidate> resolved, Set<String> issues) {
        Candidate start = resolved.get(Kind.START), end = resolved.get(Kind.END);
        if (start != null && end != null && (start.day().isAfter(end.day())
            || start.day().equals(end.day()) && start.time() != null && end.time() != null && start.time().isAfter(end.time()))) {
            issues.add("APPLICATION_PERIOD_REVERSED");
        }
    }

    private static void validateAnnouncementOrder(Map<Kind, Candidate> resolved, boolean scoped, Set<String> issues) {
        Candidate end = resolved.get(Kind.END);
        if (end == null) return;
        for (Kind kind : List.of(Kind.SELECTION, Kind.RESULT)) {
            Candidate announcement = resolved.get(kind);
            if (announcement == null) continue;
            if (!before(announcement, end)) continue;
            // A rank/channel window or early closing may explain this order, but does not prove
            // which cohort the announcement belongs to. Do not invent that relationship.
            issues.add(scoped || end.early() ? "SCHEDULE_EVENT_SCOPE_UNRESOLVED:" + kind : "SCHEDULE_ORDER_CONFLICT");
        }
        // Documents may be due before final selection, or collected with the application itself.
        // Their order is not inferred from the generic '결과 발표' label.
    }

    private static void addScopedBoundaries(Map<String, Map<Kind, Candidate>> scopes,
                                           Map<Kind, List<Candidate>> candidates, Set<String> issues) {
        List<Candidate> starts = new ArrayList<>(), ends = new ArrayList<>();
        for (var period : scopes.values()) {
            validatePeriod(period, issues);
            if (period.get(Kind.START) == null || period.get(Kind.END) == null) {
                issues.add("SCHEDULE_SCOPED_PERIOD_MISSING"); continue;
            }
            starts.add(period.get(Kind.START)); ends.add(period.get(Kind.END));
        }
        if (starts.isEmpty()) return;
        var order = java.util.Comparator.comparing(Candidate::day)
            .thenComparing(Candidate::time, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
        candidates.computeIfAbsent(Kind.START, ignored -> new ArrayList<>()).add(starts.stream().min(order).orElseThrow());
        Candidate end = ends.stream().max(order).orElseThrow();
        Candidate boundary = ends.stream().filter(candidate -> candidate.day().equals(end.day()) && candidate.time() == null).findFirst().orElse(end);
        candidates.computeIfAbsent(Kind.END, ignored -> new ArrayList<>()).add(boundary);
    }

    private static ScheduleTemplate schedule(String id, String title, Candidate c) {
        return new ScheduleTemplate(id, title + (c.early() ? " (조기 중단 가능)" : ""),
            c.time() == null ? c.day().toString() : c.day().atTime(c.time()).toString(), c.evidence());
    }

    private static void compileExcerpt(Source source, Excerpt excerpt, LocalDate checkedDay, Map<Kind, List<Candidate>> candidates,
                                       Set<String> issues, Map<Kind, List<Unresolved>> unresolved) {
        String text = excerpt.text();
        List<Label> labels = new ArrayList<>();
        var matcher = LABEL.matcher(text);
        while (matcher.find()) labels.add(new Label(matcher.start(), matcher.end(), matcher.group("documents") != null || matcher.group("documentRange") != null
            ? Kind.DOCUMENTS : matcher.group("selection") != null ? Kind.SELECTION : matcher.group("result") != null ? Kind.RESULT
            : matcher.group("end") != null ? Kind.END : Kind.START,
            matcher.group("range") != null || matcher.group("documentRange") != null));
        for (int index = 0; index < labels.size(); index++) {
            Label label = labels.get(index);
            String value = text.substring(label.end(), index + 1 < labels.size() ? labels.get(index + 1).start() : text.length());
            String prefix = index == 0 ? text.substring(0, label.start()) : "";
            // Headings, help text and explicitly unannounced optional stages are not dated events.
            if (!DATE_HINT.matcher(value).find()) {
                if (value.matches("^[\\s:：]*(?:추후|미정|별도|잠정|예정|미공개|미확정|확인 필요)[\\s\\S]*")) {
                    rememberUnresolved(source, unresolved, label.kind(), prefix + value, excerpt, checkedDay);
                }
                continue;
            }
            // A date from a neighbouring flattened table column cannot belong to a narrative label.
            if (!value.matches("^[\\s:：('’‘`]*(?:은\\s*)?\\d[\\s\\S]*")) continue;
            if ((label.kind() == Kind.START || label.kind() == Kind.END) && NON_APPLICATION.matcher(prefix).find()) {
                issues.add("SCHEDULE_AMBIGUOUS:" + label.kind());
                continue;
            }
            String uncertainty = prefix + value;
            if (label.kind() == Kind.SELECTION || label.kind() == Kind.RESULT) uncertainty = uncertainty.replaceAll("(?<=\\d)(?:시|분)?\\s*이후", "");
            if (UNCERTAIN.matcher(uncertainty).find()) {
                issues.add("SCHEDULE_UNRESOLVED:" + label.kind());
                rememberUnresolved(source, unresolved, label.kind(), uncertainty, excerpt, checkedDay);
                continue;
            }
            List<Dated> dates = new ArrayList<>();
            value = inheritRangeYear(value);
            var date = DATE.matcher(value);
            try {
                while (date.find()) dates.add(new Dated(date.start(), date.end(), LocalDate.of(Integer.parseInt(date.group(1)),
                    Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)))));
            } catch (DateTimeException ex) {
                issues.add("SCHEDULE_INVALID_DATE:" + label.kind());
                continue;
            }
            boolean singleDocumentDay = label.range() && label.kind() == Kind.DOCUMENTS && dates.size() == 1;
            if (!singleDocumentDay && (dates.size() != (label.range() ? 2 : 1)
                || label.range() && !RANGE.matcher(value.substring(dates.get(0).end(), dates.get(1).start())).find())) {
                issues.add("SCHEDULE_AMBIGUOUS:" + label.kind());
                continue;
            }
            var evidence = new Evidence(excerpt.location(), EvidenceLocationUrl.forExcerpt(source, excerpt.location(), excerpt.text()), checkedDay.toString());
            for (int at = 0; at < dates.size(); at++) {
                if (label.kind() == Kind.DOCUMENTS && label.range() && dates.size() > 1 && at == 0) continue;
                Dated d = dates.get(at);
                String suffix = value.substring(d.end(), at + 1 < dates.size() ? dates.get(at + 1).start() : value.length());
                Kind kind = label.kind() == Kind.DOCUMENTS ? Kind.DOCUMENTS : label.range() ? (at == 0 ? Kind.START : Kind.END) : label.kind();
                try {
                    LocalTime time = time(suffix);
                    candidates.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(new Candidate(d.day(), time, evidence,
                        kind == Kind.END && EARLY.matcher(value).find()));
                } catch (DateTimeException ex) {
                    issues.add("SCHEDULE_INVALID_TIME:" + kind);
                }
            }
        }
    }

    private static void rememberUnresolved(Source source, Map<Kind, List<Unresolved>> unresolved, Kind kind, String text,
                                          Excerpt excerpt, LocalDate checkedDay) {
        if (unresolved == null || kind == Kind.START || kind == Kind.END) return;
        unresolved.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(new Unresolved(text.strip(),
            new Evidence(excerpt.location(), EvidenceLocationUrl.forExcerpt(source, excerpt.location(), excerpt.text()), checkedDay.toString())));
    }

    private static String inheritRangeYear(String value) {
        var first = DATE.matcher(value);
        if (!first.find()) return value;
        // An omitted second year is allowed only inside this explicitly dated range, never from the collection date.
        var end = Pattern.compile("((?:[~∼〜～–—]|\\s-\\s|부터)\\s*)(\\d{1,2})\\s*[.월/-]\\s*(\\d{1,2})(?!\\d)").matcher(value.substring(first.end()));
        if (!end.find()) return value;
        int position = first.end() + end.start(2);
        return value.substring(0, position) + first.group(1) + "." + value.substring(position);
    }

    private static LocalTime time(String suffix) {
        var matcher = CLOCK.matcher(suffix);
        if (!matcher.find()) {
            if (CLOCK_HINT.matcher(suffix).find()) throw new DateTimeException("Unbound time");
            return null;
        }
        int hour = Integer.parseInt(matcher.group(2));
        String minutes = matcher.group(3) == null ? matcher.group(4) : matcher.group(3);
        if (matcher.group(1) != null) {
            if (hour < 1 || hour > 12) throw new DateTimeException("Invalid meridiem");
            hour = hour % 12 + (matcher.group(1).equals("오후") ? 12 : 0);
        }
        if (CLOCK_HINT.matcher(suffix.substring(matcher.end())).find()) throw new DateTimeException("Multiple times");
        return LocalTime.of(hour, minutes == null ? 0 : Integer.parseInt(minutes));
    }
}
