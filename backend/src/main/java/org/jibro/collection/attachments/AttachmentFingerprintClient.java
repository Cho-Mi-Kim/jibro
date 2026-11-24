package org.jibro.collection.attachments;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A single bounded, allowlisted transport for official documents and detail pages.
 */
@Component
public class AttachmentFingerprintClient {
    public record Fingerprint(String sha256, long bytes, String mediaType) {
    }

    public record Download(Fingerprint fingerprint, byte[] bytes) {
    }

    public Download download(String url) throws Exception {
        return request(url, response -> new ContentSubscriber(maxBytes, failure(response, false), false));
    }

    public String html(String url) throws Exception {
        var value = request(url, response -> new ContentSubscriber(2 * 1024 * 1024, failure(response, true), true));
        return new String(value.bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * A bounded page response. Callers explicitly validate each redirect before the next request.
     */
    public record Page(int status, String contentType, String location, byte[] bytes) {
    }

    public Page page(String url) throws Exception {
        final long limit = Math.min(maxBytes, 8L * 1024 * 1024);
        return request(url, response -> {
            String rejection = null;
            if (!response.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity"))
                rejection = "ATTACHMENT_ENCODING_REJECTED";
            try {
                long length = response.headers().firstValueAsLong("Content-Length").orElse(0);
                if (length < 0 || length > limit) rejection = "ATTACHMENT_SIZE_REJECTED";
            } catch (NumberFormatException invalid) {
                rejection = "ATTACHMENT_SIZE_REJECTED";
            }
            return HttpResponse.BodySubscribers.mapping(new ContentSubscriber(limit, rejection, true, true),
                    body -> new Page(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                            response.headers().firstValue("Location").orElse(""), body.bytes()));
        }, false, true);
    }

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    // Native DNS may ignore interruption. Bound both the caller's wait and outstanding DNS work.
    private static final ExecutorService DNS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> {
        Thread thread = new Thread(task, "attachment-dns");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private final Set<String> allowedHosts;
    private final HttpClient http;
    private final Resolver resolver;
    private final long maxBytes;
    private final Duration timeout;

    @Autowired
    public AttachmentFingerprintClient(
            @Value("${app.collection.attachment-hosts:apply.lh.or.kr,www.lh.or.kr}") String hosts) {
        this(hosts, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), InetAddress::getAllByName, MAX_BYTES, TIMEOUT);
    }

    AttachmentFingerprintClient(String hosts, HttpClient http, Resolver resolver, long maxBytes, Duration timeout) {
        this.allowedHosts = Set.copyOf(Arrays.stream(hosts.split(",")).map(String::trim)
                .filter(host -> !host.isEmpty()).map(host -> host.toLowerCase(Locale.ROOT)).toList());
        this.http = http;
        this.resolver = resolver;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
    }

    /**
     * Stream ZIPs to a temporary file so large official bundles never occupy the Java heap.
     */
    List<String> zipMembers(String url) throws Exception {
        var transport = new AttachmentFingerprintClient(String.join(",", allowedHosts), http, resolver,
                150L * 1024 * 1024, Duration.ofSeconds(60));
        Path temp = Files.createTempFile("jibro-form-directory-", ".zip");
        try {
            transport.request(url, response -> new BoundedFileSubscriber(temp, transport.maxBytes, transport.failure(response, false)));
            return ZipFormDirectory.read(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static final class BoundedFileSubscriber implements HttpResponse.BodySubscriber<Path> {
        private final HttpResponse.BodySubscriber<Path> target;
        private final String rejection;
        private long remaining;
        private Subscription subscription;

        BoundedFileSubscriber(Path path, long limit, String rejection) {
            this.target = HttpResponse.BodySubscribers.ofFile(path);
            this.remaining = limit;
            this.rejection = rejection;
        }

        public CompletionStage<Path> getBody() {
            return target.getBody();
        }

        public void onSubscribe(Subscription value) {
            subscription = value;
            target.onSubscribe(value);
            if (rejection != null) reject(rejection);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) remaining -= buffer.remaining();
            if (remaining < 0) reject("ATTACHMENT_SIZE_REJECTED");
            else target.onNext(buffers);
        }

        private void reject(String reason) {
            subscription.cancel();
            target.onError(new IOException(reason));
        }

        public void onComplete() {
            target.onComplete();
        }

        public void onError(Throwable error) {
            target.onError(error);
        }
    }

    public Fingerprint fetch(String url) throws Exception {
        return request(url, bodyHandler());
    }

    /**
     * Explicit source review only: streams a large official archive into a digest, never into memory.
     */
    public Fingerprint fetchForSourceReview(String url) throws Exception {
        var review = new AttachmentFingerprintClient(String.join(",", allowedHosts), http, resolver,
                150L * 1024 * 1024, Duration.ofSeconds(60));
        return review.request(url, response -> new HashSubscriber(review.maxBytes, review.failure(response, false), true));
    }

    /**
     * Explicitly reviewed LH HTML attachment only; collection's PDF/ZIP/OLE magic checks are unchanged.
     */
    public Download htmlAttachmentForSourceReview(String url) throws Exception {
        URI uri = requestUri(url);
        if (!"apply.lh.or.kr".equals(uri.getHost()) || !"/lhapply/lhFile.do".equals(uri.getPath())
                || uri.getRawQuery() == null || !uri.getRawQuery().matches("fileid=[0-9]{1,20}"))
            throw new IOException("ATTACHMENT_URL_REJECTED");
        Download result = request(url, response -> {
            String rejection = failure(response, true);
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
            if ("DETAIL_CONTENT_REJECTED".equals(rejection) && "application/octet-stream".equalsIgnoreCase(type))
                rejection = null;
            return new ContentSubscriber(2 * 1024 * 1024, rejection, true);
        });
        var digest = MessageDigest.getInstance("SHA-256").digest(result.bytes());
        return new Download(new Fingerprint(HexFormat.of().formatHex(digest), result.bytes().length, "text/html"), result.bytes());
    }

    /**
     * Tests reachability without downloading an attachment or following a redirect.
     */
    public int probe(String url) throws Exception {
        return request(url, response -> HttpResponse.BodySubscribers.replacing(response.statusCode()), true);
    }

    private <T> T request(String url, HttpResponse.BodyHandler<T> handler) throws Exception {
        return request(url, handler, false);
    }

    private <T> T request(String url, HttpResponse.BodyHandler<T> handler, boolean head) throws Exception {
        return request(url, handler, head, false);
    }

    private <T> T request(String url, HttpResponse.BodyHandler<T> handler, boolean head, boolean guide) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        URI uri = requestUri(url);
        Future<InetAddress[]> lookup;
        try {
            lookup = DNS.submit(() -> resolver.resolve(uri.getHost()));
        } catch (RejectedExecutionException ex) {
            throw new IOException("ATTACHMENT_DNS_BUSY");
        }
        try {
            InetAddress[] addresses = lookup.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !publicAddress(address)))
                throw new IOException("ATTACHMENT_ADDRESS_REJECTED");
        } finally {
            lookup.cancel(true);
        }

        var builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofNanos(remaining(deadline)))
                .header("Accept", "text/html, application/pdf, application/octet-stream, application/zip")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", guide || "www.kohom.or.kr".equals(uri.getHost())
                        ? "Mozilla/5.0 (compatible; JibroNoticeReview/1.0)" : "Jibro-Official-Attachment-Check/1.0")
                .method(head ? "HEAD" : "GET", HttpRequest.BodyPublishers.noBody());
        // Some official portals reject GET requests carrying Content-Length: 0 as malformed.
        if (guide && !head) builder.GET();
        // Namwon rejects otherwise valid public board and form requests carrying Cache-Control.
        if (!"www.namwon.go.kr".equals(uri.getHost())) builder.header("Cache-Control", "no-cache");
        HttpRequest request = builder.build();
        CompletableFuture<HttpResponse<T>> response = http.sendAsync(request, handler);
        try {
            // The subscriber only completes after all bytes arrive, including a stalled response body.
            return response.get(remaining(deadline), TimeUnit.NANOSECONDS).body();
        } finally {
            if (!response.isDone()) response.cancel(true);
        }
    }

    URI requestUri(String value) throws IOException {
        try {
            if (value == null || value.length() > 2000) throw new IllegalArgumentException();
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new IllegalArgumentException();
            // PDF page fragments identify a view, not different file bytes. Preserve signed query encoding.
            int fragment = value.indexOf('#');
            return URI.create(fragment < 0 ? value : value.substring(0, fragment));
        } catch (IllegalArgumentException ex) {
            throw new IOException("ATTACHMENT_URL_REJECTED");
        }
    }

    private long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("ATTACHMENT_TIMEOUT");
        return remaining;
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int first = b[0] & 255, second = b[1] & 255;
        if (b.length == 4) {
            return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 192 && second == 0)
                    && !(first == 198 && second == 51 && (b[2] & 255) == 100)
                    && !(first == 203 && second == 0 && (b[2] & 255) == 113);
        }
        // Only global-unicast IPv6; exclude documentation, Teredo, and 6to4 transition ranges.
        return (first & 0xe0) == 0x20 && !(first == 0x20 && second == 0x02)
                && !(first == 0x20 && second == 0x01 && ((b[2] == 0 && b[3] == 0)
                || ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)));
    }

    HttpResponse.BodyHandler<Fingerprint> bodyHandler() {
        return response -> new HashSubscriber(maxBytes, failure(response, false));
    }

    private String failure(HttpResponse.ResponseInfo response, boolean html) {
        String type = response.headers().firstValue("Content-Type").orElse("")
                .toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        String failure = null;
        if (response.statusCode() != 200) failure = "ATTACHMENT_HTTP_STATUS";
        else if (!"identity".equalsIgnoreCase(encoding)) failure = "ATTACHMENT_ENCODING_REJECTED";
        else if (!html && (type.startsWith("text/") || type.contains("html") || type.contains("json")
                || type.equals("application/xml") || type.endsWith("+xml")))
            failure = "ATTACHMENT_CONTENT_REJECTED";
        else if (html && !type.contains("html")) failure = "DETAIL_CONTENT_REJECTED";
        try {
            Optional<String> length = response.headers().firstValue("Content-Length");
            if (length.isPresent() && (Long.parseLong(length.get()) <= 0 || Long.parseLong(length.get()) > maxBytes))
                failure = "ATTACHMENT_SIZE_REJECTED";
        } catch (NumberFormatException ex) {
            failure = "ATTACHMENT_SIZE_REJECTED";
        }
        return failure;
    }

    private static final class ContentSubscriber implements HttpResponse.BodySubscriber<Download> {
        private final CompletableFuture<Download> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final HashSubscriber validator;
        private final long limit;
        private final boolean html;
        private final boolean allowEmpty;
        private final String failure;
        private Subscription subscription;

        ContentSubscriber(long limit, String failure, boolean html) {
            this(limit, failure, html, false);
        }

        ContentSubscriber(long limit, String failure, boolean html, boolean allowEmpty) {
            this.allowEmpty = allowEmpty;
            this.limit = limit;
            this.failure = failure;
            this.html = html;
            validator = new HashSubscriber(limit, failure);
        }

        public CompletionStage<Download> getBody() {
            return result;
        }

        public void onSubscribe(Subscription s) {
            subscription = s;
            if (failure != null) {
                s.cancel();
                result.completeExceptionally(new IOException(failure));
            } else s.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (var b : buffers) {
                if (b.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("ATTACHMENT_SIZE_REJECTED"));
                    return;
                }
                byte[] part = new byte[b.remaining()];
                b.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        public void onComplete() {
            if (result.isDone()) return;
            byte[] data = bytes.toByteArray();
            if (html) {
                if (data.length == 0 && !allowEmpty) {
                    result.completeExceptionally(new IOException("DETAIL_EMPTY"));
                    return;
                }
                result.complete(new Download(null, data));
                return;
            }
            validator.onSubscribe(new Subscription() {
                public void request(long n) {
                }

                public void cancel() {
                }
            });
            validator.onNext(List.of(ByteBuffer.wrap(data)));
            validator.onComplete();
            validator.getBody().whenComplete((hash, error) -> {
                if (error != null) result.completeExceptionally(error);
                else result.complete(new Download(hash, data));
            });
        }
    }

    private static final class HashSubscriber implements HttpResponse.BodySubscriber<Fingerprint> {
        private final CompletableFuture<Fingerprint> result = new CompletableFuture<>();
        private final MessageDigest hash;
        private final long maxBytes;
        private final byte[] prefix = new byte[8];
        private final String initialFailure;
        private final boolean reviewedImages;
        private Subscription subscription;
        private int prefixSize;
        private long bytes;

        private HashSubscriber(long maxBytes, String initialFailure) {
            this(maxBytes, initialFailure, false);
        }

        private HashSubscriber(long maxBytes, String initialFailure, boolean reviewedImages) {
            this.maxBytes = maxBytes;
            this.initialFailure = initialFailure;
            this.reviewedImages = reviewedImages;
            try {
                hash = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public CompletionStage<Fingerprint> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Subscription value) {
            if (subscription != null) {
                value.cancel();
                return;
            }
            subscription = value;
            if (initialFailure != null) fail(initialFailure);
            else subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                int count = buffer.remaining();
                if (count > maxBytes - bytes) {
                    fail("ATTACHMENT_SIZE_REJECTED");
                    return;
                }
                ByteBuffer head = buffer.asReadOnlyBuffer();
                while (head.hasRemaining() && prefixSize < prefix.length) prefix[prefixSize++] = head.get();
                bytes += count;
                hash.update(buffer);
                if (prefixSize == prefix.length && mediaType() == null) {
                    fail("ATTACHMENT_CONTENT_REJECTED");
                    return;
                }
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            if (result.isDone()) return;
            String type = mediaType();
            if (bytes == 0 || type == null) fail("ATTACHMENT_CONTENT_REJECTED");
            else result.complete(new Fingerprint(HexFormat.of().formatHex(hash.digest()), bytes, type));
        }

        private String mediaType() {
            // A digest proves the reviewed bytes are current, not that image text was extracted.
            // Ordinary collection/download keeps the document-only transport contract.
            if (reviewedImages && prefixSize == 8 && Arrays.equals(prefix,
                    new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) return "image/png";
            if (reviewedImages && prefixSize >= 3 && (prefix[0] & 255) == 255
                    && (prefix[1] & 255) == 216 && (prefix[2] & 255) == 255) return "image/jpeg";
            if (prefixSize >= 5 && prefix[0] == '%' && prefix[1] == 'P' && prefix[2] == 'D'
                    && prefix[3] == 'F' && prefix[4] == '-') return "application/pdf";
            if (prefixSize >= 4 && prefix[0] == 'P' && prefix[1] == 'K'
                    && ((prefix[2] == 3 && prefix[3] == 4) || (prefix[2] == 5 && prefix[3] == 6)))
                return "application/zip";
            if (prefixSize == 8 && Arrays.equals(prefix, new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11,
                    (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1}))
                return "application/x-ole-storage";
            return null;
        }

        private void fail(String reason) {
            if (subscription != null) subscription.cancel();
            result.completeExceptionally(new IOException(reason));
        }
    }
}
