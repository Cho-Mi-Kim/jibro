package org.jibro.collection.review;

import org.jibro.notice.ApplicationPeriodReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApplicationPeriodReviewWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationPeriodReviewWorker.class);
    private final ApplicationPeriodReviewService review;
    private final boolean enabled;

    public ApplicationPeriodReviewWorker(ApplicationPeriodReviewService review,
                                         @Value("${app.collection.automatic-review-enabled:true}") boolean enabled) {
        this.review = review;
        this.enabled = enabled;
    }

    @Scheduled(initialDelay = 18000, fixedDelayString = "${app.collection.automatic-review-poll-ms:30000}")
    public void tick() {
        if (!enabled) return;
        for (var id : review.pending()) {
            try {
                review.review(id);
            } catch (RuntimeException failure) {
                LOG.warn("Application period review deferred: {}", id);
            }
        }
    }
}
