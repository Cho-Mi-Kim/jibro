package org.jibro.collection.review;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceCoveragePolicyTest {
    Source source(String label, String status, String text) {
        return new Source(label, "https://apply.lh.or.kr/file/" + label, status.equals("FAILED") ? null : "a".repeat(64), status, "",
            List.of(), text == null ? List.of() : List.of(new Part("1쪽", text)));
    }
    SourceCoveragePolicy.Coverage assess(Source... sources) {
        var metadata = sources[0];
        return SourceCoveragePolicy.assess(new Draft("id", "notice", 1, "PARTIAL", Instant.parse("2026-11-07T00:00:00Z"), "", List.of(sources)),
            new Content("", new Evidence(metadata.label(), metadata.url(), "2026-11-07"), List.of(), List.of(), List.of(), List.of()));
    }
    SourceCoveragePolicy.Coverage assessScoped(Source... sources) {
        var metadata = sources[0];
        return SourceCoveragePolicy.assessScoped(new Draft("id", "notice", 1, "PARTIAL", Instant.parse("2026-11-07T00:00:00Z"), "", List.of(sources)),
            new Content("", new Evidence(metadata.label(), metadata.url(), "2026-11-07"), List.of(), List.of(), List.of(), List.of()));
    }
    @Test void metadataAndClearlyAncillaryFilesDoNotInvalidateReadableOfficialNotice() {
        var c = assess(source("공식 상세 본문", "EMPTY", "공고상태 접수중"), source("입주자모집공고.pdf", "EXTRACTED", "소득 150% 이하"), source("평면도.pdf", "EMPTY", ""), source("위치도.pdf", "FAILED", null));
        assertTrue(c.ready());
        assertEquals(1, c.draft().sources().size());
    }
    @Test void failedGoverningOrUnknownAttachmentCannotBeSilentlySkipped() {
        assertFalse(assess(source("공식 상세 본문", "EMPTY", "공고상태"), source("모집공고.pdf", "FAILED", null)).ready());
        assertFalse(assess(source("모집공고.pdf", "EXTRACTED", "소득 150% 이하"), source("추가자료.pdf", "FAILED", null)).ready());
    }
    @Test void matchingPdfAndHwpxRepresentationsKeepCanonicalPdf() {
        var c = assess(source("모집공고.hwpx", "EXTRACTED", "소득 150% 이하"), source("모집공고.pdf", "EXTRACTED", "소득 150% 이하"));
        assertTrue(c.ready()); assertEquals(1,c.draft().sources().size()); assertTrue(c.draft().sources().get(0).label().endsWith(".pdf"));
    }
    @Test void contradictoryThresholdInAlternateFormatIsHeld() {
        assertFalse(assess(source("모집공고.hwpx", "EXTRACTED", "소득 100% 이하"), source("모집공고.pdf", "EXTRACTED", "소득 150% 이하")).ready());
    }
    @Test void failedVariantAndSupplementaryCorrectionRemainRequired() {
        assertFalse(assess(source("모집공고.hwpx", "FAILED", null), source("모집공고.pdf", "EXTRACTED", "소득 150% 이하")).ready());
        assertFalse(assess(source("모집공고.pdf", "EXTRACTED", "소득 150% 이하"), source("정정공고매뉴얼.pdf", "FAILED", null)).ready());
    }

    @Test void sameNumbersDoNotProveEquivalentSubjectsOrOppositeExceptions() {
        assertFalse(assess(source("모집공고.hwpx", "EXTRACTED", "소득 100% 자산 150%"), source("모집공고.pdf", "EXTRACTED", "소득 150% 자산 100%")).ready());
        assertFalse(assess(source("모집공고.hwpx", "EXTRACTED", "외국인 신청 가능"), source("모집공고.pdf", "EXTRACTED", "외국인 신청 불가")).ready());
    }

    @Test void emptyClassifierResultAndAdviceNamesCannotHideQualifications() {
        assertFalse(assess(source("공식 상세 본문", "EMPTY", "외국인은 신청할 수 없습니다"), source("모집공고.pdf", "EXTRACTED", "소득 150% 이하")).ready());
        assertFalse(assess(source("모집공고.pdf", "EXTRACTED", "소득 150% 이하"), source("입주자격매뉴얼.pdf", "FAILED", null)).ready());
        assertFalse(assess(source("모집공고.pdf", "EXTRACTED", "소득 150% 이하"), source("제출서류양식.pdf", "FAILED", null)).ready());
    }

    @Test void missingOriginalAttachmentBlocksFallbackCompilation() {
        var s = source("모집공고.pdf", "EXTRACTED", "신청자는 무주택자이어야 합니다.");
        var original = new Content("", new Evidence(s.label(), s.url(), "2026-11-07"),
            List.of(new Evidence("추가 공고", "https://apply.lh.or.kr/missing.pdf", "2026-11-07")), List.of(), List.of(), List.of());
        var draft = new Draft("id", "notice", 1, "READY", Instant.parse("2026-11-07T00:00:00Z"), "", List.of(s));
        assertTrue(SourceCoveragePolicy.assess(draft, original).issues().contains("SOURCE_COVERAGE_INCOMPLETE"));
    }

    @Test void actualIksanListingFieldsAreNotASecondQualificationDocumentButDatesRemainCompared() {
        var listing = new Source("공식 상세 본문", "https://apply.lh.or.kr/detail", "b".repeat(64), "EXTRACTED", "",
            List.of(new Excerpt("SCHEDULE", "접수기간 : 2026.11.25 ~ 2026.11.26", "항목 4", "https://apply.lh.or.kr/detail")),
            List.of(new Part("항목 1", "공고상태공고중"), new Part("항목 2", "유형국민임대"),
                new Part("항목 3", "공고일2026.11.04"), new Part("항목 4", "접수기간 : 2026.11.25 ~ 2026.11.26"),
                new Part("항목 5", "당첨자발표일 : 2027.01.21"), new Part("항목 6", "계약기간 : ~")));
        var pdf = source("모집공고.pdf", "EXTRACTED", "소득 150% 이하");

        var coverage = assess(listing, pdf);

        assertTrue(coverage.ready());
        assertEquals(List.of(pdf), coverage.draft().sources());
        assertEquals(List.of(listing, pdf), coverage.scheduleSources());
        assertEquals(pdf.url(), coverage.original().evidence().url());
    }

    @Test void listingQualificationOrUnmatchedExcerptIsNeverHiddenAsMetadata() {
        var pdf = source("모집공고.pdf", "EXTRACTED", "소득 150% 이하");
        assertEquals(2, assess(source("공식 상세 본문", "EXTRACTED", "접수기간: 2026.11.25 ~ 2026.11.26\n외국인 신청 불가"), pdf)
            .draft().sources().size());
        assertEquals(2, assess(source("공식 상세 본문", "EXTRACTED", "유형무주택자만신청가능"), pdf).draft().sources().size());
        var listing = new Source("공식 상세 본문", "https://apply.lh.or.kr/detail", "b".repeat(64), "EXTRACTED", "",
            List.of(new Excerpt("CONDITION", "외국인 신청 불가", "본문", "https://apply.lh.or.kr/detail")),
            List.of(new Part("항목 1", "공고상태공고중")));
        assertEquals(2, assess(listing, pdf).draft().sources().size());
    }

    @Test void exactValueChangesInTheSameRulePositionsRetainBothVariantsForScopedReview() {
        String body = """
            신청 자격
            신청자는 만 19세 이상 39세 이하이어야 합니다.
            신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.
            공통 제출서류: 주민등록등본, 가족관계증명서
            접수기간: 2026.01.01 ~ 2026.01.31
            결과 발표: 2026.02.07
            """;
        var first = source("모집공고.pdf", "EXTRACTED", body);
        var second = source("모집공고.hwpx", "EXTRACTED", body.replace("3,000,000원", "4,000,000원")
            .replace("가족관계증명서", "주민등록초본").replace("2026.01.31", "2026.02.01")
            .replace("2026.02.07", "2026.02.08"));
        var coverage = assessScoped(first, second);
        assertTrue(coverage.ready(), coverage.issues().toString());
        assertEquals(List.of(first, second), coverage.draft().sources());
        assertEquals(List.of(first, second), coverage.scheduleSources());
        assertFalse(assess(first, second).ready());
    }

    @Test void missingOrNewExactRuleIsNotTreatedAsAnIndependentSupportedRule() {
        String body = "신청자는 무주택자이어야 합니다.\n공통 제출서류: 주민등록등본";
        var shortVariant = source("모집공고.pdf", "EXTRACTED", body);
        var extraCondition = source("모집공고.hwpx", "EXTRACTED", body + "\n신청자는 만 19세 이상 39세 이하이어야 합니다.");
        assertVariantHeld(assessScoped(shortVariant, extraCondition));
        assertVariantHeld(assessScoped(extraCondition, shortVariant));
        var extraDocumentScope = source("모집공고.hwpx", "EXTRACTED", body + "\n대학생 제출서류: 재학증명서");
        assertVariantHeld(assessScoped(shortVariant, extraDocumentScope));
    }

    @Test void exactThresholdDifferenceCannotHideChangedSubjectOrExceptionContext() {
        String body = "4. 신청자격\n일반 신청자\n신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.\n"
            + "8. 제출서류\n공통 제출서류: 주민등록등본";
        var first = source("모집공고.pdf", "EXTRACTED", body);
        for (String changed : List.of(body.replace("일반 신청자", "예외 대상 신청자").replace("3,000,000원", "4,000,000원"),
                body.replace("3,000,000원", "4,000,000원") + "\n다만 별표 2에 해당하는 사람은 제외합니다.")) {
            assertVariantHeld(assessScoped(first, source("모집공고.hwpx", "EXTRACTED", changed)));
        }
    }

    @Test void incompleteAlternatePageCannotDisappearDuringWhitespaceEquivalenceComparison() {
        String body = "신청자는 무주택자이어야 합니다.\n공통 제출서류: 주민등록등본";
        var readable = source("모집공고.pdf", "EXTRACTED", body);
        for (Part page : List.of(new Part("2쪽", ""), new Part("2쪽", "\n\t \u00a0\u3000"),
                new Part("2쪽", null), new Part("2쪽", "[발췌 길이 제한]"), new Part("", "조건"))) {
            var unread = variantWithParts(List.of(new Part("1쪽", body), page));
            assertIncomplete(assess(readable, unread));
            assertIncomplete(assessScoped(readable, unread));
        }
    }

    @Test void unreadSingleGoverningSourceIsHeldByCoverageRatherThanDeferredToPublication() {
        assertIncomplete(assessScoped(source("모집공고.pdf", "EXTRACTED", "\u3000\n")));
        assertIncomplete(assessScoped(source("모집공고.pdf", "EXTRACTED", "신청 자격 [발췌 길이 제한]")));
    }

    private Source variantWithParts(List<Part> parts) {
        return new Source("모집공고.hwpx", "https://apply.lh.or.kr/alternate.hwpx", "b".repeat(64),
            "EXTRACTED", "완료", List.of(), parts);
    }

    private void assertIncomplete(SourceCoveragePolicy.Coverage coverage) {
        assertFalse(coverage.ready());
        assertTrue(coverage.issues().stream().anyMatch(issue -> issue.startsWith("AUTHORITATIVE_SOURCE_INCOMPLETE:")),
            coverage.issues().toString());
    }

    private void assertVariantHeld(SourceCoveragePolicy.Coverage coverage) {
        assertFalse(coverage.ready());
        assertTrue(coverage.issues().stream().anyMatch(issue -> issue.startsWith("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:")),
            coverage.issues().toString());
    }
}
