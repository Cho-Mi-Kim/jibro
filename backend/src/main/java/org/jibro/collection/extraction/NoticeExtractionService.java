package org.jibro.collection.extraction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.jibro.collection.*;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.global.common.ApiException;
import org.jibro.notice.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.jibro.collection.extraction.ExtractionModels.*;

@Service
public class NoticeExtractionService implements CollectionItemProcessor {
    private final ExtractionStore store;
    private final NoticeService notices;
    private final AttachmentFingerprintClient http;
    private final BoundedDocumentParser parser;
    private final boolean enabled;
    public NoticeExtractionService(ExtractionStore store, NoticeService notices, AttachmentFingerprintClient http,
                                   BoundedDocumentParser parser, @Value("${app.collection.extraction-enabled:true}") boolean enabled) {
        this.store = store; this.notices = notices; this.http = http; this.parser = parser; this.enabled = enabled;
    }
    @Override public void process(UUID id, LhNoticeClient.Item item) {
        if (enabled && !notices.applicationClosed(id)) store.enqueue(id, false);
    }

    @Scheduled(fixedDelayString = "${app.collection.extraction-poll-ms:30000}", initialDelay = 15000)
    public void tick() {
        if (!enabled) return;
        runNext();
    }
    public void runNext() {
        run(store.claim());
    }
    public void runNext(UUID noticeId) {
        run(store.claim(noticeId));
    }
    private void run(ExtractionStore.Job job) {
        if (job == null) return;
        List<Source> sources = new ArrayList<>();
        try {
            sources.addAll(store.find(job.id()).sources());
            var snapshot = notices.attachmentSnapshot(job.noticeId());
            if (snapshot.revision() != job.revision()) { store.finish(job, sources, "SUPERSEDED", "공고가 변경되었습니다. 다시 발췌해 주세요."); return; }
            var content = snapshot.content();
            if (sources.isEmpty()) {
            Map<String, Link> links = new LinkedHashMap<>();
            for (var a : content.attachments()) if (a.url() != null) links.put(a.url(), new Link(a.label(), a.url()));
            String url = content.evidence().url();
            try {
                String html = http.html(url);
                ExtractionText.html(html);
                var detail = OfficialDetailParser.parse(html, url);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(html.getBytes(StandardCharsets.UTF_8)));
                sources.add(extractedSource("공식 상세 본문", url, hash, detail.parts()));
                for (var link : detail.attachments()) links.putIfAbsent(link.url(), link);
            } catch (ExtractionText.Invalid invalid) {
                sources.add(new Source("공식 상세 본문", url, null, "FAILED", ExtractionText.FAILURE_MESSAGE, List.of()));
            } catch (Exception ex) {
                sources.add(new Source("공식 상세 본문", url, null, "FAILED", "본문을 읽지 못했습니다. 원문을 직접 확인해 주세요.", List.of()));
            }
            for (var link : links.values()) {
                sources.add(new Source(link.label(), link.url(), null, "DEFERRED", "다음 발췌 작업에서 자동으로 이어서 확인합니다.", List.of()));
            }
            }
            if (!store.checkpoint(job, sources)) return;
            int count = 0;
            for (int i = 0; i < sources.size(); i++) {
                var source = sources.get(i);
                if (source.status().equals("DEFERRED") && count++ < 3) {
                    if (source.label().equals("공식 상세 본문")) retryDetail(sources, i, source);
                    else sources.set(i, extract(new Link(source.label(), source.url())));
                    if (!store.checkpoint(job, sources)) return;
                }
            }
            if (sources.stream().anyMatch(s -> s.status().equals("DEFERRED"))) {
                store.finish(job, sources, "QUEUED", "일부 첨부를 발췌했습니다. 남은 첨부는 자동으로 이어서 읽습니다.");
                return;
            }
            boolean failure = sources.stream().anyMatch(s -> !s.status().equals("EXTRACTED") || OcrProvenance.incomplete(s.context()));
            boolean any = sources.stream().anyMatch(s -> s.status().equals("EXTRACTED"));
            store.finish(job, sources, any ? (failure ? "PARTIAL" : "READY") : "FAILED",
                    any ? "검토용 발췌입니다. 조건·서류·일정은 관리자가 원문과 대조해 원고에 반영해야 합니다." : "발췌를 확인하지 못했습니다. 기준이 없다는 뜻이 아닙니다. 원문을 직접 확인해 주세요.");
        } catch (Exception ex) { store.finish(job, sources, "FAILED", "원문 발췌에 실패했습니다. 다시 시도하거나 원문을 직접 확인해 주세요."); }
    }
    private void retryDetail(List<Source> sources, int index, Source source) {
        try {
            String html = http.html(source.url());
            ExtractionText.html(html);
            var detail = OfficialDetailParser.parse(html, source.url());
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(html.getBytes(StandardCharsets.UTF_8)));
            sources.set(index, extractedSource(source.label(), source.url(), hash, detail.parts()));
            for (var link : detail.attachments()) {
                if (sources.stream().noneMatch(s -> s.url().equals(link.url())))
                    sources.add(new Source(link.label(), link.url(), null, "DEFERRED", "첨부 발췌 대기", List.of()));
            }
        } catch (ExtractionText.Invalid invalid) {
            sources.set(index, new Source(source.label(), source.url(), null, "FAILED", ExtractionText.FAILURE_MESSAGE, List.of()));
        } catch (Exception failure) {
            sources.set(index, new Source(source.label(), source.url(), null, "FAILED", "본문 재발췌 실패", List.of()));
        }
    }
    public Source extract(Link link) {
        try {
            var file = http.download(link.url());
            var parts = parser.parse(file.bytes(), file.fingerprint().mediaType());
            return extractedSource(link.label(), link.url(), file.fingerprint().sha256(), parts);
        } catch (ExtractionText.Invalid invalid) {
            return new Source(link.label(), link.url(), null, "FAILED", ExtractionText.FAILURE_MESSAGE, List.of());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Source(link.label(), link.url(), null, "FAILED", "발췌 실패·지원 범위 확인 필요. 원문에서 직접 확인해 주세요.", List.of());
        }
    }

    /** Reading source text and finding review-category keywords are separate from approving guidance. */
    private Source extractedSource(String label, String url, String hash, List<Part> parts) {
        ExtractionText.parts(parts);
        boolean hasText = parts.stream().anyMatch(part -> !part.text().isBlank());
        var excerpts = ExcerptClassifier.extract(parts, url);
        String message = !hasText ? "원문 텍스트를 확인하지 못했습니다. 이미지·빈 양식 여부를 원문에서 직접 확인해 주세요."
            : OcrProvenance.incomplete(parts) ? "이미지 판독이 일부 완료되지 않았습니다. 읽힌 내용과 누락 위치를 원문에서 확인해 주세요."
            : parts.stream().anyMatch(OcrProvenance::contains) ? "이미지에서 읽은 글자입니다. 숫자·표·도면은 원문 대조가 필요하며 판독만으로 준비 안내가 승인되지 않습니다."
            : excerpts.isEmpty() ? "원문 텍스트를 읽었습니다. 조건·서류·일정 관련 문구는 자동 분류되지 않아 원문 대조가 필요합니다."
            : "자동 발췌 · 원문 대조 필요";
        return new Source(label, url, hash, hasText ? "EXTRACTED" : "EMPTY", message, excerpts, parts);
    }

    @Transactional
    public void adoptAttachments(UUID noticeId, UUID extractionId, int revision) {
        var draft = store.find(extractionId);
        if (!draft.noticeId().equals(noticeId.toString()) || draft.revision() != revision || !Set.of("READY", "PARTIAL").contains(draft.status())) throw ApiException.conflict();
        var current = notices.attachmentSnapshot(noticeId);
        if (current.revision() != revision) throw ApiException.conflict();
        var c = current.content();
        var attachments = new LinkedHashMap<String, HousingModels.Evidence>();
        for (var a : c.attachments()) attachments.put(a.url(), a);
        String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
        for (var source : draft.sources()) if (source.sha256() != null && !source.label().equals("공식 상세 본문"))
            attachments.putIfAbsent(source.url(), new HousingModels.Evidence(source.label(), source.url(), day));
        if (attachments.size() > 50) throw new ApiException(409, "ATTACHMENTS_LIMIT", "공고에는 첨부를 50개까지 등록할 수 있습니다. 기존 첨부 목록을 먼저 정리해 주세요.");
        if (attachments.size() == c.attachments().size()) return;
        notices.updateContent(noticeId, revision, new HousingModels.Content(c.summary(), c.evidence(), List.copyOf(attachments.values()), c.questions(), c.documents(), c.schedules(), c.reviewIssues()));
    }
}
