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
            boolean disputed = result.schedules().stream().anyMatch(s -> s.title().equals(ApplicationTiming.UNDATED_HOURS_SESSION));
            boolean rolling = result.schedules().stream().anyMatch(s -> s.title().equals(ApplicationTiming.UNTIL_FILLED_SESSION));
            boolean noStart = disputed || rolling && result.start() == null;
            var start = noStart ? null : result.start() == null ? n.startDate : result.start();
            var end = disputed || rolling ? null : result.end() == null ? n.endDate : result.end();
            boolean replaceStart = noStart || result.start() != null && (timing.startsAt() != null || n.applicationStartAt == null);
            boolean replaceEnd = disputed || rolling || result.end() != null && (timing.endsAt() != null || n.applicationEndAt == null);
            var startsAt = replaceStart ? timing.startsAt() : n.applicationStartAt;
            var endsAt = replaceEnd ? timing.endsAt() : n.applicationEndAt;
            var curated = before.schedules().stream().filter(s -> s.title().startsWith("접수 회차 · ") && !ApplicationPeriodCompiler.session(s)).toList();
            var intake = result.schedules().stream().filter(s -> ApplicationPeriodCompiler.explanation(s)
                    ? curated.stream().noneMatch(c -> c.title().equals(s.title()))
                    : curated.isEmpty() || !ApplicationPeriodCompiler.session(s))
                .filter(s -> replaceStart || !ApplicationPeriod.basicTitle(s.title(), false))
                .filter(s -> replaceEnd || !ApplicationPeriod.basicTitle(s.title(), true)).toList();
            // A date-only value cannot replace an already verified clock or disagree with a reviewed date.
            boolean conflict = !noStart && n.startDate != null && !n.startDate.equals(start)
                || !disputed && !rolling && n.endDate != null && !n.endDate.equals(end)
                || !noStart && n.applicationStartAt != null && !n.applicationStartAt.equals(startsAt)
                || !disputed && !rolling && n.applicationEndAt != null && !n.applicationEndAt.equals(endsAt)
                || start != null && end != null && start.isAfter(end)
                || startsAt != null && endsAt != null && startsAt.isAfter(endsAt);
            if (conflict)
                result = new ApplicationPeriodCompiler.Result(null, null, List.of(), List.of("EXISTING_APPLICATION_PERIOD_CONFLICT"));
            else if (Objects.equals(n.startDate, start) && Objects.equals(n.endDate, end)
                && Objects.equals(n.applicationStartAt, startsAt) && Objects.equals(n.applicationEndAt, endsAt)
                && before.schedules().stream().filter(ApplicationPeriodCompiler::session).toList()
                .equals(intake.stream().filter(ApplicationPeriodCompiler::session).toList()))
                decision = "UNCHANGED";
            else {
                var schedules = new ArrayList<>(before.schedules().stream().filter(s -> !(replaceStart && ApplicationPeriod.basicTitle(s.title(), false))
                    && !(replaceEnd && ApplicationPeriod.basicTitle(s.title(), true)) && !ApplicationPeriodCompiler.session(s)).toList());
                schedules.addAll(intake);
                var updated = new Content(before.summary(), before.evidence(), before.attachments(), before.questions(),
                    before.documents(), List.copyOf(schedules), before.reviewIssues(), before.applicationClosure());
                validation.validateContent(updated);
                if (AutomaticReviewEligibility.closed(n, before, Instant.now())) return false;
                n.content = json.writeValueAsString(updated);
                n.startDate = start;
                n.endDate = end;
                n.applicationStartAt = startsAt;
                n.applicationEndAt = endsAt;
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
