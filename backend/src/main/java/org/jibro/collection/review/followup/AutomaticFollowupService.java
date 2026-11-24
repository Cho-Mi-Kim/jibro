package org.jibro.collection.review.followup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jibro.collection.extraction.ExtractionStore;
import org.jibro.collection.review.ApplicationPeriodCompiler;
import org.jibro.collection.review.GuidanceReviewCompiler;
import org.jibro.collection.review.OfficialFollowupComparison;
import org.jibro.collection.review.OfficeHoursAgreement;
import org.jibro.collection.review.IntakeChannelDates;
import org.jibro.collection.review.local.LocalAnalysisService;
import org.jibro.notice.AutomaticFollowupPublicationService;
import org.jibro.notice.FollowupReviewModels.*;
import org.jibro.notice.FollowupReviewRepository;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.NoticeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Coordinates bounded I/O outside transactions; the publication boundary owns all database mutations.
 */
@Service
public class AutomaticFollowupService {
    public static final String POLICY = FollowupReviewRepository.POLICY;
    private static final Logger LOG = LoggerFactory.getLogger(AutomaticFollowupService.class);
    private final FollowupReviewRepository queue;
    private final AutomaticFollowupPublicationService publication;
    private final OfficialFollowupDiscovery discovery;
    private final FollowupPrimaryVerifier primaryVerifier;
    private final ExtractionStore extraction;
    private final NoticeService notices;
    private final MyHomeIntakeClient myHome;
    private final AgencyIntakeClient agencies;
    private final LocalAnalysisService local;
    private final ObjectMapper json;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    public AutomaticFollowupService(FollowupReviewRepository queue, AutomaticFollowupPublicationService publication,
                                    OfficialFollowupDiscovery discovery, FollowupPrimaryVerifier primaryVerifier, ExtractionStore extraction,
                                    NoticeService notices, LocalAnalysisService local, ObjectMapper json, MyHomeIntakeClient myHome, AgencyIntakeClient agencies,
                                    @Value("${app.collection.automatic-review-enabled:true}") boolean automaticEnabled,
                                    @Value("${app.collection.automatic-followup-enabled:true}") boolean followupEnabled) {
        this.queue = queue;
        this.publication = publication;
        this.discovery = discovery;
        this.primaryVerifier = primaryVerifier;
        this.extraction = extraction;
        this.notices = notices;
        this.myHome = myHome;
        this.agencies = agencies;
        this.local = local;
        this.json = json;
        this.enabled = automaticEnabled && followupEnabled;
    }

    @Scheduled(initialDelayString = "${app.collection.followup-initial-delay-ms:45000}",
            fixedDelayString = "${app.collection.followup-poll-ms:60000}")
    public void tick() {
        if (enabled) runNext();
    }

    public boolean runNext() {
        return run(null);
    }

    public boolean reviewNotice(UUID id) {
        return run(id);
    }

    private boolean run(UUID noticeId) {
        if (!enabled || !running.compareAndSet(false, true)) return false;
        Job job = null;
        try {
            var claimed = noticeId == null ? queue.claim() : queue.claim(noticeId);
            if (claimed.isEmpty()) return false;
            job = claimed.get();
            var primary = queue.primary(job);
            if (primary.isEmpty()) {
                extraction.enqueue(job.noticeId(), false);
                queue.defer(job, "PRIMARY_EXTRACTION_PENDING");
                return false;
            }
            return process(job, primary.get());
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (job != null) {
                if (primaryChanged(failure.getMessage())) {
                    publication.primaryChanged(job);
                    return false;
                }
                queue.fail(job, failure instanceof AgencyIntakePost.InvalidField ? failure.getMessage() : "OFFICIAL_COMPARISON_RETRY_REQUIRED");
                LOG.warn("Official follow-up comparison deferred for {} ({})", job.noticeId(), failure.getClass().getSimpleName());
            }
            return false;
        } finally {
            running.set(false);
        }
    }

    private boolean process(Job job, Primary primary) throws Exception {
        var detail = notices.draft(job.noticeId());
        var draft = primary.snapshot().draft();
        var originalContent = primary.originalContent();
        var period = ApplicationPeriodCompiler.assess(draft, originalContent);
        boolean datedNotice = detail.startDate() != null && detail.endDate() != null;
        boolean activeFromPrimary = period.ready() && period.start() != null && period.end() != null;
        if (datedNotice && org.jibro.notice.AutomaticReviewEligibility.closed(detail.startDate(), detail.endDate(), primary.originalContent().schedules(), Instant.now())
                || activeFromPrimary && org.jibro.notice.AutomaticReviewEligibility.closed(period.start(), period.end(), period.schedules(), Instant.now())) {
            queue.defer(job, "APPLICATION_CLOSED_EXCLUDED");
            return false;
        }
        var target = new OfficialFollowupModels.Target(job.noticeId(), primary.snapshot().draft().revision(),
                primary.originalContent().evidence().url(), detail.title(), detail.publishedDate(), datedNotice || activeFromPrimary);
        var previous = queue.latestDependencySources(job.noticeId());
        var previousDiscovery = previous.map(raw -> json.treeToValue(json.readTree(raw).get("discovery"), OfficialFollowupModels.Discovery.class));
        var found = previousDiscovery.filter(OfficialFollowupModels.Discovery::hasMirror).isPresent()
                ? discovery.discover(target, draft, true) : discovery.discover(target, draft);
        if (found.blockers().contains("FOLLOWUP_PRIMARY_CHANGED")) {
            publication.primaryChanged(job);
            return false;
        }
        if (previousDiscovery.isPresent() && FollowupDependencies.missing(previousDiscovery.get(), found)) {
            queue.defer(job, "FOLLOWUP_PREVIOUS_EVIDENCE_MISSING");
            return false;
        }
        boolean originalItemsCurrent = OfficialFollowupComparison.repeatsPrimary(draft, found);
        var paired = List.<org.jibro.notice.source.NoticeSourceInformationModels.Section>of();
        if (!period.ready() && found.complete() && originalItemsCurrent) {
            var electronic = org.jibro.collection.review.PairedOnlineIntake.confirmed(draft, originalContent, period);
            if (electronic != null) {
                if (org.jibro.notice.AutomaticReviewEligibility.closed(electronic.start(), electronic.end(), electronic.schedules(), Instant.now())) {
                    queue.defer(job, "APPLICATION_CLOSED_EXCLUDED");
                    return false;
                }
                paired = org.jibro.collection.review.PairedPreparationItems.read(draft, originalContent.evidence().url());
                if (!paired.isEmpty()) period = electronic;
            }
        }
        boolean compareDates = found.complete() && ApplicationPeriodCompiler.disputed(period);
        var priorAgency = previous.map(raw -> json.readTree(raw).path("agencyWindows")).orElse(null);
        var corrections = found.related().stream().filter(r -> "CORRECTION".equals(r.kind())).toList();
        var latest = corrections.isEmpty() ? null : corrections.get(corrections.size() - 1);
        var correctedPeriod = currentVisitEdition(draft, found, target);
        if (correctedPeriod != null && org.jibro.notice.AutomaticReviewEligibility.closed(correctedPeriod.start(),
                correctedPeriod.end(), correctedPeriod.schedules(), Instant.now())) {
            queue.defer(job, "APPLICATION_CLOSED_EXCLUDED");
            return false;
        }
        var officeTarget = latest == null || latest.publishedDate() == null ? target
                : new OfficialFollowupModels.Target(target.noticeId(), target.revision(), latest.url(), latest.title(), latest.publishedDate(), target.activePreparation());
        boolean checkAgencies = compareDates || found.complete() && (ApplicationPeriodCompiler.missingBoundary(period) || OfficeHoursAgreement.hasVisitRounds(period) || correctedPeriod != null || latest != null && ApplicationPeriodCompiler.disputed(period) || priorAgency != null && !priorAgency.isEmpty());
        var agencyWindows = List.<IntakeChannelDates.Window>of();
        var myHomeWindows = List.<IntakeChannelDates.Window>of();
        boolean timingRetry = false;
        try {
            if (checkAgencies) agencyWindows = officeWindows(target, officeTarget);
            if (compareDates) myHomeWindows = myHome.find(target);
        } catch (java.io.IOException unavailable) {
            if (!datedNotice || !found.hasMirror()) throw unavailable;
            timingRetry = true;
        }
        if (priorAgency != null) {
            for (var prior : priorAgency) {
                if (FollowupDependencies.missingWindow(json.treeToValue(prior, IntakeChannelDates.Window.class), agencyWindows)) {
                    queue.defer(job, "AGENCY_PREVIOUS_EVIDENCE_MISSING");
                    return false;
                }
            }
        }
        var windows = java.util.stream.Stream.concat(myHomeWindows.stream(), agencyWindows.stream()).toList();
        var priorGuides = queue.priorGuides(job.noticeId());
        String priorToken = FollowupReviewRepository.priorToken(priorGuides);
        String bundleHash = hash(priorToken + "\n" + timingRetry + "\n" + POLICY + "\nchannel-dates-v6-office-dates\n" + json.writeValueAsString(windows) + "\n" + GuidanceReviewCompiler.POLICY + "\n" + ApplicationPeriodCompiler.POLICY + "\n"
                + primary.snapshot().fingerprint() + "\n" + OfficialFollowupDiscovery.fingerprint(found));
        var previousCoverage = !timingRetry && found.complete() && queue.sameBundle(job, bundleHash) ? queue.previousCoverage(job, bundleHash) : Optional.<String>empty();
        if (previousCoverage.isPresent() && draft.sources().stream().noneMatch(s -> s.sha256() == null)) {
            primaryVerifier.verify(primary.snapshot().draft(), primary.originalContent());
            var receipt = queue.stage(job, primary, bundleHash, found.checkedAt(),
                    json.writeValueAsString(Map.of("discovery", found, "myHomeWindows", myHomeWindows, "agencyWindows", agencyWindows, "priorGuideToken", priorToken, "itemCoverage", json.readTree(previousCoverage.get()))), Decision.unchanged());
            return publication.publish(receipt).changed();
        }
        local.prepare(primary.snapshot());
        var result = OfficialFollowupComparison.compare(draft, originalContent, found,
                windows.stream().filter(w -> !w.officeOnly()).toList());
        var assessment = result.assessment();
        var blockers = new LinkedHashSet<>(result.blockers());
        blockers.addAll(assessment.issues());
        if (!found.permitsVerifiedItems() && !result.invalidatesPublishedGuide()) {
            queue.defer(job, reason(List.copyOf(blockers)));
            return false;
        }
        boolean ready = found.complete() && assessment.ready() && blockers.isEmpty();
        var intake = result.intake();
        if ((compareDates || agencyWindows.stream().anyMatch(IntakeChannelDates.Window::supplementsPeriod)) && intake == null && (latest == null || originalItemsCurrent) && result.blockers().isEmpty() && !result.invalidatesPublishedGuide()
                && result.resolutions().stream().noneMatch(r -> r.field().startsWith("SCHEDULE:"))) {
            intake = ApplicationPeriodCompiler.reconcile(draft, originalContent, windows);
        }
        if (!timingRetry && found.complete() && (result.intake() != null || correctedPeriod != null || !result.invalidatesPublishedGuide())
                && result.blockers().stream().allMatch(b -> b.equals("CHANNEL_INTAKE_REVIEW"))) {
            var basis = result.intake() != null ? result.intake() : correctedPeriod;
            if (basis == null && latest == null) basis = intake == null ? period : intake;
            if (basis == null && ready && latest != null && !result.invalidatesPublishedGuide()) {
                var candidate = assessment.candidate();
                var boundaries = candidate.schedules().stream().filter(s -> ApplicationPeriodCompiler.session(s)
                        || org.jibro.notice.ApplicationPeriod.basicTitle(s.title(), false)
                        || org.jibro.notice.ApplicationPeriod.basicTitle(s.title(), true)).toList();
                basis = new ApplicationPeriodCompiler.Result(assessment.startDate(), assessment.endDate(), boundaries, List.of(),
                        candidate.reviewIssues().stream().filter(i -> i.id().startsWith("intake-review-auto-")).toList());
            }
            var currentSources = latest == null ? draft.sources() : latest.sources();
            var officeClaims = agencyWindows.stream().filter(w -> w.office() == null || w.office().complex().isEmpty()
                    || ReceptionDeskFields.namesComplex(officeTarget.title(), w.office().complex()) || currentSources.stream()
                    .anyMatch(s -> ReceptionDeskFields.namesComplex(s.label(), w.office().complex()))).toList();
            var agreement = OfficeHoursAgreement.forPublication(basis, latest == null ? period : correctedPeriod, officeClaims, officeTarget.publishedDate(),
                    latest == null ? null : period, target.publishedDate());
            if (agreement != null) intake = agreement;
        }
        if (intake != null && (intake.start() == null || intake.end() == null)) ready = false;
        if ((!ready || !priorGuides.isEmpty()) && found.complete() && originalItemsCurrent && result.blockers().isEmpty() && !result.invalidatesPublishedGuide()) {
            var verifiedPeriod = intake != null ? intake : period;
            boolean activePeriod = verifiedPeriod != null && verifiedPeriod.ready() && verifiedPeriod.start() != null && verifiedPeriod.end() != null;
            if (activePeriod && org.jibro.notice.AutomaticReviewEligibility.closed(verifiedPeriod.start(), verifiedPeriod.end(), verifiedPeriod.schedules(), Instant.now())) {
                queue.defer(job, "APPLICATION_CLOSED_EXCLUDED");
                return false;
            }
            if (datedNotice || activePeriod) {
                if (paired.isEmpty())
                    paired = org.jibro.collection.review.PairedPreparationItems.read(draft, originalContent.evidence().url());
                if (!paired.isEmpty() && intake == null && !datedNotice) intake = verifiedPeriod;
            }
        }
        if (!ready && (datedNotice || activeFromPrimary) && paired.isEmpty() && !result.invalidatesPublishedGuide()
                && result.blockers().stream().allMatch(code -> code.equals("FOLLOWUP_SOURCE_INCOMPLETE")
                || found.permitsVerifiedItems() && found.blockers().contains(code)
                || code.startsWith("AUTHORITATIVE_SOURCE_INCOMPLETE:") || code.equals("RECRUITMENT_DOCUMENT_REQUIRED")
                || code.equals(org.jibro.collection.extraction.OcrProvenance.REVIEW_REQUIRED))) {
            paired = OfficialFollowupComparison.corroboratedItems(draft, originalContent, found);
            if (!paired.isEmpty() && !datedNotice && found.complete()) intake = period;
        }
        var authoritative = ready ? assessment.candidate() : null;
        var comparison = new org.jibro.collection.review.PreparationItemComparison(originalItemsCurrent ? draft : null, authoritative, paired);
        var verifiedIntake = intake != null ? intake : period;
        var reviewCandidate = authoritative;
        boolean checkedPrimary = found.complete() && originalItemsCurrent && result.blockers().isEmpty() && !result.invalidatesPublishedGuide();
        boolean scoped = false;
        for (var prior : priorGuides) {
            var files = checkedPrimary ? queue.verifiedPrior(prior).flatMap(old -> org.jibro.collection.review.VerifiedReissueFiles.compare(old, draft, originalContent.evidence().url())) : Optional.<org.jibro.collection.review.VerifiedReissueFiles>empty();
            if (!ready && priorGuides.size() == 1 && files.isPresent() && verifiedIntake.ready() && verifiedIntake.start() != null && verifiedIntake.end() != null
                    && verifiedIntake.reviewIssues().isEmpty() && !org.jibro.notice.AutomaticReviewEligibility.closed(verifiedIntake.start(), verifiedIntake.end(), verifiedIntake.schedules(), Instant.now())) {
                var restored = files.get().restore(prior.guide(), originalContent, verifiedIntake.schedules());
                if (restored.isPresent()) {
                    reviewCandidate = restored.get();
                    intake = verifiedIntake;
                    ready = true;
                    comparison = new org.jibro.collection.review.PreparationItemComparison(draft, reviewCandidate, paired);
                    comparison.recordRestored(prior, reviewCandidate);
                    continue;
                }
            }
            comparison.compare(prior, files);
        }
        var coverage = comparison.coverage();
        if (coverage.conditions() == 0) blockers.add("QUALIFICATION_ITEMS_NOT_READ");
        if (coverage.documents() == 0) blockers.add("SUBMISSION_DOCUMENT_ITEMS_NOT_READ");
        if (comparison.requiresScopedPublication() || coverage.conditions() == 0 || coverage.documents() == 0) {
            scoped = !priorGuides.isEmpty() && (authoritative != null || checkedPrimary);
            if (scoped && authoritative != null && intake == null) {
                var boundaries = authoritative.schedules().stream().filter(s -> org.jibro.notice.ApplicationPeriod.basicTitle(s.title(), false)
                        || org.jibro.notice.ApplicationPeriod.basicTitle(s.title(), true) || ApplicationPeriodCompiler.session(s)).toList();
                intake = new ApplicationPeriodCompiler.Result(assessment.startDate(), assessment.endDate(), boundaries, List.of(),
                        authoritative.reviewIssues().stream().filter(i -> "SCHEDULE".equals(i.area())).toList());
                verifiedIntake = intake;
            }
            if (comparison.requiresScopedPublication()) blockers.add("CORRECTION_ITEMS_COMPARED_INDIVIDUALLY");
            ready = false;
        }
        if ((checkedPrimary || scoped && found.complete()) && (datedNotice || verifiedIntake.ready() && verifiedIntake.start() != null && verifiedIntake.end() != null
                && !org.jibro.notice.AutomaticReviewEligibility.closed(verifiedIntake.start(), verifiedIntake.end(), verifiedIntake.schedules(), Instant.now()))) {
            paired = comparison.sections(scoped);
            if (!ready && !paired.isEmpty() && intake == null && !datedNotice) intake = verifiedIntake;
        }
        if (timingRetry) {
            ready = false;
            intake = null;
            blockers.add("INTAKE_ALTERNATIVE_RETRY");
            paired = result.invalidatesPublishedGuide() ? List.of() : OfficialFollowupComparison.corroboratedItems(draft, originalContent, found);
        }
        if (!ready && !result.invalidatesPublishedGuide() && intake == null && paired.isEmpty()) {
            queue.deferWithItems(job, reason(List.copyOf(blockers)), coverage);
            return false;
        }
        var forms = new org.jibro.collection.review.VerifiedPreparationForms(draft, found);
        if (ready) reviewCandidate = forms.content(reviewCandidate);
        paired = forms.sections(paired);
        if (!queue.renew(job)) return false;
        // Use the beginning of this fresh pass for the receipt, not the end of a long download.
        Instant checkedAt = Instant.now();
        var refreshed = discovery.revalidate(target, primary.snapshot().draft(), found);
        if (refreshed.blockers().contains("FOLLOWUP_PRIMARY_CHANGED")) {
            publication.primaryChanged(job);
            return false;
        }
        // An unread unrelated candidate forbids publication, but does not erase a fully verified
        // correction's evidence that the currently published guidance is no longer valid.
        if (!timingRetry && ((compareDates && !myHomeWindows.equals(myHome.find(target))) || (checkAgencies && !agencyWindows.equals(officeWindows(target, officeTarget)))) || ((ready || intake != null) && !refreshed.complete()) || (!paired.isEmpty() && !refreshed.permitsVerifiedItems())
                || !OfficialFollowupDiscovery.fingerprint(found).equals(OfficialFollowupDiscovery.fingerprint(refreshed))) {
            queue.defer(job, "OFFICIAL_FOLLOWUP_CHANGED_DURING_REVIEW");
            return false;
        }
        if (!ready && intake == null && !paired.isEmpty() && found.hasMirror()) {
            primaryVerifier.verifyItems(draft, originalContent);
        } else primaryVerifier.verify(draft, originalContent);
        Decision decision = ready
                ? Decision.ready(reviewCandidate, intake == null ? assessment.startDate() : intake.start(), intake == null ? assessment.endDate() : intake.end())
                : Decision.reviewRequired(List.copyOf(blockers));
        if (ready && intake != null) {
            var merged = ApplicationPeriodCompiler.withIntake(reviewCandidate, intake.schedules(), intake.reviewIssues());
            decision = Decision.ready(merged, intake.start(), intake.end());
        }
        if (!ready && found.complete() && intake != null) {
            var original = primary.originalContent();
            var content = new Content("정정된 접수 일정을 확인했습니다. 준비 안내를 다시 확인하고 있습니다.",
                    original.evidence(), original.attachments(), List.of(), List.of(), intake.schedules(), intake.reviewIssues());
            boolean otherChange = !result.invalidations().isEmpty() || result.resolutions().stream()
                    .anyMatch(r -> !java.util.Set.of("SCHEDULE:APPLICATION", "SCHEDULE:START", "SCHEDULE:END").contains(r.field()));
            decision = new Decision(otherChange && !(scoped && authoritative != null && !paired.isEmpty()) ? "REVIEW_REQUIRED" : "INTAKE", content, intake.start(), intake.end(), List.copyOf(blockers));
        }
        if (!ready && intake == null && !paired.isEmpty())
            decision = new Decision("ITEMS", null, null, null, List.of());
        var receipt = queue.stage(job, primary, bundleHash, checkedAt,
                json.writeValueAsString(Map.of("discovery", refreshed, "resolutions", result.resolutions(),
                        "invalidations", result.invalidations(), "confirmedItems", paired, "itemCoverage", coverage, "priorGuideToken", priorToken, "scopedCorrection", scoped, "myHomeWindows", myHomeWindows, "agencyWindows", agencyWindows, "policy", POLICY)), decision);
        return publication.publish(receipt).changed();
    }

    private static ApplicationPeriodCompiler.Result currentVisitEdition(org.jibro.collection.extraction.ExtractionModels.Draft primary,
                                                                        OfficialFollowupModels.Discovery found, OfficialFollowupModels.Target target) {
        if (!found.complete()) return null;
        String parent = target.primaryUrl();
        var issued = target.publishedDate();
        OfficialFollowupModels.Related latest = null;
        for (var related : found.related()) {
            if (!"CORRECTION".equals(related.kind())) continue;
            if (!parent.equals(related.supersedesUrl()) || related.publishedDate() == null || issued == null
                    || related.publishedDate().isBefore(issued)) return null;
            latest = related;
            parent = related.url();
            issued = related.publishedDate();
        }
        if (latest == null) return null;
        var edition = new org.jibro.collection.extraction.ExtractionModels.Draft(primary.id(), primary.noticeId(), primary.revision(),
                "PARTIAL", found.checkedAt(), "", latest.sources());
        var evidence = new org.jibro.notice.HousingModels.Evidence(latest.title(), latest.url(), issued.toString());
        var result = ApplicationPeriodCompiler.assess(edition, new Content("", evidence, List.of(), List.of(), List.of(), List.of()));
        return result.ready() && OfficeHoursAgreement.hasVisitRounds(result) ? result : null;
    }

    private List<IntakeChannelDates.Window> officeWindows(OfficialFollowupModels.Target primary, OfficialFollowupModels.Target edition)
            throws java.io.IOException {
        var original = agencies.find(primary);
        if (primary.equals(edition)) return original;
        return java.util.stream.Stream.concat(original.stream(), agencies.find(edition).stream())
                .distinct().sorted(java.util.Comparator.comparing(IntakeChannelDates.Window::url)
                        .thenComparing(IntakeChannelDates.Window::toString)).toList();
    }

    private static String reason(List<String> reasons) {
        String text = String.join(" / ", reasons.isEmpty() ? List.of("OFFICIAL_INTERPRETATION_PENDING") : reasons);
        return text.length() > 1500 ? text.substring(0, 1500) : text;
    }

    private static boolean primaryChanged(String code) {
        return "PRIMARY_DETAIL_CHANGED".equals(code) || "PRIMARY_ATTACHMENT_CHANGED".equals(code)
                || "PRIMARY_ATTACHMENT_SET_CHANGED".equals(code);
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
