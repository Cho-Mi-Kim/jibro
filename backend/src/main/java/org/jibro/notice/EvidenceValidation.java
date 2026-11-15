package org.jibro.notice;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;

import org.jibro.notice.HousingModels.Evidence;

/**
 * Syntax and provenance validation, never a claim that an official document was reviewed.
 */
public final class EvidenceValidation {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private EvidenceValidation() {
    }

    public static void validate(Evidence evidence) {
        validate(evidence, LocalDate.now(SEOUL));
    }

    static void validate(Evidence evidence, LocalDate today) {
        if (evidence == null || evidence.label() == null || evidence.label().isBlank()
            || evidence.label().length() > 300 || evidence.checkedAt() == null
            || !evidence.checkedAt().matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new IllegalArgumentException("출처 위치와 확인일을 확인해 주세요.");
        validateUrl(evidence.url());
        LocalDate checkedAt;
        try {
            checkedAt = LocalDate.parse(evidence.checkedAt());
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("출처 확인일을 확인해 주세요.");
        }
        if (checkedAt.isAfter(today))
            throw new IllegalArgumentException("출처 확인일은 미래 날짜일 수 없습니다.");
    }

    public static void validateUrl(String value) {
        if (value == null) return;
        if (value.length() > 2000) throw new IllegalArgumentException("공식 주소를 확인해 주세요.");
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
            || uri.getPort() != -1 && uri.getPort() != 443)
            throw new IllegalArgumentException("HTTPS 공식 주소를 입력해 주세요.");
    }
}
