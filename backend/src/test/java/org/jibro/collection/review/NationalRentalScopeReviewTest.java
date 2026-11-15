package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.junit.jupiter.api.Test;

class NationalRentalScopeReviewTest {
    private static final String CHECKED = "2026-11-08";
    private static final String URL = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=fixture";
    private static final String WAIVER = "입주자격 완화 주요내용 (필독) 금회 모집하는 국민임대주택은 소득 요건 배제";
    private static final String FIRST_COME = "거주지역, 순위 및 배점 등에 상관없이 신청접수 순번대로 입주자를 선정합니다.";
    private static final String PREGNANCY_ROW = "(제출대상) 태아 또는 양자를 월평균소득기준의 가구원수에 포함하여 적용을 받으려는 자 "
        + "임신진단서 / 입양관계증명서 (발급방법) 공고일 이후 발급";
    private static final String SCORING_ROW = "가족관계증명서 (상세) 부모·자녀간 육아지원세대 배점을 받고자 할 경우 필수 제출";

    @Test void retainsBothUnresolvedStandardDocumentScopesWithTheirOwnPageEvidence() {
        var source = source(new Part("공고문 1쪽", WAIVER), new Part("공고문 9쪽", FIRST_COME),
            new Part("공고문 11쪽", SCORING_ROW), new Part("공고문 12쪽", PREGNANCY_ROW));

        var findings = NationalRentalScopeReview.inspect(source, CHECKED);

        assertEquals(2, findings.size());
        assertEquals("WAIVED_INCOME_DOCUMENT_SCOPE_UNRESOLVED", findings.get(0).code());
        assertTrue(findings.get(0).evidence().label().endsWith("공고문 12쪽"));
        assertEquals("FIRST_COME_SCORING_DOCUMENT_SCOPE_UNRESOLVED", findings.get(1).code());
        assertTrue(findings.get(1).evidence().label().endsWith("공고문 11쪽"));
        assertEquals(URL, findings.get(0).evidence().url());
        assertEquals(CHECKED, findings.get(0).evidence().checkedAt());
    }

    @Test void normalIncomeAndRankRulesDoNotBecomeUniversalHolds() {
        var source = source(new Part("1쪽", "일반 국민임대 소득기준 70% 이하. 거주지역 순위 및 배점을 적용합니다."),
            new Part("서류", PREGNANCY_ROW + "\n" + SCORING_ROW));
        assertTrue(NationalRentalScopeReview.inspect(source, CHECKED).isEmpty());
    }

    @Test void renewalOnlyIncomeWaiverDoesNotChangeInitialApplicationDocumentScope() {
        var source = source(new Part("재계약", "재계약 시 소득요건 배제, 총자산 요건은 별도 심사합니다."),
            new Part("제출서류", PREGNANCY_ROW));
        assertTrue(NationalRentalScopeReview.inspect(source, CHECKED).isEmpty());
    }

    @Test void directPregnancyEvidenceWithoutIncomeConditionRemainsIndependent() {
        var source = source(new Part("1쪽", WAIVER),
            new Part("서류", "임신한 신청자는 소득기준과 무관하게 임신진단서를 제출합니다."));
        assertTrue(NationalRentalScopeReview.inspect(source, CHECKED).isEmpty());
    }

    @Test void recognizesReorderedTableCellsWithoutLosingScope() {
        var source = source(new Part("1쪽", WAIVER), new Part("12쪽", "임신진단서 / 입양관계증명서 "
            + "제출대상: 태아 또는 양자를 월평균소득기준의 가구원수에 포함하여 적용받으려는 자"));
        assertEquals(1, NationalRentalScopeReview.inspect(source, CHECKED).size());
    }

    @Test void explicitDocumentExemptionIsNotReportedAsUnresolved() {
        var source = source(new Part("1쪽", WAIVER), new Part("12쪽", "태아 또는 양자를 월평균소득기준의 가구원수에 "
            + "포함하는 임신진단서/입양관계증명서는 금회 제출 불필요"));
        assertTrue(NationalRentalScopeReview.inspect(source, CHECKED).isEmpty());
    }

    @Test void exemptionForANeighboringDocumentDoesNotClearTheUnresolvedRow() {
        var source = source(new Part("1쪽", WAIVER),
            new Part("12쪽", PREGNANCY_ROW + " 다른 항목: 주민등록표초본 제출 불필요"));
        assertEquals(1, NationalRentalScopeReview.inspect(source, CHECKED).size());
    }

    @Test void repeatedDocumentRowsProduceOneFindingPerIssueAndUnreadSourcesAreNotInterpreted() {
        var source = source(new Part("1쪽", WAIVER), new Part("12쪽", PREGNANCY_ROW), new Part("별지", PREGNANCY_ROW));
        assertEquals(1, NationalRentalScopeReview.inspect(source, CHECKED).size());
        var failed = new Source(source.label(), URL, null, "FAILED", "", List.of(), source.context());
        assertTrue(NationalRentalScopeReview.inspect(failed, CHECKED).isEmpty());
    }
    private static Source source(Part... parts) {
        return new Source("국민임대 모집공고.pdf", URL, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(parts));
    }
}
