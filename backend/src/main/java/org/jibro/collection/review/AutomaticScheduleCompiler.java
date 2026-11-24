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
import org.jibro.notice.HousingModels.ApplicationTiming;
import org.jibro.notice.HousingModels.ReviewIssue;
import org.jibro.notice.HousingModels.ScheduleTemplate;

/**
 * Compiles explicit dates and evidence-bound individual results; never invents an application deadline.
 */
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

    public record Result(LocalDate startDate, LocalDate endDate, List<ScheduleTemplate> schedules,
                         List<String> issues, List<ReviewIssue> reviewIssues) {
        public boolean complete() {
            return startDate != null && endDate != null && issues.isEmpty();
        }
    }

    /**
     * Issues are publication blockers; reviewIssues identify optional events omitted from the safe schedule.
     */
    public record ScopedResult(LocalDate startDate, LocalDate endDate, List<ScheduleTemplate> schedules,
                               List<String> issues, List<ReviewIssue> reviewIssues) {
        public boolean complete() {
            return startDate != null && endDate != null && issues.isEmpty();
        }
    }

    private record Label(int start, int end, Kind kind, boolean range) {
    }

    private record Dated(int start, int end, LocalDate day) {
    }

    private record Compilation(Map<Kind, List<Candidate>> candidates, Map<Kind, Candidate> resolved,
                               Map<String, Map<Kind, Candidate>> scopes, Set<String> issues,
                               Set<String> scopeIssues, Map<Kind, List<Unresolved>> unresolved,
                               List<ScheduleTemplate> individualResults, ScheduleTemplate rolling,
                               Set<String> dailyVisits,
                               Map<String, Map<Kind, List<Candidate>>> scopedCandidates,
                               Map<Kind, List<Candidate>> common,
                               List<ReviewIssue> scopedReviewIssues) {
    }

    public static Result compile(List<Source> rawSources, LocalDate checkedDay) {
        var sources = rawSources.stream().map(org.jibro.collection.review.local.DocumentContextReader::normative).toList();
        var result = collect(sources, checkedDay, false, false);
        if (!result.issues().isEmpty())
            return new Result(null, null, List.of(), List.copyOf(result.issues()), List.copyOf(result.scopedReviewIssues()));
        var reviewIssues = new ArrayList<>(result.scopedReviewIssues());
        return new Result(result.resolved().get(Kind.START).day(), result.resolved().get(Kind.END).day(),
                schedules(result, sources, checkedDay, reviewIssues), List.of(), List.copyOf(reviewIssues));
    }

    public static ScopedResult compileScoped(List<Source> rawSources, LocalDate checkedDay) {
        var sources = rawSources.stream().map(org.jibro.collection.review.local.DocumentContextReader::normative).toList();
        var result = collect(sources, checkedDay, true, false);
        var reviewIssues = new ArrayList<>(result.scopedReviewIssues());
        reviewIssues.addAll(ScheduleReviewScope.isolate(result.candidates(), result.resolved(), result.unresolved(), result.issues()));
        // A missing/conflicting cohort window must never become an invented aggregate application boundary.
        result.issues().addAll(result.scopeIssues());
        if (!result.issues().isEmpty())
            return new ScopedResult(null, null, List.of(), List.copyOf(result.issues()), reviewIssues);
        return new ScopedResult(result.resolved().get(Kind.START).day(), result.resolved().get(Kind.END).day(),
                schedules(result, sources, checkedDay, reviewIssues), List.of(), List.copyOf(reviewIssues));
    }

    static ApplicationPeriodCompiler.Result reconcile(List<Source> rawSources, LocalDate day, List<IntakeChannelDates.Window> windows, List<ChannelAmendment> amendments) {
        var sources = rawSources.stream().map(org.jibro.collection.review.local.DocumentContextReader::normative).toList();
        windows = windows.stream().filter(w -> !w.officeOnly()).toList();
        var c = collect(sources, day, true, true);
        boolean disputed = c.issues().stream().anyMatch(i -> i.startsWith("SCHEDULE_CONFLICT:"));
        if (amendments.isEmpty() && !disputed && windows.stream().noneMatch(IntakeChannelDates.Window::supplementsPeriod)
                || c.issues().stream().anyMatch(i -> !Set.of("SCHEDULE_CONFLICT:START", "SCHEDULE_CONFLICT:END",
                "APPLICATION_PERIOD_MISSING", "SCHEDULE_SCOPED_PERIOD_MISSING").contains(i))) return null;
        var ends = new ArrayList<>(c.common().getOrDefault(Kind.END, List.of()));
        c.scopedCandidates().values().forEach(group -> ends.addAll(group.getOrDefault(Kind.END, List.of())));
        if (amendments.isEmpty() && !ends.isEmpty() && ends.stream().allMatch(value -> value.day().isBefore(day)))
            return null;
        if (c.rolling() != null && (!ends.isEmpty() || !c.scopedCandidates().isEmpty() || windows.stream().anyMatch(w -> w.end() != null)))
            return null;
        var result = IntakeChannelDates.reconcile(sources, c.common(), c.scopedCandidates(), windows, amendments);
        if (result != null && !disputed && amendments.isEmpty() && windows.stream().noneMatch(w -> w.attachment() != null)) {
            var fields = windows.stream().filter(w -> w.field() != null).map(IntakeChannelDates.Window::evidence).toList();
            if (result.schedules().stream().noneMatch(s -> fields.contains(s.evidence()))
                    && result.reviewIssues().stream().flatMap(i -> i.evidence().stream()).noneMatch(fields::contains))
                return null;
        }
        if (result == null) return null;
        if (amendments.isEmpty() && result.issues().isEmpty()) {
            // A portal or agency can correct an endpoint without confirming daily office hours.
            var daily = new LinkedHashSet<String>();
            for (String scope : c.dailyVisits()) {
                var original = c.scopes().get(scope);
                if (original == null || original.get(Kind.START) == null || original.get(Kind.END) == null) continue;
                boolean confirmed = true;
                for (var kind : List.of(Kind.START, Kind.END)) {
                    var expected = schedule("", "", original.get(kind));
                    String title = "접수 회차 · " + scope + (kind == Kind.START ? " 접수 시작" : " 접수 마감");
                    confirmed &= result.schedules().stream().anyMatch(s -> s.title().equals(title)
                            && s.date().equals(expected.date()) && s.evidence().equals(expected.evidence()));
                }
                if (confirmed) daily.add(scope);
            }
            var reviewIssues = new ArrayList<>(result.reviewIssues());
            var enriched = VisitReception.enrich(result.schedules(), daily, sources, day, result.end(), reviewIssues);
            result = new ApplicationPeriodCompiler.Result(result.start(), result.end(), enriched, result.issues(), List.copyOf(reviewIssues));
        }
        if (c.rolling() == null) return result;
        var sessions = new ArrayList<>(result.schedules());
        sessions.add(c.rolling());
        return new ApplicationPeriodCompiler.Result(result.start(), null, List.copyOf(sessions), result.issues(), result.reviewIssues());
    }

    static Result compileIntake(List<Source> rawSources, LocalDate checkedDay) {
        var sources = rawSources.stream().map(org.jibro.collection.review.local.DocumentContextReader::normative).toList();
        var result = collect(sources, checkedDay, true, true);
        var start = result.resolved().get(Kind.START);
        var end = result.resolved().get(Kind.END);
        var reviewIssues = new ArrayList<>(result.scopedReviewIssues());
        if (result.rolling() != null && result.issues().stream().allMatch(i ->
                i.equals("APPLICATION_PERIOD_MISSING") || i.equals("SCHEDULE_CONFLICT:START"))
                && !result.candidates().containsKey(Kind.END) && result.scopedCandidates().isEmpty()) {
            var events = new ArrayList<>(schedules(result, sources, checkedDay, reviewIssues));
            events.add(result.rolling());
            if (result.issues().contains("SCHEDULE_CONFLICT:START")) {
                var clocks = IntakeExplanation.clocks(result.candidates(), "", 0);
                if (clocks.isEmpty()) events.add(new ScheduleTemplate("auto-undated-rolling-start",
                        ApplicationTiming.UNDATED_HOURS_SESSION, "", result.rolling().evidence(), "상시모집 접수 시작일·시각 확인 필요"));
                else events.addAll(clocks);
            }
            return new Result(start == null ? null : start.day(), null, List.copyOf(events), List.of(), List.copyOf(reviewIssues));
        }
        if (result.issues().stream().anyMatch(i -> i.startsWith("SCHEDULE_CONFLICT:"))
                && result.issues().stream().allMatch(i -> Set.of("SCHEDULE_CONFLICT:START", "SCHEDULE_CONFLICT:END",
                "APPLICATION_PERIOD_MISSING", "SCHEDULE_SCOPED_PERIOD_MISSING").contains(i))) {
            var events = IntakeExplanation.conflictingClocks(result.candidates(), result.scopedCandidates(), checkedDay);
            if (!events.isEmpty() && events.size() <= 100)
                return new Result(null, null, List.copyOf(events), List.of(), List.copyOf(reviewIssues));
        }
        if (result.issues().equals(Set.of("APPLICATION_PERIOD_MISSING"))
                && (start != null && start.time() != null || end != null && end.time() != null))
            return new Result(start == null ? null : start.day(), end == null ? null : end.day(), schedules(result, sources, checkedDay, reviewIssues), List.of(), List.copyOf(reviewIssues));
        if (!result.issues().isEmpty())
            return new Result(null, null, List.of(), List.copyOf(result.issues()), List.copyOf(result.scopedReviewIssues()));
        return new Result(result.resolved().get(Kind.START).day(), result.resolved().get(Kind.END).day(),
                schedules(result, sources, checkedDay, reviewIssues), List.of(), List.copyOf(reviewIssues));
    }

    private static Compilation collect(List<Source> sources, LocalDate checkedDay, boolean scopedReview, boolean intakeOnly) {
        var candidates = new EnumMap<Kind, List<Candidate>>(Kind.class);
        var issues = new LinkedHashSet<String>();
        var scopeIssues = new LinkedHashSet<String>();
        var unresolved = new EnumMap<Kind, List<Unresolved>>(Kind.class);
        var scoped = new LinkedHashMap<String, Map<Kind, List<Candidate>>>();
        var scopedUnresolved = new LinkedHashMap<String, Map<Kind, List<Unresolved>>>();
        var scopedBlockers = new LinkedHashMap<String, Set<String>>();
        var scopedReviews = new ArrayList<ReviewIssue>();
        var cohortIntakes = new LinkedHashMap<String, Set<String>>();
        var resultCohorts = new LinkedHashMap<String, Set<String>>();
        var unresolvedScopes = new LinkedHashSet<String>();
        var missingBySource = new LinkedHashMap<Source, Set<String>>();
        var dailyVisits = new LinkedHashSet<String>();
        var tableRanks = new LinkedHashMap<String, Set<String>>();
        ScheduleTemplate rolling = null;
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
            missingBySource.put(source, reading.unresolvedScopes());
            for (Excerpt excerpt : reading.fields()) {
                // Excerpts must remain bound to the fetched source, not to a link supplied by its text.
                if (source.sha256() == null || source.sha256().isBlank() || source.url() == null
                        || !source.url().equals(excerpt.url()) || excerpt.location() == null || excerpt.location().isBlank()) {
                    issues.add("SCHEDULE_EVIDENCE_MISSING");
                    continue;
                }
                var condition = IntakeExplanation.rolling(excerpt.text());
                if (condition.matches()) {
                    if (rolling == null)
                        rolling = new ScheduleTemplate("auto-until-filled", ApplicationTiming.UNTIL_FILLED_SESSION, "",
                                new Evidence(excerpt.location(), EvidenceLocationUrl.forExcerpt(source, excerpt.location(), excerpt.text()), checkedDay.toString()),
                                "공고문에 모집이 완료될 때까지 접수한다고 명시되어 있습니다.");
                    if (condition.group("start") == null) continue;
                    excerpt = new Excerpt("SCHEDULE", "접수 시작: " + condition.group("start"), excerpt.location(), excerpt.url());
                }
                compileExcerpt(source, excerpt, checkedDay, candidates, issues, scopedReview ? unresolved : null, intakeOnly);
            }
            for (var field : reading.scoped()) {
                if (field.excerpt().location().contains(" · 표 ") && source.label().toLowerCase(java.util.Locale.ROOT).endsWith(".hwpx")) {
                    var ranks = Pattern.compile("(?<![\\d,])\\d(?:\\s*[,·~]\\s*\\d)*\\s*순위").matcher(field.scope());
                    while (ranks.find())
                        tableRanks.computeIfAbsent(variantStem(source), ignored -> new LinkedHashSet<>())
                                .add(ranks.group().replaceAll("\\s", ""));
                }
                if (source.sha256() == null || source.sha256().isBlank() || source.url() == null) {
                    issues.add("SCHEDULE_EVIDENCE_MISSING");
                    continue;
                }
                String scope = field.scope().replace("인터넷·모바일", "인터넷");
                if (field.dailyVisit()) dailyVisits.add(scope);
                if (intakeOnly && field.excerpt().text().startsWith("결과 발표:")) continue;
                if (field.cohort() != null) {
                    if (field.excerpt().text().startsWith("결과 발표:"))
                        resultCohorts.computeIfAbsent(scope, ignored -> new LinkedHashSet<>()).add(field.cohort());
                    else cohortIntakes.computeIfAbsent(field.cohort(), ignored -> new LinkedHashSet<>()).add(scope);
                }
                compileExcerpt(source, field.excerpt(), checkedDay, scoped.computeIfAbsent(scope, ignored -> new EnumMap<>(Kind.class)),
                        scopedBlockers.computeIfAbsent(scope, ignored -> new LinkedHashSet<>()),
                        scopedReview ? scopedUnresolved.computeIfAbsent(scope, ignored -> new EnumMap<>(Kind.class)) : null, intakeOnly);
            }
        }
        var resolvedScopes = new LinkedHashMap<String, Map<Kind, Candidate>>();
        for (var entry : scoped.entrySet())
            resolvedScopes.put(entry.getKey(), resolve(entry.getValue(), scopedBlockers.get(entry.getKey())));
        int reviewIndex = 0;
        for (var entry : scoped.entrySet()) {
            var blockers = scopedBlockers.get(entry.getKey());
            var values = resolvedScopes.get(entry.getKey());
            if (scopedReview)
                values = OfficeHoursAgreement.sourceRound(entry.getKey(), entry.getValue(), values, blockers, scopedReviews, reviewIndex + 1);
            var sameCohortEnds = resultCohorts.getOrDefault(entry.getKey(), Set.of()).stream()
                    .flatMap(cohort -> cohortIntakes.getOrDefault(cohort, Set.of()).stream())
                    .map(resolvedScopes::get).map(value -> value.get(Kind.END)).filter(java.util.Objects::nonNull).distinct().toList();
            var compared = new EnumMap<Kind, Candidate>(Kind.class);
            compared.putAll(values);
            sameCohortEnds.stream().max(java.util.Comparator.comparing(Candidate::day)
                            .thenComparing(Candidate::time, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                    .ifPresent(end -> compared.put(Kind.END, end));
            validateAnnouncementOrder(compared, false, blockers);
            if (scopedReview) {
                int group = ++reviewIndex;
                var comparedCandidates = new EnumMap<Kind, List<Candidate>>(Kind.class);
                comparedCandidates.putAll(entry.getValue());
                if (!sameCohortEnds.isEmpty()) comparedCandidates.put(Kind.END, sameCohortEnds);
                for (var finding : ScheduleReviewScope.isolate(comparedCandidates, compared,
                        scopedUnresolved.getOrDefault(entry.getKey(), Map.of()), blockers))
                    scopedReviews.add(new ReviewIssue(finding.id() + "-scope-" + group, finding.area(),
                            entry.getKey() + " · " + finding.title(), finding.detail(), finding.questionIds(), finding.documentIds(), finding.evidence()));
                values.keySet().retainAll(compared.keySet());
            }
            scopeIssues.addAll(blockers);
            resolvedScopes.put(entry.getKey(), values);
        }
        var announced = resolvedScopes.values().stream().map(scope -> scope.get(Kind.RESULT)).filter(java.util.Objects::nonNull).toList();
        var intakeScopes = new LinkedHashMap<String, Map<Kind, Candidate>>();
        resolvedScopes.forEach((scope, values) -> {
            if (values.containsKey(Kind.START) || values.containsKey(Kind.END)) intakeScopes.put(scope, values);
        });
        if (!announced.isEmpty() && candidates.containsKey(Kind.RESULT)) {
            candidates.get(Kind.RESULT).removeIf(candidate -> OfficialListingMetadata.representativeAnnouncement(sources, candidate.evidence())
                    && announced.stream().anyMatch(result -> candidate.day().equals(result.day())
                    && (candidate.time() == null || candidate.time().equals(result.time()))));
            if (candidates.get(Kind.RESULT).isEmpty()) candidates.remove(Kind.RESULT);
            else {
                issues.add("SCHEDULE_UNRESOLVED:RESULT");
                candidates.get(Kind.RESULT).forEach(candidate -> unresolved.computeIfAbsent(Kind.RESULT, ignored -> new ArrayList<>())
                        .add(new Unresolved("순위별 결과 발표와 공통 발표의 적용 대상 확인 필요", candidate.evidence())));
            }
        }
        missingBySource.forEach((source, missing) -> missing.stream()
                .filter(scope -> !source.label().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                        || !tableRanks.getOrDefault(variantStem(source), Set.of()).contains(scope))
                .forEach(unresolvedScopes::add));
        for (String scope : unresolvedScopes) {
            if (scoped.keySet().stream().noneMatch(key -> key.equals(scope) || key.startsWith(scope + " · "))) {
                scopeIssues.add("SCHEDULE_SCOPED_PERIOD_MISSING:" + scope);
            }
        }
        excludeRepresentativeListing(sources, intakeScopes, candidates);
        if (rolling != null && intakeOnly) {
            // A portal's display interval is not a fixed application deadline in an explicitly rolling attachment.
            for (var kind : List.of(Kind.START, Kind.END)) {
                var values = candidates.get(kind);
                if (values == null) continue;
                if (kind == Kind.END || values.stream().allMatch(c -> OfficialListingMetadata.displayPeriod(sources, c.evidence())))
                    values.removeIf(c -> c.time() == null && OfficialListingMetadata.displayPeriod(sources, c.evidence()));
                if (values.isEmpty()) candidates.remove(kind);
            }
        }
        var common = new EnumMap<Kind, List<Candidate>>(Kind.class);
        candidates.forEach((kind, values) -> common.put(kind, List.copyOf(values)));
        addScopedBoundaries(intakeScopes, candidates, scopeIssues);
        if (!intakeOnly)
            ConcurrentDocumentSubmission.add(sources, candidates, unresolved, issues, intakeScopes.size() > 1 || !unresolvedScopes.isEmpty());
        issues.addAll(scopeIssues);
        var resolved = resolve(candidates, issues);
        Candidate start = resolved.get(Kind.START), end = resolved.get(Kind.END);
        if (rolling != null && candidates.containsKey(Kind.END)) issues.add("OPEN_ENDED_APPLICATION_REQUIRES_REVIEW");
        if (start == null || end == null) issues.add("APPLICATION_PERIOD_MISSING");
        validatePeriod(resolved, issues);
        if (issues.isEmpty() && intakeScopes.size() == 1) {
            var round = intakeScopes.values().iterator().next();
            // The complete interval must identify this single round before sharing a clock.
            if (start != null && end != null && round.get(Kind.START) != null && round.get(Kind.END) != null
                    && start.day().equals(round.get(Kind.START).day()) && end.day().equals(round.get(Kind.END).day())) {
                round.replaceAll((kind, value) -> {
                    var boundary = resolved.get(kind);
                    return value.time() == null && boundary != null && boundary.time() != null
                            ? new Candidate(value.day(), boundary.time(), boundary.evidence(), value.early() || boundary.early(), value.description()) : value;
                });
            }
        }
        validateAnnouncementOrder(resolved, !intakeScopes.isEmpty() || !unresolvedScopes.isEmpty(), issues);
        List<ScheduleTemplate> individual = intakeOnly || end != null && end.day().isBefore(checkedDay) ? List.of()
                : IndividualAnnouncementCompiler.read(sources, checkedDay);
        var descriptions = individual.stream().map(event -> event.timingDescription().replaceAll("\\s+", "")).distinct().toList();
        if (descriptions.size() > 1 || !individual.isEmpty()
                && (candidates.containsKey(Kind.RESULT) || unresolved.containsKey(Kind.RESULT) || !announced.isEmpty())) {
            // Do not silently replace a dated final result or choose between contradictory official clauses.
            issues.add("SCHEDULE_UNRESOLVED:RESULT");
            individual.forEach(event -> unresolved.computeIfAbsent(Kind.RESULT, ignored -> new ArrayList<>())
                    .add(new Unresolved(event.timingDescription(), event.evidence())));
            individual = List.of();
        }
        return new Compilation(candidates, resolved, resolvedScopes, issues, scopeIssues, unresolved,
                individual.isEmpty() ? List.of() : List.of(individual.get(0)), rolling, dailyVisits, scoped, common, List.copyOf(scopedReviews));
    }

    private static String variantStem(Source source) {
        return source.label().replaceFirst("(?i)\\.(?:pdf|hwpx)$", "").replaceAll("\\s", "");
    }

    private static void excludeRepresentativeListing(List<Source> sources, Map<String, Map<Kind, Candidate>> scopes,
                                                     Map<Kind, List<Candidate>> candidates) {
        if (scopes.size() < 2) return;
        for (Source source : sources) {
            if (!source.label().replace(" ", "").equals("공식상세본문") || source.context().stream().noneMatch(part ->
                    part.text().matches("(?s).*단지별.{0,15}(?:접수기간|공급일정).{0,30}(?:상이|공고문.{0,6}참조).*"))) continue;
            var starts = listingDates(candidates, Kind.START, source);
            var ends = listingDates(candidates, Kind.END, source);
            if (starts.size() != 1 || ends.size() != 1) continue;
            if (scopes.values().stream().anyMatch(scope -> scope.containsKey(Kind.START) && scope.containsKey(Kind.END)
                    && starts.get(0).day().equals(scope.get(Kind.START).day()) && ends.get(0).day().equals(scope.get(Kind.END).day()))) {
                candidates.get(Kind.START).remove(starts.get(0));
                candidates.get(Kind.END).remove(ends.get(0));
            }
        }
    }

    private static List<Candidate> listingDates(Map<Kind, List<Candidate>> values, Kind kind, Source source) {
        return values.getOrDefault(kind, List.of()).stream()
                .filter(c -> c.time() == null && source.url().equals(c.evidence().url())).toList();
    }

    private static List<ScheduleTemplate> schedules(Compilation result, List<Source> sources, LocalDate checkedDay, List<ReviewIssue> reviewIssues) {
        List<ScheduleTemplate> schedules = new ArrayList<>();
        for (var entry : result.resolved().entrySet()) {
            Kind kind = entry.getKey();
            Candidate c = entry.getValue();
            schedules.add(schedule(kind.id, kind.title, c));
        }
        int index = 0;
        int eventIndex = 0;
        for (var entry : result.scopes().entrySet()) {
            if (entry.getValue().containsKey(Kind.START) || entry.getValue().containsKey(Kind.END)) {
                String id = "auto-scoped-" + ++index;
                // Scoped titles deliberately differ from the aggregate application boundary contract.
                schedules.add(schedule(id + "-start", entry.getKey() + " 접수 시작", entry.getValue().get(Kind.START)));
                schedules.add(schedule(id + "-end", entry.getKey() + " 접수 종료", entry.getValue().get(Kind.END)));
            }
            var announcement = entry.getValue().get(Kind.RESULT);
            if (announcement != null)
                schedules.add(schedule("auto-result-scope-" + ++eventIndex, entry.getKey() + " 결과 발표", announcement));
        }
        schedules.addAll(result.individualResults());
        var end = result.resolved().get(Kind.END);
        return VisitReception.enrich(schedules, result.dailyVisits(), sources, checkedDay,
                end != null ? end.day() : result.rolling() == null ? null : checkedDay, reviewIssues);
    }

    private static Map<Kind, Candidate> resolve(Map<Kind, List<Candidate>> candidates, Set<String> issues) {
        var resolved = new EnumMap<Kind, Candidate>(Kind.class);
        for (var entry : candidates.entrySet()) {
            var dates = entry.getValue().stream().map(Candidate::day).distinct().toList();
            var times = entry.getValue().stream().map(Candidate::time).filter(java.util.Objects::nonNull).distinct().toList();
            var qualifiers = entry.getValue().stream().filter(candidate -> candidate.time() != null).map(Candidate::description).distinct().toList();
            if (dates.size() != 1 || times.size() > 1 || qualifiers.size() > 1) {
                issues.add("SCHEDULE_CONFLICT:" + entry.getKey());
                continue;
            }
            Candidate first = entry.getValue().get(0);
            Candidate precise = entry.getValue().stream().filter(c -> c.time() != null).findFirst().orElse(first);
            resolved.put(entry.getKey(), new Candidate(first.day(), precise.time(), precise.evidence(),
                    entry.getValue().stream().anyMatch(Candidate::early), precise.description()));
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
            if (!period.containsKey(Kind.START) && !period.containsKey(Kind.END)) continue;
            validatePeriod(period, issues);
            if (period.get(Kind.START) == null || period.get(Kind.END) == null) {
                issues.add("SCHEDULE_SCOPED_PERIOD_MISSING");
                continue;
            }
            starts.add(period.get(Kind.START));
            ends.add(period.get(Kind.END));
        }
        if (starts.isEmpty()) return;
        var order = java.util.Comparator.comparing(Candidate::day)
                .thenComparing(Candidate::time, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()));
        candidates.computeIfAbsent(Kind.START, ignored -> new ArrayList<>()).add(starts.stream().min(order).orElseThrow());
        Candidate end = ends.stream().max(order).orElseThrow();
        Candidate boundary = ends.stream().filter(candidate -> candidate.day().equals(end.day()) && candidate.time() == null).findFirst().orElse(end);
        candidates.computeIfAbsent(Kind.END, ignored -> new ArrayList<>()).add(boundary);
    }

    static ScheduleTemplate schedule(String id, String title, Candidate c) {
        if (LocalTime.MAX.equals(c.time()))
            return new ScheduleTemplate(id, title + " (24:00)", c.day().toString(), c.evidence());
        return new ScheduleTemplate(id, title + (c.early() ? " (조기 중단 가능)" : ""),
                c.time() == null ? c.day().toString() : c.day().atTime(c.time()).toString(), c.evidence(), c.description());
    }

    private static void compileExcerpt(Source source, Excerpt excerpt, LocalDate checkedDay, Map<Kind, List<Candidate>> candidates,
                                       Set<String> issues, Map<Kind, List<Unresolved>> unresolved, boolean intakeOnly) {
        String text = excerpt.text();
        List<Label> labels = new ArrayList<>();
        var matcher = LABEL.matcher(text);
        while (matcher.find())
            labels.add(new Label(matcher.start(), matcher.end(), matcher.group("documents") != null || matcher.group("documentRange") != null
                    ? Kind.DOCUMENTS : matcher.group("selection") != null ? Kind.SELECTION : matcher.group("result") != null ? Kind.RESULT
                    : matcher.group("end") != null ? Kind.END : Kind.START,
                    matcher.group("range") != null || matcher.group("documentRange") != null));
        for (int index = 0; index < labels.size(); index++) {
            Label label = labels.get(index);
            if (intakeOnly && label.kind() != Kind.START && label.kind() != Kind.END) continue;
            String value = text.substring(label.end(), index + 1 < labels.size() ? labels.get(index + 1).start() : text.length());
            String prefix = index == 0 ? text.substring(0, label.start()) : "";
            if (value.matches("^[\\s:：]*확인 필요[\\s\\S]*")) {
                rememberUnresolved(source, unresolved, label.kind(), prefix + value, excerpt, checkedDay);
                continue;
            }
            // Headings, help text and explicitly unannounced optional stages are not dated events.
            if (!DATE_HINT.matcher(value).find()) {
                if (value.matches("^[\\s:：]*(?:추후|미정|별도|잠정|예정|미공개|미확정)[\\s\\S]*")) {
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
            if (label.kind() == Kind.SELECTION || label.kind() == Kind.RESULT)
                uncertainty = uncertainty.replaceAll("(?<=\\d)(?:시|분)?\\s*이후", "");
            if (UNCERTAIN.matcher(uncertainty).find()) {
                issues.add("SCHEDULE_UNRESOLVED:" + label.kind());
                rememberUnresolved(source, unresolved, label.kind(), uncertainty, excerpt, checkedDay);
                continue;
            }
            List<Dated> dates = new ArrayList<>();
            value = inheritRangeYear(value);
            var date = DATE.matcher(value);
            try {
                while (date.find())
                    dates.add(new Dated(date.start(), date.end(), LocalDate.of(Integer.parseInt(date.group(1)),
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
                    LocalTime time = time(suffix, kind);
                    candidates.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(new Candidate(d.day(), time, evidence,
                            kind == Kind.END && EARLY.matcher(value).find(),
                            (kind == Kind.RESULT || kind == Kind.SELECTION) && suffix.contains("이후") && time != null
                                    ? time + " 이후 발표" : null));
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

    private static LocalTime time(String suffix, Kind kind) {
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
        // LocalTime has no 24:00. Preserve the official closing day until the schedule is serialized.
        if (kind == Kind.END && hour == 24 && "00".equals(matcher.group(3)) && matcher.group(1) == null)
            return LocalTime.MAX;
        return LocalTime.of(hour, minutes == null ? 0 : Integer.parseInt(minutes));
    }
}
