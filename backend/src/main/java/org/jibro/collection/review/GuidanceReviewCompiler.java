package org.jibro.collection.review;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jibro.collection.extraction.ExtractionModels.Draft;
import org.jibro.notice.HousingModels.*;

/** One assessment for automatic publication and read-only administrator previews. */
public final class GuidanceReviewCompiler {
    public static final String POLICY = "source-review-v10";
    // These are limitations of the closed rule grammar, not proof of a contradictory source.
    // The evidence compiler rechecks full text/length markers; the final schedule compiler owns
    // stage ordering (document submission can legitimately precede the final announcement).
    private static final Set<String> FALLBACK_RESOLVABLE = Set.of("UNSUPPORTED_CLAUSE", "SOURCE_RULES_MISSING",
        "CONDITIONS_MISSING", "DOCUMENTS_MISSING", "APPLICATION_PERIOD_MISSING", "SOURCE_TRUNCATED", "SCHEDULE_ORDER_CONFLICT");
    private GuidanceReviewCompiler() {}
    public record Assessment(String profile, boolean ready, Content candidate, List<String> issues,
                             List<GuidanceFinding> findings, LocalDate startDate, LocalDate endDate) {}

    public static Assessment assess(Draft draft, Content original) {
        return assess(draft, original, null);
    }

    /** A verified follow-up may replace individual schedule claims without rewriting qualification prose. */
    static Assessment assess(Draft draft, Content original, List<org.jibro.collection.extraction.ExtractionModels.Source> scheduleOverride) {
        return assess(draft, original, scheduleOverride, Set.of(), List.of());
    }

    static Assessment assess(Draft draft, Content original, List<org.jibro.collection.extraction.ExtractionModels.Source> scheduleOverride,
                             Set<String> supersededRules, List<AutomaticGuidanceCompiler.LocatedRule> replacements) {
        var coverage = SourceCoveragePolicy.assessScoped(draft, original);
        var issues = new LinkedHashSet<>(coverage.issues());
        var findings = new LinkedHashSet<>(OfficialFormConsistency.inspect(draft));
        if (draft.checkedAt() != null) {
            String checked = draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
            draft.sources().forEach(source -> findings.addAll(NationalRentalScopeReview.inspect(source, checked)));
        }
        var template = NationalRentalGuidancePreview.compile(draft, original);
        if (template != null) findings.addAll(template.findings());
        var guide = new AutomaticGuidanceCompiler.Result(false, List.of(), null);
        if (coverage.ready()) {
            guide = AutomaticGuidanceCompiler.compileScoped(coverage.draft(), coverage.original(), supersededRules, replacements);
            if (!guide.ready()) {
                if (!replacements.isEmpty()) issues.add("FOLLOWUP_QUALIFICATION_GRAMMAR_UNSUPPORTED");
                guide.issues().stream().filter(issue -> !FALLBACK_RESOLVABLE.contains(issue)).forEach(issues::add);
                guide = EvidenceGuidanceCompiler.compileScoped(coverage.draft(), coverage.original());
            }
            issues.addAll(guide.issues());
            if (!guide.ready()) issues.addAll(SourceCoveragePolicy.assess(draft, original).issues());
        }
        var dates = AutomaticScheduleCompiler.compileScoped(scheduleOverride == null ? coverage.scheduleSources() : scheduleOverride,
            draft.checkedAt() == null ? LocalDate.now(ZoneId.of("Asia/Seoul")) : draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
        issues.addAll(dates.issues());
        // A concise type-specific draft does not prove that every governing exception was compiled.
        // Keep it available for review even while an independent source or rule check blocks publication.
        Content candidate = guide.ready() ? guide.content() : template == null ? null : template.candidate();
        var reviewIssues = new java.util.ArrayList<ReviewIssue>();
        if (guide.ready()) {
            var scopedFindings = ScopedFindingGuidance.scope(draft, candidate, List.copyOf(findings));
            issues.addAll(scopedFindings.blockers());
            reviewIssues.addAll(scopedFindings.reviewIssues());
        } else findings.forEach(f -> issues.add(f.code()));
        if (candidate != null) {
            var attachments = new LinkedHashMap<String, Evidence>();
            original.attachments().forEach(a -> attachments.put(a.url(), a));
            String checked = draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
            draft.sources().stream().filter(s -> !s.url().equals(original.evidence().url()))
                .forEach(s -> attachments.put(s.url(), new Evidence(s.label(), s.url(), checked)));
            reviewIssues.addAll(candidate.reviewIssues());
            reviewIssues.addAll(dates.reviewIssues());
            candidate = new Content(candidate.summary(), original.evidence(), List.copyOf(attachments.values()),
                candidate.questions(), candidate.documents(), dates.schedules(), reviewIssues);
            try { org.jibro.notice.GuidanceIssues.validate(candidate); }
            catch (IllegalArgumentException invalid) { issues.add("SCOPED_GUIDANCE_INVALID"); }
            if (guide.ready() && issues.isEmpty() && original.applicationClosure() == null
                && supersededRules.isEmpty() && replacements.isEmpty())
                candidate = org.jibro.collection.review.summary.SupportSummaryCompiler.apply(coverage.draft(), candidate,
                    dates.startDate(), dates.endDate(), java.time.Instant.now());
        }
        return new Assessment(template == null ? "official-evidence" : NationalRentalGuidancePreview.PROFILE,
            guide.ready() && issues.isEmpty(), candidate, List.copyOf(issues), List.copyOf(findings), dates.startDate(), dates.endDate());
    }
}
