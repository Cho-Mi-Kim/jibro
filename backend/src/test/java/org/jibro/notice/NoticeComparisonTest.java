package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

class NoticeComparisonTest {
    private final NoticeRepository repository = mock(NoticeRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final NoticeService notices = new NoticeService(repository, null, json, null);
    private final NoticeComparisonService comparison = new NoticeComparisonService(notices);
    private NoticeEntity entity;

    @BeforeEach
    void setup() {
        entity = new NoticeEntity();
        entity.id = UUID.randomUUID();
        entity.title = "비교용 공고";
        entity.agency = "LH";
        entity.region = "서울";
        entity.supportType = "청년 임대";
        entity.basicReview = "PASS";
        entity.guideReview = "PASS";
        entity.startDate = LocalDate.now().minusDays(1);
        entity.endDate = LocalDate.now().plusDays(7);
        entity.content = json.writeValueAsString(SampleData.content());
        when(repository.findById(entity.id)).thenReturn(Optional.of(entity));
    }

    @Test
    void publicReviewedComparisonContainsCommonConditionalAndIssueCriteriaWithoutCreatingPreparation() {
        var response = comparison.comparison(entity.id);
        assertEquals(2, response.notice().questions().size());
        var common = response.documentRequirements().get(0);
        var conditional = response.documentRequirements().get(1);
        assertNull(common.conditionQuestionId());
        assertEquals("house", conditional.conditionQuestionId());
        assertEquals("공고의 발급 기준과 제출 방법 확인", common.method());
        assertNotNull(conditional.evidence().checkedAt());
        assertEquals(SampleData.content().schedules(), response.notice().schedules());
        verify(repository, times(1)).findById(entity.id);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({"HOLD,false,REVIEWING", "UNSUPPORTED,false,UNSUPPORTED", "PASS,true,CHANGE_REVIEW"})
    void unavailableGuidanceNeverExposesQuestionsOrDocumentRulesButKeepsPublicSchedules(String review, boolean changed, String state) {
        entity.guideReview = review;
        entity.changeReview = changed;
        var response = comparison.comparison(entity.id);
        assertEquals(state, response.notice().guideState());
        assertTrue(response.notice().questions().isEmpty());
        assertTrue(response.notice().documents().isEmpty());
        assertTrue(response.documentRequirements().isEmpty());
        assertNull(response.notice().guideVersion());
        assertFalse(response.notice().schedules().isEmpty());
        assertEquals(SampleData.content().schedules(), response.notice().schedules());
    }

    @Test
    void unpublishedAndMissingNoticesRemainNotFound() {
        entity.basicReview = "HOLD";
        assertEquals(404, assertThrows(ApiException.class, () -> comparison.comparison(entity.id)).status);
        assertEquals(404, assertThrows(ApiException.class, () -> comparison.comparison(UUID.randomUUID())).status);
    }

    @Test
    void closedReviewedNoticeStillHasPublicComparisonWithoutAllowingStart() {
        entity.endDate = LocalDate.now().minusDays(1);
        var response = comparison.comparison(entity.id);
        assertFalse(response.notice().canStart());
        assertFalse(response.documentRequirements().isEmpty());
    }
}
