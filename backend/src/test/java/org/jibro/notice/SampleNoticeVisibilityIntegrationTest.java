package org.jibro.notice;

import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.jibro.auth.AuthService;
import org.jibro.member.AlertRepository;
import org.jibro.member.SavedNoticeRepository;
import org.jibro.preparation.PreparationRepository;
import org.jibro.preparation.PreparationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SampleNoticeVisibilityIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired GuideService guides;
    @Autowired GuideRepository guideRepository;
    @Autowired PreparationService preparationService;
    @Autowired PreparationRepository preparations;
    @Autowired SavedNoticeRepository saved;
    @Autowired AlertRepository alerts;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;
    @Autowired MockMvc mvc;

    UUID notice(String key, String title) {
        UUID id = notices.insert(key, title, "LH", "서울", "임대", LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(30), LocalDate.now(), SampleData.content());
        notices.samplePublish(id, "PASS");
        entities.flush();
        return id;
    }

    @Test void samplesCannotReappearThroughIncludeSamplesOrDirectLinks() throws Exception {
        String title = "sample-visibility-" + UUID.randomUUID();
        UUID sample = notice("local-sample-" + UUID.randomUUID(), title);
        UUID real = notice("LH:" + UUID.randomUUID(), title);
        mvc.perform(get("/api/v1/notices").param("keyword", title).param("includeSamples", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(real.toString()));
        mvc.perform(get("/api/v1/notices/" + sample)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notices/" + sample + "/comparison")).andExpect(status().isNotFound());
        assertFalse(notices.reviews().stream().anyMatch(item -> item.id().equals(sample.toString())));
        assertEquals(false, guides.meta().get("sampleData"));
        var page = guides.guides("", title, 0);
        assertTrue(page.items().stream().anyMatch(item -> item.id().startsWith(real.toString())));
        assertFalse(page.items().stream().anyMatch(item -> item.id().startsWith(sample.toString()) || item.id().equals("sample-process")));
        mvc.perform(get("/api/v1/guides").param("keyword", title))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test void trialRecommendationUsesRealGuidanceAndOldSampleRecordsArePreservedButHidden() {
        UUID old = notice("LH:" + UUID.randomUUID(), "이전 준비");
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("테스트", email, "test-password-123");
        UUID owner = UUID.fromString(auth.login(email, "test-password-123").user().id());
        var preparation = preparationService.start(owner.toString(), old, 1);
        saved.save(owner, old, 1);
        entities.flush();
        db.update("UPDATE notice SET source_key=? WHERE id=?", "local-sample-" + UUID.randomUUID(), old);
        entities.clear();
        UUID real = notice("LH:" + UUID.randomUUID(), "실제 공고");

        assertEquals(real, guideRepository.findTrialNotice().orElseThrow());
        assertTrue(preparations.findActive(owner).isEmpty());
        assertTrue(preparations.findActive(owner, UUID.fromString(preparation.id)).isEmpty());
        assertTrue(saved.findOwned(owner).isEmpty());
        assertTrue(saved.lockPublicVersion(old).isEmpty());
        assertTrue(alerts.findTargets(owner, true).isEmpty());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM preparation WHERE id=?", Integer.class, UUID.fromString(preparation.id)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM saved_notice WHERE user_id=? AND notice_id=?", Integer.class, owner, old));
    }
}
