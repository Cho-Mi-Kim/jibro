package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GuidanceIssuesTest {
    private final Evidence source = new Evidence("공식 원문 비교 위치", "https://apply.lh.or.kr/", "2026-11-01");

    private Content content(List<ReviewIssue> issues) {
        return new Content("확인된 안내", source, List.of(),
                List.of(new Question("safe", "확인된 조건", "확인된 기준", source),
                        new Question("conflict", "충돌 조건", "기관 확인 필요", source)),
                List.of(new DocumentTemplate("common", "공통 서류", null, "방법", "기관", null, source),
                        new DocumentTemplate("dependent", "조건 종속 서류", "conflict", "방법", "기관", null, source)),
                List.of(new ScheduleTemplate("end", "신청 접수 마감", "2026-12-31", source)), issues);
    }

    private ReviewIssue conditionIssue(List<String> questionIds, List<String> documentIds) {
        return new ReviewIssue("issue", "CONDITION", "원문 간 조건 차이", "기관의 확인이 필요합니다.",
                questionIds, documentIds, List.of(source));
    }

    @Test
    void projectsOnlyAffectedQuestionsAndTheirDependentDocuments() {
        var content = content(List.of(conditionIssue(List.of("conflict"), List.of())));
        assertDoesNotThrow(() -> GuidanceIssues.validate(content));
        assertFalse(content.questions().get(0).reviewRequired());
        assertTrue(content.questions().get(1).reviewRequired());
        assertTrue(content.questions().get(1).needsFollowUp("YES"));
        assertTrue(content.questions().get(1).needsFollowUp("NO"));
        assertEquals(List.of("dependent"), content.reviewIssues().get(0).documentIds());
        assertFalse(GuidanceIssues.documentRequiresReview(content.reviewIssues(), "common", null));
        assertTrue(GuidanceIssues.documentRequiresReview(content.reviewIssues(), "dependent", "conflict"));
    }

    @Test
    void dependencyProjectionDoesNotHideOrphanOrDuplicateExplicitReferences() {
        for (var issue : List.of(
                conditionIssue(List.of("missing"), List.of()),
                conditionIssue(List.of("conflict"), List.of("missing")),
                conditionIssue(List.of("conflict"), List.of("dependent", "dependent")))) {
            var content = content(List.of(issue));
            assertThrows(IllegalArgumentException.class, () -> GuidanceIssues.validate(content));
        }
    }

    @Test
    void rejectsStandaloneQuestionFlagsAndMissingScopeOrEvidence() {
        var base = content(List.of());
        var flagged = new Question("safe", "조건", "기준", source, null, "SINGLE", null, true);
        var withoutIssue = new Content(base.summary(), source, List.of(), List.of(flagged), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> GuidanceIssues.validate(withoutIssue));
        for (var issue : List.of(
                conditionIssue(List.of(), List.of()),
                new ReviewIssue("issue", "DOCUMENT", "서류 차이", "공식 확인 필요", List.of(), List.of(), List.of(source)),
                new ReviewIssue("issue", "SCHEDULE", "일정 차이", "공식 확인 필요", List.of(), List.of(), List.of()))) {
            var content = content(List.of(issue));
            assertThrows(IllegalArgumentException.class, () -> GuidanceIssues.validate(content));
        }
    }

    @Test
    void scheduleIssueMayRemainSeparateFromQuestionsAndLegacyJsonKeepsDefaultBehavior() throws Exception {
        var content = content(List.of(new ReviewIssue("schedule", "SCHEDULE", "결과 발표일 확인 필요",
                "서로 다른 발표일이 있어 확정 일정에서 제외했습니다.", List.of(), List.of(), List.of(source))));
        assertDoesNotThrow(() -> GuidanceIssues.validate(content));
        assertTrue(content.questions().stream().noneMatch(Question::reviewRequired));
        var json = new ObjectMapper();
        var oldContent = json.readValue("{\"summary\":\"이전 안내\",\"questions\":[],\"documents\":[],\"schedules\":[]}", Content.class);
        var oldQuestion = json.readValue("{\"id\":\"legacy\",\"title\":\"조건\"}", Question.class);
        assertEquals(List.of(), oldContent.reviewIssues());
        assertFalse(oldQuestion.reviewRequired());
        assertFalse(oldQuestion.needsFollowUp("YES"));
        assertEquals(content, json.readValue(json.writeValueAsString(content), Content.class));
    }
}
