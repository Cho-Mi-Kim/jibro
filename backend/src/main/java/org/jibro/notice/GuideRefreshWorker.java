package org.jibro.notice;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class GuideRefreshWorker {
    private static final Logger LOG = LoggerFactory.getLogger(GuideRefreshWorker.class);
    private final GuideRefreshService service;
    private final boolean enabled;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "official-guide-refresh");
        thread.setDaemon(true);
        return thread;
    });

    public GuideRefreshWorker(GuideRefreshService service, @Value("${app.guide-refresh.enabled:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${app.guide-refresh.initial-delay-ms:45000}", fixedDelayString = "${app.guide-refresh.poll-ms:60000}")
    public void tick() {
        if (!enabled || !busy.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    var result = service.refreshDue(Instant.now());
                    if (result.sourcesChecked() > 0 || result.updated() > 0)
                        LOG.info("Guide refresh: checked={}, failed={}, confirmed={}, updated={}, pending={}", result.sourcesChecked(), result.sourceFailures(), result.confirmed(), result.updated(), result.pending());
                } catch (RuntimeException failed) {
                    LOG.warn("Guide refresh could not finish; retained the last verified catalogue.");
                } finally {
                    busy.set(false);
                }
            });
        } catch (RejectedExecutionException closing) {
            busy.set(false);
        }
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
    }
}
