package org.jibro.collection.review;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jibro.collection.review.IntakeChannelDates.AgencyField;
import org.jibro.collection.review.IntakeChannelDates.Window;
import org.jibro.notice.ApplicationPeriod;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.ReviewIssue;
import org.jibro.notice.HousingModels.ScheduleTemplate;

/**
 * Intersect reception hours only inside an identified physical application round.
 */
public final class OfficeHoursAgreement {
    private static final String CLOCK = "(\\d{2}:\\d{2})";
    private static final Pattern DAILY = Pattern.compile("^접수 회차 · (.*?)\\s*(?:현장(?:방문)?|방문)(?:\\s*접수)?"
        + "(?:\\s*\\(점심\\s*" + CLOCK + "~" + CLOCK + "\\s*제외\\))?\\s+" + CLOCK + "~" + CLOCK
        + "(?: · (\\d{4}-\\d{2}-\\d{2})까지 접수일마다)?( \\(공휴일 제외\\))?$");
    private static final Pattern SINGLE = Pattern.compile("^(?:접수 회차 · )?(.*?)\\s*(?:현장(?:방문)?|방문)(?:\\s*\\(점심\\s*" + CLOCK + "~" + CLOCK + "\\s*제외\\))?(?: · (20\\d{2}[.년/-]\\s*\\d{1,2}[.월/-]\\s*\\d{1,2}[.일]?))? 접수 시작$");
    private static final Pattern NOTICE_BREAK = Pattern.compile("방문 접수 점심시간 " + CLOCK + "~" + CLOCK + " 제외");

    record Range(LocalTime start, LocalTime end) {
        boolean valid() {
            return start != null && end != null && start.isBefore(end);
        }

        int from() {
            return start.toSecondOfDay() / 60;
        }

        int to() {
            return end.toSecondOfDay() / 60;
        }
    }

    record Hours(Range day, Range lunch) {
    }

    record Round(ScheduleTemplate row, ScheduleTemplate pairedEnd, String scope, LocalDate first,
                 LocalDate last, Hours hours, String suffix) {
    }

    private OfficeHoursAgreement() {
    }

    /**
     * Intersects complete, paired source claims for the same explicitly labelled visit day.
     */
    static Map<ScheduleCandidates.Kind, ScheduleCandidates.Candidate> sourceRound(String scope,
                                                                                  Map<ScheduleCandidates.Kind, List<ScheduleCandidates.Candidate>> claims,
                                                                                  Map<ScheduleCandidates.Kind, ScheduleCandidates.Candidate> resolved, Set<String> blockers,
                                                                                  List<ReviewIssue> review, int group) {
        if (!scope.matches(".*(?:현장|방문).*")) return resolved;
        var starts = claims.getOrDefault(ScheduleCandidates.Kind.START, List.of());
        var ends = claims.getOrDefault(ScheduleCandidates.Kind.END, List.of());
        if (starts.isEmpty() || ends.isEmpty() || starts.stream().anyMatch(c -> c.time() == null) || ends.stream().anyMatch(c -> c.time() == null))
            return resolved;
        var days = java.util.stream.Stream.concat(starts.stream(), ends.stream()).map(ScheduleCandidates.Candidate::day).distinct().toList();
        if (days.size() != 1) return resolved;
        var evidence = java.util.stream.Stream.concat(starts.stream(), ends.stream()).map(ScheduleCandidates.Candidate::evidence).distinct().toList();
        for (var e : evidence) {
            var first = starts.stream().filter(c -> c.evidence().equals(e)).toList();
            var last = ends.stream().filter(c -> c.evidence().equals(e)).toList();
            if (first.size() != 1 || last.size() != 1 || !first.get(0).time().isBefore(last.get(0).time()))
                return resolved;
        }
        var first = starts.stream().max(java.util.Comparator.comparing(ScheduleCandidates.Candidate::time)).orElseThrow();
        var last = ends.stream().min(java.util.Comparator.comparing(ScheduleCandidates.Candidate::time)).orElseThrow();
        if (!first.time().isBefore(last.time()) || evidence.size() > 10) return resolved;
        if (starts.stream().map(ScheduleCandidates.Candidate::time).distinct().count() == 1 && ends.stream().map(ScheduleCandidates.Candidate::time).distinct().count() == 1)
            return resolved;
        String detail = "같은 방문 접수일에 공식 자료의 시간이 서로 달라요. 공통으로 확인된 " + first.time() + "~" + last.time() + "는 안내하고, 그 밖의 엇갈리는 시간대만 기관에 확인해요. 점심 제외 시간은 회차 안내를 따라요.\n";
        detail += evidence.stream().map(e -> e.label() + " · " + starts.stream().filter(c -> c.evidence().equals(e)).findFirst().orElseThrow().time() + "~" + ends.stream().filter(c -> c.evidence().equals(e)).findFirst().orElseThrow().time()).collect(java.util.stream.Collectors.joining("\n"));
        if (detail.length() > 3000) return resolved;
        var result = new java.util.EnumMap<ScheduleCandidates.Kind, ScheduleCandidates.Candidate>(ScheduleCandidates.Kind.class);
        result.putAll(resolved);
        result.put(ScheduleCandidates.Kind.START, first);
        result.put(ScheduleCandidates.Kind.END, last);
        blockers.remove("SCHEDULE_CONFLICT:START");
        blockers.remove("SCHEDULE_CONFLICT:END");
        review.add(new ReviewIssue("auto-visit-source-hours-" + group, "SCHEDULE", scope + " · 방문 접수 시간 차이", detail, List.of(), List.of(), evidence));
        return result;
    }

    private static String key(String scope) {
        return scope.replaceAll("[\\s·]+", "");
    }

    static Round round(ScheduleTemplate row, List<ScheduleTemplate> rows) {
        if (row.date() == null || row.evidence() == null) return null;
        var daily = DAILY.matcher(row.title());
        if (row.id().startsWith("auto-visit-") && daily.matches() && row.date().length() == 10) {
            var day = LocalDate.parse(row.date());
            var last = daily.group(6) == null ? day : LocalDate.parse(daily.group(6));
            var lunch = daily.group(2) == null ? null : new Range(LocalTime.parse(daily.group(2)), LocalTime.parse(daily.group(3)));
            return new Round(row, null, daily.group(1), day, last,
                new Hours(new Range(LocalTime.parse(daily.group(4)), LocalTime.parse(daily.group(5))), lunch),
                row.title().substring(daily.end(5)));
        }
        var single = SINGLE.matcher(row.title());
        if (!row.id().startsWith("auto-scoped-") || !row.id().endsWith("-start") || row.date().length() != 16 || !single.matches())
            return null;
        String endId = row.id().replaceFirst("-start$", "-end");
        var end = rows.stream().filter(s -> s.id().equals(endId) && s.date() != null && s.date().length() == 16
            && s.date().substring(0, 10).equals(row.date().substring(0, 10))
            && s.title().replaceFirst(" 접수 (?:마감|종료)$", " 접수 시작").equals(row.title())
            && s.evidence() != null && EvidenceLocationUrl.base(s.evidence().url()).equals(EvidenceLocationUrl.base(row.evidence().url()))).findFirst();
        if (end.isEmpty()) return null;
        var day = LocalDate.parse(row.date().substring(0, 10));
        return new Round(row, end.get(), single.group(1), day, day,
            new Hours(new Range(LocalTime.parse(row.date().substring(11)), LocalTime.parse(end.get().date().substring(11))),
                single.group(2) == null ? null : new Range(LocalTime.parse(single.group(2)), LocalTime.parse(single.group(3)))), "");
    }

    public static boolean hasVisitRounds(ApplicationPeriodCompiler.Result period) {
        return period != null && DeskHoursAgreement.hasVisitRows(period.schedules());
    }

    public static List<Window> attachmentHours(org.jibro.collection.extraction.ExtractionModels.Source source, LocalDate issued) {
        var schedule = AutomaticScheduleCompiler.compileIntake(List.of(source), issued);
        if (!schedule.issues().isEmpty()) return List.of();
        var windows = new ArrayList<Window>();
        for (var s : schedule.schedules()) {
            var r = round(s, schedule.schedules());
            if (r == null || !r.hours().day().valid()) continue;
            var lunch = r.hours().lunch();
            windows.add(new Window("현장", r.scope().replaceFirst("[·\\s]+$", ""), r.first(), r.last(), source.url(),
                r.hours().day().start(), r.hours().day().end(), issued, null,
                new IntakeChannelDates.Attachment(source.label(), s.evidence().label(), source.sha256()), AgencyField.VISIT_DAILY,
                lunch == null ? null : lunch.start(), lunch == null ? null : lunch.end()));
        }
        return List.copyOf(windows);
    }

    static boolean overlaps(Window w, Round round, LocalDate edition) {
        return w.office() == null && w.issued() != null && (edition == null || !w.issued().isBefore(edition))
            && w.start() != null && w.end() != null && !w.start().isAfter(w.end()) && !w.start().isAfter(round.last()) && !w.end().isBefore(round.first())
            && (w.field() == AgencyField.VISIT_DAILY || w.start().equals(w.end()))
            && IntakeChannelDates.channel(w.channel()).equals("현장") && key(round.scope()).equals(key(java.util.Objects.toString(w.qualifier(), "")))
            && new Range(w.opens(), w.closes()).valid();
    }

    private static List<ScheduleTemplate> splitRanges(List<ScheduleTemplate> rows, List<Window> posts, java.util.function.Function<Round, LocalDate> effectiveEdition) {
        var result = new ArrayList<ScheduleTemplate>();
        for (var row : rows) {
            var round = round(row, rows);
            if (round == null || round.first().equals(round.last())) {
                result.add(row);
                continue;
            }
            var cuts = new java.util.TreeSet<LocalDate>();
            cuts.add(round.first());
            cuts.add(round.last().plusDays(1));
            for (var post : posts) {
                if (!overlaps(post, round, effectiveEdition.apply(round))) continue;
                if (post.start().isAfter(round.first())) cuts.add(post.start());
                if (post.end().isBefore(round.last())) cuts.add(post.end().plusDays(1));
            }
            if (cuts.size() == 2) {
                result.add(row);
                continue;
            }
            for (var first : cuts.headSet(cuts.last())) {
                var last = cuts.higher(first).minusDays(1);
                String title = row.title().replace(round.last() + "까지 접수일마다", last + "까지 접수일마다");
                String id = row.id().substring(0, row.id().length() - 10) + first;
                result.add(new ScheduleTemplate(id, title, first.toString(), row.evidence()));
            }
        }
        return List.copyOf(result);
    }

    static BitSet minutes(Hours hours, Range sharedLunch) {
        var bits = new BitSet(1440);
        bits.set(hours.day().from(), hours.day().to());
        var lunch = hours.lunch() == null ? sharedLunch : hours.lunch();
        if (lunch != null && lunch.valid()) bits.clear(lunch.from(), lunch.to());
        return bits;
    }

    static String intervals(BitSet minutes) {
        var labels = new ArrayList<String>();
        for (int from = minutes.nextSetBit(0); from >= 0; ) {
            int until = minutes.nextClearBit(from);
            labels.add(LocalTime.ofSecondOfDay(from * 60L) + "~" + LocalTime.ofSecondOfDay(until * 60L));
            from = minutes.nextSetBit(until);
        }
        return String.join(", ", labels);
    }

    /**
     * Retain the latest notice's daily rows when the channel resolver returned only period boundaries.
     */
    public static ApplicationPeriodCompiler.Result forPublication(ApplicationPeriodCompiler.Result basis,
                                                                  ApplicationPeriodCompiler.Result source, List<Window> posts, LocalDate edition,
                                                                  ApplicationPeriodCompiler.Result original, LocalDate originalDate) {
        if (source != null && source.ready() && posts.stream().anyMatch(w -> w.office() != null)
            && (basis == null || source.start() != null && source.start().equals(basis.start()) && source.end() != null && source.end().equals(basis.end()))) {
            var updated = compare(source, posts, edition, original, originalDate);
            if (updated == null || updated.schedules().stream().noneMatch(s -> s.id().startsWith("auto-visit-desk-"))
                || basis != null && !updated.reviewIssues().containsAll(basis.reviewIssues()))
                return compare(basis, posts, edition, original, originalDate);
            if (basis == null || !basis.ready() || !basis.reviewIssues().isEmpty()) return updated;
            var rows = new ArrayList<>(updated.schedules());
            // Copy only newly verified boundary clocks. Never restore a clock that the desk comparison removed.
            rows.replaceAll(s -> {
                if (s.date() == null || s.date().length() != 10 || source.schedules().stream()
                    .noneMatch(old -> old.id().equals(s.id()) && s.date().equals(old.date()))) return s;
                return basis.schedules().stream().filter(r -> r.id().equals(s.id()) && r.date() != null
                        && r.date().startsWith(s.date() + "T") && (r.title().replaceFirst("^접수 회차 · ", "").replaceFirst(" 접수 마감$", " 접수 종료").equals(s.title().replaceFirst(" 접수 마감$", " 접수 종료"))
                        || ApplicationPeriod.basicTitle(s.title(), true) && ApplicationPeriod.basicTitle(r.title(), true)
                        || ApplicationPeriod.basicTitle(s.title(), false) && ApplicationPeriod.basicTitle(r.title(), false)))
                    .map(r -> new ScheduleTemplate(s.id(), s.title(), r.date(), r.evidence())).findFirst().orElse(s);
            });
            return new ApplicationPeriodCompiler.Result(updated.start(), updated.end(), List.copyOf(rows), updated.issues(), updated.reviewIssues());
        }
        return compare(basis, posts, edition, original, originalDate);
    }

    public static ApplicationPeriodCompiler.Result compare(ApplicationPeriodCompiler.Result period, List<Window> posts, LocalDate edition) {
        return compare(period, posts, edition, null, null);
    }

    public static ApplicationPeriodCompiler.Result compare(ApplicationPeriodCompiler.Result period, List<Window> posts,
                                                           LocalDate edition, ApplicationPeriodCompiler.Result previous, LocalDate originalDate) {
        if (period == null || !period.ready() || posts.isEmpty()) return null;
        var prior = previous == null || !previous.ready() ? List.<Round>of() : previous.schedules().stream()
            .map(s -> round(s, previous.schedules())).filter(java.util.Objects::nonNull).toList();
        // A rent or document correction does not revoke the office's earlier hours for an unchanged round.
        java.util.function.Function<Round, LocalDate> effectiveEdition = current -> prior.stream().anyMatch(old ->
            key(current.scope()).equals(key(old.scope())) && !current.first().isBefore(old.first())
                && !current.last().isAfter(old.last()) && current.hours().equals(old.hours())
                && current.suffix().contains("공휴일 제외") == old.suffix().contains("공휴일 제외")) ? originalDate : edition;
        var ordinary = compareDaily(period, posts, effectiveEdition);
        var scoped = DeskHoursAgreement.compare(period, ordinary, posts, edition, effectiveEdition);
        return scoped != null ? scoped : ordinary;
    }

    private static ApplicationPeriodCompiler.Result compareDaily(ApplicationPeriodCompiler.Result period, List<Window> posts,
                                                                 java.util.function.Function<Round, LocalDate> effectiveEdition) {
        var rows = splitRanges(period.schedules(), posts, effectiveEdition);
        if (rows.size() > 100) return null;
        var schedules = new ArrayList<>(rows);
        var issues = new ArrayList<>(period.reviewIssues());
        var disputedBoundaries = new LinkedHashSet<String>();
        boolean changed = false;
        for (var row : rows) {
            var round = round(row, rows);
            if (round == null || !round.hours().day().valid()) continue;
            var since = effectiveEdition.apply(round);
            var matches = posts.stream().filter(w -> overlaps(w, round, since) && !w.start().isAfter(round.first()) && !w.end().isBefore(round.last())).toList();
            if (matches.isEmpty()) continue;
            var claims = new ArrayList<Hours>();
            claims.add(round.hours());
            var evidence = new LinkedHashSet<Evidence>();
            evidence.add(row.evidence());
            if (round.pairedEnd() != null) evidence.add(round.pairedEnd().evidence());
            for (var note : period.schedules()) {
                if (!note.id().equals("auto-visit-break") || note.timingDescription() == null || note.evidence() == null
                    || !EvidenceLocationUrl.base(row.evidence().url()).equals(EvidenceLocationUrl.base(note.evidence().url())))
                    continue;
                var pause = NOTICE_BREAK.matcher(note.timingDescription());
                if (pause.matches()) {
                    claims.add(new Hours(round.hours().day(), new Range(LocalTime.parse(pause.group(1)), LocalTime.parse(pause.group(2)))));
                    evidence.add(note.evidence());
                }
            }
            for (var w : matches) {
                claims.add(new Hours(new Range(w.opens(), w.closes()), w.breakStart() == null ? null : new Range(w.breakStart(), w.breakEnd())));
                evidence.add(w.evidence());
            }
            var breaks = claims.stream().map(Hours::lunch).filter(java.util.Objects::nonNull).distinct().toList();
            Range lunch = breaks.size() == 1 ? breaks.get(0) : null;
            var common = minutes(round.hours(), lunch);
            var union = (BitSet) common.clone();
            for (var claim : claims) {
                var bits = minutes(claim, lunch);
                common.and(bits);
                union.or(bits);
            }
            union.andNot(common);
            String commonText = common.isEmpty() ? "공통 확인 시간 없음" : intervals(common);
            String label = "접수 회차 · " + round.scope() + "방문 " + commonText + round.suffix();
            if (!union.isEmpty()) {
                String difference = intervals(union);
                label += " · 확인 필요 " + difference;
                if (evidence.size() > 10 || issues.size() >= 30) return null;
                issues.add(new ReviewIssue("intake-review-auto-visit-" + row.id().replaceFirst("^auto-(?:visit-auto-)?scoped-", ""),
                    "SCHEDULE", "방문 접수 시간 확인 필요", round.first() + (round.first().equals(round.last()) ? "" : "~" + round.last())
                    + " " + round.scope() + "방문 접수 " + difference + "은 공식 자료의 시간이 달라 접수처 확인이 필요합니다.",
                    List.of(), List.of(), List.copyOf(evidence)));
                for (var claim : claims) {
                    if (!claim.day().start().equals(round.hours().day().start()))
                        disputedBoundaries.add(round.first() + "T" + round.hours().day().start());
                    if (!claim.day().end().equals(round.hours().day().end()))
                        disputedBoundaries.add(round.last() + "T" + round.hours().day().end());
                }
            }
            if (label.length() > 300) return null;
            schedules.set(schedules.indexOf(row), new ScheduleTemplate(row.id(), label, round.first().toString(), row.evidence()));
            if (round.pairedEnd() != null) schedules.remove(round.pairedEnd());
            changed = true;
        }
        if (!changed) return null;
        // Preserve a boundary independently verified for another application method.
        for (var s : schedules) {
            if (s.id().startsWith("auto-scoped-") && (s.id().endsWith("-start") || s.id().endsWith("-end")))
                disputedBoundaries.remove(s.date());
        }
        schedules.replaceAll(s -> disputedBoundaries.contains(s.date()) && (ApplicationPeriod.basicTitle(s.title(), false)
            || ApplicationPeriod.basicTitle(s.title(), true)) ? new ScheduleTemplate(s.id(), ApplicationPeriod.basicTitle(s.title(), true)
            ? "신청 접수 마감" : "신청 접수 시작", s.date().substring(0, 10), s.evidence()) : s);
        return new ApplicationPeriodCompiler.Result(period.start(), period.end(), List.copyOf(schedules), period.issues(), List.copyOf(issues));
    }
}
