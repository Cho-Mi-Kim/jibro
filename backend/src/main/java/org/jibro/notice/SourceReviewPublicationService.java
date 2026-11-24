package org.jibro.notice;

import java.time.*;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.jibro.notice.SourceReviewModels.*;

import org.jibro.notice.SourceReviewVerifier.Verification;

/**
 * Applies source-reviewed content with an explicit AI-assisted audit, without inventing a human reviewer.
 */
@Service
public class SourceReviewPublicationService {
    private enum EditorialScope {ORIGINAL, CONDITIONS, CURRENT_SOURCES, DOCUMENT_DEADLINE}

    private final NoticeRepository notices;
    private final NoticeService validation;
    private final AutomaticReviewRepository extractions;
    private final SourceReviewRepository history;
    private final ObjectMapper json;

    public SourceReviewPublicationService(NoticeRepository notices, NoticeService validation,
                                          AutomaticReviewRepository extractions, SourceReviewRepository history, ObjectMapper json) {
        this.notices = notices;
        this.validation = validation;
        this.extractions = extractions;
        this.history = history;
        this.json = json;
    }

    @Transactional
    public boolean apply(SourceReviewModels.Package p, Verification verified) {
        return publish(p, verified, null, null, EditorialScope.ORIGINAL);
    }

    /**
     * Explicit editorial amendment with fresh evidence and an unchanged previous publication.
     */
    @Transactional
    public boolean amend(SourceReviewModels.Package p, Verification verified, String previousFingerprint) {
        if (previousFingerprint == null || !previousFingerprint.matches("[0-9a-f]{64}")
                || p == null || !java.util.Set.of("PASS", "PARTIAL").contains(p.disposition()))
            throw new IllegalArgumentException("PREVIOUS_SOURCE_REVIEW_REQUIRED");
        return publish(p, verified, previousFingerprint, null, EditorialScope.ORIGINAL);
    }

    public record PublicationBaseline(UUID noticeId, int revision, int version, String contentFingerprint,
                                      String sourceFingerprint, String previousReviewFingerprint) {
    }

    /**
     * Re-review an explicitly captured current publication after independent, reviewed schedule edits.
     * It neither weakens amend's exact-publication guard nor places the current guide on hold first.
     */
    @Transactional
    public boolean rereview(SourceReviewModels.Package p, Verification verified, PublicationBaseline baseline) {
        if (p == null || baseline == null || !java.util.Set.of("PASS", "PARTIAL").contains(p.disposition())
                || !p.noticeId().equals(baseline.noticeId()) || p.revision() != baseline.revision()
                || baseline.version() < 1 || !digest(baseline.contentFingerprint())
                || !digest(baseline.sourceFingerprint()) || !digest(baseline.previousReviewFingerprint()))
            throw new IllegalArgumentException("CURRENT_PUBLICATION_BASELINE_REQUIRED");
        return publish(p, verified, null, baseline, EditorialScope.ORIGINAL);
    }

    /**
     * Explicit question/document correction of an already available guide, including its closed archive.
     */
    @Transactional
    public boolean refineConditions(SourceReviewModels.Package p, Verification verified, PublicationBaseline baseline) {
        if (p == null || baseline == null || !java.util.Set.of("PASS", "PARTIAL").contains(p.disposition())
                || !p.noticeId().equals(baseline.noticeId()) || p.revision() != baseline.revision()
                || baseline.version() < 1 || !digest(baseline.contentFingerprint()) || !digest(baseline.sourceFingerprint()))
            throw new IllegalArgumentException("CURRENT_CONDITIONS_BASELINE_REQUIRED");
        return publish(p, verified, null, baseline, EditorialScope.CONDITIONS);
    }

    /**
     * Reconcile an available, active guide with its already collected current attachments.
     * A fresh full source review is mandatory; this path cannot replace the collected notice or files.
     */
    @Transactional
    public boolean reconcileCurrentSources(SourceReviewModels.Package p, Verification verified, PublicationBaseline baseline) {
        if (p == null || baseline == null || !java.util.Set.of("PASS", "PARTIAL").contains(p.disposition())
                || !p.noticeId().equals(baseline.noticeId()) || p.revision() != baseline.revision()
                || baseline.version() < 1 || !digest(baseline.contentFingerprint()) || !digest(baseline.sourceFingerprint()))
            throw new IllegalArgumentException("CURRENT_SOURCES_BASELINE_REQUIRED");
        return publish(p, verified, null, baseline, EditorialScope.CURRENT_SOURCES);
    }

    @Transactional
    public boolean amendDocumentDeadline(SourceReviewModels.Package p, Verification verified, PublicationBaseline baseline) {
        if (p == null || baseline == null || !p.noticeId().equals(baseline.noticeId())
                || p.revision() != baseline.revision() || !digest(baseline.contentFingerprint())
                || !digest(baseline.sourceFingerprint()) || !digest(baseline.previousReviewFingerprint()))
            throw new IllegalArgumentException("DOCUMENT_DEADLINE_BASELINE_REQUIRED");
        return publish(p, verified, null, baseline, EditorialScope.DOCUMENT_DEADLINE);
    }

    private boolean matchesDocumentDeadline(NoticeEntity n, SourceReviewModels.Package p, PublicationBaseline baseline, String detailUrl) {
        if (!matchesBaseline(n, p, baseline, detailUrl) || !Objects.equals(n.startDate, p.startDate())
                || !Objects.equals(n.endDate, p.endDate()) || n.endDate == null) return false;
        var old = json.readValue(n.content, HousingModels.Content.class);
        var next = p.content();
        if (next == null || next.schedules().size() != old.schedules().size() + 1
                || !next.schedules().subList(0, old.schedules().size()).equals(old.schedules())) return false;
        var added = next.schedules().get(old.schedules().size());
        if (!added.title().equals("서류 제출 마감") || !added.date().equals(n.endDate.toString())
                || old.schedules().stream().anyMatch(s -> s.id().equals(added.id()) || s.title().matches(".*서류.*마감.*")))
            return false;
        return old.equals(new HousingModels.Content(next.summary(), next.evidence(), next.attachments(), next.questions(),
                next.documents(), old.schedules(), next.reviewIssues(), next.applicationClosure()));
    }

    private boolean matchesCurrentSources(NoticeEntity n, SourceReviewModels.Package p, PublicationBaseline baseline) {
        var old = json.readValue(n.content, HousingModels.Content.class);
        return n.version == baseline.version() && n.revision == baseline.revision()
                && Objects.equals(n.sourceFingerprint, baseline.sourceFingerprint())
                && history.contentFingerprint(n.content).equals(baseline.contentFingerprint()) && !extractions.manual(n.id)
                && !AutomaticReviewEligibility.closed(n, old, Instant.now())
                && p.content() != null && Objects.equals(old.evidence(), p.content().evidence())
                && Objects.equals(old.attachments(), p.content().attachments());
    }

    private boolean matchesConditionsBaseline(NoticeEntity n, SourceReviewModels.Package p, PublicationBaseline baseline) {
        if (n.version != baseline.version() || n.revision != baseline.revision()
                || !Objects.equals(n.sourceFingerprint, baseline.sourceFingerprint())
                || !history.contentFingerprint(n.content).equals(baseline.contentFingerprint()) || extractions.manual(n.id)
                || !Objects.equals(n.startDate, p.startDate()) || !Objects.equals(n.endDate, p.endDate())) return false;
        var old = json.readValue(n.content, HousingModels.Content.class);
        var next = p.content();
        if (next == null || !Objects.equals(old.summary(), next.summary()) || !Objects.equals(old.evidence(), next.evidence())
                || !Objects.equals(old.attachments(), next.attachments()) || !Objects.equals(old.schedules(), next.schedules())
                || !issuesPreserved(old, next) || !Objects.equals(old.applicationClosure(), next.applicationClosure()))
            return false;
        // Unresolved official questions cannot be silently removed or marked confirmed by a readability correction.
        return old.questions().stream().filter(q -> q.reviewRequired()).allMatch(q -> next.questions().stream().anyMatch(q::equals));
    }

    private boolean issuesPreserved(HousingModels.Content old, HousingModels.Content next) {
        for (var prior : old.reviewIssues()) {
            var current = next.reviewIssues().stream().filter(i -> i.id().equals(prior.id())).findFirst();
            if (current.isEmpty()) return false;
            var issue = current.get();
            if (!Objects.equals(prior.area(), issue.area()) || !Objects.equals(prior.title(), issue.title())
                    || !Objects.equals(prior.detail(), issue.detail()) || !Objects.equals(prior.evidence(), issue.evidence())
                    || !Objects.equals(prior.questionIds(), issue.questionIds()) || !issue.documentIds().containsAll(prior.documentIds()))
                return false;
        }
        return next.reviewIssues().stream().filter(i -> old.reviewIssues().stream().noneMatch(p -> p.id().equals(i.id())))
                .allMatch(i -> java.util.Set.of("CONDITION", "DOCUMENT").contains(i.area()));
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean matchesBaseline(NoticeEntity n, SourceReviewModels.Package p, PublicationBaseline baseline, String detailUrl) {
        if (n.version != baseline.version() || n.revision != baseline.revision()
                || !Objects.equals(n.sourceFingerprint, baseline.sourceFingerprint())
                || !history.contentFingerprint(n.content).equals(baseline.contentFingerprint())
                || AutomaticReviewEligibility.closed(n, json.readValue(n.content, HousingModels.Content.class), Instant.now()))
            return false;
        var predecessor = history.editorialPredecessor(n.id, n.revision, baseline.previousReviewFingerprint());
        if (predecessor.isEmpty()) return false;
        var prior = json.treeToValue(json.readTree(predecessor.get()).get("review"), SourceReviewModels.Package.class);
        String currentUrl = json.readValue(n.content, HousingModels.Content.class).evidence().url();
        if (!n.id.equals(prior.noticeId()) || !Objects.equals(currentUrl, prior.content().evidence().url())
                || !Objects.equals(currentUrl, p.content().evidence().url())) return false;
        // This is an editorial re-review of the same originals, not authorization to replace sources.
        var oldSources = prior.sourceReviews().stream().filter(s -> !s.url().equals(detailUrl))
                .collect(Collectors.toMap(SourceReview::url, SourceReview::sha256));
        var newSources = p.sourceReviews().stream().filter(s -> !s.url().equals(detailUrl))
                .collect(Collectors.toMap(SourceReview::url, SourceReview::sha256));
        return !oldSources.isEmpty() && oldSources.equals(newSources);
    }

    private boolean publish(SourceReviewModels.Package p, Verification verified, String previousFingerprint, PublicationBaseline baseline, EditorialScope scope) {
        boolean conditionsOnly = scope == EditorialScope.CONDITIONS;
        boolean documentDeadline = scope == EditorialScope.DOCUMENT_DEADLINE;
        SourceReviewVerifier.validate(p);
        var fingerprint = SourceReviewVerifier.hash(json.writeValueAsString(p));
        if (verified == null || verified.conditionsOnly() && !conditionsOnly && !documentDeadline
                || !fingerprint.equals(verified.packageFingerprint()) || verified.checkedAt() == null
                || verified.checkedAt().isBefore(Instant.now().minusSeconds(900)) || verified.checkedAt().isAfter(Instant.now().plusSeconds(5))
                || p.sourceReviews().stream().anyMatch(s -> !verified.freshDigests().containsKey(s.url())
                || !s.url().equals(verified.detailUrl()) && !s.sha256().equals(verified.freshDigests().get(s.url()))))
            throw new IllegalArgumentException("SOURCE_VERIFICATION_REQUIRED");
        var n = notices.locked(p.noticeId()).orElseThrow(ApiException::notFound);
        if (history.applied(fingerprint)) return false;
        boolean editable = baseline != null ? n.guideReview.equals("PASS") && !n.changeReview
                && (scope == EditorialScope.CURRENT_SOURCES ? matchesCurrentSources(n, p, baseline)
                : documentDeadline ? matchesDocumentDeadline(n, p, baseline, verified.detailUrl())
                : conditionsOnly ? matchesConditionsBaseline(n, p, baseline) : matchesBaseline(n, p, baseline, verified.detailUrl()))
                : previousFingerprint == null ? n.guideReview.equals("HOLD")
                : n.guideReview.equals("PASS") && !n.changeReview
                && history.matchesPublication(n.id, n.revision, previousFingerprint, n.content);
        boolean revalidateBasic = scope == EditorialScope.ORIGINAL && baseline == null && previousFingerprint == null
                && n.basicReview.equals("HOLD") && n.guideReview.equals("HOLD") && n.changeReview && n.automaticReviewBlocked
                && !extractions.manual(n.id) && n.collectedAt != null && java.util.Set.of("PASS", "PARTIAL").contains(p.disposition())
                && p.content() != null && p.content().applicationClosure() == null && LhBasicReview.valid(n, p.content())
                && Objects.equals(json.readValue(n.content, HousingModels.Content.class).evidence().url(), p.content().evidence().url())
                && Objects.equals(n.sourceKey, org.jibro.collection.LhNoticeClient.sourceKey(verified.detailUrl()))
                && !AutomaticReviewEligibility.closed(n, json.readValue(n.content, HousingModels.Content.class), Instant.now())
                && !AutomaticReviewEligibility.closed(p.startDate(), p.endDate(), p.content().schedules(), Instant.now());
        if (n.serviceExcluded || n.serviceClosed || n.revision != p.revision() || !n.basicReview.equals("PASS") && !revalidateBasic || !editable
                || !n.lifecycleState.equals("NORMAL") || n.sourceKey == null || !n.sourceKey.startsWith("LH:")
                || !extractions.matchesCurrentEvidence(p.noticeId(), p.revision(), p.extractionId(), verified.extractionFingerprint()))
            throw ApiException.conflict();
        if (p.disposition().equals("PASS") || p.disposition().equals("PARTIAL")) {
            validation.validateContent(p.content());
            if (p.content().questions().isEmpty() || p.content().documents().isEmpty())
                throw new IllegalArgumentException("GUIDANCE_INCOMPLETE");
            if (!conditionsOnly) ReviewedApplicationPeriod.validate(p);
            var timing = ApplicationPeriod.resolve(p.startDate(), p.endDate(), p.content().schedules());
            if (timing.startsAt() != null && timing.endsAt() != null && timing.startsAt().isAfter(timing.endsAt()))
                throw new IllegalArgumentException("REVIEWED_APPLICATION_TIME_REVERSED");
            n.content = json.writeValueAsString(p.content());
            if (!conditionsOnly) {
                n.startDate = p.startDate();
                n.endDate = p.endDate();
                n.applicationStartAt = timing.startsAt();
                n.applicationEndAt = timing.endsAt();
                n.guideCheckedAt = Instant.now();
            }
            n.version++;
        } else if (p.disposition().equals("UNSUPPORTED")) {
            validation.validateContent(p.content());
            var c = p.content();
            // Explain the reviewed scope on the public detail without offering a personal eligibility flow.
            n.content = json.writeValueAsString(new HousingModels.Content(c.summary(), c.evidence(), c.attachments(),
                    java.util.List.of(), java.util.List.of(), c.schedules()));
            n.version++;
        }
        if (revalidateBasic) {
            n.basicReview = "PASS";
            n.basicCheckedAt = verified.checkedAt();
        }
        // PASS is the existing availability gate. Scoped unresolved facts stay in Content.reviewIssues
        // and the immutable audit records PARTIAL, so availability never implies those facts were resolved.
        n.guideReview = p.disposition().equals("PARTIAL") ? "PASS" : p.disposition();
        n.reason = "원문 대조 검토 · " + p.reviewNotes();
        if (!p.disposition().equals("HOLD")) {
            n.automaticReviewBlocked = true;
            n.changeReview = false;
            n.revision++;
        }
        notices.saveAndFlush(n);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("review", p);
        if (revalidateBasic) payload.put("basicRevalidated", true);
        payload.put("verification", Map.of(
                "packageFingerprint", verified.packageFingerprint(), "extractionFingerprint", verified.extractionFingerprint(),
                "checkedAt", verified.checkedAt(), "freshDigests", verified.freshDigests(),
                "detailUrl", verified.detailUrl(), "detailDigestKind", "CANONICAL_EXTRACTED_PARTS_SHA256",
                "attachmentDigestKind", "RAW_FILE_SHA256",
                "supplementalPageDigestKind", "SUPPLEMENTAL_SOURCE_V1_TITLE_BODY_LINKS_SHA256",
                "scope", documentDeadline ? "DOCUMENT_DEADLINE_WITH_UNCHANGED_ANCILLARY" : verified.conditionsOnly() ? "CONDITIONS_ONLY" : "ALL_CONTENT",
                "unchangedAncillaryEvidence", verified.retainedEvidence()));
        if (baseline != null) {
            payload.put("scope", documentDeadline ? "CURRENT_PUBLICATION_DOCUMENT_DEADLINE_AMENDMENT" : scope == EditorialScope.CURRENT_SOURCES ? "CURRENT_COLLECTED_SOURCES_RECONCILIATION"
                    : conditionsOnly ? "CURRENT_PUBLICATION_CONDITIONS_REFINEMENT" : "CURRENT_PUBLICATION_SOURCE_REREVIEW");
            payload.put("baseline", baseline);
        }
        history.record(n.id, p.revision(), n.revision, p.disposition(), fingerprint, json.writeValueAsString(payload));
        return true;
    }
}
