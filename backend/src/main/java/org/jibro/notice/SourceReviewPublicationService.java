package org.jibro.notice;

import java.time.*;
import java.util.Map;
import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.jibro.notice.SourceReviewModels.*;
import org.jibro.notice.SourceReviewVerifier.Verification;

/** Applies source-reviewed content with an explicit AI-assisted audit, without inventing a human reviewer. */
@Service
public class SourceReviewPublicationService {
    private final NoticeRepository notices;
    private final NoticeService validation;
    private final AutomaticReviewRepository extractions;
    private final SourceReviewRepository history;
    private final ObjectMapper json;
    public SourceReviewPublicationService(NoticeRepository notices, NoticeService validation,
            AutomaticReviewRepository extractions, SourceReviewRepository history, ObjectMapper json) {
        this.notices = notices; this.validation = validation; this.extractions = extractions; this.history = history; this.json = json;
    }
    @Transactional
    public boolean apply(SourceReviewModels.Package p, Verification verified) {
        return publish(p, verified, null);
    }
    /** Explicit editorial amendment with fresh evidence and an unchanged previous publication. */
    @Transactional
    public boolean amend(SourceReviewModels.Package p, Verification verified, String previousFingerprint) {
        if (previousFingerprint == null || !previousFingerprint.matches("[0-9a-f]{64}")
            || p == null || !java.util.Set.of("PASS", "PARTIAL").contains(p.disposition()))
            throw new IllegalArgumentException("PREVIOUS_SOURCE_REVIEW_REQUIRED");
        return publish(p, verified, previousFingerprint);
    }
    private boolean publish(SourceReviewModels.Package p, Verification verified, String previousFingerprint) {
        SourceReviewVerifier.validate(p);
        var fingerprint = SourceReviewVerifier.hash(json.writeValueAsString(p));
        if (verified == null || !fingerprint.equals(verified.packageFingerprint()) || verified.checkedAt() == null
            || verified.checkedAt().isBefore(Instant.now().minusSeconds(900)) || verified.checkedAt().isAfter(Instant.now().plusSeconds(5))
            || p.sourceReviews().stream().anyMatch(s -> !verified.freshDigests().containsKey(s.url())
                || !s.url().equals(verified.detailUrl()) && !s.sha256().equals(verified.freshDigests().get(s.url()))))
            throw new IllegalArgumentException("SOURCE_VERIFICATION_REQUIRED");
        var n = notices.locked(p.noticeId()).orElseThrow(ApiException::notFound);
        if (history.applied(fingerprint)) return false;
        boolean editable = previousFingerprint == null ? n.guideReview.equals("HOLD")
            : n.guideReview.equals("PASS") && !n.changeReview
                && history.matchesPublication(n.id, n.revision, previousFingerprint, n.content);
        if (n.serviceExcluded || n.serviceClosed || n.revision != p.revision() || !n.basicReview.equals("PASS") || !editable
            || !n.lifecycleState.equals("NORMAL") || n.sourceKey == null || !n.sourceKey.startsWith("LH:")
            || !extractions.matchesCurrentEvidence(p.noticeId(), p.revision(), p.extractionId(), verified.extractionFingerprint()))
            throw ApiException.conflict();
        if (p.disposition().equals("PASS") || p.disposition().equals("PARTIAL")) {
            validation.validateContent(p.content());
            if (p.content().questions().isEmpty() || p.content().documents().isEmpty())
                throw new IllegalArgumentException("GUIDANCE_INCOMPLETE");
            ReviewedApplicationPeriod.validate(p);
            var timing = ApplicationPeriod.resolve(p.startDate(), p.endDate(), p.content().schedules());
            if (timing.startsAt() != null && timing.endsAt() != null && timing.startsAt().isAfter(timing.endsAt()))
                throw new IllegalArgumentException("REVIEWED_APPLICATION_TIME_REVERSED");
            n.content = json.writeValueAsString(p.content());
            n.startDate = p.startDate(); n.endDate = p.endDate();
            n.applicationStartAt = timing.startsAt(); n.applicationEndAt = timing.endsAt();
            n.guideCheckedAt = Instant.now(); n.version++;
        } else if (p.disposition().equals("UNSUPPORTED")) {
            validation.validateContent(p.content());
            var c = p.content();
            // Explain the reviewed scope on the public detail without offering a personal eligibility flow.
            n.content = json.writeValueAsString(new HousingModels.Content(c.summary(), c.evidence(), c.attachments(),
                java.util.List.of(), java.util.List.of(), c.schedules()));
            n.version++;
        }
        // PASS is the existing availability gate. Scoped unresolved facts stay in Content.reviewIssues
        // and the immutable audit records PARTIAL, so availability never implies those facts were resolved.
        n.guideReview = p.disposition().equals("PARTIAL") ? "PASS" : p.disposition();
        n.reason = "원문 대조 검토 · " + p.reviewNotes();
        if (!p.disposition().equals("HOLD")) {
            n.automaticReviewBlocked = true; n.changeReview = false; n.revision++;
        }
        notices.saveAndFlush(n);
        history.record(n.id, p.revision(), n.revision, p.disposition(), fingerprint,
            json.writeValueAsString(Map.of("review", p, "verification", Map.of(
                "packageFingerprint",verified.packageFingerprint(),"extractionFingerprint",verified.extractionFingerprint(),
                "checkedAt",verified.checkedAt(),"freshDigests",verified.freshDigests(),
                "detailUrl",verified.detailUrl(),"detailDigestKind","CANONICAL_EXTRACTED_PARTS_SHA256",
                "attachmentDigestKind","RAW_FILE_SHA256",
                "supplementalPageDigestKind","SUPPLEMENTAL_SOURCE_V1_TITLE_BODY_LINKS_SHA256"))));
        return true;
    }
}
