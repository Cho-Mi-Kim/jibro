package org.jibro.notice;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.jibro.notice.HousingModels.Content;

/**
 * Shared official identity and catalogue requirements for automatic and source-reviewed publication.
 */
final class LhBasicReview {
    private LhBasicReview() {
    }

    static boolean valid(NoticeEntity n, Content c) {
        try {
            EvidenceValidation.validate(c.evidence());
            URI uri = URI.create(c.evidence().url());
            String key = "LH:" + UUID.nameUUIDFromBytes(c.evidence().url().getBytes(StandardCharsets.UTF_8));
            return "apply.lh.or.kr".equals(uri.getHost()) && key.equals(n.sourceKey)
                    && n.sourceFingerprint.matches("[0-9a-f]{64}") && text(n.title) && text(n.region) && text(n.supportType)
                    && n.agency.equals("LH") && n.publishedDate != null && !n.publishedDate.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank() && !value.contains("확인 필요");
    }
}
