package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class OfficialFormConsistencyTest {
    private static final String PAGE = "https://apply.lh.or.kr/notice";
    private static final String FILE = "https://apply.lh.or.kr/notice.hwpx";
    private static final String PUBLICATION = "공고일:2026.11.04";

    @Test void verifiedEmptyListingStillBlocksAnInconsistentHwpxOnlyForm() {
        var draft = draft("EMPTY", PUBLICATION, "26.10.08");
        var result = GuidanceReviewCompiler.assess(draft, original());
        assertFalse(result.ready());
        assertNotNull(result.candidate());
        assertEquals(List.of("FORM_PUBLICATION_DATE_MISMATCH"), result.issues());
        assertEquals(FILE, result.findings().get(0).evidence().url());
        assertTrue(result.findings().get(0).message().contains("2026-11-04"));
        assertTrue(result.findings().get(0).message().contains("2026-10-08"));
    }

    @Test void matchingHwpxFormDoesNotBecomeAConflictMerelyBecauseListingHasNoExcerpt() {
        var result = GuidanceReviewCompiler.assess(draft("EMPTY", PUBLICATION, "26.11.04"), original());
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(result.findings().isEmpty());
    }

    @Test void failedUnknownAndUnverifiedEmptyBodiesCannotSupplyPublicationAnchors() {
        for (String status : List.of("FAILED", "UNKNOWN")) {
            var draft = draft(status, PUBLICATION, "26.10.08");
            assertTrue(OfficialFormConsistency.inspect(draft).isEmpty(), status);
            assertFalse(GuidanceReviewCompiler.assess(draft, original()).ready(), status);
        }
        var unknownBody = draft("EMPTY", PUBLICATION + "\n신청 자격은 추가 확인이 필요합니다.", "26.10.08");
        assertTrue(OfficialFormConsistency.inspect(unknownBody).isEmpty());
        assertFalse(GuidanceReviewCompiler.assess(unknownBody, original()).ready());
    }

    private static Content original() {
        return new Content("공고 안내", new Evidence("공식 상세 본문", PAGE, "2026-11-04"),
            List.of(new Evidence("모집공고.hwpx", FILE, "2026-11-04")), List.of(), List.of(), List.of());
    }

    private static Draft draft(String listingStatus, String listingText, String formDate) {
        var listing = new Source("공식 상세 본문", PAGE, "a".repeat(64), listingStatus, "", List.of(),
            List.of(new Part("공식 상세 본문", listingText)));
        String body = """
            입주자모집공고일 : 2026.11.04
            1. 신청 자격
            신청자는 무주택자이어야 합니다.
            2. 제출서류
            공통 제출서류: 가족관계증명서
            3. 모집 일정
            접수기간: 2026.11.25 ~ 2026.11.26
            확약서
            공고('%s)에 따른 입주자로서 위 사실을 확인합니다.
            """.formatted(formDate);
        var attachment = new Source("모집공고.hwpx", FILE, "b".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("문서 구간 1", body)));
        return new Draft("extraction", "notice", 1, "PARTIAL", Instant.parse("2026-11-04T00:00:00Z"), "", List.of(listing, attachment));
    }
}
