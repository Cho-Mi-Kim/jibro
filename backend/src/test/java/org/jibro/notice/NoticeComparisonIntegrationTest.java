package org.jibro.notice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NoticeComparisonIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired MockMvc mvc;

    private UUID notice(String guide) {
        var day = LocalDate.now();
        var id = notices.insert("comparison-test-" + UUID.randomUUID(), "공개 비교 공고", "LH", "서울", "청년 임대",
                day.minusDays(1), day.plusDays(5), day.minusDays(2), SampleData.content());
        notices.samplePublish(id, guide);
        return id;
    }

    @Test
    void anonymousComparisonReadsReviewedRulesAndSourcesThroughThePublicEndpoint() throws Exception {
        var id = notice("PASS");
        mvc.perform(get("/api/v1/notices/{id}/comparison", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice.id").value(id.toString()))
                .andExpect(jsonPath("$.notice.guideState").value("AVAILABLE"))
                .andExpect(jsonPath("$.documentRequirements.length()").value(2))
                .andExpect(jsonPath("$.documentRequirements[1].conditionQuestionId").value("house"))
                .andExpect(jsonPath("$.documentRequirements[0].method").value("공고의 발급 기준과 제출 방법 확인"))
                .andExpect(jsonPath("$.documentRequirements[0].evidence.checkedAt").isNotEmpty());
    }

    @Test
    void anonymousComparisonCannotReadHeldOrChangedGuidanceButCanReadPublicSchedules() throws Exception {
        for (String state : new String[] {"HOLD", "UNSUPPORTED", "CHANGE_REVIEW"}) {
            var id = notice(state.equals("CHANGE_REVIEW") ? "PASS" : state);
            if (state.equals("CHANGE_REVIEW")) notices.entity(id).changeReview = true;
            mvc.perform(get("/api/v1/notices/{id}/comparison", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.notice.questions").isEmpty())
                    .andExpect(jsonPath("$.notice.documents").isEmpty())
                    .andExpect(jsonPath("$.documentRequirements").isEmpty())
                    .andExpect(jsonPath("$.notice.schedules.length()").value(1));
        }
    }

    @Test
    void unpublishedComparisonIsNotFoundEvenWhenGuidancePassed() throws Exception {
        var id = notice("PASS");
        notices.entity(id).basicReview = "HOLD";
        mvc.perform(get("/api/v1/notices/{id}/comparison", id)).andExpect(status().isNotFound());
    }
}
