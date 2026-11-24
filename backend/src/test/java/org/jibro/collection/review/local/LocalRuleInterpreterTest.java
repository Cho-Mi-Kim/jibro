package org.jibro.collection.review.local;

import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.junit.jupiter.api.Test;

import static org.jibro.collection.review.local.LocalRuleBook.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalRuleInterpreterTest {
    private static final String URL = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1";

    private Source source(Part... parts) {
        return new Source("입주자 모집공고.pdf", URL, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(parts));
    }

    private LocalRuleBook read(String text) {
        return LocalRuleInterpreter.interpret(List.of(source(new Part("공고문 1쪽", text))));
    }

    @Test
    void exactOriginalSpansCoverEveryNonWhitespaceCharacterAndIdsAreStable() {
        String text = "4. 신청 자격\r\n  신청자는 만 19세 이상 39세 이하입니다.\r\n\r\n▪ 예외는 9쪽을 확인하세요.\r\n  미확인 표 내용도 그대로 남깁니다.\r\n";
        var first = read(text);
        var second = read(text);
        assertEquals(first, second);
        var covered = new boolean[text.length()];
        for (var clause : first.clauses()) {
            assertEquals(text.substring(clause.start(), clause.end()), clause.quote());
            for (int i = clause.start(); i < clause.end(); i++) covered[i] = true;
            for (var amount : clause.quantities())
                assertEquals(text.substring(amount.start(), amount.end()), amount.literal());
        }
        for (int i = 0; i < text.length(); i++)
            if (!Character.isWhitespace(text.charAt(i))) assertTrue(covered[i], "lost offset " + i);
        assertEquals(text.length(), first.sourceCharacters());
        assertTrue(first.unresolved().stream().anyMatch(u -> u.code().equals("REFERENCE_TARGET_MISSING")));
    }

    @Test
    void incomeWaiverDoesNotWaiveCarAndRetainsEveryHouseholdMemberScope() {
        // Complete literal excerpt from the Iksan relaxation table; subsequent exception evidence remains required.
        var book = read("① 소득 및 총자산 요건 완화\n▪ 소득 요건 소득요건 배제\n▪ 총자산 요건 총자산요건 배제\n"
                + "▪ 완화요건 미적용\n자동차 가액\n(세대구성원 전원이 보유하고 있는 개별 자동차가액 ( 4,542 )만원 이하)\n");
        assertTrue(book.facts().stream().anyMatch(f -> f.type().equals("INCOME_REQUIREMENT_WAIVED")));
        assertTrue(book.facts().stream().anyMatch(f -> f.type().equals("TOTAL_ASSET_REQUIREMENT_WAIVED")));
        assertTrue(book.facts().stream().anyMatch(f -> f.type().equals("VEHICLE_NOT_WAIVED")));
        var vehicle = book.facts().stream().filter(f -> f.type().equals("HOUSEHOLD_INDIVIDUAL_VEHICLE_LIMIT")).findFirst().orElseThrow();
        assertEquals("세대구성원 전원", vehicle.subject());
        assertEquals("4542", vehicle.values().get("amount"));
        assertEquals("만원", vehicle.values().get("unit"));
        assertEquals("separateEvidenceRequired", vehicle.values().get("exceptions"));
        assertTrue(book.unresolved().stream().anyMatch(u -> u.clauseIds().contains(vehicle.clauseIds().get(0))));
    }

    @Test
    void aKnownFactDoesNotCertifyTheRestOfAnUnknownParagraph() {
        var book = read("소득요건 배제\n다만 신청자 가족관계에 따른 별도의 새로운 제한이 있습니다.\n");
        assertEquals(1, book.facts().size());
        assertTrue(book.unresolved().stream().anyMatch(u -> u.code().equals("RULE_COMPLETENESS_REVIEW")));
        assertTrue(read("소득요건 배제하지 않습니다.").facts().isEmpty());
        assertTrue(read("소득요건 배제할 수 없습니다.").facts().isEmpty());
        assertTrue(read("총자산요건 배제 대상이 아닙니다.").facts().isEmpty());
        assertTrue(read("선착순으로 접수하지 않습니다.").facts().isEmpty());
    }

    @Test
    void adultRuleKeepsMinorExceptionsAndTheirProofRequirements() {
        var book = read("■ 성년자\n「민법」상 미성년자(19세 미만)는 공급 신청할 수 없습니다. 단, 아래의 어느 하나에 해당하는 경우 미성년자도 공급 신청 가능합니다.\n"
                + "- 자녀가 있는 미성년 세대주(단, 동일한 세대별 주민등록표에 자녀가 등재되어야 함)\n"
                + "- 직계존속의 사망, 실종, 행방불명 등으로 형제자매를 부양하여야 하는 미성년 세대주(단, 행방불명은 신고접수증으로 증빙)\n"
                + "- 외국인 부모와 미성년 자녀 세대주의 한부모가족(단, 외국인 부모가 대리하여 신청)\n");
        var adult = book.facts().stream().filter(f -> f.type().equals("ADULT_AGE_RESTRICTION")).findFirst().orElseThrow();
        assertEquals("19", adult.values().get("minimumAge"));
        assertEquals("notEvaluated", adult.values().get("finalDecision"));
        var initial = book.clauses().stream().filter(c -> c.id().equals(adult.clauseIds().get(0))).findFirst().orElseThrow();
        assertTrue(initial.markers().contains(Marker.ANY_OF));
        assertTrue(book.clauses().stream().anyMatch(c -> c.quote().contains("신고접수증") && !c.relatedClauseIds().isEmpty()));
        assertTrue(book.clauses().stream().anyMatch(c -> c.quote().contains("외국인 부모가 대리") && !c.relatedClauseIds().isEmpty()));
    }

    @Test
    void firstComeSelectionNeverReusesAnOrdinaryRankTable() {
        var book = read("5. 선정방법\n거주지역 및 순위, 배점 등과 관계없이 선착순으로 접수합니다.\n");
        assertTrue(book.facts().stream().anyMatch(f -> f.type().equals("SELECTION_FIRST_COME_WITHOUT_RANK")));
        assertTrue(book.facts().stream().noneMatch(f -> f.type().equals("RANK_TIER_CRITERIA")));
        var actual = read("▪ 국민임대주택 입주자격을 충족하는 자에 대하여 거주지역, 순위 및 배점 등에 상관없이 신청접수 순번대로 입주자를 선정하되, 신청자를 대상으로 주택, 소득, 총자산 등을 검색하여 적격자에 한하여 개별적으로 계약체결을 통보합니다.");
        var firstCome = actual.facts().stream().filter(f -> f.type().equals("SELECTION_FIRST_COME_WITHOUT_RANK")).findFirst().orElseThrow();
        assertEquals("separatelyRequired", firstCome.values().get("eligibility"));
    }

    @Test
    void renewalSurchargeDoesNotBecomeAnIncomeEligibilityThreshold() {
        var book = read("해당 세대의 월평균소득이 전년도 도시근로자 가구원수별 가구당 월평균소득 100%를 초과하는 비율에 따라 아래 할증비율이 적용됨\n소득기준 초과 비율\n최초 갱신계약 시\n10%이하\n110%\n120%");
        assertTrue(book.facts().stream().noneMatch(f -> f.type().equals("INCOME_PERCENT_OF_REFERENCE")));
        assertFalse(book.unresolved().isEmpty());
        assertTrue(read("소득·총자산 요건 배제 재계약 허용").facts().stream().noneMatch(f -> f.type().contains("WAIVED")));
    }

    @Test
    void studentEquivalenceDoesNotBecomeAnIncomeOrMarriageExemption() {
        var book = read("①-㉯ 취업준비생은 대학 또는 고등학교를 졸업 또는 중퇴한 지 2년 이내일 것\n"
                + "* 모든 학기 수료를 완료하였으나 아직 졸업을 하지 않은 경우 재학생으로 간주\n② 혼인 중이 아닐 것\n"
                + "③ 신청자 부모와 본인의 월평균소득 합계가 가구당 월평균소득의 100퍼센트 이하\n"
                + "※ 단, 가구원 수가 1인인 경우에는 120퍼센트, 2인인 경우에는 110퍼센트 이하일 것\n");
        assertTrue(book.facts().stream().anyMatch(f -> f.type().equals("JOBSEEKER_EDUCATION_WINDOW")));
        assertTrue(book.facts().stream().anyMatch(f -> f.type().equals("STUDENT_ENROLMENT_EQUIVALENCE")));
        assertTrue(book.facts().stream().anyMatch(f -> f.type().equals("UNMARRIED_REQUIREMENT")));
        var income = book.facts().stream().filter(f -> f.type().equals("INCOME_PERCENT_OF_REFERENCE")).findFirst().orElseThrow();
        assertTrue(income.subject().contains("신청자 부모와 본인"));
        assertEquals("100", income.values().get("percent"));
        assertTrue(book.clauses().stream().anyMatch(c -> c.quote().contains("120퍼센트") && c.markers().contains(Marker.EXCEPTION)));
        assertTrue(book.facts().stream().noneMatch(f -> f.type().contains("WAIVED")));
    }

    @Test
    void referencesOnlyResolveWithinTheirOwnSourceAndChapterHeadingsAreNotReferences() {
        var first = source(new Part("공고문 1쪽", "4. 신청 자격\n자동차 예외는 공고문 9쪽을 참조하세요."));
        var other = new Source("다른 공고.pdf", URL + "2", "b".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("공고문 9쪽", "다른 공고의 자동차 예외")));
        var book = LocalRuleInterpreter.interpret(List.of(first, other));
        assertTrue(book.unresolved().stream().anyMatch(u -> u.code().equals("REFERENCE_TARGET_MISSING")));
        assertTrue(book.clauses().stream().noneMatch(c -> c.references().contains("section:4")));
        var complete = LocalRuleInterpreter.interpret(List.of(source(new Part("공고문 1쪽", "자동차 예외는 공고문 9쪽을 참조하세요."), new Part("공고문 9쪽", "장애인사용 자동차는 원문 기준에 따라 제외합니다."))));
        assertFalse(complete.unresolved().stream().anyMatch(u -> u.code().equals("REFERENCE_TARGET_MISSING")));
    }

    @Test
    void missingSourcesAndBlankPagesRemainExplicitUnresolvedWork() {
        assertTrue(LocalRuleInterpreter.interpret(List.of()).unresolved().stream().anyMatch(u -> u.code().equals("SOURCE_MISSING")));
        assertTrue(LocalRuleInterpreter.interpret(List.of(new Source("공고.pdf", URL, null, "FAILED", "", List.of()))).unresolved().stream().anyMatch(u -> u.code().equals("SOURCE_UNREADABLE")));
        assertTrue(read("   ").unresolved().stream().anyMatch(u -> u.code().equals("SOURCE_CONTEXT_MISSING")));
    }

    @Test
    void longUnknownClauseIsRetainedWholeInsteadOfBeingSilentlyTruncated() {
        String text = "신청 자격의 예외 " + "미해석내용".repeat(700);
        var book = read(text);
        assertEquals(text, book.clauses().get(0).quote());
        assertFalse(book.unresolved().isEmpty());
    }

    @Test
    void negatedExclusionsDeductionsAndEquivalenceDoNotBecomePositiveFacts() {
        for (String text : List.of("장애인 자동차는 산정에서 제외하지 않습니다.",
                "장애인 자동차를 제외할 수 없습니다.", "장애인 자동차는 산정에서 미포함 대상이 아닙니다.")) {
            var book = read(text);
            assertTrue(book.facts().stream().noneMatch(f -> f.type().equals("VEHICLE_DISABILITY_EXCLUSION")));
            assertFalse(book.unresolved().isEmpty());
        }
        for (String text : List.of("저공해 자동차 보조금을 공제하지 않습니다.",
                "저공해 자동차 보조금은 차감 대상이 아닙니다.", "저공해 자동차 보조금을 제외할 수 없습니다."))
            assertTrue(read(text).facts().stream().noneMatch(f -> f.type().equals("VEHICLE_SUBSIDY_DEDUCTION")));
        assertTrue(read("모든 학기 수료 후 졸업하지 않은 경우에도 재학생으로 간주하지 않습니다.")
                .facts().stream().noneMatch(f -> f.type().equals("STUDENT_ENROLMENT_EQUIVALENCE")));
        assertTrue(read("혼인 중이 아닐 것은 적용하지 않습니다.").facts().stream().noneMatch(f -> f.type().equals("UNMARRIED_REQUIREMENT")));
        assertTrue(read("자동차가 2대 이상인 경우 높은 가액을 적용하지 않습니다.")
                .facts().stream().noneMatch(f -> f.type().equals("VEHICLE_MAXIMUM_VALUATION")));
        assertTrue(read("장애인 자동차는 제외하지 않습니다. 다른 자동차는 제외합니다.")
                .facts().stream().noneMatch(f -> f.type().equals("VEHICLE_DISABILITY_EXCLUSION")));
        assertTrue(read("저공해 자동차 보조금은 공제하지 않습니다. 다른 비용은 공제합니다.")
                .facts().stream().noneMatch(f -> f.type().equals("VEHICLE_SUBSIDY_DEDUCTION")));
    }

    @Test
    void unrelatedNegationDoesNotSuppressAnExplicitPositivePredicate() {
        var disability = read("장애인 자동차는 산정에서 제외합니다. 다른 자동차는 제외하지 않습니다.");
        assertTrue(disability.facts().stream().anyMatch(f -> f.type().equals("VEHICLE_DISABILITY_EXCLUSION")));
        var subsidy = read("저공해 자동차의 정부 보조금을 제외한 금액으로 산정합니다. 개인 할인금은 공제하지 않습니다.");
        assertTrue(subsidy.facts().stream().anyMatch(f -> f.type().equals("VEHICLE_SUBSIDY_DEDUCTION")));
        var student = read("모든 학기 수료 후 졸업하지 않은 사람은 재학생으로 간주합니다. 수료하지 않은 사람은 간주하지 않습니다.");
        assertTrue(student.facts().stream().anyMatch(f -> f.type().equals("STUDENT_ENROLMENT_EQUIVALENCE")));
        assertTrue(read("장애인 자동차는 산정에 포함하지 않습니다.").facts().stream().anyMatch(f -> f.type().equals("VEHICLE_DISABILITY_EXCLUSION")));
        assertTrue(read("신청자는 미혼이어야 합니다.").facts().stream().anyMatch(f -> f.type().equals("UNMARRIED_REQUIREMENT")));
        assertTrue(read("자동차가 2대 이상인 경우 높은 가액을 적용합니다.")
                .facts().stream().anyMatch(f -> f.type().equals("VEHICLE_MAXIMUM_VALUATION")));
    }

    @Test
    void explicitAppendixReferencesRemainMissingUntilAnAppendixResolverExists() {
        var book = read("4. 신청 자격\n소득요건 배제. 다만 별표 2 및 별지 제1호의 예외 기준을 확인하세요.");
        var clause = book.clauses().stream().filter(c -> c.quote().contains("별표")).findFirst().orElseThrow();
        assertTrue(clause.references().contains("appendix:별표2"));
        assertTrue(clause.references().contains("appendix:별지제1호"));
        assertTrue(book.unresolved().stream().anyMatch(u -> u.code().equals("REFERENCE_TARGET_MISSING") && u.clauseIds().contains(clause.id())));
        var ordinary = read("신청자는 제출 내용을 다시 확인하세요.");
        assertTrue(ordinary.unresolved().stream().noneMatch(u -> u.code().equals("REFERENCE_TARGET_MISSING")));
    }
}
