package org.jibro.collection;

import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class SourceResponseSanitizer {
    static final int MAX_BYTES = 5_000_000;
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)(service.?key|api.?key|access.?token|refresh.?token|authorization|password|passwd|secret|cookie|credential|client.?id|client.?secret)");
    private static final Pattern QUERY_SECRET = Pattern.compile("(?i)((?:service.?key|api.?key|access.?token|refresh.?token|password|secret|authorization)=)[^&\\s\"<>]*");
    private final ObjectMapper json;
    private final List<Pattern> secrets;

    SourceResponseSanitizer(ObjectMapper json, String key) {
        this.json = json;
        var forms = new LinkedHashSet<String>();
        if (!key.isBlank()) {
            forms.add(key);
            forms.add(URLEncoder.encode(key, StandardCharsets.UTF_8));
            try {
                forms.add(URLDecoder.decode(key, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) { /* Literal key remains masked. */ }
        }
        secrets = forms.stream().filter(value -> !value.isBlank()).map(value -> Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE)).toList();
    }

    SourceResponse snapshot(int status, String body, Instant received) {
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            return new SourceResponse(status, received, "TOO_LARGE", null, null, false);
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isContainer()) throw new IllegalArgumentException();
            boolean[] redacted = {false};
            JsonNode safe = scrub(root, redacted, 0);
            String value = json.writeValueAsString(safe);
            if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
                return new SourceResponse(status, received, "TOO_LARGE", null, null, redacted[0]);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
            return new SourceResponse(status, received, "JSON", value, hash, redacted[0]);
        } catch (Exception invalid) {
            return new SourceResponse(status, received, "INVALID_JSON", null, null, false);
        }
    }

    private JsonNode scrub(JsonNode node, boolean[] redacted, int depth) {
        if (depth > 64) throw new IllegalArgumentException();
        if (node.isObject()) {
            ObjectNode copy = json.createObjectNode();
            node.properties().forEach(entry -> {
                if (CREDENTIAL.matcher(entry.getKey()).find()) {
                    copy.put(entry.getKey(), "[REDACTED]");
                    redacted[0] = true;
                } else copy.set(entry.getKey(), scrub(entry.getValue(), redacted, depth + 1));
            });
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = json.createArrayNode();
            node.forEach(item -> copy.add(scrub(item, redacted, depth + 1)));
            return copy;
        }
        if (node.isString()) {
            String original = node.asText(), value = original;
            for (Pattern secret : secrets) value = secret.matcher(value).replaceAll("[REDACTED]");
            value = QUERY_SECRET.matcher(value).replaceAll("$1[REDACTED]");
            redacted[0] |= !original.equals(value);
            return json.getNodeFactory().stringNode(value);
        }
        return node;
    }
}
