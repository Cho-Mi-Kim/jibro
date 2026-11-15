package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;
import org.jibro.auth.AuthService;
import org.jibro.notice.NoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OneTimeCheckIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired PreparationService saved;
    @Autowired OneTimeCheckService once;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    UUID notice;
    String owner;
    final Evidence evidence = new Evidence("미저장 검증 근거", null, "2026-11-07");

    @BeforeEach void setup() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("미저장 테스트", email, "test-password-123");
        owner = auth.login(email, "test-password-123").user().id();
        notice = notices.insert(UUID.randomUUID().toString(), "미저장 검증 공고", "기관", "서울", "테스트",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content());
        notices.samplePublish(notice, "PASS");
    }

    Content content() {
        return new Content("검증용", evidence, List.of(), List.of(new Question("house", "조건 확인", "공식 기준 확인", evidence)),
                List.of(new DocumentTemplate("common", "공통 서류", null, "방법", "기관", null, evidence),
                        new DocumentTemplate("conditional", "조건별 서류", "house", "방법", "기관", null, evidence)),
                List.of(new ScheduleTemplate("apply", "신청 접수 마감", LocalDate.now().plusDays(30).toString(), evidence)));
    }

    String personalState() {
        return db.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(p) ORDER BY id), '[]'::jsonb)::text FROM preparation p", String.class)
                + db.queryForObject("SELECT count(*) FROM preparation_history", Long.class)
                + db.queryForObject("SELECT count(*) FROM app_user", Long.class);
    }

    @Test void anonymousCheckHasNoStoreAndDoesNotCreateAccountsOrPersonalRecords() throws Exception {
        String before = personalState();
        mvc.perform(post("/api/v1/notices/" + notice + "/check-once").with(csrf()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("version", 1, "answers", Map.of("house", "YES")))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.answers.house").value("YES"))
                .andExpect(jsonPath("$.documents[1].necessity").value("CONDITIONAL"))
                .andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.nextAction").doesNotExist());
        assertEquals(before, personalState());
    }

    @Test void sameRulesAsSavedFlowAndExistingAnswersNotesRemainUntouched() {
        var preparation = saved.start(owner, notice, 1);
        preparation = saved.answers(owner, UUID.fromString(preparation.id), preparation.revision, Map.of("house", "YES"));
        preparation = saved.document(owner, UUID.fromString(preparation.id), "conditional", preparation.revision, DocumentState.READY, "기존 메모");
        String before = personalState();
        var result = once.evaluate(notice, 1, Map.of("house", "NO"));
        assertEquals("NOT_APPLICABLE", result.documents().get(1).necessity);
        assertEquals(before, personalState());
        var matching = once.evaluate(notice, 1, Map.of("house", "YES"));
        assertEquals(preparation.answers, matching.answers());
        assertEquals(preparation.documents.get(1).necessity, matching.documents().get(1).necessity);
        assertEquals("", matching.documents().get(1).note);
        assertEquals(before, personalState());
    }

    @Test void requiresCsrfAndRejectsInvalidAnswersAndStaleVersionWithoutWrites() throws Exception {
        String path = "/api/v1/notices/" + notice + "/check-once";
        String before = personalState();
        mvc.perform(post(path).contentType("application/json").content("{\"version\":1,\"answers\":{}}"))
                .andExpect(status().isForbidden());
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("house", 10), Map.<String, Object>of("fake", "YES")))
            mvc.perform(post(path).with(csrf()).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("version", 1, "answers", invalid))))
                    .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(csrf()).contentType("application/json").content("{\"version\":2,\"answers\":{}}"))
                .andExpect(status().isConflict());
        assertEquals(before, personalState());
    }

    @Test void unreviewedPrivateAndClosedNoticesCannotBeEvaluated() throws Exception {
        int revision = notices.reviews().stream().filter(review -> review.id().equals(notice.toString())).findFirst().orElseThrow().revision();
        notices.review(notice, owner, revision, "PASS", "HOLD", "재검수");
        mvc.perform(post("/api/v1/notices/" + notice + "/check-once").with(csrf()).contentType("application/json").content("{\"version\":1,\"answers\":{}}"))
                .andExpect(status().isConflict());
        revision = notices.reviews().stream().filter(review -> review.id().equals(notice.toString())).findFirst().orElseThrow().revision();
        notices.review(notice, owner, revision, "HOLD", "HOLD", "비공개");
        mvc.perform(post("/api/v1/notices/" + notice + "/check-once").with(csrf()).contentType("application/json").content("{\"version\":1,\"answers\":{}}"))
                .andExpect(status().isNotFound());
        UUID closed = notices.insert(UUID.randomUUID().toString(), "마감된 검증 공고", "기관", "서울", "테스트",
                LocalDate.now().minusDays(3), LocalDate.now().minusDays(1), LocalDate.now().minusDays(3), content());
        notices.samplePublish(closed, "PASS");
        mvc.perform(post("/api/v1/notices/" + closed + "/check-once").with(csrf()).contentType("application/json").content("{\"version\":1,\"answers\":{}}"))
                .andExpect(status().isConflict());
    }
}
