package org.jibro.notice.source;

import static org.jibro.notice.source.NoticeSourceInformationModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.ExtractionStore;
import org.jibro.notice.AutomaticPublicationService;
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
class NoticeSourceInformationIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired AutomaticPublicationService publication;
    @Autowired ExtractionStore extraction;
    @Autowired NoticeSourceInformationService service;
    @Autowired NoticeSourceInformationRepository repository;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    UUID id;
    String url;

    @BeforeEach void setup() {
        url = "https://apply.lh.or.kr/detail.do?id=" + UUID.randomUUID();
        id = notices.importSource(new LhNoticeClient.Item("LH:" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)),
            "원문 확인 중 공고", "서울", "청년 매입임대", url, LocalDate.now().minusDays(1), "a".repeat(64)));
        assertTrue(publication.basic(id));
        entities.flush();
    }

    private Source goodSource() {
        return new Source("모집공고.pdf", url, "b".repeat(64), "EXTRACTED", "private-extraction-message", List.of(),
            List.of(new Part("공고문 2쪽", "신청 자격: 무주택자\n다만, 예외는 별도 기준을 확인해야 합니다."),
                new Part("공고문 4쪽", "제출 서류: 주민등록표등본\n해당하지 않는 사람은 제출하지 않습니다."),
                new Part("공고문 8쪽", "신청 기간: 2026.11.25 ~ 2026.11.26")));
    }

    private UUID completed(List<Source> sources, String state) {
        entities.flush();
        UUID extractionId = UUID.fromString(extraction.enqueue(id, true).id());
        var job = extraction.claim(id);
        // A terminal partial result is distinct from retry checkpoints, which remain unavailable.
        db.update("UPDATE notice_extraction SET retries_used=2 WHERE id=?", extractionId);
        extraction.finish(job, sources, state, "private-review-reason");
        return extractionId;
    }

    @Test void publicAnonymousGetUsesTheRawReaderContractAndCannotStartPreparation() throws Exception {
        completed(List.of(goodSource()), "READY");
        mvc.perform(get("/api/v1/notices/{id}/source-information", id))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.noticeId").value(id.toString()))
            .andExpect(jsonPath("$.state").value("AVAILABLE"))
            .andExpect(jsonPath("$.sections.length()").value(3))
            .andExpect(jsonPath("$.sections[0].area").value("CONDITION"))
            .andExpect(jsonPath("$.sections[0].title").value("공고문 2쪽"))
            .andExpect(jsonPath("$.sections[0].text").value(goodSource().context().get(0).text()))
            .andExpect(jsonPath("$.sections[0].evidence.url").value(url))
            .andExpect(jsonPath("$.sections[0].evidence.label").value("모집공고.pdf · 공고문 2쪽"))
            .andExpect(jsonPath("$.pendingAreas.length()").value(0))
            .andExpect(jsonPath("$.questions").doesNotExist())
            .andExpect(jsonPath("$.candidate").doesNotExist())
            .andExpect(jsonPath("$.canStart").doesNotExist())
            .andExpect(jsonPath("$.fingerprint").doesNotExist())
            .andExpect(jsonPath("$.reason").doesNotExist());
        assertEquals("REVIEWING", notices.notice(id).guideState());
        assertFalse(notices.notice(id).canStart());
        assertTrue(notices.notice(id).questions().isEmpty());
        assertTrue(notices.notice(id).documentTemplates().isEmpty());
    }

    @Test void terminalMixedResultShowsOnlyAuthenticSuccessfulParts() {
        var failed = new Source("실패.pdf", "https://apply.lh.or.kr/failed.pdf", "c".repeat(64), "FAILED", "실패 이유", List.of(),
            List.of(new Part("공고문 1쪽", "노출하면 안 되는 신청 자격")));
        var empty = new Source("빈자료.xlsx", "https://apply.lh.or.kr/empty.xlsx", "d".repeat(64), "EMPTY", "", List.of(), List.of());
        completed(List.of(goodSource(), failed, empty), "PARTIAL");
        var result = service.get(id);
        assertEquals(State.AVAILABLE, result.state());
        assertEquals(3, result.sections().size());
        assertTrue(result.sections().stream().allMatch(section -> section.evidence().url().equals(url)));
        assertTrue(result.sections().stream().noneMatch(section -> section.text().contains("노출하면 안")));
    }

    @Test void missingOrNewerQueuedJobDoesNotFallBackToAnOlderCompletedExtraction() {
        assertEquals(Information.pending(id), service.get(id));
        completed(List.of(goodSource()), "READY");
        assertEquals(State.AVAILABLE, service.get(id).state());
        extraction.enqueue(id, true);
        assertEquals(Information.pending(id), service.get(id));
    }

    @Test void sameRevisionWithChangedNoticeFingerprintCannotExposeOldSourceText() {
        completed(List.of(goodSource()), "READY");
        db.update("UPDATE notice SET source_fingerprint=? WHERE id=?", "e".repeat(64), id);
        assertEquals(Information.pending(id), service.get(id));
    }

    @Test void changingExtractionContentInvalidatesTheCapturedSnapshotToken() {
        UUID extractionId = completed(List.of(goodSource()), "READY");
        var before = repository.latest(id, repository.publishedRevision(id)).orElseThrow();
        assertTrue(repository.current(before));
        var changed = new Source("모집공고.pdf", url, "e".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("공고문 2쪽", "변경된 신청 자격 원문")));
        db.update("UPDATE notice_extraction SET sources=?::jsonb WHERE id=?", json.writeValueAsString(List.of(changed)), extractionId);
        assertFalse(repository.current(before));
        assertEquals("변경된 신청 자격 원문", service.get(id).sections().get(0).text());
    }

    @Test void publishedGuidanceRevisionCannotReuseAnOlderGuidanceExtraction() {
        completed(List.of(goodSource()), "READY");
        db.update("UPDATE notice SET guide_review='PASS', revision=revision+1 WHERE id=?", id);
        assertEquals(Information.pending(id), service.get(id));
    }

    @Test void privateAndLocalSampleNoticesAreExcludedEvenWithCompleteExtraction() throws Exception {
        completed(List.of(goodSource()), "READY");
        db.update("UPDATE notice SET basic_review='HOLD' WHERE id=?", id);
        mvc.perform(get("/api/v1/notices/{id}/source-information", id)).andExpect(status().isNotFound());
        db.update("UPDATE notice SET basic_review='PASS',source_key=? WHERE id=?", "local-sample-" + UUID.randomUUID(), id);
        mvc.perform(get("/api/v1/notices/{id}/source-information", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notices/{id}/source-information", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test void publicReadChangesNeitherNoticeExtractionAuditNorPrivatePreparationTables() {
        UUID extractionId = completed(List.of(goodSource()), "READY");
        String noticeBefore = db.queryForObject("SELECT row_to_json(n)::text FROM notice n WHERE id=?", String.class, id);
        String extractionBefore = db.queryForObject("SELECT row_to_json(e)::text FROM notice_extraction e WHERE id=?", String.class, extractionId);
        int audits = db.queryForObject("SELECT count(*) FROM automatic_notice_review", Integer.class);
        int analyses = db.queryForObject("SELECT count(*) FROM notice_local_analysis", Integer.class);
        int preparations = db.queryForObject("SELECT count(*) FROM preparation", Integer.class);
        var first = service.get(id);
        assertEquals(first, service.get(id));
        assertEquals(noticeBefore, db.queryForObject("SELECT row_to_json(n)::text FROM notice n WHERE id=?", String.class, id));
        assertEquals(extractionBefore, db.queryForObject("SELECT row_to_json(e)::text FROM notice_extraction e WHERE id=?", String.class, extractionId));
        assertEquals(audits, db.queryForObject("SELECT count(*) FROM automatic_notice_review", Integer.class));
        assertEquals(analyses, db.queryForObject("SELECT count(*) FROM notice_local_analysis", Integer.class));
        assertEquals(preparations, db.queryForObject("SELECT count(*) FROM preparation", Integer.class));
    }
}
