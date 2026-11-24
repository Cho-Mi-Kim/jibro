package org.jibro.collection.review;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.ApplicationTiming;
import org.jibro.notice.HousingModels.ReviewIssue;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.ScheduleTemplate;

import static org.jibro.collection.review.ScheduleCandidates.*;

/**
 * Compares a boundary only within the same method and cohort. General portal dates cannot break a tie.
 */
public final class IntakeChannelDates {
    public record Previous(LocalDate day, LocalTime time) {
    }

    public record Attachment(String name, String location, String sha256) {
    }

    public enum AgencyField {KOHOM_INTAKE, VISIT_DAILY, OFFICE_BREAK, OFFICE_CLOSE}

    public record Office(String name, LocalDate edition, String complex) {
    }

    public record Window(String channel, String qualifier, LocalDate start, LocalDate end, String url,
                         LocalTime opens, LocalTime closes, LocalDate issued, Previous previous, Attachment attachment,
                         AgencyField field,
                         LocalTime breakStart, LocalTime breakEnd, Office office) {
        public Window(String channel, String qualifier, LocalDate start, LocalDate end, String url,
                      LocalTime opens, LocalTime closes, LocalDate issued, Previous previous, Attachment attachment, AgencyField field,
                      LocalTime breakStart, LocalTime breakEnd) {
            this(channel, qualifier, start, end, url, opens, closes, issued, previous, attachment, field, breakStart, breakEnd, null);
        }

        public boolean officeOnly() {
            return field == AgencyField.VISIT_DAILY || field == AgencyField.OFFICE_BREAK || field == AgencyField.OFFICE_CLOSE;
        }

        public Window(String channel, String qualifier, LocalDate start, LocalDate end, String url,
                      LocalTime opens, LocalTime closes, LocalDate issued, Previous previous, Attachment attachment, AgencyField field) {
            this(channel, qualifier, start, end, url, opens, closes, issued, previous, attachment, field, null, null);
        }

        public Window(String channel, String qualifier, LocalDate start, LocalDate end, String url,
                      LocalTime opens, LocalTime closes, LocalDate issued, Previous previous, Attachment attachment) {
            this(channel, qualifier, start, end, url, opens, closes, issued, previous, attachment, null);
        }

        public boolean supplementsPeriod() {
            return !officeOnly() && (attachment != null || field == AgencyField.KOHOM_INTAKE);
        }

        public Window(String channel, String qualifier, LocalDate start, LocalDate end, String url,
                      LocalTime opens, LocalTime closes, LocalDate issued, Previous previous) {
            this(channel, qualifier, start, end, url, opens, closes, issued, previous, null);
        }

        public Window from(Attachment file) {
            return new Window(channel, qualifier, start, end, url, opens, closes, issued, previous, file, field, breakStart, breakEnd, office);
        }

        public Window(String channel, String qualifier, LocalDate start, LocalDate end, String url,
                      LocalTime opens, LocalTime closes, LocalDate issued) {
            this(channel, qualifier, start, end, url, opens, closes, issued, null);
        }

        public Window(String channel, String qualifier, LocalDate start, LocalDate end, String url) {
            this(channel, qualifier, start, end, url, null, null, null);
        }

        LocalDate date(Kind kind) {
            return kind == Kind.START ? start : end;
        }

        LocalTime time(Kind kind) {
            return kind == Kind.START ? opens : closes;
        }

        Evidence evidence() {
            return new Evidence((field == AgencyField.KOHOM_INTAKE ? "주택관리공단 · 신청접수일 · " : "공식 후속 공지 · ") + issued
                    + (attachment == null ? "" : " · " + attachment.name() + " · " + attachment.location()), url,
                    LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString());
        }
    }

    private static final Pattern METHOD_SCOPE = Pattern.compile("^(?:(.+?)(?:\\s*·\\s*|\\s+))?(인터넷[/·]모바일|현장방문|현장|방문|인터넷|온라인|등기우편|우편)$");

    private IntakeChannelDates() {
    }

    static String channel(String value) {
        return switch (value.replaceAll("\\s+", "")) {
            case "현장", "방문", "현장방문" -> "현장";
            case "온라인", "인터넷", "인터넷·모바일", "인터넷/모바일" -> "온라인";
            case "우편", "등기우편" -> "우편";
            default -> "";
        };
    }

    static ApplicationPeriodCompiler.Result reconcile(List<Source> sources, Map<Kind, List<Candidate>> common,
                                                      Map<String, Map<Kind, List<Candidate>>> scoped, List<Window> portal, List<ChannelAmendment> amendments) {
        String only = "";
        for (var method : List.of("현장", "온라인", "인터넷", "우편")) {
            var explicit = Pattern.compile(method + "\\s*(?:방문\\s*)?(?:신청|접수)만\\s*가능");
            if (sources.stream().flatMap(s -> s.context().stream()).anyMatch(p -> explicit.matcher(p.text()).find())) {
                if (!only.isEmpty() && !only.equals(channel(method))) return null;
                only = channel(method);
            }
        }
        var groups = scoped.isEmpty() ? Map.of(only, common) : scoped;
        var managementRounds = managementRounds(groups, only, sources, portal);
        var events = new ArrayList<ScheduleTemplate>();
        var bounds = new EnumMap<Kind, List<Candidate>>(Kind.class);
        var review = new ArrayList<ReviewIssue>();
        var applied = new HashSet<ChannelAmendment>();
        int index = 0;
        for (var group : groups.entrySet()) {
            index++;
            String scope = group.getKey();
            // Keep rank, complex, and method distinct. An unlabeled portal row cannot confirm a ranked window.
            var round = Round.parse(scope, only);
            String method = round.method();
            String qualifier = round.qualifier();
            var windows = portal.stream().filter(w -> w.field() == AgencyField.KOHOM_INTAKE
                    ? scope.equals(managementRounds.get(w))
                    : (!method.isEmpty() || w.issued() != null && w.channel().isEmpty()) && method.equals(channel(w.channel()))
                    && normalize(w.qualifier()).equals(normalize(qualifier))).toList();
            ChannelAmendment latest = null;
            boolean conflictingCorrection = false;
            for (var amendment : amendments) {
                if (!method.equals(amendment.method()) || !qualifier.isEmpty()) continue;
                if (!applied.add(amendment)) return null;
                if (latest != null && latest.before().equals(amendment.before()) && latest.after().equals(amendment.after())
                        && latest.published().equals(amendment.published())) continue;
                if (latest == null ? !amendment.before().matches(group.getValue().getOrDefault(Kind.START, List.of()),
                        group.getValue().getOrDefault(Kind.END, List.of()))
                        : !(latest.after().equals(amendment.before()) && latest.published().isBefore(amendment.published())))
                    conflictingCorrection = true;
                latest = amendment;
            }
            Candidate groupStart = null;
            for (var kind : List.of(Kind.START, Kind.END)) {
                var scopedValues = group.getValue().getOrDefault(kind, List.of());
                var values = new LinkedHashSet<>(scopedValues);
                if (groups.size() == 1) values.addAll(common.getOrDefault(kind, List.of()));
                if (latest != null) {
                    var change = latest.after();
                    if (!conflictingCorrection) values.clear();
                    values.add(new Candidate(kind == Kind.START ? change.start() : change.end(),
                            kind == Kind.START ? change.opens() : change.closes(), latest.evidence(), false, null));
                }
                var days = values.stream().map(Candidate::day).distinct().toList();
                LocalDate chosen = days.size() == 1 ? days.get(0) : null;
                var agency = windows.stream().filter(w -> w.issued() != null && (w.date(kind) != null || w.time(kind) != null)).toList();
                var portalDays = windows.stream().filter(w -> w.issued() == null).map(w -> w.date(kind)).filter(Objects::nonNull).distinct().toList();
                // Multiple consecutively advertised rounds describe the outer boundary only for an exclusive channel.
                if (!only.isEmpty() && only.equals(method) && groups.size() == 1 && !portalDays.isEmpty())
                    portalDays = List.of(kind == Kind.START ? Collections.min(portalDays) : Collections.max(portalDays));
                if (latest == null && !portalDays.isEmpty()) {
                    var match = portalDays.size() == 1 ? portalDays.get(0) : null;
                    boolean backed = scopedValues.stream().anyMatch(c -> c.day().equals(match)
                            && !OfficialListingMetadata.displayPeriod(sources, c.evidence())
                            && sources.stream().anyMatch(s -> s.url().equals(EvidenceLocationUrl.base(c.evidence().url()))
                            && !s.label().replace(" ", "").equals("공식상세본문")));
                    if (backed) chosen = match;
                    else if (chosen == null || !portalDays.contains(chosen) || portalDays.size() > 1) chosen = null;
                }
                // A local post may corroborate a primary value for this boundary. A later LH amendment
                // supersedes earlier local guidance only when its explicit change chain was verified.
                if (latest != null) {
                    LocalDate correctionDay = latest.published();
                    agency = agency.stream().filter(w -> !w.issued().isBefore(correctionDay)).toList();
                }
                var dateClaims = agency.stream().filter(w -> w.field() == null).toList();
                chosen = agencyDay(kind, chosen, portalDays, dateClaims, values, sources, latest != null);
                if (conflictingCorrection) chosen = null;
                String id = "auto-scoped-" + index + "-" + kind.name().toLowerCase(Locale.ROOT);
                String name = (scope.isBlank() ? "공통" : scope) + (kind == Kind.START ? " 접수 시작" : " 접수 마감");
                if (chosen == null) {
                    var timed = values.stream().filter(c -> c.time() != null).toList();
                    var first = values.stream().findFirst();
                    if (first.isEmpty()) {
                        if (agency.isEmpty()) continue;
                        return null;
                    }
                    addIssue(review, id, name + " 날짜 확인 필요", values, agency);
                    boolean consistent = agency.stream().map(w -> w.time(kind)).filter(Objects::nonNull)
                            .allMatch(t -> !timed.isEmpty() && t.equals(timed.get(0).time()));
                    String clock = consistent && !conflictingCorrection && !timed.isEmpty() && timed.stream().map(Candidate::time).distinct().count() == 1
                            ? " " + clock(timed.get(0).time()) : "";
                    events.add(new ScheduleTemplate(id, ApplicationTiming.UNDATED_HOURS_SESSION, "", first.get().evidence(),
                            name + clock + " · 접수 날짜 확인 필요"));
                    continue;
                }
                LocalDate day = chosen;
                var matching = values.stream().filter(c -> c.day().equals(day)).toList();
                var times = matching.stream().filter(c -> c.time() != null).toList();
                var precise = !times.isEmpty() && times.stream().map(Candidate::time).distinct().count() == 1
                        ? times.get(0) : new Candidate(day, null, matching.get(0).evidence(), matching.stream().anyMatch(Candidate::early), null);
                var clocks = times.stream().map(Candidate::time).distinct().toList();
                var clockClaims = agency.stream().filter(w -> w.field() == null || clocks.isEmpty() && w.time(kind) != null).toList();
                precise = agencyClock(kind, day, values.stream().map(Candidate::day).distinct().count() == 1, clocks, precise, clockClaims, id, name, values, review);
                if (kind == Kind.START) groupStart = precise;
                else if (before(precise, groupStart)) return null;
                bounds.computeIfAbsent(kind, k -> new ArrayList<>()).add(precise);
                if (!scope.isBlank() || managementRounds.containsValue(scope))
                    events.add(AutomaticScheduleCompiler.schedule(id, "접수 회차 · " + name, precise));
            }
        }
        if (applied.size() != amendments.size()) return null;
        var aggregate = new EnumMap<Kind, LocalDate>(Kind.class);
        for (var kind : List.of(Kind.START, Kind.END)) {
            var values = bounds.getOrDefault(kind, List.of());
            if (values.size() != groups.size()) continue;
            var days = values.stream().map(Candidate::day).toList();
            var day = kind == Kind.START ? Collections.min(days) : Collections.max(days);
            // A general period extending beyond all identified channels might represent another cohort.
            if (groups.size() > 1 && amendments.isEmpty()) {
                var conflicting = common.getOrDefault(kind, List.of()).stream().filter(c -> !c.day().equals(day)).findFirst();
                if (conflicting.isPresent()) {
                    var candidates = new ArrayList<>(common.getOrDefault(kind, List.of()));
                    candidates.addAll(values);
                    addIssue(review, "auto-common-" + kind.name().toLowerCase(Locale.ROOT), kind.title + " 날짜 확인 필요", candidates, List.of());
                    events.add(new ScheduleTemplate("auto-undated-common-" + kind.name().toLowerCase(Locale.ROOT),
                            ApplicationTiming.UNDATED_HOURS_SESSION, "", conflicting.get().evidence(), kind.title + " · 전체 접수 날짜 확인 필요"));
                    continue;
                }
            }
            var edge = values.stream().filter(c -> c.day().equals(day)).toList();
            Candidate c = edge.stream().filter(v -> v.time() == null).findFirst().orElseGet(() -> kind == Kind.START
                    ? Collections.min(edge, Comparator.comparing(Candidate::time)) : Collections.max(edge, Comparator.comparing(Candidate::time)));
            aggregate.put(kind, day);
            events.add(AutomaticScheduleCompiler.schedule(kind.id, kind.title, c));
        }
        var start = aggregate.get(Kind.START);
        var end = aggregate.get(Kind.END);
        if (start != null && end != null && start.isAfter(end) || events.isEmpty() || events.size() > 100 || review.size() > 30 || review.stream().anyMatch(i -> i.evidence().size() > 10))
            return null;
        return new ApplicationPeriodCompiler.Result(start, end, List.copyOf(events), List.of(), List.copyOf(review));
    }

    private record Round(String method, String qualifier) {
        static Round parse(String scope, String exclusiveMethod) {
            String label = scope.replaceFirst("\\s*\\([^()]*제외\\)$", "").strip();
            var matcher = METHOD_SCOPE.matcher(label);
            return matcher.matches() ? new Round(channel(matcher.group(2)), Objects.toString(matcher.group(1), ""))
                    : new Round(exclusiveMethod, label);
        }
    }

    private static Map<Window, String> managementRounds(Map<String, Map<Kind, List<Candidate>>> groups,
                                                        String exclusiveMethod, List<Source> sources, List<Window> windows) {
        var matches = new HashMap<Window, String>();
        for (var w : windows) {
            if (w.field() != AgencyField.KOHOM_INTAKE) continue;
            if (groups.size() > 1 && w.channel().isEmpty() && w.qualifier().isEmpty()) continue;
            var candidates = groups.entrySet().stream().filter(group -> {
                var round = Round.parse(group.getKey(), exclusiveMethod);
                return (w.channel().isEmpty() || channel(w.channel()).equals(round.method()))
                        && (w.qualifier().isEmpty() || normalize(w.qualifier()).equals(normalize(round.qualifier())))
                        && hasAttachmentPeriod(w, sources, group.getValue());
            }).toList();
            if (candidates.size() == 1) matches.put(w, candidates.get(0).getKey());
        }
        return matches;
    }

    private static boolean hasAttachmentPeriod(Window w, List<Source> sources, Map<Kind, List<Candidate>> group) {
        // The same primary attachment must date both edges of this round. A portal summary or
        // two unrelated half-ranges cannot establish that its clock applies to this recruitment.
        return sources.stream().filter(s -> "EXTRACTED".equals(s.status()) && !s.label().replace(" ", "").equals("공식상세본문")
                        && !org.jibro.collection.extraction.OcrProvenance.contains(s))
                .anyMatch(s -> java.util.stream.Stream.of(Kind.START, Kind.END).allMatch(kind -> {
                    var candidates = group.getOrDefault(kind, List.of());
                    return !candidates.isEmpty() && candidates.stream().allMatch(c -> c.day().equals(w.date(kind)))
                            && candidates.stream().anyMatch(c -> EvidenceLocationUrl.base(c.evidence().url()).equals(s.url()));
                }));
    }

    private static LocalDate agencyDay(Kind kind, LocalDate chosen, List<LocalDate> portalDays, List<Window> agency,
                                       Collection<Candidate> values, List<Source> sources, boolean lhAmended) {
        if (agency.isEmpty()) return chosen;
        // Only a documented old-to-new transition can introduce a value missing in the original.
        // Match against original candidates, not against another unverified post.
        var originalValues = List.copyOf(values);
        if (values.isEmpty()) {
            // A verified attachment may supply an omitted boundary in the same recruitment and scope.
            agency.stream().filter(w -> w.attachment() != null && w.previous() == null && w.date(kind) != null)
                    .forEach(w -> values.add(new Candidate(w.date(kind), w.time(kind), w.evidence(), false, null)));
        }
        boolean verifiedChange = false;
        boolean brokenChange = false;
        for (var post : agency) {
            var old = post.previous();
            if (old == null || post.date(kind) == null) continue;
            if (originalValues.stream().noneMatch(c -> c.day().equals(old.day())
                    && (old.time() == null || old.time().equals(c.time())) && !OfficialListingMetadata.displayPeriod(sources, c.evidence()))) {
                brokenChange = true;
                continue;
            }
            verifiedChange = true;
            values.add(new Candidate(post.date(kind), post.time(kind), post.evidence(), false, null));
        }
        var agencyDays = agency.stream().map(w -> w.date(kind)).filter(Objects::nonNull).distinct().toList();
        if (!agencyDays.isEmpty()) {
            LocalDate actual = agencyDays.get(0);
            boolean supported = values.stream().anyMatch(c -> actual.equals(c.day())
                    && !OfficialListingMetadata.displayPeriod(sources, c.evidence()));
            LocalDate portalDay = portalDays.size() == 1 ? portalDays.get(0) : null;
            boolean explicitlyReplaced = verifiedChange && portalDay != null && agency.stream().allMatch(w ->
                    w.previous() != null && w.previous().day().equals(portalDay));
            if (brokenChange || agencyDays.size() != 1 || !lhAmended && !portalDays.isEmpty() && !portalDays.equals(agencyDays) && !explicitlyReplaced
                    || lhAmended && !Objects.equals(chosen, actual) || !lhAmended && !supported) chosen = null;
            else chosen = actual;
        }
        return chosen;
    }

    private static Candidate agencyClock(Kind kind, LocalDate day, boolean uniqueDay, List<LocalTime> clocks,
                                         Candidate precise, List<Window> agency, String id, String name,
                                         Collection<Candidate> values, List<ReviewIssue> review) {
        var clockClaims = agency.stream().filter(w -> w.date(kind) != null ? w.date(kind).equals(day) : uniqueDay).toList();
        var agencyClocks = clockClaims.stream().map(w -> w.time(kind)).filter(Objects::nonNull).distinct().toList();
        if (!agencyClocks.isEmpty() && (agencyClocks.size() != 1 || !clocks.isEmpty() && !clocks.contains(agencyClocks.get(0)))) {
            precise = new Candidate(day, null, precise.evidence(), precise.early(), precise.description());
        } else if (!clockClaims.isEmpty()) {
            var proof = clockClaims.stream().filter(w -> w.time(kind) != null).findFirst().orElse(clockClaims.get(0));
            var confirmedClock = agencyClocks.isEmpty() ? precise.time() : agencyClocks.get(0);
            if (confirmedClock == null || proof.time(kind) != null)
                precise = new Candidate(day, confirmedClock, proof.evidence(), precise.early(), precise.description());
        }
        if (precise.time() == null && (clocks.size() > 1 || !agencyClocks.isEmpty()))
            addIssue(review, id, name + " 시각 확인 필요", values, agency);
        return precise;
    }

    private static void addIssue(List<ReviewIssue> issues, String id, String title,
                                 Collection<Candidate> candidates, List<Window> posts) {
        var evidence = new LinkedHashSet<Evidence>();
        candidates.forEach(c -> evidence.add(c.evidence()));
        posts.forEach(p -> evidence.add(p.evidence()));
        issues.add(new ReviewIssue("intake-review-" + id, "SCHEDULE", title,
                "공식 자료마다 " + (title.contains("시각") ? "접수 시각을" : "접수 날짜를") + " 다르게 안내합니다.", List.of(), List.of(),
                List.copyOf(evidence)));
    }

    private static String normalize(String value) {
        return value.replaceAll("[\\s·]", "");
    }

    private static String clock(LocalTime time) {
        return LocalTime.MAX.equals(time) ? "24:00" : time.toString();
    }
}
