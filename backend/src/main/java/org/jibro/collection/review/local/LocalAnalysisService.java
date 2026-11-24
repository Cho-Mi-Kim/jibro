package org.jibro.collection.review.local;

import java.util.*;

import org.jibro.collection.review.SourceCoveragePolicy;
import org.jibro.collection.review.GuidanceReviewCompiler;
import org.jibro.collection.review.OfficialFormConsistency;
import org.jibro.collection.review.NationalRentalScopeReview;
import org.jibro.collection.review.ai.GeminiReviewService;
import org.jibro.collection.review.ai.GeminiReviewModels.Status;
import org.jibro.global.common.ApiException;
import org.jibro.notice.AutomaticReviewRepository;
import org.jibro.notice.AutomaticReviewRepository.ExtractionSnapshot;
import org.jibro.notice.NoticeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local reading and selective API assistance are independent of publication approval.
 */
@Service
public class LocalAnalysisService {
    public static final String POLICY = "local-v2/" + LocalRuleInterpreter.POLICY + "/" + CompactReviewPlanner.POLICY + "/" + CompactReviewPacket.REVIEW_POLICY + "/" + GuidanceReviewCompiler.POLICY;
    private static final Logger LOG = LoggerFactory.getLogger(LocalAnalysisService.class);
    private final LocalAnalysisRepository repository;
    private final AutomaticReviewRepository reviews;
    private final NoticeService notices;
    private final GeminiReviewService gemini;
    private final boolean enabled;

    public LocalAnalysisService(LocalAnalysisRepository repository, AutomaticReviewRepository reviews, NoticeService notices,
                                GeminiReviewService gemini, @Value("${app.collection.automatic-review-enabled:true}") boolean enabled) {
        this.repository = repository;
        this.reviews = reviews;
        this.notices = notices;
        this.gemini = gemini;
        this.enabled = enabled;
    }

    public LocalAnalysisRepository.Entry prepare(ExtractionSnapshot snapshot) {
        if (!Set.of("READY", "PARTIAL", "FAILED").contains(snapshot.draft().status()) || snapshot.draft().checkedAt() == null)
            throw ApiException.conflict();
        UUID extraction = UUID.fromString(snapshot.draft().id());
        var cached = repository.find(extraction, snapshot.fingerprint(), POLICY);
        if (cached.isPresent()) return cached.get();
        var draft = snapshot.draft();
        var book = LocalRuleInterpreter.interpret(draft.sources());
        var plan = CompactReviewPlanner.plan(book);
        var coverage = SourceCoveragePolicy.assess(draft, notices.content(UUID.fromString(draft.noticeId())));
        var issues = new LinkedHashSet<>(coverage.issues());
        OfficialFormConsistency.inspect(draft).forEach(finding -> issues.add(finding.code()));
        String checked = draft.checkedAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        draft.sources().forEach(source -> NationalRentalScopeReview.inspect(source, checked).forEach(finding -> issues.add(finding.code())));
        // An already identified source conflict/scope gap needs source resolution, not another model call.
        repository.save(snapshot, POLICY, book, plan, List.copyOf(issues));
        return repository.find(extraction, snapshot.fingerprint(), POLICY).orElseThrow();
    }

    public LocalAnalysisRepository.Entry get(UUID notice, UUID extraction) {
        var snapshot = reviews.snapshot(extraction);
        if (!snapshot.draft().noticeId().equals(notice.toString())) throw ApiException.notFound();
        return prepare(snapshot);
    }

    @Scheduled(initialDelay = 25000, fixedDelayString = "${app.collection.local-analysis-poll-ms:30000}")
    public void tick() {
        if (!enabled) return;
        for (UUID id : repository.unprocessed(POLICY)) {
            try {
                prepare(reviews.snapshot(id));
            } catch (RuntimeException failure) {
                LOG.warn("Local interpretation deferred: {}", id);
            }
        }
        // At most one new provider request per tick. The shared durable budget additionally enforces 60s/10 per day.
        for (var entry : repository.pending(POLICY)) {
            for (var packet : entry.plan().packets()) {
                if (entry.apiResults().has(packet.id())) continue;
                if (!repository.current(entry)) break;
                var result = gemini.reviewPacket(packet);
                if (result.status() == Status.DISABLED || result.status() == Status.RATE_LIMITED
                        && Set.of("RATE_WAIT", "DAILY_LIMIT", "IN_FLIGHT", "PROVIDER_PAUSED").contains(result.code()))
                    return;
                repository.result(entry, packet.id(), result);
                return;
            }
        }
    }
}
