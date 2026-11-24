package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import org.jibro.auth.AuthService;
import org.jibro.notice.NoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TypedAnswersIntegrationTest {
    @Autowired
    AuthService auth;
    @Autowired
    NoticeService notices;
    @Autowired
    PreparationService preparations;
    @Autowired
    PreparationHistoryService history;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;
    @Autowired
    MockMvc mvc;
    String owner, token;
    UUID notice, id;
    Preparation current;
    final Evidence evidence = new Evidence("입력 계약 테스트 근거", null, "2026-11-06");

    @BeforeEach
    void setup() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("입력 테스트", email, "test-password-123");
        var user = auth.login(email, "test-password-123");
        owner = user.user().id();
        token = user.accessToken();
        notice = notices.insert(UUID.randomUUID().toString(), "입력 계약 검증용 공고", "테스트 기관", "서울", "테스트",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content(10));
        notices.samplePublish(notice, "PASS");
        current = preparations.start(owner, notice, 1);
        id = UUID.fromString(current.id);
    }

    Content content(int max) {
        return new Content("공식 공고 데이터를 변경하지 않는 테스트 자료", evidence, List.of(), List.of(
                new Question("legacy", "이전 단일 선택", "이유", evidence),
                new Question("amount", "수량 테스트", "이유", evidence, null, "NUMBER",
                        new InputConstraints(BigDecimal.ZERO, BigDecimal.valueOf(max), new BigDecimal("0.5"), null, null, null, "개")),
                new Question("date", "날짜 테스트", "이유", evidence, null, "DATE",
                        new InputConstraints(null, null, null, "2024-01-01", "2024-12-31", null, null)),
                new Question("choices", "여러 선택 테스트", "이유", evidence,
                        List.of(new AnswerOption("A", "첫째"), new AnswerOption("B", "둘째"), new AnswerOption("UNKNOWN", "모름")),
                        "MULTIPLE", new InputConstraints(null, null, null, null, null, 2, null))),
                List.of(new DocumentTemplate("conditional", "조건부 서류 테스트", "choices", "발급 안내", "테스트 기관", null, evidence, List.of("B"))),
                List.of(new ScheduleTemplate("apply", "테스트 접수 마감", LocalDate.now().plusDays(30).toString(), evidence)));
    }

    Map<String, Object> answers() {
        return Map.of("legacy", "YES", "amount", 0, "date", "2024-02-29", "choices", List.of("B", "A"));
    }

    @Test
    void savesTypedJsonAndMultiChoiceDocumentsUseAnyMatchingSelection() throws Exception {
        mvc.perform(put("/api/v1/preparations/" + id + "/answers").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("revision", current.revision, "answers", answers()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answers.amount").value(0))
                .andExpect(jsonPath("$.answers.date").value("2024-02-29"))
                .andExpect(jsonPath("$.answers.choices[0]").value("A"))
                .andExpect(jsonPath("$.answers.choices[1]").value("B"))
                .andExpect(jsonPath("$.documents[0].necessity").value("CONDITIONAL"));
        current = preparations.get(owner, id);
        current = preparations.document(owner, id, "conditional", current.revision, DocumentState.READY, "메모 보존");
        current = preparations.answers(owner, id, current.revision, Map.of("choices", "UNKNOWN"));
        assertEquals("UNKNOWN", current.documents.get(0).necessity);
        assertEquals(DocumentState.RECHECK, current.documents.get(0).state);
        assertEquals("메모 보존", current.documents.get(0).note);
        current = preparations.answers(owner, id, current.revision, Map.of("choices", List.of("A")));
        assertEquals("NOT_APPLICABLE", current.documents.get(0).necessity);
    }

    @Test
    void rejectedInputDoesNotReplaceAnswersOrIncrementRevision() throws Exception {
        current = preparations.answers(owner, id, current.revision, answers());
        String before = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, id);
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("amount", "2"), Map.<String, Object>of("amount", 10.5),
                Map.<String, Object>of("date", "2024-02-30"), Map.<String, Object>of("choices", List.of("A", "UNKNOWN")),
                Map.<String, Object>of("choices", List.of("A", "A")))) {
            mvc.perform(put("/api/v1/preparations/" + id + "/answers").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("revision", current.revision, "answers", invalid))))
                    .andExpect(status().isBadRequest());
            assertEquals(before, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, id));
        }
    }

    @Test
    void constraintChangesClearOnlyChangedAnswersAndPreserveHistoricalTypedSnapshot() {
        current = preparations.answers(owner, id, current.revision, answers());
        var changed = notices.updateContent(notice, 1, content(20));
        notices.review(notice, owner, changed.revision(), "PASS", "PASS", "입력 계약 검수");
        current = preparations.refresh(owner, id, current.revision, changed.notice().version());
        assertFalse(current.answers.containsKey("amount"));
        assertEquals("YES", current.answers.get("legacy"));
        assertEquals("2024-02-29", current.answers.get("date"));
        assertEquals(List.of("A", "B"), current.answers.get("choices"));
        var saved = history.get(owner, id, 1).preparation();
        assertEquals(0, ((Number) saved.answers.get("amount")).intValue());
        assertEquals(List.of("A", "B"), saved.answers.get("choices"));
        assertEquals(0, saved.questions.get(1).constraints().max().compareTo(BigDecimal.TEN));
    }

    @Test
    void numericAndDateInputsCannotSilentlyBecomeEligibilityPredicates() {
        var original = content(10);
        for (String question : List.of("amount", "date")) {
            var invalid = new Content(original.summary(), evidence, List.of(), original.questions(),
                    List.of(new DocumentTemplate("rule", "서류", question, "안내", "기관", null, evidence, List.of("YES"))), List.of());
            assertThrows(IllegalArgumentException.class, () -> notices.validateContent(invalid));
        }
        assertDoesNotThrow(() -> notices.validateContent(original));
    }
}
