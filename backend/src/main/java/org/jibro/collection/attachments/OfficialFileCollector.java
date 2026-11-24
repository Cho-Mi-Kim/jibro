package org.jibro.collection.attachments;

import jakarta.annotation.PreDestroy;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jibro.collection.CollectionItemProcessor;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.extraction.OfficialDetailParser;
import org.jibro.notice.ApplicationPeriod;
import org.jibro.notice.OfficialCorrectionLink;
import org.jibro.notice.OfficialFileManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "app.collection.official-files-enabled", matchIfMissing = true)
public class OfficialFileCollector implements CollectionItemProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(OfficialFileCollector.class);
    private final OfficialFileRepository store;
    private final AttachmentFingerprintClient http;
    private final ObjectMapper json;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public OfficialFileCollector(OfficialFileRepository store, AttachmentFingerprintClient http, ObjectMapper json) {
        this.store = store;
        this.http = http;
        this.json = json;
    }

    @Override
    public void process(UUID id, LhNoticeClient.Item item) {
        if (!item.officiallyClosed() && LhNoticeClient.sourceKey(item.url()).equals(item.key()))
            store.enqueue(id, item);
    }

    @Scheduled(initialDelay = 10000, fixedDelay = 10000)
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                for (int i = 0; i < 8 && !Thread.currentThread().isInterrupted(); i++) {
                    var next = store.claim();
                    if (next.isEmpty()) break;
                    refresh(next.get());
                }
            } finally {
                running.set(false);
            }
        });
    }

    void refresh(OfficialFileRepository.Job job) {
        try {
            if (!LhNoticeClient.sourceKey(job.url()).equals(job.sourceKey()))
                throw new IllegalArgumentException("SOURCE_MISMATCH");
            String html = http.html(job.url());
            // Also verifies that the rendered LH form carries the requested notice ID.
            if (OfficialCorrectionLink.explicitlyClosed(job.url(), html)) {
                store.finish(job, null, false);
                return;
            }
            var files = new ArrayList<OfficialFileManifest.File>();
            boolean retry = false;
            for (var link : OfficialDetailParser.attachments(html, job.url())) {
                List<String> members = List.of();
                if (link.label().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    try {
                        members = http.zipMembers(link.url());
                    } catch (InterruptedException interrupted) {
                        throw interrupted;
                    } catch (Exception unreadable) {
                        members = null;
                        retry = true;
                        LOG.warn("ZIP directory deferred for {} ({})", job.id(), unreadable.getClass().getSimpleName());
                    }
                }
                files.add(new OfficialFileManifest.File(link.label(), link.url(), members));
            }
            var manifest = new OfficialFileManifest(LocalDate.now(ApplicationPeriod.SEOUL).toString(), files);
            store.finish(job, json.writeValueAsString(manifest), retry);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            store.finish(job, null, true);
            LOG.warn("Official file check deferred for {} ({})", job.id(), failure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }
}
