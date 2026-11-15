package org.jibro.notice;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.LhNoticeClient;
import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Explicit editorial commands; collection uses AutomaticCorrectionLinkService and the same relation repository.
 */
@Service
public class NoticeCorrectionService {
    public record Correction(String noticeId, String title, String reason, HousingModels.Evidence evidence,
                             Instant checkedAt) {
    }

    private record Snapshot(UUID id, int revision, String url) {
    }

    private final NoticeRepository notices;
    private final NoticeCorrectionRepository links;
    private final NoticeService views;
    private final AttachmentFingerprintClient http;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public NoticeCorrectionService(NoticeRepository notices, NoticeCorrectionRepository links, NoticeService views,
                                   AttachmentFingerprintClient http, ObjectMapper json, PlatformTransactionManager transactions) {
        this.notices = notices;
        this.links = links;
        this.views = views;
        this.http = http;
        this.json = json;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Correction link(UUID originalId, UUID correctedId, int originalRevision, int correctedRevision) throws Exception {
        if (originalId == null || correctedId == null || originalId.equals(correctedId)
            || originalRevision < 1 || correctedRevision < 1)
            throw new IllegalArgumentException("INVALID_CORRECTION_LINK");
        var original = snapshot(originalId, originalRevision, false);
        var corrected = snapshot(correctedId, correctedRevision, true);
        Instant startedAt = Instant.now();
        // Network access completes before locking either notice. No data can be applied from a caller-created proof.
        var proof = verify(original.url(), corrected.url());
        return transaction.execute(status -> {
            links.lockGraph();
            var locked = List.of(originalId, correctedId).stream().sorted().map(id -> notices.locked(id).orElseThrow(ApiException::notFound)).toList();
            var oldNotice = locked.stream().filter(n -> n.id.equals(originalId)).findFirst().orElseThrow();
            var target = locked.stream().filter(n -> n.id.equals(correctedId)).findFirst().orElseThrow();
            if (startedAt.isBefore(Instant.now().minusSeconds(120)) || links.touches(originalId, correctedId)
                || !matches(oldNotice, original, false) || !matches(target, corrected, true))
                throw ApiException.conflict();
            var evidence = new HousingModels.Evidence("LH 공식 정정 공고 · 정정사유", corrected.url(),
                startedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString());
            links.insert(new NoticeCorrectionRepository.Link(originalId, correctedId, originalRevision, correctedRevision,
                proof.reason(), new NoticeCorrectionRepository.Evidence(evidence, original.url(), corrected.url(),
                proof.originalPanId(), proof.correctedPanId(), proof.originalHtmlSha256(), proof.correctedHtmlSha256(),
                proof.historyHtmlSha256() == null ? null : OfficialCorrectionLink.historyUrl(corrected.url()), proof.historyHtmlSha256()), startedAt));
            return new Correction(target.id.toString(), target.title, proof.reason(), evidence, startedAt);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<Correction> correction(UUID originalId) {
        views.notice(originalId); // Hidden/unreviewed originals keep the same 404 boundary as normal detail.
        var link = links.find(originalId);
        if (link.isEmpty()) return Optional.empty();
        var value = link.get();
        var original = notices.findById(originalId).orElseThrow(ApiException::notFound);
        var target = notices.findById(value.correctedId()).orElse(null);
        if (original.revision != value.originalRevision() || target == null || target.revision != value.correctedRevision()
            || !publishable(target, true)) return Optional.empty();
        return Optional.of(new Correction(target.id.toString(), target.title, value.reason(), value.evidence().source(), value.checkedAt()));
    }

    /**
     * Editorial amendments hide stale links; only another live verification can restore the same pair.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Correction reverify(UUID originalId, int originalRevision, int correctedRevision) throws Exception {
        var previous = links.find(originalId).orElseThrow(ApiException::notFound);
        var original = snapshot(originalId, originalRevision, false);
        var corrected = snapshot(previous.correctedId(), correctedRevision, true);
        Instant startedAt = Instant.now();
        var proof = verify(original.url(), corrected.url());
        return transaction.execute(status -> {
            links.lockGraph();
            var locked = List.of(original.id(), corrected.id()).stream().sorted()
                .map(id -> notices.locked(id).orElseThrow(ApiException::notFound)).toList();
            var oldNotice = locked.stream().filter(n -> n.id.equals(original.id())).findFirst().orElseThrow();
            var target = locked.stream().filter(n -> n.id.equals(corrected.id())).findFirst().orElseThrow();
            if (startedAt.isBefore(Instant.now().minusSeconds(120))
                || !links.find(originalId).filter(previous::equals).isPresent()
                || !matches(oldNotice, original, false) || !matches(target, corrected, true))
                throw ApiException.conflict();
            var source = new HousingModels.Evidence("LH 공식 정정 공고 · 정정사유", corrected.url(),
                startedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString());
            var refreshed = new NoticeCorrectionRepository.Link(original.id(), corrected.id(), originalRevision, correctedRevision,
                proof.reason(), new NoticeCorrectionRepository.Evidence(source, original.url(), corrected.url(),
                proof.originalPanId(), proof.correctedPanId(), proof.originalHtmlSha256(), proof.correctedHtmlSha256(),
                proof.historyHtmlSha256() == null ? null : OfficialCorrectionLink.historyUrl(corrected.url()), proof.historyHtmlSha256()), startedAt);
            if (!links.reverify(previous, refreshed)) throw ApiException.conflict();
            return new Correction(target.id.toString(), target.title, proof.reason(), source, startedAt);
        });
    }

    private OfficialCorrectionLink.Proof verify(String originalUrl, String correctedUrl) throws Exception {
        String originalHtml = http.html(originalUrl), correctedHtml = http.html(correctedUrl);
        try {
            return OfficialCorrectionLink.verify(originalUrl, originalHtml, correctedUrl, correctedHtml);
        } catch (IllegalArgumentException unverified) {
            var id = OfficialCorrectionLink.identity(correctedUrl);
            if (!id.system().equals("02") || !id.category().equals("06") || !id.type().equals("08")) throw unverified;
            return OfficialCorrectionLink.verifyHistory(originalUrl, originalHtml, correctedUrl, correctedHtml,
                http.html(OfficialCorrectionLink.historyUrl(correctedUrl)));
        }
    }

    private Snapshot snapshot(UUID id, int revision, boolean target) {
        var n = notices.findById(id).orElseThrow(ApiException::notFound);
        if (n.revision != revision || !publishable(n, target)) throw ApiException.conflict();
        var url = url(n);
        OfficialCorrectionLink.identity(url);
        return new Snapshot(id, revision, url);
    }

    private boolean matches(NoticeEntity notice, Snapshot snapshot, boolean target) {
        return notice.revision == snapshot.revision() && publishable(notice, target) && url(notice).equals(snapshot.url());
    }

    private boolean publishable(NoticeEntity n, boolean target) {
        return !n.serviceExcluded && n.sourceKey != null && n.sourceKey.startsWith("LH:") && n.basicReview.equals("PASS")
            && (!target || n.guideReview.equals("PASS") && !n.changeReview && n.lifecycleState.equals("NORMAL"));
    }

    private String url(NoticeEntity n) {
        var content = json.readValue(n.content, HousingModels.Content.class);
        var candidates = new LinkedHashSet<>(links.detailCandidates(n.id));
        if (content.evidence() != null && content.evidence().url() != null) candidates.add(content.evidence().url());
        // A published guide may cite a PDF as its main evidence. Only the collected source identity
        // can select the HTML detail; neither a title match nor an arbitrary content URL is sufficient.
        var bound = candidates.stream().filter(candidate -> LhNoticeClient.sourceKey(candidate).equals(n.sourceKey))
            .filter(candidate -> {
                try {
                    OfficialCorrectionLink.identity(candidate);
                    return true;
                } catch (IllegalArgumentException unsupported) {
                    return false;
                }
            }).toList();
        if (bound.size() != 1) throw new IllegalArgumentException("OFFICIAL_CORRECTION_SOURCE_REQUIRED");
        return bound.get(0);
    }
}
