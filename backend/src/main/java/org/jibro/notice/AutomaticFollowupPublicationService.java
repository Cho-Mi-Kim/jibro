package org.jibro.notice;

import static org.jibro.notice.FollowupReviewModels.*;
import static org.jibro.notice.HousingModels.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes only the exact, freshly verified worker assessment stored before taking the notice lock.
 */
@Service
public class AutomaticFollowupPublicationService {
    private final NoticeRepository notices;
    private final NoticeService validation;
    private final FollowupReviewRepository reviews;
    private final NoticeCorrectionRepository corrections;
    private final ObjectMapper json;

    public AutomaticFollowupPublicationService(NoticeRepository notices, NoticeService validation,
                                               FollowupReviewRepository reviews, NoticeCorrectionRepository corrections, ObjectMapper json) {
        this.notices = notices;
        this.validation = validation;
        this.reviews = reviews;
        this.corrections = corrections;
        this.json = json;
    }

    /**
     * Call only after a successfully downloaded primary source is proven changed, never on an IO failure.
     */
    @Transactional
    public boolean primaryChanged(Job job) {
        if (job == null) return false;
        var found = notices.locked(job.noticeId());
        if (found.isEmpty() || !reviews.current(job) || !reviews.active(job)) return false;
        var n = found.get();
        withdraw(n, "공식 원문이 변경되어 준비 안내를 다시 확인하고 있습니다.");
        n.basicReview = "HOLD";
        n.basicCheckedAt = null;
        n.reason = "공식 원문 변경 · 최신 본문·첨부를 다시 읽고 검수합니다.";
        notices.saveAndFlush(n);
        reviews.primaryInvalidated(job, n.revision, reviews.contentHash(n.id));
        return true;
    }

    @Transactional
    public Outcome publish(Receipt receipt) {
        if (receipt == null) return new Outcome("SUPERSEDED", false, 0);
        var id = reviews.noticeId(receipt);
        if (id == null) return new Outcome("SUPERSEDED", false, 0);
        corrections.lockGraph();
        var n = notices.locked(id).orElseThrow();
        var stored = reviews.staged(receipt);
        if (stored.isEmpty()) return new Outcome("SUPERSEDED", false, n.revision);
        var staged = stored.get();
        var job = staged.job();
        if (!reviews.active(job) || !reviews.current(job) || !reviews.primaryMatches(job, staged.primary())
            || staged.checkedAt().isBefore(Instant.now().minusSeconds(FollowupReviewRepository.VERIFICATION_TTL_SECONDS))
            || staged.checkedAt().isAfter(Instant.now().plusSeconds(5))) {
            reviews.unsuccessful(job, "SUPERSEDED", "FOLLOWUP_EVIDENCE_OR_NOTICE_CHANGED");
            return new Outcome("SUPERSEDED", false, n.revision);
        }
        var sources = json.readTree(staged.sourcesJson());
        if (sources.has("priorGuideToken") && !FollowupReviewRepository.priorToken(reviews.priorGuides(id)).equals(sources.path("priorGuideToken").asString())) {
            reviews.unsuccessful(job, "SUPERSEDED", "CORRECTION_BASELINE_CHANGED");
            return new Outcome("SUPERSEDED", false, n.revision);
        }
        if (reviews.sameBundle(job, staged.bundleHash())) {
            reviews.completed(staged, "UNCHANGED", n.revision, job.contentFingerprint(), "UNCHANGED_VERIFIED_BUNDLE");
            return new Outcome("UNCHANGED", false, n.revision);
        }
        var decision = staged.decision();
        if (decision.state().equals("UNCHANGED")) {
            reviews.unsuccessful(job, "FAILED", "UNCHANGED_BUNDLE_NOT_PREVIOUSLY_VERIFIED");
            return new Outcome("FAILED", false, n.revision);
        }
        if (decision.state().equals("ITEMS")) {
            var status = ApplicationPeriod.status(n.startDate, n.endDate, new ApplicationPeriod.Window(n.applicationStartAt, n.applicationEndAt), Instant.now());
            boolean scopeChange = sources.path("scopedCorrection").asBoolean(false) && "PASS".equals(n.guideReview);
            if (!java.util.Set.of("OPEN", "UPCOMING").contains(status) || !scopeChange && !"HOLD".equals(n.guideReview)
                || json.readTree(staged.sourcesJson()).path("confirmedItems").isEmpty()) {
                reviews.unsuccessful(job, "SUPERSEDED", "VERIFIED_ITEMS_INELIGIBLE");
                return new Outcome("SUPERSEDED", false, n.revision);
            }
            if (scopeChange) {
                n.content = json.writeValueAsString(scopedContent(json.readValue(n.content, Content.class)));
                markScoped(n);
                n.revision++;
                n.version++;
                notices.saveAndFlush(n);
            }
            reviews.completed(staged, "ITEMS", n.revision, scopeChange ? reviews.contentHash(n.id) : job.contentFingerprint(), "VERIFIED_PREPARATION_ITEMS");
            return new Outcome("ITEMS", true, n.revision);
        }
        if (decision.state().equals("INTAKE")) {
            return publishIntake(n, staged);
        }
        if (decision.state().equals("REVIEW_REQUIRED")) {
            if (decision.blockers().isEmpty()) {
                reviews.unsuccessful(job, "FAILED", "VERIFIED_CHANGE_REASON_REQUIRED");
                return new Outcome("FAILED", false, n.revision);
            }
            // Actual contradictory/new governing evidence withdraws stale public facts. Ordinary
            // discovery/download failures never reach this branch, and private snapshots are untouched.
            var intake = decision.content();
            if (intake != null) {
                validation.validateContent(intake);
                if (AutomaticReviewEligibility.closed(decision.startDate(), decision.endDate(), intake.schedules(), Instant.now())) {
                    reviews.unsuccessful(job, "SUPERSEDED", "APPLICATION_CLOSED_EXCLUDED");
                    return new Outcome("SUPERSEDED", false, n.revision);
                }
            }
            withdraw(n, "공식 후속 자료에 따라 준비 안내를 다시 확인하고 있습니다.");
            if (intake != null) {
                n.content = json.writeValueAsString(intake);
                n.startDate = decision.startDate();
                n.endDate = decision.endDate();
                var timing = ApplicationPeriod.resolve(n.startDate, n.endDate, intake.schedules());
                n.applicationStartAt = timing.startsAt();
                n.applicationEndAt = timing.endsAt();
            }
            n.reason = message("공식 후속 자료 변경 · 재검토 대기 · ", decision.blockers());
            notices.saveAndFlush(n);
            reviews.completed(staged, "REVIEW_REQUIRED", n.revision, reviews.contentHash(n.id), n.reason);
            return new Outcome("REVIEW_REQUIRED", true, n.revision);
        }

        var content = decision.content();
        ApplicationPeriod.Window timing;
        try {
            validation.validateContent(content);
            if (content.applicationClosure() != null || !decision.blockers().isEmpty()
                || content.questions().isEmpty() || content.documents().isEmpty()
                || decision.startDate() == null || decision.endDate() == null || decision.startDate().isAfter(decision.endDate())
                || !AutomaticPublicationService.containsBoundary(content, decision.startDate(), false)
                || !AutomaticPublicationService.containsBoundary(content, decision.endDate(), true))
                throw new IllegalArgumentException();
            timing = ApplicationPeriod.resolve(decision.startDate(), decision.endDate(), content.schedules());
            if (timing.startsAt() != null && timing.endsAt() != null && timing.startsAt().isAfter(timing.endsAt()))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            reviews.unsuccessful(job, "FAILED", "FOLLOWUP_COMPILED_CONTENT_INVALID");
            return new Outcome("FAILED", false, n.revision);
        }
        if (AutomaticReviewEligibility.closed(decision.startDate(), decision.endDate(), content.schedules(), Instant.now())) {
            reviews.unsuccessful(job, "SUPERSEDED", "APPLICATION_CLOSED_EXCLUDED");
            return new Outcome("SUPERSEDED", false, n.revision);
        }
        boolean unchanged = json.readValue(n.content, Content.class).equals(content)
            && Objects.equals(n.startDate, decision.startDate()) && Objects.equals(n.endDate, decision.endDate())
            && n.guideReview.equals("PASS") && !n.changeReview;
        if (unchanged) {
            reviews.completed(staged, "UNCHANGED", n.revision, job.contentFingerprint(), "UNCHANGED_COMPILED_GUIDANCE");
            return new Outcome("UNCHANGED", false, n.revision);
        }
        String outcome = content.reviewIssues().isEmpty() ? "PASS" : "PARTIAL";
        n.content = json.writeValueAsString(content);
        n.startDate = decision.startDate();
        n.endDate = decision.endDate();
        n.applicationStartAt = timing.startsAt();
        n.applicationEndAt = timing.endsAt();
        n.guideReview = "PASS";
        n.guideCheckedAt = Instant.now();
        n.changeReview = false;
        n.reason = outcome.equals("PARTIAL") ? "자동 후속 자료 대조 · 일부 항목은 확인 필요로 남겼습니다."
            : "자동 후속 자료 대조 · 확인된 조건·서류·일정을 제공합니다.";
        n.revision++;
        n.version++;
        notices.saveAndFlush(n);
        reviews.completed(staged, outcome, n.revision, reviews.contentHash(n.id), n.reason);
        return new Outcome(outcome, true, n.revision);
    }

    private Outcome publishIntake(NoticeEntity n, Staged staged) {
        var decision = staged.decision();
        var before = json.readValue(n.content, Content.class);
        var intake = decision.content().schedules();
        var start = decision.startDate();
        var end = decision.endDate();
        if (intake.isEmpty() || start != null && end != null && start.isAfter(end)
            || AutomaticReviewEligibility.closed(start, end, intake, Instant.now())) {
            reviews.unsuccessful(staged.job(), "SUPERSEDED", "APPLICATION_PERIOD_INELIGIBLE");
            return new Outcome("SUPERSEDED", false, n.revision);
        }
        var sources = json.readTree(staged.sourcesJson());
        boolean scope = sources.path("scopedCorrection").asBoolean(false) && "PASS".equals(n.guideReview);
        var content = org.jibro.collection.review.ApplicationPeriodCompiler.withIntake(scope ? scopedContent(before) : before, intake, decision.content().reviewIssues());
        validation.validateContent(content);
        var timing = ApplicationPeriod.resolve(start, end, content.schedules());
        boolean changed = scope || !before.equals(content) || !Objects.equals(start, n.startDate) || !Objects.equals(end, n.endDate)
            || !Objects.equals(n.applicationStartAt, timing.startsAt()) || !Objects.equals(n.applicationEndAt, timing.endsAt());
        if (changed) {
            if (scope) markScoped(n);
            n.content = json.writeValueAsString(content);
            n.startDate = start;
            n.endDate = end;
            n.applicationStartAt = timing.startsAt();
            n.applicationEndAt = timing.endsAt();
            n.version++;
            n.revision++;
            notices.saveAndFlush(n);
        }
        reviews.completed(staged, "INTAKE", n.revision, reviews.contentHash(n.id), "VERIFIED_CHANNEL_DATES");
        return new Outcome("INTAKE", changed, n.revision);
    }

    private static Content scopedContent(Content old) {
        return new Content(old.summary(), old.evidence(), old.attachments(), List.of(), List.of(), old.schedules(), old.reviewIssues().stream().filter(i -> "SCHEDULE".equals(i.area())).toList());
    }

    private static void markScoped(NoticeEntity n) {
        n.guideReview = "HOLD";
        n.guideCheckedAt = null;
        n.reason = "정정본과 대조한 준비 항목을 제공합니다. 나머지 항목은 확인 중입니다.";
    }

    private void withdraw(NoticeEntity n, String summary) {
        var before = json.readValue(n.content, Content.class);
        n.content = json.writeValueAsString(new Content(summary, before.evidence(), before.attachments(), List.of(), List.of(), List.of()));
        n.startDate = null;
        n.endDate = null;
        n.applicationStartAt = null;
        n.applicationEndAt = null;
        n.guideReview = "HOLD";
        n.guideCheckedAt = null;
        n.changeReview = true;
        n.revision++;
        n.version++;
    }

    private String message(String prefix, List<String> reasons) {
        String text = prefix + String.join(" / ", reasons);
        return text.substring(0, Math.min(1900, text.length()));
    }
}
