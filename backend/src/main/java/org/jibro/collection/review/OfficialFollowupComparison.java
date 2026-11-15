package org.jibro.collection.review;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OcrProvenance;
import org.jibro.collection.review.followup.OfficialFollowupModels.*;
import org.jibro.notice.HousingModels.*;
import static org.jibro.collection.review.FollowupClaimReader.*;

/** Pure comparison of fetched, related official sources. It neither discovers relations nor publishes. */
public final class OfficialFollowupComparison {
    public record Resolution(String field, String before, String after, List<Evidence> previousEvidence,
                             Evidence replacementEvidence, String relationshipProof) {
        public Resolution { previousEvidence = List.copyOf(previousEvidence); }
    }
    public record Invalidation(String code, String statement, Evidence evidence, String relationshipProof) {}
    public record Result(GuidanceReviewCompiler.Assessment assessment, List<Resolution> resolutions, List<String> blockers,
                         List<Invalidation> invalidations) {
        public Result { resolutions = List.copyOf(resolutions); blockers = List.copyOf(blockers); invalidations = List.copyOf(invalidations); }
        public Result(GuidanceReviewCompiler.Assessment assessment, List<Resolution> resolutions, List<String> blockers) {
            this(assessment, resolutions, blockers, List.of());
        }
        public boolean invalidatesPublishedGuide() {
            return !invalidations.isEmpty() || resolutions.stream().anyMatch(resolution -> Arrays.stream(resolution.before().split("\\R"))
                .anyMatch(before -> !equivalent(before, resolution.after())));
        }
    }
    private record Occurrence(Source source, String location, String line, Value value) {}
    private OfficialFollowupComparison() {}

    public static Result compare(Draft primary, Content original, Discovery discovery) {
        Objects.requireNonNull(primary); Objects.requireNonNull(original); Objects.requireNonNull(discovery);
        var blockers = new LinkedHashSet<>(discovery.blockers());
        var baseline = GuidanceReviewCompiler.assess(primary, original);
        if (discovery.related().isEmpty()) return result(baseline, List.of(), blockers);
        if (discovery.checkedAt() == null || primary.checkedAt() == null
            || discovery.checkedAt().isBefore(primary.checkedAt()) || discovery.related().size() > 10) {
            blockers.add("FOLLOWUP_DISCOVERY_INVALID"); return result(baseline, List.of(), blockers);
        }
        String checked = discovery.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        var coverage = SourceCoveragePolicy.assessScoped(primary, original);
        // Full original coverage remains mandatory. A good follow-up cannot certify an unread primary page.
        blockers.addAll(coverage.issues());
        var sourceHashes = new LinkedHashMap<String, String>();
        primary.sources().forEach(source -> sourceHashes.put(source.url(), source.sha256()));
        var readings = new ArrayList<Change>();
        var invalidations = new ArrayList<Invalidation>();
        for (Related related : discovery.related()) {
            if (!Set.of("CORRECTION", "FOLLOWUP").contains(related.kind()) || !official(related.url())
                || related.relationshipProof() == null || related.relationshipProof().isBlank()
                || related.publishedDate() == null && related.kind().equals("FOLLOWUP")
                || related.publishedDate() != null && related.publishedDate().isAfter(LocalDate.parse(checked))
                || related.sources().isEmpty() || related.sources().size() > 51
                || related.sources().stream().noneMatch(source -> source.url().equals(related.url()))) {
                blockers.add("FOLLOWUP_RELATION_UNVERIFIED"); continue;
            }
            boolean valid = true;
            for (Source source : related.sources()) {
                if (!complete(source)) { blockers.add("FOLLOWUP_SOURCE_INCOMPLETE"); valid = false; continue; }
                if (OcrProvenance.contains(source)) { blockers.add(OcrProvenance.REVIEW_REQUIRED); valid = false; }
                String previous = sourceHashes.putIfAbsent(source.url(), source.sha256());
                if (previous != null && !previous.equals(source.sha256())) {
                    blockers.add("FOLLOWUP_SOURCE_IDENTITY_CHANGED"); valid = false;
                }
            }
            if (!valid) continue;
            var reading = FollowupClaimReader.read(related);
            blockers.addAll(reading.blockers()); readings.addAll(reading.changes());
            if (related.kind().equals("CORRECTION")) {
                // A verified complete replacement can make the old guide stale even when its new
                // governing clause is outside the closed compiler grammar. This is review work,
                // not an invented field-level conflict or an assertion that the new clause is parsed.
                reading.governingClauses().stream().filter(clause -> !presentIn(primary, clause.statement()))
                    .forEach(clause -> invalidations.add(new Invalidation("FOLLOWUP_CORRECTION_REQUIRES_RECOMPILATION",
                        clause.statement(), evidence(clause.source(), clause.location(), clause.statement(), checked), related.relationshipProof())));
            }
        }
        if (readings.size() > 100) { blockers.add("FOLLOWUP_CLAIM_LIMIT"); return result(baseline, List.of(), blockers, invalidations); }
        readings.sort(Comparator.comparing(Change::published, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(change -> change.source().url()));
        var occurrences = occurrences(primary);
        var removed = new LinkedHashSet<Occurrence>();
        var active = new ArrayList<Change>();
        var resolutions = new ArrayList<Resolution>();
        boolean scopedApplication = primary.sources().stream().map(ScheduleSourceReader::read)
            .anyMatch(reading -> !reading.scoped().isEmpty() || !reading.unresolvedScopes().isEmpty());
        for (Change change : readings) {
            String field = change.value().field();
            if (field.startsWith("SCHEDULE:") && scopedApplication) {
                blockers.add("FOLLOWUP_EVENT_SCOPE_UNSUPPORTED"); continue;
            }
            var matching = occurrences.stream().filter(old -> old.value().field().equals(field)
                && (change.before() == null || equivalent(old.line(), change.before()))).toList();
            var preceding = active.stream().filter(old -> old.value().field().equals(field)
                && old.published() != null && change.published() != null && old.published().isBefore(change.published())
                && (change.before() != null ? equivalent(old.value().statement(), change.before())
                    : change.value().uncertain() || old.value().uncertain())).toList();
            if (matching.isEmpty() && preceding.isEmpty()) {
                blockers.add("FOLLOWUP_CHANGED_FIELD_NOT_FOUND:" + field); continue;
            }
            // Only an explicit old value, or an explicit later unresolved→confirmed transition,
            // replaces an already encountered follow-up. Newer timestamps alone do not win conflicts.
            active.removeAll(preceding);
            removed.addAll(matching);
            active.add(change);
            List<Evidence> prior = new ArrayList<>();
            matching.forEach(old -> prior.add(evidence(old.source(), old.location(), old.line(), checked)));
            preceding.forEach(old -> prior.add(evidence(old.source(), old.location(), old.value().statement(), checked)));
            String before = matching.stream().map(Occurrence::line).distinct().reduce((a,b) -> a + "\n" + b)
                .orElseGet(() -> preceding.stream().map(old -> old.value().statement()).distinct().reduce((a,b) -> a + "\n" + b).orElse(""));
            resolutions.add(new Resolution(field, before, change.value().statement(), prior.stream().distinct().toList(),
                evidence(change.source(), change.location(), change.value().statement(), checked), change.relationshipProof()));
        }
        if (active.isEmpty()) return result(baseline, resolutions, blockers, invalidations);

        // These are ephemeral claim projections. Raw sources and their SHA/context in Discovery/Draft
        // are untouched. Removing a recognized superseded line never removes its neighbouring exception.
        var supersededRules = new LinkedHashSet<String>();
        removed.stream().filter(old -> !old.value().field().startsWith("SCHEDULE:")).forEach(old -> supersededRules.add(old.line()));
        var ruleReplacements = active.stream().filter(change -> !change.value().field().startsWith("SCHEDULE:"))
            .map(change -> new AutomaticGuidanceCompiler.LocatedRule(GuidanceRuleGrammar.parse(change.value().statement()),
                evidence(change.source(), change.location(), change.value().statement(), checked), change.value().statement())).toList();
        var scheduleSources = project(coverage.scheduleSources(), removed, true);
        scheduleSources.addAll(replacements(active, true));
        var assessment = GuidanceReviewCompiler.assess(primary, original, scheduleSources, supersededRules, ruleReplacements);
        if (assessment.candidate() != null) {
            Content candidate = assessment.candidate();
            var attachments = new LinkedHashMap<String,Evidence>();
            candidate.attachments().forEach(item -> attachments.put(item.url(), item));
            discovery.related().forEach(related -> related.sources().forEach(source ->
                attachments.putIfAbsent(source.url(), evidence(source, "후속 공식 자료", "", checked))));
            // '이후' is a lower bound, not a promise of publication at that exact minute.
            var schedules = candidate.schedules().stream().map(schedule -> {
                boolean after = active.stream().anyMatch(change -> change.source().url().equals(EvidenceLocationUrl.base(schedule.evidence().url()))
                    && change.value().statement().endsWith(" 이후") && matchesSchedule(change.value().field(), schedule.id()));
                return after ? new ScheduleTemplate(schedule.id(), schedule.title() + " (이후)", schedule.date(), schedule.evidence()) : schedule;
            }).toList();
            candidate = new Content(candidate.summary(), candidate.evidence(), List.copyOf(attachments.values()), candidate.questions(),
                candidate.documents(), schedules, candidate.reviewIssues());
            assessment = new GuidanceReviewCompiler.Assessment(assessment.profile(), assessment.ready(), candidate,
                assessment.issues(), assessment.findings(), assessment.startDate(), assessment.endDate());
        }
        return result(assessment, resolutions, blockers, invalidations);
    }

    private static Result result(GuidanceReviewCompiler.Assessment assessment, List<Resolution> resolutions, Set<String> blockers) {
        return result(assessment, resolutions, blockers, List.of());
    }
    private static Result result(GuidanceReviewCompiler.Assessment assessment, List<Resolution> resolutions, Set<String> blockers,
                                 List<Invalidation> invalidations) {
        var all = new LinkedHashSet<>(assessment.issues()); all.addAll(blockers);
        var checked = new GuidanceReviewCompiler.Assessment(assessment.profile(), assessment.ready() && blockers.isEmpty(),
            assessment.candidate(), List.copyOf(all), assessment.findings(), assessment.startDate(), assessment.endDate());
        return new Result(checked, resolutions, List.copyOf(blockers), invalidations);
    }
    private static boolean presentIn(Draft primary, String statement) {
        return primary.sources().stream().flatMap(source -> source.context().stream())
            .filter(part -> part != null && part.text() != null).flatMap(part -> Arrays.stream(part.text().split("\\R")))
            .anyMatch(line -> equivalent(line, statement));
    }
    private static List<Occurrence> occurrences(Draft draft) {
        var result = new ArrayList<Occurrence>();
        for (Source source : draft.sources()) for (Part part : source.context()) for (String raw : part.text().split("\\R")) {
            String line = clean(raw); Value value = value(line);
            if (value != null) result.add(new Occurrence(source, part.location(), line, value));
        }
        return result;
    }
    private static ArrayList<Source> project(List<Source> sources, Set<Occurrence> removed, boolean schedule) {
        var result = new ArrayList<Source>();
        for (Source source : sources) {
            var parts = source.context().stream().map(part -> {
                String retained = Arrays.stream(part.text().split("\\R")).filter(line -> removed.stream().noneMatch(old ->
                    old.source().url().equals(source.url()) && old.location().equals(part.location()) && old.line().equals(clean(line))
                        && old.value().field().startsWith("SCHEDULE:") == schedule)).reduce((a,b) -> a + "\n" + b).orElse("");
                return new Part(part.location(), retained.isBlank() ? (schedule ? "공급 일정" : "신청 자격") : retained);
            }).toList();
            result.add(new Source(source.label(), source.url(), source.sha256(), source.status(), source.message(), List.of(), parts));
        }
        return result;
    }
    private static List<Source> replacements(List<Change> active, boolean schedule) {
        var groups = new LinkedHashMap<String,List<Change>>();
        for (Change change : active) if (change.value().field().startsWith("SCHEDULE:") == schedule)
            groups.computeIfAbsent(change.source().url(), ignored -> new ArrayList<>()).add(change);
        return groups.values().stream().map(changes -> {
            Source source = changes.get(0).source();
            return new Source(source.label(), source.url(), source.sha256(), "EXTRACTED", source.message(), List.of(),
                changes.stream().map(change -> new Part(change.location(), change.value().statement())).distinct().toList());
        }).toList();
    }
    private static boolean equivalent(String first, String second) {
        if (clean(first).equals(clean(second))) return true;
        var a = GuidanceRuleGrammar.parse(clean(first)); var b = GuidanceRuleGrammar.parse(clean(second));
        if (a != null && b != null) return a.kind().equals(b.kind()) && a.key().equals(b.key()) && a.value().equals(b.value());
        var left = FollowupClaimReader.event(first); var right = FollowupClaimReader.event(second);
        return left != null && left.equals(right);
    }
    private static boolean matchesSchedule(String field, String id) {
        return field.equals("SCHEDULE:RESULT") && id.equals("auto-result")
            || field.equals("SCHEDULE:SELECTION") && id.equals("auto-document-selection");
    }
    private static Evidence evidence(Source source, String location, String anchor, String checked) {
        return new Evidence(source.label() + " · " + location, EvidenceLocationUrl.forExcerpt(source, location, anchor), checked);
    }
    private static boolean complete(Source source) {
        return source != null && official(source.url()) && source.label() != null && !source.label().isBlank()
            && "EXTRACTED".equals(source.status()) && source.sha256() != null && source.sha256().matches("[a-fA-F0-9]{64}")
            && !source.context().isEmpty() && source.context().size() <= 200
            && source.context().stream().allMatch(part -> part != null && part.location() != null && !part.location().isBlank()
                && part.text() != null && !part.text().isBlank() && !part.text().contains("[발췌 길이 제한]"))
            && source.context().stream().mapToLong(part -> part.text().length()).sum() <= 1_000_000;
    }
    private static boolean official(String url) {
        try {
            var uri = URI.create(url);
            return "https".equals(uri.getScheme()) && "apply.lh.or.kr".equals(uri.getHost()) && uri.getUserInfo() == null
                && uri.getFragment() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (RuntimeException invalid) { return false; }
    }
}
