package org.jibro.collection.attachments;

import java.time.Duration;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.*;

import org.springframework.stereotype.Service;

@Service
public class AttachmentCheckService {
    private final AttachmentCheckStore store;
    private final AttachmentFingerprintClient client;

    public AttachmentCheckService(AttachmentCheckStore store, AttachmentFingerprintClient client) {
        this.store = store;
        this.client = client;
    }

    /**
     * Shared cap across a collection run; a single administrator check gets the same bounded budget.
     */
    public static final class Budget {
        private final long started = System.nanoTime();
        private int requests;

        boolean take() {
            if (requests >= 10 || System.nanoTime() - started >= Duration.ofSeconds(45).toNanos()) return false;
            requests++;
            return true;
        }
    }

    public AttachmentCheckStore.Run check(UUID noticeId, String actor) {
        return check(noticeId, actor, new Budget());
    }

    public AttachmentCheckStore.Run check(UUID noticeId, String actor, Budget budget) {
        var scan = store.begin(noticeId, actor);
        List<AttachmentCheckStore.Download> downloads = new ArrayList<>();
        for (var target : scan.targets()) {
            if (!budget.take() || Thread.currentThread().isInterrupted()) {
                downloads.add(new AttachmentCheckStore.Download(target, null, "DEFERRED"));
                continue;
            }
            try {
                downloads.add(new AttachmentCheckStore.Download(target, client.fetch(target.url()), null));
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                downloads.add(new AttachmentCheckStore.Download(target, null, failure(ex)));
            }
        }
        return store.finish(scan, downloads);
    }

    private String failure(Exception ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException) return "TIMEOUT";
            String code = cause.getMessage();
            if (code != null && Set.of("ATTACHMENT_URL_REJECTED", "ATTACHMENT_ADDRESS_REJECTED", "ATTACHMENT_HTTP_STATUS",
                    "ATTACHMENT_ENCODING_REJECTED", "ATTACHMENT_CONTENT_REJECTED", "ATTACHMENT_SIZE_REJECTED").contains(code))
                return code;
        }
        return "FAILED";
    }
}
