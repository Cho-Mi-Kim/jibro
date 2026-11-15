package org.jibro.collection.attachments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class AttachmentFingerprintClientTest {
    private static final String URL = "https://apply.lh.or.kr/download.pdf?id=1";
    private static final byte[] PDF = "%PDF-1.7\nhello".getBytes(StandardCharsets.US_ASCII);
    private final HttpClient http = mock(HttpClient.class);
    private final AttachmentFingerprintClient client = new AttachmentFingerprintClient("apply.lh.or.kr,www.lh.or.kr",
        http, host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})},
        1024, Duration.ofSeconds(1));

    @Test
    void acceptsOnlyExactOfficialHttpsHostsAndPreservesEncodedQuery() throws Exception {
        assertEquals("https://apply.lh.or.kr/download?name=a%20b&token=x%2By",
            client.requestUri("https://apply.lh.or.kr/download?name=a%20b&token=x%2By#page=4").toString());
        for (String unsafe : List.of("http://apply.lh.or.kr/file", "https://apply.lh.or.kr.evil.invalid/file",
            "https://user@apply.lh.or.kr/file", "https://apply.lh.or.kr:8443/file", "file:///tmp/a.pdf",
            "https://127.0.0.1/file", "https://evil.invalid/file")) {
            assertThrows(IOException.class, () -> client.fetch(unsafe), unsafe);
        }
        verifyNoInteractions(http);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reachabilityProbeUsesHeadWithoutCredentialsAndPreservesRedirectStatus() throws Exception {
        HttpResponse<Integer> response = mock(HttpResponse.class);
        when(response.body()).thenReturn(302);
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            sent.set(call.getArgument(0));
            return CompletableFuture.completedFuture(response);
        });
        assertEquals(302, client.probe(URL + "#page=2"));
        assertEquals("HEAD", sent.get().method());
        assertEquals(URL, sent.get().uri().toString());
        assertTrue(sent.get().headers().firstValue("Cookie").isEmpty());
        assertTrue(sent.get().headers().firstValue("Authorization").isEmpty());
        verify(http, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void rejectsNonPublicDnsAnswersBeforeOpeningConnection() throws Exception {
        for (String ip : List.of("127.0.0.1", "10.2.3.4", "172.16.1.1", "192.168.0.1", "169.254.169.254",
            "100.64.1.1", "198.18.0.1", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fc00::1",
            "2001:db8::1", "2002:7f00:1::")) {
            assertFalse(AttachmentFingerprintClient.publicAddress(InetAddress.getByName(ip)), ip);
        }
        var blocked = new AttachmentFingerprintClient("apply.lh.or.kr", http,
            host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}, 1024, Duration.ofSeconds(1));
        assertEquals("ATTACHMENT_ADDRESS_REJECTED", assertThrows(IOException.class, () -> blocked.fetch(URL)).getMessage());
        verifyNoInteractions(http);
    }

    @Test
    void hashesAllBytesAcrossChunkBoundariesAndSupportsOfficeContainers() throws Exception {
        var subscriber = client.bodyHandler().apply(info(200, Map.of("Content-Type", List.of("application/pdf"))));
        Subscription subscription = mock(Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(Arrays.copyOfRange(PDF, 0, 2))));
        subscriber.onNext(List.of(ByteBuffer.wrap(Arrays.copyOfRange(PDF, 2, PDF.length))));
        subscriber.onComplete();
        var result = subscriber.getBody().toCompletableFuture().get();
        assertEquals("application/pdf", result.mediaType());
        assertEquals(PDF.length, result.bytes());
        assertEquals("79d6a59e1f914ed60b8216fcec3f3732c959e1636527e68c415bb52c1ef4ee79", result.sha256());
        verify(subscription, never()).cancel();

        assertEquals("application/zip", read(new byte[]{80, 75, 3, 4, 1, 2, 3, 4},
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document").mediaType());
        assertEquals("application/x-ole-storage", read(new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11,
            (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1, 1}, "application/octet-stream").mediaType());
    }

    @Test
    void rejectsRedirectsFailuresCompressedBodiesAndOversizedDeclaredLengthsWithoutReading() {
        for (var response : List.of(info(302, Map.of("Location", List.of("https://apply.lh.or.kr/other.pdf"))),
            info(304, Map.of()), info(404, Map.of()), info(200, Map.of("Content-Length", List.of("1025"))),
            info(200, Map.of("Content-Length", List.of("0"))),
            info(200, Map.of("Content-Type", List.of("text/html"))),
            info(200, Map.of("Content-Encoding", List.of("gzip"))))) {
            var subscriber = client.bodyHandler().apply(response);
            Subscription subscription = mock(Subscription.class);
            subscriber.onSubscribe(subscription);
            assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
            verify(subscription).cancel();
            verify(subscription, never()).request(anyLong());
        }
    }

    @Test
    void cancelsWhenActualBytesExceedLimitEvenWithoutContentLength() {
        var subscriber = client.bodyHandler().apply(info(200, Map.of()));
        Subscription subscription = mock(Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(PDF)));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1024])));
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
        verify(subscription).cancel();
    }

    @Test
    void rejectsEmptyBodyAndHtmlDisguisedAsPdf() {
        assertThrows(ExecutionException.class, () -> read(new byte[0], "application/pdf"));
        assertThrows(ExecutionException.class, () -> read("<!doctype html><h1>Error</h1>".getBytes(StandardCharsets.UTF_8), "application/pdf"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void boundsCompleteResponseTimeAndCancelsAStalledBody() {
        CompletableFuture<HttpResponse<AttachmentFingerprintClient.Fingerprint>> pending = new CompletableFuture<>();
        AtomicReference<HttpRequest> requested = new AtomicReference<>();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            requested.set(call.getArgument(0));
            return pending;
        });
        var shortTimeout = new AttachmentFingerprintClient("apply.lh.or.kr", http,
            host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")}, 1024, Duration.ofMillis(100));
        assertTimeout(Duration.ofSeconds(2), () -> assertThrows(TimeoutException.class, () -> shortTimeout.fetch(URL)));
        assertTrue(pending.isCancelled());
        var request = requested.get();
        assertEquals("GET", request.method());
        assertEquals("identity", request.headers().firstValue("Accept-Encoding").orElseThrow());
        assertTrue(request.headers().firstValue("Cookie").isEmpty());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
        assertTrue(request.headers().firstValue("If-None-Match").isEmpty());
        assertTrue(request.headers().firstValue("If-Modified-Since").isEmpty());
    }

    @Test
    void boundsDnsWaitAndDoesNotSendAfterTimeout() {
        CountDownLatch release = new CountDownLatch(1);
        var delayedDns = new AttachmentFingerprintClient("apply.lh.or.kr", http, host -> {
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException();
            }
            return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        }, 1024, Duration.ofMillis(100));
        try {
            assertTimeout(Duration.ofSeconds(2), () -> assertThrows(TimeoutException.class, () -> delayedDns.fetch(URL)));
            verifyNoInteractions(http);
        } finally {
            release.countDown();
        }
    }

    private AttachmentFingerprintClient.Fingerprint read(byte[] bytes, String type) throws Exception {
        var subscriber = client.bodyHandler().apply(info(200, Map.of("Content-Type", List.of(type))));
        subscriber.onSubscribe(mock(Subscription.class));
        subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
        subscriber.onComplete();
        return subscriber.getBody().toCompletableFuture().get();
    }

    @Test
    void downloadReturnsTheExactValidatedBytesAndRejectsHtmlImpersonatingADocument() throws Exception {
        respond(200, "application/pdf", PDF);
        var download = client.download(URL);
        assertArrayEquals(PDF, download.bytes());
        assertEquals("79d6a59e1f914ed60b8216fcec3f3732c959e1636527e68c415bb52c1ef4ee79", download.fingerprint().sha256());
        respond(200, "application/pdf", "<html>Error</html>".getBytes(StandardCharsets.UTF_8));
        assertThrows(ExecutionException.class, () -> client.download(URL));
        respond(302, "application/pdf", PDF);
        assertThrows(ExecutionException.class, () -> client.download(URL));
    }

    @Test
    void htmlTransportRequiresHtmlAndEnforcesItsOwnBodyLimit() throws Exception {
        String html = "<main>공식 공고 본문</main>";
        respond(200, "text/html; charset=UTF-8", html.getBytes(StandardCharsets.UTF_8));
        assertEquals(html, client.html(URL));
        respond(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(ExecutionException.class, () -> client.html(URL));
        respond(200, "text/html", new byte[2 * 1024 * 1024 + 1]);
        assertThrows(ExecutionException.class, () -> client.html(URL));
    }

    @Test
    void explicitSourceReviewHtmlAttachmentPreservesRawBytesAndMagicChecksElsewhere() throws Exception {
        String file = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=24662701";
        byte[] html = "<html><body>공식 접수 결과</body></html>".getBytes(StandardCharsets.UTF_8);
        respond(200, "application/octet-stream;charset=utf-8", html);
        var result = client.htmlAttachmentForSourceReview(file);
        assertArrayEquals(html, result.bytes());
        assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(html)), result.fingerprint().sha256());
        assertThrows(ExecutionException.class, () -> client.fetchForSourceReview(file));
        respond(200, "text/html", html);
        assertArrayEquals(html, client.htmlAttachmentForSourceReview(file).bytes());
    }

    @Test
    void reviewedDrawingDigestsDoNotEnableImageExtractionOrRelaxHtmlRejection() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        byte[] jpg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3, 4, 5};
        for (byte[] bytes : List.of(png, jpg)) {
            respond(200, "application/octet-stream", bytes);
            var result = client.fetchForSourceReview(URL);
            assertEquals(bytes == png ? "image/png" : "image/jpeg", result.mediaType());
            assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), result.sha256());
            assertThrows(ExecutionException.class, () -> client.fetch(URL));
            assertThrows(ExecutionException.class, () -> client.download(URL));
        }
        respond(200, "image/png", "<html>error page</html>".getBytes(StandardCharsets.UTF_8));
        assertThrows(ExecutionException.class, () -> client.fetchForSourceReview(URL));
        respond(302, "image/png", png);
        assertThrows(ExecutionException.class, () -> client.fetchForSourceReview(URL));
    }

    @Test
    void explicitHtmlAttachmentStillRejectsRedirectsErrorsOversizeAndOtherPaths() throws Exception {
        String file = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=24662701";
        for (var url : List.of(URL, file + "&url=https://example.com", file.replace("apply.lh.or.kr", "www.lh.or.kr")))
            assertThrows(IOException.class, () -> client.htmlAttachmentForSourceReview(url));
        verifyNoInteractions(http);
        respond(302, "text/html", "<html>redirect</html>".getBytes(StandardCharsets.UTF_8));
        assertThrows(ExecutionException.class, () -> client.htmlAttachmentForSourceReview(file));
        respond(500, "application/octet-stream", "<html>error</html>".getBytes(StandardCharsets.UTF_8));
        assertThrows(ExecutionException.class, () -> client.htmlAttachmentForSourceReview(file));
        respond(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(ExecutionException.class, () -> client.htmlAttachmentForSourceReview(file));
        respond(200, "application/octet-stream", new byte[2 * 1024 * 1024 + 1]);
        assertThrows(ExecutionException.class, () -> client.htmlAttachmentForSourceReview(file));
    }

    @SuppressWarnings("unchecked")
    @Test
    void sourceReviewStreamsLargeArchivesButKeepsTheReviewAndNormalDownloadBounds() throws Exception {
        byte[] chunk = new byte[1024 * 1024];
        System.arraycopy("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), 0, chunk, 0, 8);
        for (int count : List.of(120, 151)) {
            when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
                HttpResponse.BodyHandler<?> handler = call.getArgument(1);
                var subscriber = handler.apply(info(200, Map.of("Content-Type", List.of("application/octet-stream"))));
                subscriber.onSubscribe(mock(Subscription.class));
                for (int i = 0; i < count; i++) subscriber.onNext(List.of(ByteBuffer.wrap(chunk)));
                subscriber.onComplete();
                return subscriber.getBody().toCompletableFuture().thenApply(body -> {
                    HttpResponse<Object> response = mock(HttpResponse.class);
                    when(response.body()).thenReturn(body); return response;
                });
            });
            if (count == 120) {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                for (int i = 0; i < count; i++) digest.update(chunk);
                var result = client.fetchForSourceReview(URL);
                assertEquals((long) count * chunk.length, result.bytes());
                assertEquals(HexFormat.of().formatHex(digest.digest()), result.sha256());
                assertThrows(ExecutionException.class, () -> client.download(URL));
            } else assertThrows(ExecutionException.class, () -> client.fetchForSourceReview(URL));
        }
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String type, byte[] bytes) {
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpResponse.BodyHandler<?> handler = call.getArgument(1);
            var subscriber = handler.apply(info(status, Map.of("Content-Type", List.of(type))));
            subscriber.onSubscribe(mock(Subscription.class));
            subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
            subscriber.onComplete();
            return subscriber.getBody().toCompletableFuture().thenApply(body -> {
                HttpResponse<Object> response = mock(HttpResponse.class);
                when(response.body()).thenReturn(body);
                return response;
            });
        });
    }

    private HttpResponse.ResponseInfo info(int status, Map<String, List<String>> headers) {
        return new HttpResponse.ResponseInfo() {
            public int statusCode() {
                return status;
            }

            public HttpHeaders headers() {
                return HttpHeaders.of(headers, (key, value) -> true);
            }

            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
