package org.jibro.collection.regions;

import java.util.*;
import java.util.stream.Collectors;
import org.jibro.collection.extraction.OfficialDetailParser;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.jibro.collection.CollectionItemProcessor;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="app.collection.supply-regions-enabled", matchIfMissing=true)
public class SupplyRegionCollector implements CollectionItemProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(SupplyRegionCollector.class);
    private final SupplyRegionRepository store;
    private final AttachmentFingerprintClient http;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();

    public SupplyRegionCollector(SupplyRegionRepository store, AttachmentFingerprintClient http) {
        this.store = store;
        this.http = http;
    }

    @Override
    public void process(UUID id, LhNoticeClient.Item item) {
        if (LhNoticeClient.sourceKey(item.url()).equals(item.key())) store.enqueue(id, item);
    }

    @Scheduled(initialDelay=5000, fixedDelay=10000)
    public void tick() {
        if (!running.compareAndSet(false,true)) return;
        worker.execute(() -> {
            try {
                for (int i=0; i<8 && !Thread.currentThread().isInterrupted(); i++) {
                    var next = store.claim();
                    if (next.isEmpty()) break;
                    refresh(next.get());
                }
            } finally {
                running.set(false);
            }
        });
    }

    void refresh(SupplyRegionRepository.Job job) {
        try {
            if (!LhNoticeClient.sourceKey(job.url()).equals(job.sourceKey())) throw new IllegalArgumentException("SOURCE_MISMATCH");
            String html = http.html(job.url());
            var regions = OfficialSupplyRegions.read(html, job.url());
            if (regions.isEmpty() || (job.listingRegion().endsWith(" 외") && !regions.containsKey("전국"))) {
                var fromDocument = attachmentRegions(job, html);
                if (fromDocument.keySet().containsAll(regions.keySet())) fromDocument.forEach(regions::putIfAbsent);
            }
            store.finish(job, regions);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            store.finish(job, null);
            LOG.warn("Supply region check deferred for {} ({})", job.id(), ex.getClass().getSimpleName());
        }
    }

    private Map<String,OfficialSupplyRegions.Proof> attachmentRegions(SupplyRegionRepository.Job job, String html) throws Exception {
        var links = OfficialDetailParser.attachments(html, job.url()).stream()
            .filter(file -> file.label().contains("공고문") && !file.label().matches(".*(?:예시|견본|정정전|변경전).*"))
            .map(file -> file.url()).collect(Collectors.toSet());
        Map<String,OfficialSupplyRegions.Proof> confirmed = Map.of();
        for (var file : store.extractedAttachments(job)) {
            if (!links.contains(file.url()) || !"EXTRACTED".equals(file.status()) || file.sha256() == null) continue;
            var regions = OfficialSupplyRegions.attachmentFields(file.url(), file.context());
            if (regions.isEmpty() || !file.sha256().equals(http.fetchForSourceReview(file.url()).sha256())) continue;
            if (!confirmed.isEmpty() && !confirmed.keySet().equals(regions.keySet())) return Map.of();
            confirmed = regions;
        }
        return confirmed;
    }

    @PreDestroy
    void close() { worker.shutdownNow(); }
}
