package org.jibro.collection.review;

import java.time.*;
import java.util.*;

import org.jibro.collection.extraction.ExtractionStore;
import org.jibro.collection.review.local.LocalAnalysisService;
import org.jibro.notice.*;
import org.jibro.global.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.jibro.notice.HousingModels.*;

@Service
public class AutomaticReviewService {
    private static final Logger LOG = LoggerFactory.getLogger(AutomaticReviewService.class);
    private final AutomaticReviewRepository repository;
    private final AutomaticPublicationService publication;
    private final ExtractionStore extraction;
    private final NoticeService notices;
    private final boolean enabled;
    private final LocalAnalysisService local;
    private final org.jibro.collection.review.followup.AutomaticFollowupService followup;
    private final FollowupReviewRepository followupResults;
    private final boolean followupEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public AutomaticReviewService(AutomaticReviewRepository repository, AutomaticPublicationService publication,
                                  ExtractionStore extraction, NoticeService notices, LocalAnalysisService local, @Value("${app.collection.automatic-review-enabled:true}") boolean enabled,
                                  org.jibro.collection.review.followup.AutomaticFollowupService followup,
                                  FollowupReviewRepository followupResults,
                                  @Value("${app.collection.automatic-followup-enabled:true}") boolean followupEnabled) {
        this.repository = repository;
        this.publication = publication;
        this.extraction = extraction;
        this.notices = notices;
        this.enabled = enabled;
        this.local = local;
        this.followup = followup;
        this.followupResults = followupResults;
        this.followupEnabled = followupEnabled;
    }

    @Scheduled(initialDelay = 20000, fixedDelayString = "${app.collection.automatic-review-poll-ms:30000}")
    public void tick() {
        if (!enabled) return;
        for (UUID id : repository.pendingBasic()) {
            isolated(id, () -> {
                if (publication.basic(id)) extraction.enqueue(id, false);
            });
        }
        for (UUID id : repository.pendingExtraction()) isolated(id, () -> extraction.enqueue(id, false));
        if (!followupEnabled) for (UUID id : repository.pendingGuidance()) isolated(id, () -> review(id));
    }

    public boolean review(UUID extractionId) {
        var snapshot = repository.snapshot(extractionId);
        var draft = snapshot.draft();
        if (!Set.of("READY", "PARTIAL", "FAILED").contains(draft.status())) return false;
        UUID id = UUID.fromString(draft.noticeId());
        if (repository.closed(id)) return false;
        if (followupEnabled) return followup.reviewNotice(id);
        local.prepare(snapshot);
        var assessment = GuidanceReviewCompiler.assess(draft, notices.content(id));
        List<String> issues = new ArrayList<>(assessment.issues());
        Content content = assessment.ready() ? assessment.candidate() : null;
        var coverage = new PreparationItemComparison(draft, content, List.of()).coverage();
        if (coverage.conditions() == 0) issues.add("QUALIFICATION_ITEMS_NOT_READ");
        if (coverage.documents() == 0) issues.add("SUBMISSION_DOCUMENT_ITEMS_NOT_READ");
        LocalDate start = assessment.ready() ? assessment.startDate() : null;
        LocalDate end = assessment.ready() ? assessment.endDate() : null;
        return publication.guidance(id, draft.revision(), extractionId, snapshot.fingerprint(), content, start, end, issues);
    }

    public GuidanceReviewCompiler.Assessment preview(UUID noticeId, UUID extractionId) {
        var snapshot = repository.snapshot(extractionId);
        if (!snapshot.draft().noticeId().equals(noticeId.toString())) throw ApiException.notFound();
        if (followupEnabled) {
            var completed = followupResults.completedPreview(noticeId, extractionId, snapshot.fingerprint());
            if (completed.isPresent()) return completed.get();
        }
        var current = notices.attachmentSnapshot(noticeId);
        if (current.revision() != snapshot.draft().revision()
                || !repository.matchesCurrentEvidence(noticeId, current.revision(), extractionId, snapshot.fingerprint()))
            throw ApiException.conflict();
        var draft = GuidanceReviewCompiler.assess(snapshot.draft(), current.content());
        if (!followupEnabled) return draft;
        var issues = new ArrayList<>(draft.issues());
        issues.add("FOLLOWUP_REVIEW_PENDING");
        return new GuidanceReviewCompiler.Assessment(draft.profile(), false, draft.candidate(), List.copyOf(issues),
                draft.findings(), draft.startDate(), draft.endDate(), "PENDING", List.of());
    }

    private void isolated(UUID id, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            // One unavailable row must not block independent reviews; never log source text or credentials.
            LOG.warn("Automatic review work deferred: {}", id);
        }
    }
}
