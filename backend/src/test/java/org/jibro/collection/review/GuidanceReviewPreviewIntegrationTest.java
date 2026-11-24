package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.ExtractionStore;
import org.jibro.notice.AutomaticPublicationService;
import org.jibro.notice.AutomaticReviewRepository;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.NoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GuidanceReviewPreviewIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    AutomaticPublicationService publication;
    @Autowired
    ExtractionStore extractions;
    @Autowired
    JdbcTemplate db;
    @Autowired
    EntityManager entities;
    @Autowired
    ObjectMapper json;
    @Autowired
    MockMvc mvc;
    @MockitoSpyBean
    AutomaticReviewRepository reviews;
    UUID notice;
    UUID extraction;
    Fixture fixture;

    record Fixture(Draft draft, Content original) {
    }

    record State(String noticeRow, List<Map<String, Object>> published, int manualReviews,
                 int automaticReviews, int localAnalyses, int apiAttempts, int apiCaches) {
    }

    @BeforeEach
    void setup() throws java.io.IOException {
        try (var stream = getClass().getResourceAsStream("/review/iksan-waiver-20261104.json")) {
            assertNotNull(stream);
            fixture = json.readValue(stream, Fixture.class);
        }
        String url = fixture.original().evidence().url();
        notice = notices.importSource(new LhNoticeClient.Item("LH:" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)),
                "익산한스빌 국민임대주택 예비입주자 자격완화 모집 공고", "익산", "국민임대", url,
                LocalDate.now().minusDays(1), "a".repeat(64)));
        entities.flush();
        db.update("UPDATE notice SET content=?::jsonb WHERE id=?", json.writeValueAsString(fixture.original()), notice);
        entities.clear();
        assertTrue(publication.basic(notice));
        entities.flush();
        extraction = UUID.fromString(extractions.enqueue(notice, true).id());
        var job = extractions.claim(notice);
        assertNotNull(job);
        assertEquals(extraction, job.id());
        extractions.finish(job, fixture.draft().sources(), "READY", "공식 원문 회귀 검증 자료");
    }

    @Test
    void endpointRequiresAdministratorEvenWhenNoticeBasicsArePublic() throws Exception {
        State before = state();
        mvc.perform(get(path(notice, extraction))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(notice, extraction)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        assertEquals(before, state());
    }

    @Test
    void administratorCanInspectActualIksanCandidateWithoutPublishingOrConsumingApiBudget() throws Exception {
        State before = state();
        var response = mvc.perform(get(path(notice, extraction)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value(NationalRentalGuidancePreview.PROFILE))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.candidate.questions.length()").value(14))
                .andExpect(jsonPath("$.candidate.documents.length()").value(19))
                .andExpect(jsonPath("$.startDate").value("2026-11-25"))
                .andExpect(jsonPath("$.endDate").value("2026-11-26"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var result = json.readValue(response, GuidanceReviewCompiler.Assessment.class);
        Set<String> findingCodes = result.findings().stream().map(GuidanceFinding::code).collect(Collectors.toSet());
        assertEquals(Set.of("FORM_PUBLICATION_DATE_MISMATCH", "WAIVED_INCOME_DOCUMENT_SCOPE_UNRESOLVED",
                "FIRST_COME_SCORING_DOCUMENT_SCOPE_UNRESOLVED"), findingCodes);
        assertTrue(result.findings().stream().allMatch(f -> f.evidence().url().startsWith("https://apply.lh.or.kr/")));
        mvc.perform(get(path(notice, extraction)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
        assertEquals(before, state());
        assertEquals("HOLD", db.queryForObject("SELECT guide_review FROM notice WHERE id=?", String.class, notice));
        assertFalse(notices.notice(notice).canStart());
    }

    @Test
    void crossNoticeExtractionAndUnknownExtractionReturnNotFound() throws Exception {
        mvc.perform(get(path(UUID.randomUUID(), extraction)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
        mvc.perform(get(path(notice, UUID.randomUUID())).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void sourceRevisionChangeRejectsPreviouslyFinishedExtraction() throws Exception {
        int revision = db.queryForObject("SELECT revision FROM notice WHERE id=?", Integer.class, notice);
        notices.updateContent(notice, revision, notices.content(notice));
        entities.flush();
        State afterChange = state();
        mvc.perform(get(path(notice, extraction)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isConflict());
        assertEquals(afterChange, state());
    }

    @Test
    void newerQueuedExtractionRejectsOldPreviewAndUnfinishedNewPreview() throws Exception {
        UUID queued = UUID.fromString(extractions.enqueue(notice, true).id());
        assertNotEquals(extraction, queued);
        // Both jobs are created within one transaction; make their ordering explicit rather than rely on UUID order.
        db.update("UPDATE notice_extraction SET created_at=created_at+interval '1 second' WHERE id=?", queued);
        State before = state();
        mvc.perform(get(path(notice, extraction)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isConflict());
        mvc.perform(get(path(notice, queued)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isConflict());
        assertEquals(before, state());
    }

    @Test
    void fileRetryBetweenSnapshotAndValidationRejectsStalePreview() throws Exception {
        Source previous = fixture.draft().sources().get(0);
        var changed = new Source(previous.label(), previous.url(), "f".repeat(64), previous.status(), previous.message(),
                previous.excerpts(), previous.context());
        doAnswer(invocation -> {
            var captured = invocation.callRealMethod();
            extractions.updateFile(notice, extraction, changed);
            return captured;
        }).when(reviews).snapshot(extraction);

        mvc.perform(get(path(notice, extraction)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isConflict());
        assertEquals("HOLD", db.queryForObject("SELECT guide_review FROM notice WHERE id=?", String.class, notice));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM automatic_notice_review WHERE extraction_id=?", Integer.class, extraction));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM notice_local_analysis WHERE extraction_id=?", Integer.class, extraction));
    }

    private State state() {
        entities.flush();
        return new State(db.queryForObject("SELECT to_jsonb(n)::text FROM notice n WHERE id=?", String.class, notice),
                db.queryForList("SELECT version,revision,facts::text FROM published_notice_version WHERE notice_id=? ORDER BY version,revision", notice),
                count("SELECT count(*) FROM review_audit WHERE notice_id=?", notice),
                count("SELECT count(*) FROM automatic_notice_review WHERE notice_id=?", notice),
                count("SELECT count(*) FROM notice_local_analysis WHERE extraction_id=?", extraction),
                count("SELECT count(*) FROM gemini_review_attempt"), count("SELECT count(*) FROM gemini_review_cache"));
    }

    private int count(String sql, Object... arguments) {
        return db.queryForObject(sql, Integer.class, arguments);
    }

    private static String path(UUID noticeId, UUID extractionId) {
        return "/api/v1/admin/reviews/" + noticeId + "/extractions/" + extractionId + "/guidance-preview";
    }
}
