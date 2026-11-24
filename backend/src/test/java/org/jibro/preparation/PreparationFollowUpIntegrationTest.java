package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.UUID;

import org.jibro.auth.AuthService;
import org.jibro.global.common.ApiException;
import org.jibro.member.ExperienceService;
import org.jibro.notice.NoticeService;
import org.jibro.notice.SampleData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PreparationFollowUpIntegrationTest {
    @Autowired
    PreparationService preparations;
    @Autowired
    PreparationHistoryService history;
    @Autowired
    NoticeService notices;
    @Autowired
    ExperienceService experiences;
    @Autowired
    AuthService auth;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    String owner, access, otherAccess;
    UUID notice, id;
    Preparation initial;

    AuthService.Tokens user() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("후속 기록 테스트", email, "test-password-123");
        return auth.login(email, "test-password-123");
    }

    @BeforeEach
    void setup() {
        var u = user();
        owner = u.user().id();
        access = u.accessToken();
        otherAccess = user().accessToken();
        notice = notices.insert(UUID.randomUUID().toString(), "후속 기록 공고", "기관", "서울", "임대",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(20), LocalDate.now(), SampleData.content());
        notices.samplePublish(notice, "PASS");
        initial = preparations.start(owner, notice, 1);
        id = UUID.fromString(initial.id);
    }

    String endpoint() {
        return "/api/v1/preparations/" + id + "/follow-up";
    }

    String body(int revision) {
        return "{\"revision\":" + revision + ",\"result\":\"WAITLIST\",\"waitlistNumber\":12,\"documentsSubmitted\":true,\"contractCompleted\":false,\"movedIn\":false}";
    }

    @Test
    void savesPersonalResultsWithoutChangingAnswersSchedulesOrProgressAndPreservesArchive() throws Exception {
        String answers = json.writeValueAsString(initial.answers), schedules = json.writeValueAsString(initial.schedules);
        mvc.perform(put(endpoint()).header("Authorization", "Bearer " + access).contentType("application/json").content(body(initial.revision)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.followUp.result").value("WAITLIST"))
                .andExpect(jsonPath("$.followUp.waitlistNumber").value(12));
        var saved = preparations.get(owner, id);
        assertEquals(initial.progress, saved.progress);
        assertEquals(answers, json.writeValueAsString(saved.answers));
        assertEquals(schedules, json.writeValueAsString(saved.schedules));
        var record = saved.followUp;
        experiences.archive(UUID.fromString(owner), id, "보관 메모", saved.revision);
        assertEquals(record, experiences.list(UUID.fromString(owner)).get(0).followUp());
        assertThrows(ApiException.class, () -> preparations.followUp(owner, id, saved.revision + 1, record));
        experiences.restore(UUID.fromString(owner), id);
        assertEquals(record, preparations.get(owner, id).followUp);
    }

    @Test
    void noticeChangesKeepPersonalResultsAndTheirHistoricalVersion() {
        var waiting = new FollowUp(FollowUpResult.WAITLIST, 7, true, false, false);
        var saved = preparations.followUp(owner, id, initial.revision, waiting);
        var changed = notices.updateContent(notice, 1, SampleData.content());
        var selected = new FollowUp(FollowUpResult.SELECTED, null, true, true, false);
        saved = preparations.followUp(owner, id, saved.revision, selected);
        assertNotEquals("AVAILABLE", saved.guideState);
        notices.review(notice, owner, changed.revision(), "PASS", "PASS", "새 안내");
        saved = preparations.refresh(owner, id, saved.revision, changed.notice().version());
        assertEquals(selected, saved.followUp);
        assertEquals(selected, history.get(owner, id, 1).preparation().followUp);
        var moved = new FollowUp(FollowUpResult.SELECTED, null, true, true, true);
        preparations.followUp(owner, id, saved.revision, moved);
        assertEquals(moved, preparations.get(owner, id).followUp);
        assertEquals(selected, history.get(owner, id, 1).preparation().followUp);
    }

    @Test
    void enforcesOwnershipRevisionAndInputBoundaries() throws Exception {
        mvc.perform(put(endpoint()).contentType("application/json").content(body(1))).andExpect(status().isForbidden());
        mvc.perform(put(endpoint()).header("Authorization", "Bearer " + otherAccess).contentType("application/json").content(body(1))).andExpect(status().isNotFound());
        for (String invalid : new String[]{body(1).replace("12", "0"), body(1).replace("12", "1000000"), body(1).replace("WAITLIST", "SELECTED"), body(1).replace("WAITLIST", "UNKNOWN")})
            mvc.perform(put(endpoint()).header("Authorization", "Bearer " + access).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        assertNull(preparations.get(owner, id).followUp);
        mvc.perform(put(endpoint()).header("Authorization", "Bearer " + access).contentType("application/json").content(body(1))).andExpect(status().isOk());
        mvc.perform(put(endpoint()).header("Authorization", "Bearer " + access).contentType("application/json").content(body(1))).andExpect(status().isConflict());
        assertEquals(12, preparations.get(owner, id).followUp.waitlistNumber());
    }
}
