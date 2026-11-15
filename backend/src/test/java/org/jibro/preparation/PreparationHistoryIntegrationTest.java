package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jibro.auth.AuthService;
import org.jibro.notice.NoticeService;
import org.jibro.notice.SampleData;
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
class PreparationHistoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired NoticeService notices;
    @Autowired PreparationService preparations;
    @Autowired PreparationHistoryService history;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    String owner, access, otherAccess;
    UUID notice, preparationId;
    Preparation current;
    int noticeRevision;

    @BeforeEach
    void setup() {
        var credentials = createUser();
        owner = credentials.user().id();
        access = credentials.accessToken();
        otherAccess = createUser().accessToken();
        notice = notices.insert(UUID.randomUUID().toString(), "이전 안내 테스트", "테스트 기관", "서울", "공공임대",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content("이전 선택지"));
        notices.samplePublish(notice, "PASS");
        noticeRevision = 1;
        current = preparations.start(owner, notice, 1);
        preparationId = UUID.fromString(current.id);
    }

    private AuthService.Tokens createUser() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("기록 테스트", email, "test-password-123");
        return auth.login(email, "test-password-123");
    }

    private Content content(String yesLabel) {
        var base = SampleData.content();
        var questions = base.questions().stream().map(q -> new Question(q.id(), q.title(), q.reason(), q.evidence(),
                List.of(new AnswerOption("YES", yesLabel), new AnswerOption("NO", "해당 없음"),
                        new AnswerOption("UNKNOWN", "모름", true)))).toList();
        return new Content(base.summary(), base.evidence(), base.attachments(), questions, base.documents(), base.schedules());
    }

    private String endpoint() {
        return "/api/v1/preparations/" + preparationId + "/history";
    }

    private void refresh() {
        var changed = notices.updateContent(notice, noticeRevision, content("바뀐 선택지"));
        noticeRevision = notices.review(notice, owner, changed.revision(), "PASS", "PASS", "새 안내 테스트").revision();
        current = preparations.refresh(owner, preparationId, current.revision, changed.notice().version());
    }

    @Test
    void requiresAuthenticationAndDoesNotExposeWhetherAnotherOwnersHistoryExists() throws Exception {
        refresh();
        mvc.perform(get(endpoint())).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint() + "/1")).andExpect(status().isUnauthorized());
        String foreignList = mvc.perform(get(endpoint()).header("Authorization", "Bearer " + otherAccess))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missingList = mvc.perform(get("/api/v1/preparations/" + UUID.randomUUID() + "/history")
                        .header("Authorization", "Bearer " + otherAccess))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(missingList).get("code"), json.readTree(foreignList).get("code"));
        assertEquals(json.readTree(missingList).get("message"), json.readTree(foreignList).get("message"));
        mvc.perform(get(endpoint() + "/1").header("Authorization", "Bearer " + otherAccess))
                .andExpect(status().isNotFound());
        mvc.perform(get(endpoint() + "/99").header("Authorization", "Bearer " + access))
                .andExpect(status().isNotFound());
    }

    @Test
    void initialPreparationHasNoInventedHistory() throws Exception {
        mvc.perform(get(endpoint()).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.archived").value(false));
        mvc.perform(get(endpoint() + "/1").header("Authorization", "Bearer " + access))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsTheImmutableSavedAnswersOptionsNotesStatesAndSchedulesWithoutReassessment() throws Exception {
        current = preparations.answers(owner, preparationId, current.revision, Map.of("residence", "YES", "house", "NO"));
        current = preparations.document(owner, preparationId, current.documents.get(0).id, current.revision, DocumentState.READY, "당시 준비 메모");
        current = preparations.schedule(owner, preparationId, current.schedules.get(0).id, current.revision, true);
        var original = json.readValue(db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class,
                preparationId), Snapshot.class).preparation();
        refresh();
        current = preparations.answers(owner, preparationId, current.revision, Map.of("residence", "NO"));
        current = preparations.document(owner, preparationId, current.documents.get(0).id, current.revision, DocumentState.IN_PROGRESS, "새 준비 메모");
        notices.samplePublish(notice, "HOLD");
        String currentBefore = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, preparationId);
        String historicalBefore = db.queryForObject("SELECT snapshot::text FROM preparation_history WHERE preparation_id=?", String.class, preparationId);

        var response = mvc.perform(get(endpoint() + "/1").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.savedAt").isNotEmpty())
                .andExpect(jsonPath("$.preparation.questions[0].options[0].label").value("이전 선택지"))
                .andExpect(jsonPath("$.preparation.documents[0].state").value("READY"))
                .andExpect(jsonPath("$.preparation.documents[0].note").value("당시 준비 메모"))
                .andExpect(jsonPath("$.preparation.schedules[0].recorded").value(true))
                .andReturn().getResponse().getContentAsString();
        assertEquals(json.valueToTree(original), json.readTree(response).get("preparation"));
        assertEquals(currentBefore, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, preparationId));
        assertEquals(historicalBefore, db.queryForObject("SELECT snapshot::text FROM preparation_history WHERE preparation_id=?", String.class, preparationId));
    }

    @Test
    void ownersCanReadVersionsNewestFirstAfterArchivingThePreparation() throws Exception {
        refresh();
        refresh();
        db.update("UPDATE preparation SET archived_at=now() WHERE id=?", preparationId);
        mvc.perform(get(endpoint()).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.entries[0].version").value(2))
                .andExpect(jsonPath("$.entries[1].version").value(1));
        assertEquals(1, history.get(owner, preparationId, 1).version());
        mvc.perform(get(endpoint() + "/2").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        mvc.perform(get(endpoint() + "/2").header("Authorization", "Bearer " + otherAccess))
                .andExpect(status().isNotFound());
    }
}
