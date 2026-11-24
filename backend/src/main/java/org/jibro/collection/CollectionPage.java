package org.jibro.collection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Provider page validation is independent of persistence and HTTP transport.
 */
final class CollectionPage {
    private CollectionPage() {
    }

    static void validate(LhNoticeClient.Batch batch, int page) {
        if (page < 1 || page > CollectionQueryPlan.MAX_PAGE || batch == null || batch.items() == null
                || batch.items().size() > 100 || batch.total() < 0
                || batch.total() > 0 && batch.items().size() != Math.min(100, Math.max(0, batch.total() - (page - 1) * 100))
                || batch.items().stream().anyMatch(Objects::isNull)
                || batch.items().stream().map(LhNoticeClient.Item::key).anyMatch(key -> key == null || key.isBlank())
                || batch.items().stream().map(LhNoticeClient.Item::key).distinct().count() != batch.items().size())
            throw new IllegalArgumentException("SOURCE_PAGINATION_INVALID");
    }

    static boolean hasMore(LhNoticeClient.Batch batch, int page) {
        return batch.total() > page * 100 || batch.total() == 0 && batch.items().size() == 100;
    }

    static String signature(LhNoticeClient.Batch batch) {
        if (batch.items().isEmpty()) return null;
        String keys = String.join("\n", batch.items().stream().map(LhNoticeClient.Item::key).sorted().toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keys.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
