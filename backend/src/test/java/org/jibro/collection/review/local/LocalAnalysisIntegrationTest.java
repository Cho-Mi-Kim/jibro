package org.jibro.collection.review.local;

import java.time.LocalDate;
import java.util.*;

import jakarta.persistence.EntityManager;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.extraction.ExtractionStore;
import org.jibro.global.common.ApiException;
import org.jibro.notice.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.jibro.collection.review.local.LocalRuleBook.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LocalAnalysisIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    AutomaticPublicationService publication;
    @Autowired
    AutomaticReviewRepository reviews;
    @Autowired
    ExtractionStore extractions;
    @Autowired
    LocalAnalysisService service;
    @Autowired
    LocalAnalysisRepository repository;
    @Autowired
    JdbcTemplate db;
    @Autowired
    EntityManager entities;
    @Autowired
    MockMvc mvc;
    UUID notice, extraction;
    String url;
    private static final String POLICY = LocalAnalysisService.POLICY;
    private static final String TEXT = "신청자는 무주택자이어야 합니다. 다만 예외 대상은 별도 조건을 확인합니다.";

    @BeforeEach
    void setup() {
        url = "https://apply.lh.or.kr/detail.do?id=" + UUID.randomUUID();
        notice = notices.importSource(new LhNoticeClient.Item("LH:" + UUID.nameUUIDFromBytes(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "청년 공고", "서울", "청년 매입임대",
                url, LocalDate.now(), "a".repeat(64)));
        assertTrue(publication.basic(notice));
        entities.flush();
        extraction = UUID.fromString(extractions.enqueue(notice, true).id());
        var job = extractions.claim();
        assertEquals(extraction, job.id());
        extractions.finish(job, List.of(source(TEXT)), "READY", "완료");
    }

    @Test
    void localAnalysisIsCachedByExactEvidenceAndNeverChangesPublicContent() {
        var before = notices.content(notice);
        var first = service.get(notice, extraction);
        var second = service.get(notice, extraction);
        assertEquals(first, second);
        assertFalse(first.ruleBook().clauses().isEmpty());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM notice_local_analysis WHERE extraction_id=?", Integer.class, extraction));
        assertEquals(before, notices.content(notice));
        assertEquals("HOLD", db.queryForObject("SELECT guide_review FROM notice WHERE id=?", String.class, notice));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM gemini_review_attempt WHERE fingerprint=?", Integer.class, first.fingerprint()));
    }

    @Test
    void changedTextInSameExtractionInvalidatesCacheAndRejectsOldApiOutcome() {
        var old = seeded();
        assertTrue(repository.current(old));
        extractions.updateFile(notice, extraction, source(TEXT + "\n자동차 가액은 별도 기준을 적용합니다."));
        assertFalse(repository.current(old));
        repository.result(old, old.plan().packets().get(0).id(), Map.of("status", "CANDIDATE"));
        assertTrue(repository.find(extraction, old.fingerprint(), POLICY).orElseThrow().apiResults().isEmpty());
        var changed = service.get(notice, extraction);
        assertNotEquals(old.fingerprint(), changed.fingerprint());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM notice_local_analysis WHERE extraction_id=?", Integer.class, extraction));
    }

    @Test
    void completedScopedResultDoesNotApproveGuidanceAndLeavesQueue() {
        var entry = seeded();
        assertTrue(repository.pending(POLICY).stream().anyMatch(e -> e.extractionId().equals(extraction)));
        repository.result(entry, entry.plan().packets().get(0).id(), Map.of("status", "CANDIDATE", "decisions", List.of("NO_CONFLICT")));
        assertTrue(repository.pending(POLICY).stream().noneMatch(e -> e.extractionId().equals(extraction)));
        assertEquals("HOLD", db.queryForObject("SELECT guide_review FROM notice WHERE id=?", String.class, notice));
    }

    @Test
    void closedNoticeLeavesLocalAndProviderQueuesAndRejectsLateProviderResult() {
        assertTrue(repository.unprocessed(POLICY).contains(extraction));
        var entry = seeded();
        db.update("UPDATE notice SET end_date=(clock_timestamp() AT TIME ZONE 'Asia/Seoul')::date-1 WHERE id=?", notice);
        assertFalse(repository.current(entry));
        assertTrue(repository.pending(POLICY).stream().noneMatch(e -> e.extractionId().equals(extraction)));
        assertFalse(repository.unprocessed(POLICY + "-next").contains(extraction));
        repository.result(entry, entry.plan().packets().get(0).id(), Map.of("status", "CANDIDATE"));
        assertTrue(repository.find(extraction, entry.fingerprint(), POLICY).orElseThrow().apiResults().isEmpty());
    }

    @Test
    void newerExtractionAndManualReviewStopStalePendingWork() {
        var entry = seeded();
        db.update("UPDATE notice SET automatic_review_blocked=true WHERE id=?", notice);
        assertFalse(repository.current(entry));
        repository.result(entry, entry.plan().packets().get(0).id(), Map.of("status", "CANDIDATE"));
        assertTrue(repository.find(extraction, entry.fingerprint(), POLICY).orElseThrow().apiResults().isEmpty());
        db.update("UPDATE notice SET automatic_review_blocked=false WHERE id=?", notice);
        assertTrue(repository.current(entry));
        extractions.enqueue(notice, true);
        assertFalse(repository.current(entry));
        assertTrue(repository.pending(POLICY).stream().noneMatch(e -> e.extractionId().equals(extraction)));
    }

    @Test
    void missingSourceCannotConsumeApiBudgetButVariantMismatchCanBeAssisted() {
        var entry = seeded();
        db.update("UPDATE notice_local_analysis SET coverage_issues='[\"SOURCE_COVERAGE_INCOMPLETE\"]'::jsonb WHERE extraction_id=?", extraction);
        assertTrue(repository.pending(POLICY).stream().noneMatch(e -> e.extractionId().equals(extraction)));
        db.update("UPDATE notice_local_analysis SET coverage_issues='[\"DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:공고\"]'::jsonb WHERE extraction_id=?", extraction);
        assertTrue(repository.pending(POLICY).stream().anyMatch(e -> e.extractionId().equals(extraction)));
    }

    @Test
    void adminReadingUnapprovedOrCancelledNoticeDoesNotMakeItEligibleForApiWork() {
        var entry = seeded();
        db.update("UPDATE notice SET basic_review='HOLD' WHERE id=?", notice);
        assertFalse(repository.current(entry));
        assertTrue(repository.pending(POLICY).stream().noneMatch(e -> e.extractionId().equals(extraction)));
        db.update("UPDATE notice SET basic_review='PASS',lifecycle_state='CANCELLED' WHERE id=?", notice);
        assertFalse(repository.current(entry));
        repository.result(entry, entry.plan().packets().get(0).id(), Map.of("status", "CANDIDATE"));
        assertTrue(repository.find(extraction, entry.fingerprint(), POLICY).orElseThrow().apiResults().isEmpty());
    }

    @Test
    void localEvidenceEndpointRequiresAdminAndChecksNoticeOwnership() throws Exception {
        String path = "/api/v1/admin/reviews/" + notice + "/extractions/" + extraction + "/local-analysis";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.policy").value(POLICY));
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(UUID.randomUUID(), extraction)).status);
        var queued = extractions.enqueue(notice, true);
        assertEquals(409, assertThrows(ApiException.class, () -> service.get(notice, UUID.fromString(queued.id()))).status);
    }

    private LocalAnalysisRepository.Entry seeded() {
        var snapshot = reviews.snapshot(extraction);
        var clause = new Clause("clause", url, "b".repeat(64), "1쪽", 0, TEXT.length(), TEXT, "신청 자격",
                List.of(Topic.HOUSING), List.of("신청자"), List.of(), List.of(Marker.EXCEPTION), List.of(), List.of(), List.of());
        var book = new LocalRuleBook(LocalRuleInterpreter.POLICY, List.of(clause), List.of(),
                List.of(new Unresolved("EXCEPTION_SCOPE_UNRESOLVED", List.of("clause"))), TEXT.length());
        var plan = CompactReviewPlanner.plan(book);
        assertEquals(1, plan.packets().size());
        repository.save(snapshot, POLICY, book, plan, List.of());
        return repository.find(extraction, snapshot.fingerprint(), POLICY).orElseThrow();
    }

    private Source source(String text) {
        return new Source("공식 상세 본문", url, "b".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", text)));
    }
}
