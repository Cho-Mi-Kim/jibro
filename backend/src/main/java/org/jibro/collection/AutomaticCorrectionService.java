package org.jibro.collection;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import org.jibro.notice.CorrectionCheckRepository;
import org.jibro.notice.AutomaticCorrectionLinkService;
import org.jibro.notice.OfficialCorrectionLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Collection only enqueues; one leased official check runs per tick without delaying the list API.
 */
@Service
public class AutomaticCorrectionService implements CollectionItemProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(AutomaticCorrectionService.class);
    private final CorrectionCheckRepository queue;
    private final AutomaticCorrectionLinkService links;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "official-correction-check");
        thread.setDaemon(true);
        return thread;
    });

    public AutomaticCorrectionService(CorrectionCheckRepository queue, AutomaticCorrectionLinkService links,
                                      @Value("${app.collection.automatic-correction-enabled:true}") boolean enabled) {
        this.queue = queue;
        this.links = links;
        this.enabled = enabled;
    }

    @Override
    public void process(UUID noticeId, LhNoticeClient.Item item) {
        if (!enabled) return;
        try {
            OfficialCorrectionLink.identity(item.url());
        } catch (IllegalArgumentException unsupported) {
            return;
        }
        if (!LhNoticeClient.sourceKey(item.url()).equals(item.key()))
            throw new IllegalArgumentException("CORRECTION_SOURCE_MISMATCH");
        queue.enqueue(noticeId, item);
    }

    @Scheduled(initialDelayString = "${app.collection.correction-initial-delay-ms:60000}",
            fixedDelayString = "${app.collection.correction-poll-ms:30000}")
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            executor.execute(this::processNext);
        } catch (RejectedExecutionException closing) {
            running.set(false);
        }
    }

    public boolean runNext() {
        if (!running.compareAndSet(false, true)) return false;
        return processNext();
    }

    private boolean processNext() {
        CorrectionCheckRepository.Job job = null;
        try {
            var next = queue.claim();
            if (next.isEmpty()) return false;
            job = next.get();
            queue.finish(job, links.reconcile(job));
            return true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            if (job != null) {
                queue.finish(job, ex instanceof IllegalArgumentException ? "OFFICIAL_RELATION_UNVERIFIED" : "RETRY_REQUIRED");
                LOG.warn("Correction check deferred for {} ({})", job.noticeId(), ex.getClass().getSimpleName());
            }
            return false;
        } finally {
            running.set(false);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
