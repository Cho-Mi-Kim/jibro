package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.jibro.notice.HousingModels.Evidence;
import org.junit.jupiter.api.Test;

class EvidenceValidationTest {
    private final LocalDate today = LocalDate.of(2026, 11, 7);

    @Test
    void preservesMissingAddressAndOriginalDateWithoutInventingVerification() {
        assertDoesNotThrow(() -> EvidenceValidation.validate(new Evidence("공고문 4쪽", null, "2026-11-01"), today));
        assertDoesNotThrow(() -> EvidenceValidation.validate(new Evidence("공고문 4쪽",
                "https://apply.lh.or.kr/file?id=1#page=4", "2026-11-07"), today));
    }

    @Test
    void rejectsFutureImpossibleAndUnqualifiedEvidenceDates() {
        for (String date : List.of("2026-11-08", "2026-02-30", "2026-1-1", ""))
            assertThrows(IllegalArgumentException.class, () -> EvidenceValidation.validate(new Evidence("공고문 4쪽", null, date), today));
        assertThrows(IllegalArgumentException.class, () -> EvidenceValidation.validate(new Evidence("", null, "2026-11-07"), today));
    }

    @Test
    void onlyAcceptsHttpsWithoutCredentialsOrAlternatePorts() {
        for (String url : List.of("http://apply.lh.or.kr/", "https://user@apply.lh.or.kr/",
                "https://apply.lh.or.kr:8443/", "javascript:alert(1)", "file:///tmp/a"))
            assertThrows(IllegalArgumentException.class, () -> EvidenceValidation.validateUrl(url));
    }
}
