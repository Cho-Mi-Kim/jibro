package org.jibro.collection;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class CollectionScheduler {
    private final CollectionService service;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "official-collection");
        thread.setDaemon(true);
        return thread;
    });

    public CollectionScheduler(CollectionService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 0 0,6,9-18 * * *", zone = "Asia/Seoul")
    public void scheduledHour() {
        tick();
    }

    @Scheduled(initialDelayString = "${app.collection.initial-delay-ms:15000}", fixedDelayString = "${app.collection.tick-ms:30000}")
    public void tick() {
        // Persist each hour even while the previous worker is still collecting or extracting.
        service.enqueueLatest(Instant.now());
        if (!busy.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    service.processDue();
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
