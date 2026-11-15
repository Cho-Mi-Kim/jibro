package org.jibro.notice;

import java.time.Instant;
import java.util.*;

import org.jibro.collection.review.ApplicationPeriodCompiler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.jibro.notice.HousingModels.*;

/**
 * Publishes only application boundaries; never approves guidance or edits a preparation snapshot.
 */
@Service
public class ApplicationPeriodReviewService {
    private final NoticeRepository notices;
    private final NoticeService validation;
    private final AutomaticReviewRepository evidence;
    private final ApplicationPeriodReviewRepository periods;
    private final ObjectMapper json;

    public ApplicationPeriodReviewService(NoticeRepository notices, NoticeService validation,
                                          AutomaticReviewRepository evidence, ApplicationPeriodReviewRepository periods,
                                          ObjectMapper json) {
        this.notices = notices;
        this.validation = validation;
        this.evidence = evidence;
        this.periods = periods;
        this.json = json;
    }

    public List<UUID> pending() {
        return periods.pending();
    }

    @Transactional
    public boolean review(UUID extractionId) {
        var snapshot = evidence.snapshot(extractionId);
        var draft = snapshot.draft();
        UUID id = UUID.fromString(draft.noticeId());
        var n = notices.locked(id).orElseThrow();
        Content before = json.readValue(n.content, Content.class);
        if (n.revision != draft.revision() || n.automaticReviewBlocked || evidence.manual(id)
            || !"PASS".equals(n.basicReview) || !"NORMAL".equals(n.lifecycleState)
            || n.sourceKey == null || !n.sourceKey.startsWith("LH:") || n.sourceFingerprint == null || n.collectedAt == null
            || AutomaticReviewEligibility.closed(n, before, Instant.now())
            || !evidence.matchesCurrentEvidence(id, n.revision, extractionId, snapshot.fingerprint())) return false;
        if (!periods.matchesSource(extractionId, n.sourceFingerprint)
            || periods.alreadyReviewed(extractionId, snapshot.fingerprint())) return false;
        int revision = n.revision;
        var result = ApplicationPeriodCompiler.assess(draft, before);
        String decision = "HOLD";
        if (result.ready()) {
            var timing = ApplicationPeriod.resolve(result.start(), result.end(), result.schedules());
            // A date-only value cannot replace an already verified clock or disagree with a reviewed date.
            boolean conflict = n.startDate != null && !n.startDate.equals(result.start())
                || n.endDate != null && !n.endDate.equals(result.end())
                || n.applicationStartAt != null && !n.applicationStartAt.equals(timing.startsAt())
                || n.applicationEndAt != null && !n.applicationEndAt.equals(timing.endsAt());
            if (conflict)
                result = new ApplicationPeriodCompiler.Result(null, null, List.of(), List.of("EXISTING_APPLICATION_PERIOD_CONFLICT"));
            else if (Objects.equals(n.startDate, result.start()) && Objects.equals(n.endDate, result.end())
                && Objects.equals(n.applicationStartAt, timing.startsAt()) && Objects.equals(n.applicationEndAt, timing.endsAt()))
                decision = "UNCHANGED";
            else {
                var schedules = new ArrayList<>(before.schedules().stream().filter(s -> !ApplicationPeriod.basicTitle(s.title(), false)
                    && !ApplicationPeriod.basicTitle(s.title(), true)).toList());
                schedules.addAll(result.schedules());
                var updated = new Content(before.summary(), before.evidence(), before.attachments(), before.questions(),
                    before.documents(), List.copyOf(schedules), before.reviewIssues(), before.applicationClosure());
                validation.validateContent(updated);
                n.content = json.writeValueAsString(updated);
                n.startDate = result.start();
                n.endDate = result.end();
                n.applicationStartAt = timing.startsAt();
                n.applicationEndAt = timing.endsAt();
                n.revision++;
                n.version++;
                notices.saveAndFlush(n);
                decision = "APPLIED";
            }
        }
        periods.record(id, extractionId, revision, n.revision, snapshot.fingerprint(), decision, result);
        return "APPLIED".equals(decision);
    }
}
