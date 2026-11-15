package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NoticeSummaryTest {
    @Test void preservesReviewedNoticeContentIncludingExceptionsAndUncertainty() {
        String summary = "남원노암 영구임대 예비입주자를 모집합니다. 소득·총자산은 배제하되 자동차 기준은 유지합니다. 재계약 조건은 확인이 필요합니다.";
        assertEquals(summary, NoticeSummary.display("남원노암 모집", "LH", summary));
    }

    @Test void legacyProcessingMessagesUseOnlyTheActualNoticeIdentity() {
        for (String summary : new String[] {null, "", "공식 목록에서 자동 수집했습니다. 조건을 확인 중입니다.",
                "공식 원문의 신청 기준·순위·예외와 제출 대상을 함께 확인하는 준비 안내입니다. 원문 구간 19개를 보존했습니다."}) {
            assertEquals("LH의 ‘신혼·신생아 전세임대Ⅱ 수시모집’ 공고입니다.",
                NoticeSummary.display("신혼·신생아 전세임대Ⅱ 수시모집", "LH", summary));
        }
    }

    @Test void shortAudienceKeepsTheActualChildCountAndOtherConditions() {
        String rest = " 공고의 소득·자산 기준을 충족하는 무주택세대가 대상입니다. 임대료는 시세의 30~40%입니다.";
        assertEquals("2자녀 이상이며" + rest, NoticeSummary.display("다자녀 모집", "LH",
            "미성년 직계비속(태아 포함) 2명 이상을 양육하며" + rest));
        assertEquals("3자녀 이상인 가구가 대상입니다.", NoticeSummary.concise(
            "미성년 직계비속(태아 포함)을 3명 이상 양육하는 가구가 대상입니다."));
        assertEquals("2자녀 이상인 무주택가구가 대상입니다.", NoticeSummary.concise(
            "미성년 직계비속(태아 포함) 2명 이상을 양육하는 무주택가구가 대상입니다."));
    }

    @Test void unrelatedRangesNegativeConditionsAndExistingBriefCopyArePreserved() {
        for (String text : new String[] {"미성년 직계비속(태아 포함) 2명 이하를 양육하는 가구입니다.",
                "미성년 직계비속(태아 포함) 2명 이상을 양육하지 않는 가구는 제외합니다.",
                "2자녀 이상인 가구가 대상입니다.", "공고의 세부 기준: 미성년 직계비속(태아 포함) 2명 이상을 양육하는 가구"})
            assertEquals(text, NoticeSummary.concise(text));
    }

    @Test void benefitKeepsTheLocationRateAndAdditionalPaymentConditions() {
        String note = " 보증금은 별도이며 재계약 시 조건이 달라질 수 있습니다.";
        String result = NoticeSummary.concise("거창군 신원면의 다자녀 매입임대주택을 자격에 따라 시중시세의 30~40% 수준의 임대료로 공급합니다." + note);
        assertEquals("거창군 신원면의 다자녀 매입임대주택을 시세의 30~40% 수준의 임대료로 공급합니다." + note, result);
        assertEquals(result, NoticeSummary.concise(result));
        assertEquals("매입임대주택을 시세의 35.5% 수준의 임대조건으로 공급합니다.", NoticeSummary.concise(
            "매입임대주택을 자격에 따라 시중 시세의 35.5% 수준의 임대조건으로 공급합니다."));
        String negative = "주택을 시세의 30~40% 수준으로 공급하지 않습니다. 자격에 따라 임대료가 달라집니다.";
        assertEquals(negative, NoticeSummary.concise(negative));
    }

    @Test void shorterSupplyWordingPreservesTheDepositAndMonthlyRent() {
        assertEquals("국민임대주택을 보증금과 월 임대료로 공급합니다.", NoticeSummary.concise(
            "국민임대주택을 보증금과 월 임대료를 내고 거주할 수 있도록 공급합니다."));
        assertEquals("LH가 매입한 주택을 보증금과 월 임대료로 공급합니다.", NoticeSummary.concise(
            "LH가 매입한 주택을 공고에서 정한 보증금과 월 임대료로 거주할 수 있도록 공급합니다."));
    }
}
