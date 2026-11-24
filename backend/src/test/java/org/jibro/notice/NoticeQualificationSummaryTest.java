package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jibro.notice.HousingModels.*;
import tools.jackson.databind.json.JsonMapper;

class NoticeQualificationSummaryTest {
    private final Evidence source = new Evidence("공고문 4쪽", "https://apply.lh.or.kr/notice", "2026-11-11");
    private final String audience = "2자녀 이상이며 소득·자산 기준을 충족하는 무주택가구가 대상입니다.";

    private NoticeQualificationSummary.Overview overview(String reason) {
        return NoticeQualificationSummary.from(audience + " 매입임대주택을 공급합니다.",
                List.of(new Question("rank", "선정 기준", reason, source)));
    }

    @Test
    void separatesEligibilityAndRegionalPriorityKeepingExceptionInDetail() {
        String reason = "공고일 2026.10.27. 주민등록을 기준으로 타지역 거주자를 먼저 경합·선정하고, 선순위 미달 때 같은 사업지역(거창군) 거주자를 포함한 후순위를 진행합니다. 기존 입주자는 지자체장이 인정하면 타지역 거주자로 간주하고 5점을 추가합니다.";
        var result = overview(reason);
        assertEquals(audience, result.eligibility());
        assertEquals("거창군 외 거주자 우선, 미달 시 거창군 거주자를 포함합니다.", result.selection());
        assertEquals(reason, result.detail());
        assertEquals(source, result.evidence());
    }

    @Test
    void keepsAreaThresholdsAndSameRankRestrictions() {
        for (String reason : List.of(
                "51㎡는 청약저축 인정납입 24회 이상 1순위, 6회 이상 2순위, 나머지 3순위입니다.",
                "동일순위에서 남원시 거주자를 우선합니다.",
                "10시 전 도착자 경합은 추첨, 이후는 도착순입니다.")) {
            assertEquals(reason, overview(reason + " 세부 예외는 원문을 따릅니다.").selection());
        }
    }

    @Test
    void skipsGenericLeadAndPointTablesForActualSelectionOrder() {
        String order = "합산배점→전입일이 오래된 사람→세대원수→연령→추첨 순서입니다.";
        assertEquals(order, overview("신청자격 충족과 선정순위는 다릅니다. " + order).selection());
        assertEquals(order, overview("거주기간은 10~20점입니다. " + order).selection());
        assertEquals("일반공급·주거약자형 모두 배점→세대원수→연령→오산전입일→추첨입니다.",
                overview("일반공급은 10점입니다. 주거약자형은 5점입니다. 두 유형 모두 배점→세대원수→연령→오산전입일→추첨입니다.").selection());
    }

    @Test
    void summarizesFamilyTiersWithoutChangingDetailedConditions() {
        String reason = "1순위는 신생아가구 또는 법정 보호대상 한부모, 2순위는 미성년 자녀가 있는 신혼·예비신혼 또는 6세 이하 자녀의 일반 한부모, 3순위는 미성년 자녀가 없는 신혼·예비신혼, 4순위는 6세 이하 자녀의 혼인가구입니다. 태아를 포함하며 공통 요건은 별도로 확인합니다.";
        var result = overview(reason);
        assertEquals("신생아·법정 보호대상 한부모가 1순위입니다. 이후 자녀 유무·가구 유형에 따라 2~4순위를 정합니다.", result.selection());
        assertEquals(reason, result.detail());
    }

    @Test
    void neverPublishesConflictedCriterionOrUnsourcedText() {
        String reason = "동일순위에서 남원시 거주자를 우선합니다.";
        var pending = new Question("rank", "거주 순위", reason, source, null, null, null, true);
        var result = NoticeQualificationSummary.from(audience, List.of(pending));
        assertTrue(result.reviewRequired());
        assertNull(result.detail());
        assertFalse(result.selection().contains("남원시"));
        assertNull(NoticeQualificationSummary.from(audience,
                List.of(new Question("rank", "순위", reason, null))).detail());
    }

    @Test
    void documentProofNegativeRulesAndProceduralMentionsAreNotSelectionSummaries() {
        for (String reason : List.of("교육급여만으로는 이 공고의 1순위가 아닙니다.",
                "계약 시 추첨 동호 변경·명의 변경은 불가합니다.",
                "소득1순위 2순위\n가구원 3순위\n표입니다.", "선순위 확인 중입니다.",
                "기본 비율은 일반입주자 50%, 유공자·북한이탈·장애인1순위·시설퇴소 70%, 장애인2순위 100%입니다.")) {
            assertNull(overview(reason).detail());
        }
    }

    @Test
    void extractedSectionHeadingDoesNotHideCompleteRankingClauseOrFlattenTables() {
        String order = "동일순위에서 남원시 거주자를 우선합니다.";
        String reason = "원문 구간 공고문 1쪽입니다. 연결된 앞뒤 구간과 예외를 함께 확인하세요.\n\n5. 선정기준\n" + order;
        var result = overview(reason);
        assertEquals(order, result.selection());
        assertEquals(reason, result.detail());
        assertNull(overview("5. 선정기준\n1순위는 해당지역\n2순위는 그 외 지역입니다.").detail());
        assertNull(overview("5. 선정기준\n완화 대상에 한해\n" + order).detail());
    }

    @Test
    void viewPublishesOnlyReviewedNonClosedGuidanceWithoutMutatingStoredContent() {
        var json = JsonMapper.builder().build();
        var service = new NoticeService(null, null, json, org.mockito.Mockito.mock(NoticeAuditRepository.class));
        var entity = new NoticeEntity();
        entity.id = UUID.randomUUID();
        entity.title = "테스트 공고";
        entity.agency = "LH";
        entity.startDate = LocalDate.of(2026, 1, 1);
        entity.endDate = LocalDate.of(2026, 12, 31);
        entity.basicReview = entity.guideReview = "PASS";
        entity.content = json.writeValueAsString(new Content(audience, source, List.of(),
                List.of(new Question("rank", "순위", "동일순위에서 남원시 거주자를 우선합니다.", source)),
                List.of(), List.of()));
        String stored = entity.content;
        Instant now = Instant.parse("2026-11-11T00:00:00Z");
        assertNotNull(service.view(entity, false, now).qualificationSummary());
        assertNotNull(service.view(entity, false, now).documentSummary());
        assertNull(service.view(entity, true, now).qualificationSummary());
        assertNull(service.view(entity, true, now).documentSummary());
        entity.guideReview = "HOLD";
        assertNull(service.view(entity, false, now).qualificationSummary());
        assertNull(service.view(entity, false, now).documentSummary());
        entity.guideReview = "PASS";
        entity.endDate = LocalDate.of(2026, 1, 2);
        assertNull(service.view(entity, false, now).qualificationSummary());
        assertNull(service.view(entity, false, now).documentSummary());
        assertEquals(stored, entity.content);
    }
}
