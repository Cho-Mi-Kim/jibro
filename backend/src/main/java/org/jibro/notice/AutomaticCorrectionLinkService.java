package org.jibro.notice;

import java.time.*;
import java.util.*;

import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Fresh official identity proofs only. Notice content, review status and preparations are never written.
 */
@Service
public class AutomaticCorrectionLinkService {
    private record Snapshot(UUID id, String url, int revision, String fingerprint) {
    }

    private record Verified(Snapshot original, OfficialCorrectionLink.Proof proof) {
    }

    private final NoticeRepository notices;
    private final NoticeCorrectionRepository relations;
    private final CorrectionCheckRepository queue;
    private final AttachmentFingerprintClient http;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public AutomaticCorrectionLinkService(NoticeRepository notices, NoticeCorrectionRepository relations,
                                          CorrectionCheckRepository queue, AttachmentFingerprintClient http, ObjectMapper json, PlatformTransactionManager tx) {
        this.notices = notices;
        this.relations = relations;
        this.queue = queue;
        this.http = http;
        this.json = json;
        this.transaction = new TransactionTemplate(tx);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String reconcile(CorrectionCheckRepository.Job job) throws Exception {
        return reconcile(job, null, null);
    }

    /**
     * Explicitly requested pair repair, never called by collection workers. The target must remain open.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String reconcileRequestedLegacyRoot(CorrectionCheckRepository.Job job, UUID originalId) throws Exception {
        if (originalId == null || originalId.equals(job.noticeId()))
            throw new IllegalArgumentException("INVALID_CORRECTION_LINK");
        return reconcile(job, originalId, null);
    }

    /**
     * Exact pair and reviewed full-document digests only; no worker or HTTP entry point.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String reconcileReviewedLegacyAttachments(CorrectionCheckRepository.Job job, UUID originalId,
                                                     ReviewedLegacyCorrection.Review review) throws Exception {
        if (originalId == null || originalId.equals(job.noticeId()) || review == null)
            throw new IllegalArgumentException("INVALID_CORRECTION_LINK");
        return reconcile(job, originalId, review);
    }

    private String reconcile(CorrectionCheckRepository.Job job, UUID requestedLegacyRoot,
                             ReviewedLegacyCorrection.Review attachmentReview) throws Exception {
        if (!queue.current(job)) return "SOURCE_CHANGED";
        Instant started = Instant.now();
        var seed = snapshot(new CorrectionCheckRepository.Source(job.noticeId(), job.url()));
        var seedEntity = notices.findById(seed.id()).orElseThrow(ApiException::notFound);
        if (closed(seedEntity)) return "CLOSED_EXCLUDED";
        String seedHtml = http.html(seed.url());
        if (OfficialCorrectionLink.explicitlyClosed(seed.url(), seedHtml)) return "CLOSED_EXCLUDED";
        var navigation = OfficialCorrectionLink.navigation(seed.url(), seedHtml);
        if (navigation.isEmpty()) return "NO_CORRECTION";
        var targets = queue.sources(navigation.get().latestUrl());
        if (targets.isEmpty()) return "WAITING_FOR_COLLECTION";
        if (targets.size() != 1) return "AMBIGUOUS_COLLECTED_IDENTITY";
        var target = snapshot(targets.get(0));
        var latest = notices.findById(target.id()).orElseThrow(ApiException::notFound);
        if (closed(latest)) return "CLOSED_EXCLUDED";
        if (!"PASS".equals(latest.basicReview) || !"NORMAL".equals(latest.lifecycleState))
            return "WAITING_FOR_PUBLICATION";
        String latestHtml = target.id().equals(seed.id()) ? seedHtml : http.html(target.url());
        if (OfficialCorrectionLink.explicitlyClosed(target.url(), latestHtml)) return "CLOSED_EXCLUDED";
        var latestNavigation = OfficialCorrectionLink.navigation(target.url(), latestHtml).orElseThrow();
        if (!OfficialCorrectionLink.identity(latestNavigation.latestUrl()).equals(OfficialCorrectionLink.identity(target.url())))
            return "OFFICIAL_LATEST_CHANGED";

        String historyHtml = null;
        List<String> predecessors;
        if (attachmentReview != null) {
            if (!target.url().equals(attachmentReview.correctedUrl()))
                throw new IllegalArgumentException("CORRECTION_SOURCE_MISMATCH");
            predecessors = List.of(attachmentReview.originalUrl());
        } else try {
            historyHtml = http.html(OfficialCorrectionLink.historyUrl(target.url()));
            predecessors = OfficialCorrectionHistory.read(target.url(), historyHtml).stream().skip(1)
                .map(OfficialCorrectionHistory.Entry::url).toList();
        } catch (java.io.IOException | IllegalArgumentException unavailable) {
            historyHtml = null;
            if (latestNavigation.originalUrl() == null) throw unavailable;
            predecessors = List.of(latestNavigation.originalUrl());
        }
        var snapshots = new LinkedHashMap<UUID, Snapshot>();
        snapshots.put(seed.id(), seed);
        snapshots.put(target.id(), target);
        var verified = new ArrayList<Verified>();
        boolean missing = false;
        boolean excludedClosed = false;
        for (String url : predecessors) {
            if (started.isBefore(Instant.now().minusSeconds(100)))
                throw new java.io.IOException("CORRECTION_CHECK_BUDGET_EXCEEDED");
            var sources = queue.sources(url);
            if (sources.isEmpty()) {
                missing = true;
                continue;
            }
            if (sources.size() != 1) return "AMBIGUOUS_COLLECTED_IDENTITY";
            var old = snapshot(sources.get(0));
            if (requestedLegacyRoot != null && !requestedLegacyRoot.equals(old.id())) continue;
            if (requestedLegacyRoot == null && closed(notices.findById(old.id()).orElseThrow(ApiException::notFound))) {
                excludedClosed = true;
                continue;
            }
            String html = old.id().equals(seed.id()) ? seedHtml : http.html(old.url());
            if (requestedLegacyRoot == null && OfficialCorrectionLink.explicitlyClosed(old.url(), html)) {
                excludedClosed = true;
                continue;
            }
            var proof = attachmentReview != null
                ? ReviewedLegacyCorrection.verify(attachmentReview, old.url(), html, target.url(), latestHtml, http)
                : requestedLegacyRoot != null
                ? OfficialCorrectionLink.verifyLegacyRootHistory(old.url(), html, target.url(), latestHtml, historyHtml)
                : historyHtml == null ? OfficialCorrectionLink.verify(old.url(), html, target.url(), latestHtml)
                : OfficialCorrectionLink.verifyFamilyHistory(old.url(), html, target.url(), latestHtml, historyHtml);
            snapshots.put(old.id(), old);
            verified.add(new Verified(old, proof));
        }
        if (verified.isEmpty()) return excludedClosed ? "CLOSED_EXCLUDED" : "WAITING_FOR_COLLECTION";
        boolean awaitingCollection = missing;
        return transaction.execute(status -> {
            relations.lockGraph();
            var locked = snapshots.keySet().stream().sorted().map(id -> notices.locked(id).orElseThrow(ApiException::notFound)).toList();
            if (started.isBefore(Instant.now().minusSeconds(120)) || !queue.current(job)) throw ApiException.conflict();
            for (var n : locked) {
                var before = snapshots.get(n.id);
                if (closed(n) && !n.id.equals(requestedLegacyRoot)
                    || n.revision != before.revision() || !Objects.equals(n.sourceFingerprint, before.fingerprint())
                    || !LhNoticeClient.sourceKey(before.url()).equals(n.sourceKey)) throw ApiException.conflict();
            }
            var currentTarget = locked.stream().filter(n -> n.id.equals(target.id())).findFirst().orElseThrow();
            if (!"PASS".equals(currentTarget.basicReview) || !"NORMAL".equals(currentTarget.lifecycleState)
                || closed(currentTarget) || relations.find(target.id()).isPresent()) throw ApiException.conflict();
            var family = new HashSet<UUID>();
            family.add(target.id());
            verified.forEach(v -> family.add(v.original().id()));
            for (var v : verified) {
                var previous = relations.find(v.original().id());
                if (previous.isPresent() && !family.contains(previous.get().correctedId()))
                    throw ApiException.conflict();
            }
            for (var v : verified) {
                var proof = v.proof();
                var source = new HousingModels.Evidence("LH 공식 정정 공고 · 정정사유", target.url(),
                    started.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString());
                relations.replaceLatest(new NoticeCorrectionRepository.Link(v.original().id(), target.id(), v.original().revision(),
                    target.revision(), proof.reason(), new NoticeCorrectionRepository.Evidence(source, v.original().url(), target.url(),
                    proof.originalPanId(), proof.correctedPanId(), proof.originalHtmlSha256(), proof.correctedHtmlSha256(),
                    proof.historyHtmlSha256() == null ? null : OfficialCorrectionLink.historyUrl(target.url()), proof.historyHtmlSha256(), attachmentReview), started));
            }
            return awaitingCollection ? "WAITING_FOR_COLLECTION" : "LINKED";
        });
    }

    private Snapshot snapshot(CorrectionCheckRepository.Source source) {
        var n = notices.findById(source.id()).orElseThrow(ApiException::notFound);
        if (!LhNoticeClient.sourceKey(source.url()).equals(n.sourceKey))
            throw new IllegalArgumentException("CORRECTION_SOURCE_MISMATCH");
        return new Snapshot(n.id, source.url(), n.revision, n.sourceFingerprint);
    }

    private boolean closed(NoticeEntity n) {
        return AutomaticReviewEligibility.closed(n, json.readValue(n.content, HousingModels.Content.class), Instant.now());
    }
}
