package org.jibro.collection;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class LhNoticeClient {
    private static final Set<String> CLOSED_STATUSES = Set.of("접수마감", "접수종료", "공고종료");

    public record Item(String key, String title, String region, String type, String url, LocalDate published,
                       String fingerprint, boolean officiallyClosed, Set<String> previousStatusFingerprints) {
        public Item {
            previousStatusFingerprints = Set.copyOf(previousStatusFingerprints);
        }

        public Item(String key, String title, String region, String type, String url, LocalDate published,
                    String fingerprint, boolean officiallyClosed) {
            this(key, title, region, type, url, published, fingerprint, officiallyClosed, Set.of());
        }

        public boolean onlyClosedSince(String previousFingerprint) {
            return officiallyClosed && previousFingerprint != null && previousStatusFingerprints.contains(previousFingerprint);
        }

        public Item(String key, String title, String region, String type, String url, LocalDate published, String fingerprint) {
            this(key, title, region, type, url, published, fingerprint, false);
        }
    }

    public record Batch(List<Item> items, int total, SourceResponse response) {
        public Batch(List<Item> items, int total) {
            this(items, total, null);
        }
    }

    private final String key;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public LhNoticeClient(@Value("${LH_SERVICE_KEY:}") String key, ObjectMapper json) {
        this(key, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    LhNoticeClient(String key, ObjectMapper json, HttpClient http) {
        this.key = key;
        this.json = json;
        this.http = http;
    }

    public boolean configured() {
        return !key.isBlank();
    }

    public Batch fetch(int page, CollectionQueryPlan.Query query) throws Exception {
        var request = HttpRequest.newBuilder(requestUri(page, query))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build();
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, info -> {
                status.set(info.statusCode());
                return new LimitedJsonSubscriber();
            });
        } catch (java.io.IOException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof LimitedJsonSubscriber.TooLarge)
                    throw new SourceResponse.Rejected(new SourceResponse(status.get(), Instant.now(), "TOO_LARGE", null, null, false), new IllegalArgumentException("SOURCE_RESPONSE_TOO_LARGE"));
            }
            throw failure;
        }
        return accept(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), response.headers().firstValue("Retry-After").orElse(null), Instant.now());
    }

    Batch accept(int status, String body, String retryAfter, Instant received) throws SourceResponse.Rejected {
        var snapshot = new SourceResponseSanitizer(json, key).snapshot(status, body, received);
        try {
            if (status != 200) throw CollectionFailure.http(status, retryAfter, received);
            if (!snapshot.bodyStatus().equals("JSON")) throw new IllegalArgumentException("SOURCE_RESPONSE_INVALID");
            var result = parse(body);
            return new Batch(result.items(), result.total(), snapshot);
        } catch (Exception failure) {
            throw new SourceResponse.Rejected(snapshot, failure);
        }
    }

    URI requestUri(int page, CollectionQueryPlan.Query scope) {
        if (page < 1 || page > CollectionQueryPlan.MAX_PAGE)
            throw new IllegalArgumentException("COLLECTION_PAGE_INVALID");
        // PAN_NT_ST_DT and CLSG_DT are response fields, not the publication-date search range.
        String query = "ServiceKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&PG_SZ=100&PAGE=" + page + "&UPP_AIS_TP_CD=" + scope.categoryCode()
                + "&PAN_ST_DT=" + scope.publishedFrom().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "&PAN_ED_DT=" + scope.publishedThrough().format(DateTimeFormatter.BASIC_ISO_DATE);
        return URI.create("https://apis.data.go.kr/B552555/lhLeaseNoticeInfo1/lhLeaseNoticeInfo1?" + query);
    }

    public Batch parse(String body) {
        JsonNode root = json.readTree(body);
        List<JsonNode> objects = new ArrayList<>();
        walk(root, objects);
        if (objects.stream().noneMatch(n -> "Y".equals(n.path("SS_CODE").asText())))
            throw new IllegalArgumentException("SOURCE_RESULT_INVALID");
        List<Item> items = new ArrayList<>();
        int total = 0;
        for (JsonNode n : objects) {
            if (n.has("ALL_CNT")) {
                String value = n.path("ALL_CNT").asText();
                if (!value.matches("[0-9]{1,10}")) throw new IllegalArgumentException("SOURCE_TOTAL_INVALID");
                total = Math.max(total, Integer.parseInt(value));
            }
            if (!n.has("PAN_NM")) continue;
            String title = n.path("PAN_NM").asText();
            String url = n.path("DTL_URL").asText();
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"apply.lh.or.kr".equals(uri.getHost()) || uri.getUserInfo() != null
                    || title.isBlank() || title.length() > 300 || url.length() > 2000)
                throw new IllegalArgumentException("SOURCE_ITEM_INVALID");
            // URL is the official stable detail identifier; never construct a guessed detail link.
            String sourceKey = sourceKey(url);
            String region = n.path("CNP_CD_NM").asText("확인 필요");
            String type = n.path("AIS_TP_CD_NM").asText("임대주택");
            if (region.length() > 100 || type.length() > 100) throw new IllegalArgumentException("SOURCE_ITEM_INVALID");
            Map<String, String> stable = new TreeMap<>();
            n.properties().forEach(entry -> {
                if (!Set.of("RNUM", "ALL_CNT", "RS_DTTM").contains(entry.getKey()))
                    stable.put(entry.getKey(), entry.getValue().toString());
            });
            String fingerprint = fingerprint(stable);
            // Preserve compatibility with stored full-row hashes; vary only the official status.
            String status = n.path("PAN_SS").asText().strip();
            boolean closed = CLOSED_STATUSES.contains(status);
            Set<String> previous = new HashSet<>();
            if (closed) {
                for (String prior : List.of("접수중", "공고중", "접수마감", "접수종료", "공고종료")) {
                    if (prior.equals(status)) continue;
                    stable.put("PAN_SS", json.writeValueAsString(prior));
                    previous.add(fingerprint(stable));
                }
            }
            items.add(new Item(sourceKey, title, region, type, url, date(n.path("PAN_NT_ST_DT").asText()), fingerprint, closed, previous));
        }
        return new Batch(items, total);
    }

    private String fingerprint(Map<String, String> fields) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(fields)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Stable identity of the exact detail URL supplied by the official listing.
     */
    public static String sourceKey(String url) {
        return "LH:" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8));
    }

    private LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return value.matches("\\d{8}") ? LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE) : LocalDate.parse(value.replace('.', '-'));
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("SOURCE_PUBLICATION_DATE_INVALID", ex);
        }
    }

    private void walk(JsonNode node, List<JsonNode> result) {
        if (node.isObject()) result.add(node);
        if (node.isContainer()) node.forEach(child -> walk(child, result));
    }
}
