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
import org.jibro.collection.review.GuidanceReviewCompiler;
import org.jibro.collection.review.OfficialFollowupComparison;
import org.jibro.collection.review.local.LocalAnalysisService;
import org.jibro.notice.AutomaticFollowupPublicationService;
import org.jibro.notice.FollowupReviewModels.*;
import org.jibro.notice.FollowupReviewRepository;
import org.jibro.notice.NoticeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Coordinates bounded I/O outside transactions; the publication boundary owns all database mutations. */
@Service
public class AutomaticFollowupService {
    public static final String POLICY = "official-followup-v1";
    private static final Logger LOG = LoggerFactory.getLogger(AutomaticFollowupService.class);
    private final FollowupReviewRepository queue;
    private final AutomaticFollowupPublicationService publication;
    private final OfficialFollowupDiscovery discovery;
    private final FollowupPrimaryVerifier primaryVerifier;
    private final ExtractionStore extraction;
    private final NoticeService notices;
    private final LocalAnalysisService local;
    private final ObjectMapper json;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    public AutomaticFollowupService(FollowupReviewRepository queue, AutomaticFollowupPublicationService publication,
        OfficialFollowupDiscovery discovery, FollowupPrimaryVerifier primaryVerifier, ExtractionStore extraction,
        NoticeService notices, LocalAnalysisService local, ObjectMapper json,
        @Value("${app.collection.automatic-review-enabled:true}") boolean automaticEnabled,
        @Value("${app.collection.automatic-followup-enabled:true}") boolean followupEnabled) {
        this.queue = queue; this.publication = publication; this.discovery = discovery;
        this.primaryVerifier = primaryVerifier; this.extraction = extraction; this.notices = notices;
        this.local = local; this.json = json; this.enabled = automaticEnabled && followupEnabled;
    }

    @Scheduled(initialDelayString = "${app.collection.followup-initial-delay-ms:45000}",
        fixedDelayString = "${app.collection.followup-poll-ms:60000}")
    public void tick() {
        if (enabled) runNext();
    }

    public boolean runNext() { return run(null); }

    public boolean reviewNotice(UUID id) { return run(id); }

    /** Administrator previews replay stored evidence only; a GET never starts remote collection. */
    public Optional<GuidanceReviewCompiler.Assessment> preview(UUID noticeId,
        org.jibro.notice.AutomaticReviewRepository.ExtractionSnapshot snapshot,
        org.jibro.notice.HousingModels.Content original) {
        return queue.latestSources(noticeId, UUID.fromString(snapshot.draft().id()), snapshot.fingerprint()).map(raw -> {
            var tree = json.readTree(raw);
            var found = json.treeToValue(tree.get("discovery"), OfficialFollowupModels.Discovery.class);
            return queue.previewOriginal(noticeId, UUID.fromString(snapshot.draft().id()), snapshot.fingerprint())
                .map(clean -> OfficialFollowupComparison.compare(snapshot.draft(), clean, found).assessment());
        }).flatMap(value -> value);
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
                queue.fail(job, "OFFICIAL_COMPARISON_RETRY_REQUIRED");
                LOG.warn("Official follow-up comparison deferred for {} ({})", job.noticeId(), failure.getClass().getSimpleName());
            }
            return false;
        } finally { running.set(false); }
    }

    private boolean process(Job job, Primary primary) throws Exception {
        var detail = notices.draft(job.noticeId());
        var target = new OfficialFollowupModels.Target(job.noticeId(), primary.snapshot().draft().revision(),
            primary.originalContent().evidence().url(), detail.title(), detail.publishedDate());
        var found = discovery.discover(target, primary.snapshot().draft());
        if (found.blockers().contains("FOLLOWUP_PRIMARY_CHANGED")) {
            publication.primaryChanged(job);
            return false;
        }
        var previous = queue.latestDependencySources(job.noticeId());
        if (previous.isPresent() && FollowupDependencies.missing(
                json.treeToValue(json.readTree(previous.get()).get("discovery"), OfficialFollowupModels.Discovery.class), found)) {
            queue.defer(job, "FOLLOWUP_PREVIOUS_EVIDENCE_MISSING");
            return false;
        }
        String bundleHash = hash(POLICY + "\n" + GuidanceReviewCompiler.POLICY + "\n"
            + primary.snapshot().fingerprint() + "\n" + OfficialFollowupDiscovery.fingerprint(found));
        if (found.complete() && queue.sameBundle(job, bundleHash)) {
            primaryVerifier.verify(primary.snapshot().draft(), primary.originalContent());
            var receipt = queue.stage(job, primary, bundleHash, found.checkedAt(),
                json.writeValueAsString(Map.of("discovery", found)), Decision.unchanged());
            return publication.publish(receipt).changed();
        }
        local.prepare(primary.snapshot());
        var result = OfficialFollowupComparison.compare(primary.snapshot().draft(), primary.originalContent(), found);
        var assessment = result.assessment();
        var blockers = new LinkedHashSet<>(result.blockers());
        blockers.addAll(assessment.issues());
        boolean ready = found.complete() && assessment.ready() && blockers.isEmpty();
        if (!ready && !result.invalidatesPublishedGuide()) {
            queue.defer(job, reason(List.copyOf(blockers)));
            return false;
        }
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
        if (ready && !refreshed.complete()
            || !OfficialFollowupDiscovery.fingerprint(found).equals(OfficialFollowupDiscovery.fingerprint(refreshed))) {
            queue.defer(job, "OFFICIAL_FOLLOWUP_CHANGED_DURING_REVIEW");
            return false;
        }
        primaryVerifier.verify(primary.snapshot().draft(), primary.originalContent());
        Decision decision = ready
            ? Decision.ready(assessment.candidate(), assessment.startDate(), assessment.endDate())
            : Decision.reviewRequired(List.copyOf(blockers));
        var receipt = queue.stage(job, primary, bundleHash, checkedAt,
            json.writeValueAsString(Map.of("discovery", refreshed, "resolutions", result.resolutions(),
                "invalidations", result.invalidations(), "policy", POLICY)), decision);
        return publication.publish(receipt).changed();
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
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
