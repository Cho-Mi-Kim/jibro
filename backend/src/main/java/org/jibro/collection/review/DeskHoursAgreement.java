package org.jibro.collection.review;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jibro.collection.review.IntakeChannelDates.AgencyField;
import org.jibro.collection.review.IntakeChannelDates.Window;
import org.jibro.collection.review.OfficeHoursAgreement.*;
import org.jibro.notice.ApplicationPeriod;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.ReviewIssue;
import org.jibro.notice.HousingModels.ScheduleTemplate;

/** A counter's lunch and the last timestamp of a multi-day interval have narrower scope than daily hours. */
final class DeskHoursAgreement {
    private static final Pattern LUNCH = Pattern.compile("^(?:접수 회차 · )?(.*?)현장 \\(점심 (\\d{2}:\\d{2})~(\\d{2}:\\d{2}) 제외\\) 접수 시작$");
    private record Visit(LocalDate first, LocalDate last, Range day, Range lunch, String scope, ScheduleTemplate row, Round round) {}
    private record Claim(LocalDate first, LocalDate last, Window source) {}
    private DeskHoursAgreement() {}

    private static Visit visit(ScheduleTemplate row, List<ScheduleTemplate> rows) {
        var round = OfficeHoursAgreement.round(row, rows);
        if (round != null && round.hours().day().valid())
            return new Visit(round.first(), round.last(), round.hours().day(), round.hours().lunch(), round.scope(), row, round);
        var lunch = LUNCH.matcher(row.title());
        if (!row.id().matches("auto-scoped-\\d+-start") || !lunch.matches() || row.date() == null || row.evidence() == null) return null;
        var end = rows.stream().filter(s -> s.id().equals(row.id().replace("-start","-end")) && s.date() != null
            && s.title().replaceFirst("(종료|마감)$","시작").equals(row.title())).findFirst();
        if (end.isEmpty()) return null;
        return new Visit(LocalDate.parse(row.date().substring(0,10)), LocalDate.parse(end.get().date().substring(0,10)), null,
            new Range(LocalTime.parse(lunch.group(2)),LocalTime.parse(lunch.group(3))),lunch.group(1),row,null);
    }

    static boolean hasVisitRows(List<ScheduleTemplate> rows) {
        return rows.stream().anyMatch(row -> visit(row,rows) != null);
    }

    private static String key(String s) { return s.replaceAll("[\\s·]+",""); }

    private static boolean sameScope(Window w, Visit v) {
        String scope = key(v.scope()), rank = key(w.qualifier()), name = key(w.office().name());
        return scope.equals(rank) || scope.equals(rank + name) || scope.equals(name + rank);
    }

    private static boolean closing(Window w) { return w.field() == AgencyField.KOHOM_INTAKE || w.field() == AgencyField.OFFICE_CLOSE; }

    private static Claim claim(Window w, Visit v, LocalDate edition, ApplicationPeriodCompiler.Result base) {
        if (w.office() == null || w.office().name().isBlank() || !Objects.equals(w.office().edition(), edition)
            || !sameScope(w,v) || w.start() == null || w.end() == null || w.start().isAfter(w.end())) return null;
        LocalDate first = w.start(), last = w.end();
        if (closing(w)) {
            boolean samePeriod = first.equals(base.start()) && last.equals(base.end());
            boolean sameDay = first.equals(last) && first.equals(v.first()) && last.equals(v.last());
            if (v.day() == null || w.closes() == null || (!samePeriod && !sameDay) || !w.channel().matches("|현장|방문")) return null;
            first = last;
        } else if (w.field() == AgencyField.VISIT_DAILY) {
            if (v.day() == null || !new Range(w.opens(), w.closes()).valid()) return null;
        } else if (w.field() != AgencyField.OFFICE_BREAK || !new Range(w.breakStart(),w.breakEnd()).valid()) return null;
        if (first.isAfter(v.last()) || last.isBefore(v.first())) return null;
        return new Claim(first.isAfter(v.first()) ? first : v.first(), last.isBefore(v.last()) ? last : v.last(), w);
    }

    private static BitSet pause(Range lunch) {
        var bits = new BitSet(1440);
        if (lunch != null && lunch.valid()) bits.set(lunch.from(), lunch.to());
        return bits;
    }

    static ApplicationPeriodCompiler.Result compare(ApplicationPeriodCompiler.Result base, ApplicationPeriodCompiler.Result ordinary,
            List<Window> posts, LocalDate edition, Function<Round, LocalDate> effectiveEdition) {
        if (posts.stream().noneMatch(w -> w.office() != null)) return null;
        var result = ordinary == null ? base : ordinary;
        var rows = new ArrayList<>(result.schedules());
        var issues = new ArrayList<>(result.reviewIssues());
        var visits = base.schedules().stream().map(row -> visit(row, base.schedules())).filter(Objects::nonNull).toList();
        var disputed = new HashSet<String>();
        int count = 0;
        for (var v : visits) {
            var since = v.round() == null ? edition : effectiveEdition.apply(v.round());
            var desks = new TreeMap<String,List<Claim>>();
            for (var w : posts) {
                // Either the correction itself or the unchanged original round can identify the edition.
                var c = claim(w, v, Objects.equals(edition,w.office() == null ? null : w.office().edition()) ? edition : since, base);
                if (c != null) desks.computeIfAbsent(w.office().name(), k -> new ArrayList<>()).add(c);
            }
            for (var entry : desks.entrySet()) {
                var claims = entry.getValue();
                var cuts = new TreeSet<LocalDate>();
                for (var c : claims) { cuts.add(c.first()); cuts.add(c.last().plusDays(1)); }
                var general = v.round() == null ? List.<Window>of() : posts.stream()
                    .filter(w -> w.office() == null && OfficeHoursAgreement.overlaps(w,v.round(),since)).toList();
                for (var w : general) {
                    if (!w.start().isBefore(v.first()) && !w.start().isAfter(v.last())) cuts.add(w.start());
                    if (!w.end().isBefore(v.first()) && w.end().isBefore(v.last())) cuts.add(w.end().plusDays(1));
                }
                for (var from : cuts.headSet(cuts.last())) {
                    LocalDate until = cuts.higher(from).minusDays(1);
                    var active = claims.stream().filter(c -> !c.first().isAfter(from) && !c.last().isBefore(until)).map(Claim::source).toList();
                    if (active.isEmpty()) continue;
                    var all = new ArrayList<>(active);
                    all.addAll(general.stream().filter(w -> !w.start().isAfter(from) && !w.end().isBefore(until)).toList());
                    var evidence = new LinkedHashSet<Evidence>();
                    evidence.add(v.row().evidence());
                    var hours = new ArrayList<Hours>();
                    hours.add(new Hours(v.day(),v.lunch()));
                    for (var w : all) {
                        evidence.add(w.evidence());
                        var day = closing(w) ? new Range(w.start().equals(w.end()) && w.opens() != null ? w.opens() : v.day().start(),w.closes())
                            : w.opens() == null ? v.day() : new Range(w.opens(),w.closes());
                        hours.add(new Hours(day,w.breakStart() == null ? v.lunch() : new Range(w.breakStart(),w.breakEnd())));
                    }
                    var breaks = hours.stream().map(Hours::lunch).filter(Objects::nonNull).distinct().toList();
                    Range shared = breaks.size() == 1 ? breaks.get(0) : null;
                    BitSet common = null;
                    var union = new BitSet(1440);
                    for (var h : hours) {
                        var bits = v.day() == null ? pause(h.lunch() == null ? shared : h.lunch()) : h.day().valid() ? OfficeHoursAgreement.minutes(h,shared) : new BitSet(1440);
                        if (common == null) common = (BitSet)bits.clone(); else common.and(bits);
                        union.or(bits);
                    }
                    union.andNot(common);
                    String office = entry.getKey();
                    String span = from.equals(until) ? from.toString() : from + "~" + until;
                    String qualifier = active.get(0).qualifier();
                    String label = "접수 회차 · " + (qualifier.isBlank() ? "" : qualifier + " · ") + office + " 방문" + (from.equals(until) ? "" : " · " + until + "까지 접수일마다");
                    if (v.round() != null && v.round().suffix().contains("공휴일 제외")) label += " (공휴일 제외)";
                    if (v.day() != null) label += common.isEmpty() ? " · 공통 확인 시간 없음" : " · " + OfficeHoursAgreement.intervals(common);
                    else if (!common.isEmpty()) label += " · 점심 " + OfficeHoursAgreement.intervals(common) + " 제외";
                    String id = "auto-visit-desk-" + ++count;
                    if (!union.isEmpty()) {
                        String diff = OfficeHoursAgreement.intervals(union);
                        if (v.day() == null && v.lunch() != null) label += " · 공고문 점심 " + v.lunch().start() + "~" + v.lunch().end();
                        if (v.day() == null && active.size() == 1) label += " · 접수처 점심 " + active.get(0).breakStart() + "~" + active.get(0).breakEnd();
                        if (v.day() != null && rows.contains(v.row())) {
                            int index = rows.indexOf(v.row());
                            rows.set(index, new ScheduleTemplate(v.row().id(),v.row().title().replace("접수 회차 · ", "접수 회차 · 공고문 기준 ")
                                + " · 접수처별 예외 참조",v.row().date(),v.row().evidence()));
                        }
                        label += " · 확인 필요 " + diff;
                        if (evidence.size() > 10 || issues.size() >= 30) throw new IllegalStateException("OFFICE_REVIEW_LIMIT");
                        issues.add(new ReviewIssue("intake-review-" + id,"SCHEDULE","방문 접수 시간 확인 필요",
                            span + " " + office + "의 " + diff + " 접수 여부를 확인해 주세요.",List.of(),List.of(),List.copyOf(evidence)));
                        if (v.day() != null) {
                            if (hours.stream().anyMatch(h -> !h.day().end().equals(v.day().end()))) disputed.add(until + "T" + v.day().end());
                            if (hours.stream().anyMatch(h -> !h.day().start().equals(v.day().start()))) disputed.add(from + "T" + v.day().start());
                        }
                    }
                    rows.add(new ScheduleTemplate(id,label,from.toString(),active.get(0).evidence()));
                }
            }
        }
        if (count == 0) return null;
        for (var row : rows) if (row.id().startsWith("auto-scoped-") && (row.title().contains("온라인") || row.title().contains("인터넷") || row.title().contains("우편")))
            disputed.remove(row.date());
        rows.replaceAll(s -> disputed.contains(s.date()) && (ApplicationPeriod.basicTitle(s.title(),true) || ApplicationPeriod.basicTitle(s.title(),false))
            ? new ScheduleTemplate(s.id(),ApplicationPeriod.basicTitle(s.title(),true) ? "신청 접수 마감" : "신청 접수 시작",
                s.date().substring(0,10),s.evidence()) : s);
        if (rows.size() > 100) throw new IllegalStateException("OFFICE_SCHEDULE_LIMIT");
        return new ApplicationPeriodCompiler.Result(result.start(),result.end(),List.copyOf(rows),result.issues(),List.copyOf(issues));
    }
}
