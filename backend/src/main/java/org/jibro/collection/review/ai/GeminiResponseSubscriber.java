package org.jibro.collection.review.ai;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/**
 * Bounds the provider body during download, including non-success responses.
 */
final class GeminiResponseSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    static final int MAX_BYTES = 512 * 1024;

    static final class TooLarge extends IllegalStateException {
    }

    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (var buffer : buffers) {
            if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                subscription.cancel();
                body.completeExceptionally(new TooLarge());
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
        body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
        body.complete(bytes.toByteArray());
    }
}
