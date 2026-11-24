package org.jibro.notice;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.attachments.AttachmentFingerprintClient.Page;
import org.jibro.collection.extraction.BoundedDocumentParser;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Official source text is untrusted evidence; availability never means its rules were approved.
 */
@Component
public class GuideSourceClient {
    public enum Status {AVAILABLE, UNREACHABLE, UNREADABLE}

    public record Snapshot(String requestedUrl, String finalUrl, String title, String text, String contentHash,
                           Instant checkedAt, List<String> redirects) {
        public Snapshot {
            redirects = List.copyOf(redirects);
        }
    }

    public record Result(Status status, Snapshot snapshot, String code) {
    }

    @FunctionalInterface
    interface Fetcher {
        Page fetch(String url) throws Exception;
    }

    @FunctionalInterface
    interface PdfReader {
        List<Part> read(byte[] bytes) throws Exception;
    }

    private static final int MAX_TEXT = 160_000;
    private final Fetcher fetcher;
    private final PdfReader pdf;
    private final GuideSourceContent content;
    private final Clock clock;

    @Autowired
    public GuideSourceClient(BoundedDocumentParser parser, ObjectMapper json) {
        this(transport(), bytes -> parser.parseNative(bytes, "application/pdf"), json, Clock.systemUTC());
    }

    GuideSourceClient(Fetcher fetcher, PdfReader pdf, ObjectMapper json, Clock clock) {
        this.fetcher = fetcher;
        this.pdf = pdf;
        this.content = new GuideSourceContent(json);
        this.clock = clock;
    }

    private static Fetcher transport() {
        Map<String, AttachmentFingerprintClient> clients = new ConcurrentHashMap<>();
        return url -> {
            URI uri = GuideSourcePolicy.requireOfficialUri(url);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!clients.containsKey(host) && clients.size() >= 32)
                throw new Failure(Status.UNREACHABLE, "GUIDE_SOURCE_HOST_LIMIT");
            return clients.computeIfAbsent(host, AttachmentFingerprintClient::new).page(url);
        };
    }

    public Result fetch(String url) {
        try {
            URI requested = GuideSourcePolicy.requireOfficialUri(url);
            Budget budget = new Budget();
            List<String> redirects = new ArrayList<>();
            Fetched source = fetchPage(requested, budget, redirects);
            String title;
            String text;
            if (isPdf(source.page().bytes())) {
                title = "공식 PDF 자료";
                text = pdf(source.page().bytes(), budget);
            } else {
                String media = source.page().contentType().toLowerCase(Locale.ROOT);
                if (!media.contains("html")) throw new Failure(Status.UNREADABLE, "GUIDE_SOURCE_MEDIA_REJECTED");
                GuideSourceContent.Content extracted;
                try {
                    extracted = content.extract(source.uri(), source.page().bytes(), source.page().contentType());
                } catch (Exception invalid) {
                    throw new Failure(Status.UNREADABLE, "GUIDE_SOURCE_CONTENT_UNREADABLE");
                }
                title = extracted.title();
                StringBuilder combined = new StringBuilder(extracted.text());
                for (var attachment : extracted.attachments()) {
                    // The source's known LH file function is parsed; arbitrary document links are never followed.
                    Fetched document = fetchPage(GuideSourcePolicy.requireOfficialUri(attachment.url()), budget, new ArrayList<>());
                    if (!isPdf(document.page().bytes()))
                        throw new Failure(Status.UNREADABLE, "GUIDE_SOURCE_DOCUMENT_UNREADABLE");
                    combined.append("\n공식 첨부: ").append(attachment.title()).append('\n')
                        .append(pdf(document.page().bytes(), budget));
                }
                text = combined.toString();
            }
            text = GuideSourceContent.normalize(text);
            if (text.length() < 40 || text.length() > MAX_TEXT || text.indexOf('\uFFFD') >= 0)
                throw new Failure(Status.UNREADABLE, "GUIDE_SOURCE_TEXT_REJECTED");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            return new Result(Status.AVAILABLE, new Snapshot(requested.toString(), source.uri().toString(), title, text,
                hash, clock.instant(), redirects), "OK");
        } catch (Failure failure) {
            return new Result(failure.status, null, failure.code);
        } catch (IllegalArgumentException rejected) {
            return new Result(Status.UNREADABLE, null, "GUIDE_SOURCE_URL_REJECTED");
        } catch (Exception failed) {
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(Status.UNREACHABLE, null, "GUIDE_SOURCE_FETCH_FAILED");
        }
    }

    private Fetched fetchPage(URI requested, Budget budget, List<String> redirects) throws Exception {
        URI current = requested;
        Set<URI> visited = new HashSet<>();
        for (int count = 0; count <= 3; count++) {
            budget.check();
            if (!visited.add(current)) throw new Failure(Status.UNREADABLE, "GUIDE_SOURCE_REDIRECT_LOOP");
            Page page = fetcher.fetch(current.toString());
            budget.received(page.bytes());
            if (page.status() == 200) return new Fetched(current, page);
            if (Set.of(301, 302, 303, 307, 308).contains(page.status())) {
                URI next;
                try {
                    if (page.location().isBlank()) throw new IllegalArgumentException();
                    next = GuideSourcePolicy.requireOfficialUri(current.resolve(page.location()).toString());
                    if (!GuideSourcePolicy.sameAgency(requested, next)
                        || !GuideSourcePolicy.preservesResourceIdentity(requested, next))
                        throw new IllegalArgumentException();
                } catch (IllegalArgumentException rejected) {
                    throw new Failure(Status.UNREADABLE, "GUIDE_SOURCE_REDIRECT_REJECTED");
                }
                if (count == 3) throw new Failure(Status.UNREADABLE, "GUIDE_SOURCE_REDIRECT_LIMIT");
                redirects.add(next.toString());
                current = next;
            } else throw new Failure(Status.UNREACHABLE, "GUIDE_SOURCE_HTTP_STATUS");
        }
        throw new Failure(Status.UNREADABLE, "GUIDE_SOURCE_REDIRECT_LIMIT");
    }

    private String pdf(byte[] bytes, Budget budget) throws Exception {
        budget.check();
        try {
            List<Part> parts = pdf.read(bytes);
            if (parts.isEmpty() || parts.stream().anyMatch(part -> part.text() == null || part.text().isBlank()))
                throw new IllegalArgumentException();
            String text = parts.stream().map(part -> part.location() + "\n" + part.text()).reduce((a, b) -> a + "\n" + b).orElse("");
            if (text.length() > MAX_TEXT) throw new IllegalArgumentException();
            return text;
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception invalid) {
            throw new Failure(Status.UNREADABLE, "GUIDE_SOURCE_DOCUMENT_UNREADABLE");
        }
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    private record Fetched(URI uri, Page page) {
    }

    private static final class Budget {
        private final long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        private long remainingBytes = 12L * 1024 * 1024;
        private int requests;

        void check() throws Failure {
            if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted())
                throw new Failure(Status.UNREACHABLE, "GUIDE_SOURCE_TIMEOUT");
        }

        void received(byte[] bytes) throws Failure {
            check();
            remainingBytes -= bytes.length;
            if (bytes.length > 8L * 1024 * 1024 || remainingBytes < 0 || ++requests > 8)
                throw new Failure(Status.UNREADABLE, "GUIDE_SOURCE_SIZE_REJECTED");
        }
    }

    private static final class Failure extends Exception {
        final Status status;
        final String code;

        Failure(Status status, String code) {
            this.status = status;
            this.code = code;
        }
    }
}
