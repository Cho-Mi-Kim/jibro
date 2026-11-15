package org.jibro.collection.extraction;

import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.global.common.ApiException;
import org.jibro.notice.NoticeService;
import org.jibro.notice.SampleData;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NoticeExtractionIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired ExtractionStore store;
    @Autowired NoticeExtractionService service;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;
    @Autowired MockMvc mvc;
    @MockitoBean AttachmentFingerprintClient http;
    @MockitoBean BoundedDocumentParser parser;
    UUID notice;
    final String detail = "https://apply.lh.or.kr/lhapply/detail.do";
    final String file = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=100";

    @BeforeEach void setup() throws Exception {
        notice = insert(List.of());
        when(http.html(detail)).thenReturn("<main><p>접수 마감은 공식 공고를 확인하세요.</p><a href='" + file + "'>공고문</a></main>");
        when(http.download(anyString())).thenReturn(new AttachmentFingerprintClient.Download(
                new AttachmentFingerprintClient.Fingerprint("a".repeat(64), 9, "application/pdf"), new byte[9]));
        when(parser.parse(any(byte[].class), eq("application/pdf"))).thenReturn(List.of(new Part("공고문 2쪽", "신청 자격과 제출 서류 확인 필요")));
    }
    UUID insert(List<Evidence> attachments) {
        var original = SampleData.content();
        UUID id = notices.insert("extract-test-" + UUID.randomUUID(), "원문 발췌 테스트", "LH", "서울", "임대",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(),
                new Content(original.summary(), new Evidence("공식 원문", detail, "2026-11-06"), attachments,
                        original.questions(), original.documents(), original.schedules()));
        notices.samplePublish(id, "PASS");
        entities.flush();
        return id;
    }
    UUID ready(List<Source> sources) {
        var draft = store.enqueue(notice, true);
        var job = store.claim();
        assertEquals(draft.id(), job.id().toString());
        store.finish(job, sources, "PARTIAL", "테스트 발췌");
        return job.id();
    }
    Source source(String url, String state) {
        return new Source("공고문", url, state.equals("EXTRACTED") ? "a".repeat(64) : null, state, "원문 대조 필요",
                state.equals("EXTRACTED") ? List.of(new Excerpt("DOCUMENT", "제출 서류 테스트", "공고문 2쪽", url)) : List.of());
    }

    @Test void extractionNeverReplacesPublishedContentOrPublishesUnreviewedGuidance() {
        var before = notices.content(notice);
        var draft = store.enqueue(notice, false);
        service.runNext();
        var extracted = store.find(UUID.fromString(draft.id()));
        assertEquals("READY", extracted.status());
        assertEquals(2, extracted.sources().size());
        assertTrue(extracted.sources().stream().allMatch(s -> !s.excerpts().isEmpty()));
        assertEquals(before, notices.content(notice));
        assertEquals("AVAILABLE", notices.notice(notice).guideState());
        assertEquals(1, notices.notice(notice).version());
        assertTrue(notices.content(notice).attachments().isEmpty());
    }

    @Test void incompleteOcrRetriesTwiceWithoutLosingReadableTextOrBecomingReady() throws Exception {
        var before = notices.content(notice);
        var parts = List.of(new Part("공고문 1쪽", "신청 자격과 제출 서류는 원문 대조가 필요합니다."),
            new Part("공고문 2쪽 " + OcrProvenance.MARKER, ""));
        when(parser.parse(any(byte[].class), eq("application/pdf"))).thenReturn(parts);
        UUID id = UUID.fromString(store.enqueue(notice, false).id());

        for (int attempt = 0; attempt < 3; attempt++) {
            service.runNext(notice);
            var result = store.find(id);
            var attachment = result.sources().stream().filter(s -> s.url().equals(file)).findFirst().orElseThrow();
            assertEquals(parts, attachment.context());
            assertFalse(attachment.excerpts().isEmpty());
            assertEquals("a".repeat(64), attachment.sha256());
            assertEquals(Math.min(attempt + 1, 2), db.queryForObject(
                "SELECT retries_used FROM notice_extraction WHERE id=?", Integer.class, id));
            if (attempt < 2) {
                assertEquals("QUEUED", result.status());
                assertEquals("DEFERRED", attachment.status());
                assertNull(store.claim(notice));
                db.update("UPDATE notice_extraction SET next_attempt_at=now() WHERE id=?", id);
            } else {
                assertEquals("PARTIAL", result.status());
                assertEquals("EXTRACTED", attachment.status());
                assertTrue(attachment.message().contains("일부 완료되지"));
                assertNull(store.claim(notice));
            }
        }

        verify(http, times(1)).html(detail);
        verify(http, times(3)).download(file);
        verify(parser, times(3)).parse(any(byte[].class), eq("application/pdf"));
        assertEquals(before, notices.content(notice));
        assertEquals(1, notices.notice(notice).version());
    }

    @Test void perFileRereadingRemainsPartialUntilEveryOcrLocationHasReadableText() {
        var before = notices.content(notice);
        var readable = new Part("공고문 1쪽", "신청 자격 안내");
        var initial = new Source("공고문", file, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(readable));
        UUID id = UUID.fromString(store.enqueue(notice, false).id());
        store.finish(store.claim(notice), List.of(initial), "READY", "일반 텍스트 읽기 완료");
        var incomplete = new Source(initial.label(), file, initial.sha256(), "EXTRACTED", "일부 미판독", List.of(),
            List.of(readable, new Part("공고문 2쪽 " + OcrProvenance.MARKER, "")));

        assertEquals(incomplete, store.updateFile(notice, id, incomplete));
        assertEquals("PARTIAL", store.find(id).status());
        assertEquals(incomplete.context(), store.find(id).sources().get(0).context());

        var complete = new Source(initial.label(), file, initial.sha256(), "EXTRACTED", "원문 대조 필요", List.of(),
            List.of(readable, new Part("공고문 2쪽 " + OcrProvenance.MARKER, "제출 서류 안내: 원문과 대조하세요.")));
        store.updateFile(notice, id, complete);
        assertEquals("READY", store.find(id).status(), "READY is extraction completion, not guidance approval");
        assertEquals(complete, store.find(id).sources().get(0));
        assertEquals(before, notices.content(notice));
        assertEquals(1, notices.notice(notice).version());
    }

    @Test void activeAndCompletedJobsDeduplicateButExplicitRetryCreatesANewJob() {
        var first = store.enqueue(notice, false);
        assertEquals(first.id(), store.enqueue(notice, true).id());
        var job = store.claim();
        assertEquals(first.id(), store.enqueue(notice, false).id());
        assertNull(store.claim());
        store.finish(job, List.of(source(file, "EXTRACTED")), "READY", "완료");
        assertEquals(first.id(), store.enqueue(notice, false).id());
        assertNotEquals(first.id(), store.enqueue(notice, true).id());
    }

    @Test void outdatedQueuedSourceIsRetiredWithoutReadingOfficialPagesOrFiles() {
        var draft = store.enqueue(notice, false);
        notices.updateContent(notice, 1, notices.content(notice));
        entities.flush();

        service.runNext();

        assertEquals("SUPERSEDED", store.find(UUID.fromString(draft.id())).status());
        verifyNoInteractions(http, parser);
    }

    @Test void selectedNoticeRunsWithoutProcessingOrRetiringOtherQueuedNotices() throws Exception {
        UUID other = insert(List.of());
        var unrelated = store.enqueue(other, false);
        notices.updateContent(other, 1, notices.content(other));
        entities.flush();
        var selected = store.enqueue(notice, false);

        service.runNext(notice);

        assertEquals("READY", store.find(UUID.fromString(selected.id())).status());
        assertEquals("QUEUED", store.find(UUID.fromString(unrelated.id())).status());
        assertNull(store.claim(notice));
        verify(http, times(1)).html(detail);
        verify(http, times(1)).download(file);
    }

    @Test void changedRevisionMakesRunningDraftSupersededAndCannotBeAdopted() {
        store.enqueue(notice, true);
        var job = store.claim();
        notices.updateContent(notice, 1, notices.content(notice));
        entities.flush();
        store.finish(job, List.of(source(file, "EXTRACTED")), "READY", "완료");
        assertEquals("SUPERSEDED", store.find(job.id()).status());
        assertEquals(409, assertThrows(ApiException.class, () -> service.adoptAttachments(notice, job.id(), 1)).status);
    }

    @Test void completedDraftBecameStaleAlsoRejectsAdoptionAndPerFileUpdates() {
        UUID draft = ready(List.of(source(file, "DEFERRED")));
        notices.updateContent(notice, 1, notices.content(notice));
        entities.flush();
        assertEquals(409, assertThrows(ApiException.class, () -> store.updateFile(notice, draft, source(file, "EXTRACTED"))).status);
        assertEquals(409, assertThrows(ApiException.class, () -> service.adoptAttachments(notice, draft, 1)).status);
    }

    @Test void adoptionAddsOnlyVerifiedAttachmentsAndRequiresReviewAgain() {
        var content = notices.content(notice);
        UUID draft = ready(List.of(source(file, "EXTRACTED"), source(file + "-deferred", "DEFERRED"),
                new Source("공식 상세 본문", detail, "b".repeat(64), "EXTRACTED", "본문", List.of())));
        service.adoptAttachments(notice, draft, 1);
        entities.flush();
        assertEquals(List.of(file), notices.content(notice).attachments().stream().map(Evidence::url).toList());
        assertEquals(content.questions(), notices.content(notice).questions());
        assertEquals(content.documents(), notices.content(notice).documents());
        var review = notices.reviews().stream().filter(r -> r.id().equals(notice.toString())).findFirst().orElseThrow();
        assertEquals("HOLD", review.basic());
        assertEquals("HOLD", review.guide());
        assertEquals(2, review.notice().version());
        assertEquals(404, assertThrows(ApiException.class, () -> notices.notice(notice)).status);
    }

    @Test void moreThanFiftyAttachmentsIsRejectedWithoutChangingExistingContent() {
        var attachments = new ArrayList<Evidence>();
        for (int i = 0; i < 50; i++) attachments.add(new Evidence("첨부 " + i, file + "-" + i, "2026-11-06"));
        notice = insert(attachments);
        var before = notices.content(notice);
        UUID draft = ready(List.of(source(file, "EXTRACTED")));
        assertEquals(409, assertThrows(ApiException.class, () -> service.adoptAttachments(notice, draft, 1)).status);
        assertEquals(before, notices.content(notice));
        assertEquals(1, notices.notice(notice).version());
    }

    @Test void deferredFileExtractionPersistsForReloadAndLaterAttachmentAdoption() throws Exception {
        UUID draft = ready(List.of(source(file, "DEFERRED")));
        mvc.perform(post("/api/v1/admin/reviews/" + notice + "/extractions/" + draft + "/file")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json").content("{\"url\":\"" + file + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXTRACTED"));
        assertEquals("EXTRACTED", store.find(draft).sources().get(0).status());
        service.adoptAttachments(notice, draft, 1);
        assertEquals(file, notices.content(notice).attachments().get(0).url());
    }

    @Test void onlyAdministratorsCanReadOrStartExtractionJobs() throws Exception {
        String path = "/api/v1/admin/reviews/" + notice + "/extractions";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test void stalledRunCanBeRetriedWithoutAcceptingLateCompletion() {
        store.enqueue(notice, true);
        var old = store.claim();
        db.update("UPDATE notice_extraction SET started_at=now()-interval '10 minutes 1 second' WHERE id=?", old.id());
        var retry = store.enqueue(notice, true);
        assertNotEquals(old.id().toString(), retry.id());
        assertEquals("FAILED", store.find(old.id()).status());
        store.finish(old, List.of(source(file, "EXTRACTED")), "READY", "늦은 완료");
        assertEquals("FAILED", store.find(old.id()).status());
    }

    @Test void checkpointsPreserveCompletedSourcesAndRejectLostClaimsOrChangedNotices() {
        store.enqueue(notice, true);
        var job = store.claim();
        var sources = List.of(source(file, "EXTRACTED"));
        assertTrue(store.checkpoint(job, sources));
        assertEquals(sources, store.find(job.id()).sources());
        assertEquals("RUNNING", store.find(job.id()).status());
        assertFalse(store.checkpoint(new ExtractionStore.Job(job.id(), job.noticeId(), job.revision(), UUID.randomUUID()), List.of()));
        assertEquals(sources, store.find(job.id()).sources());
        notices.updateContent(notice, 1, notices.content(notice));
        entities.flush();
        assertFalse(store.checkpoint(job, List.of()));
        assertEquals(sources, store.find(job.id()).sources());
    }

    @Test void sevenAttachmentsContinueAcrossTicksWithoutDownloadingAlreadyReadSourcesAgain() throws Exception {
        StringBuilder html = new StringBuilder("<main><p>접수 마감은 원문 확인 필요</p>");
        for (int i = 1; i <= 7; i++) html.append("<a href='").append(file).append(i).append("'>첨부 ").append(i).append("</a>");
        when(http.html(detail)).thenReturn(html.append("</main>").toString());
        UUID draft = UUID.fromString(store.enqueue(notice, false).id());
        service.runNext();
        assertEquals("QUEUED", store.find(draft).status());
        assertEquals(3, store.find(draft).sources().stream().filter(s -> s.sha256() != null && !s.label().equals("공식 상세 본문")).count());
        verify(http, times(3)).download(anyString());
        service.runNext();
        assertEquals("QUEUED", store.find(draft).status());
        verify(http, times(6)).download(anyString());
        service.runNext();
        assertEquals("READY", store.find(draft).status());
        assertEquals(8, store.find(draft).sources().size());
        verify(http, times(1)).html(detail);
        for (int i = 1; i <= 7; i++) verify(http, times(1)).download(file + i);
        service.runNext();
        verify(http, times(7)).download(anyString());
        assertEquals(1, store.recent(notice).size());
        assertEquals(1, notices.notice(notice).version());
    }

    @Test void revisionChangedBetweenContinuationTicksStopsDownloadsButPreservesEarlierExcerpts() throws Exception {
        StringBuilder html = new StringBuilder("<main><p>접수 마감은 원문 확인 필요</p>");
        for (int i = 1; i <= 4; i++) html.append("<a href='").append(file).append(i).append("'>첨부 ").append(i).append("</a>");
        when(http.html(detail)).thenReturn(html.append("</main>").toString());
        UUID draft = UUID.fromString(store.enqueue(notice, false).id());
        service.runNext();
        var partial = store.find(draft).sources();
        assertEquals("QUEUED", store.find(draft).status());
        notices.updateContent(notice, 1, notices.content(notice));
        entities.flush();
        service.runNext();
        assertEquals("SUPERSEDED", store.find(draft).status());
        assertEquals(partial, store.find(draft).sources());
        verify(http, times(1)).html(detail);
        verify(http, times(3)).download(anyString());
        verify(http, never()).download(file + "4");
    }

    @Test void malformedParserTextCannotPoisonJsonbOrLeaveTheJobRunningAcrossRetries() throws Exception {
        when(parser.parse(any(byte[].class), eq("application/pdf")))
            .thenReturn(List.of(new Part("공고문 2쪽", "신청 자격" + (char) 0 + "제출 서류")));
        UUID id = UUID.fromString(store.enqueue(notice, false).id());
        for (int attempt = 0; attempt < 3; attempt++) {
            assertDoesNotThrow(() -> service.runNext());
            var draft = store.find(id);
            assertNotEquals("RUNNING", draft.status());
            assertNull(db.queryForObject("SELECT claim_token FROM notice_extraction WHERE id=?", UUID.class, id));
            assertEquals(attempt < 2 ? "QUEUED" : "PARTIAL", draft.status());
            var failed = draft.sources().stream().filter(source -> source.url().equals(file)).findFirst().orElseThrow();
            assertTrue(failed.context().isEmpty());
            assertTrue(failed.excerpts().isEmpty());
            assertEquals(attempt < 2 ? "DEFERRED" : "FAILED", failed.status());
            if (attempt == 2) assertEquals(ExtractionText.FAILURE_MESSAGE, failed.message());
            else db.update("UPDATE notice_extraction SET next_attempt_at=now() WHERE id=?", id);
        }
        assertEquals(2, db.queryForObject("SELECT retries_used FROM notice_extraction WHERE id=?", Integer.class, id));
        verify(http, times(3)).download(file);
    }

    @Test void malformedHtmlIsRejectedBeforeJsoupCanReplaceCharactersAndOnDetailRetry() throws Exception {
        when(http.html(detail)).thenReturn("<main><p>접수 기간 " + (char) 0 + "2026.11.16</p></main>");
        UUID id = UUID.fromString(store.enqueue(notice, false).id());
        for (int attempt = 0; attempt < 3; attempt++) {
            assertDoesNotThrow(() -> service.runNext());
            var draft = store.find(id);
            assertEquals(attempt < 2 ? "QUEUED" : "FAILED", draft.status());
            assertEquals(1, draft.sources().size());
            assertTrue(draft.sources().get(0).context().isEmpty());
            if (attempt == 2) assertEquals(ExtractionText.FAILURE_MESSAGE, draft.sources().get(0).message());
            else db.update("UPDATE notice_extraction SET next_attempt_at=now() WHERE id=?", id);
        }
        verify(http, times(3)).html(detail);
        verify(http, never()).download(anyString());
    }

    @Test void readableMetadataAndHousingTableWithoutKeywordsAreKeptWithoutChangingGuidance() throws Exception {
        var before = notices.content(notice);
        when(http.html(detail)).thenReturn("<main><p>담당부서: 구미 사업단</p><a href='" + file + "'>주택목록.xlsx</a></main>");
        var table = List.of(new Part("Excel 워크시트 Sheet1 (행·열 위치)", "A1: 주택목록\nA2: 101호\nB2: 1,234,500"));
        when(parser.parse(any(byte[].class), eq("application/pdf"))).thenReturn(table);
        UUID draftId = UUID.fromString(store.enqueue(notice, false).id());

        service.runNext(notice);

        var result = store.find(draftId);
        assertEquals("READY", result.status());
        assertEquals(2, result.sources().size());
        assertTrue(result.sources().stream().allMatch(source -> source.status().equals("EXTRACTED") && source.excerpts().isEmpty()));
        assertEquals(table, result.sources().get(1).context());
        assertEquals(before, notices.content(notice));
        assertEquals(1, notices.notice(notice).version());
    }

    @Test void detailRetryAlsoRecognizesReadTextWithoutClassifiedExcerpts() throws Exception {
        when(http.html(detail)).thenThrow(new java.io.IOException("test failure"))
            .thenReturn("<main><p>담당부서: 구미 사업단</p></main>");
        UUID draftId = UUID.fromString(store.enqueue(notice, false).id());
        service.runNext(notice);
        assertEquals("QUEUED", store.find(draftId).status());
        db.update("UPDATE notice_extraction SET next_attempt_at=now() WHERE id=?", draftId);

        service.runNext(notice);

        var result = store.find(draftId);
        assertEquals("READY", result.status());
        assertEquals("EXTRACTED", result.sources().get(0).status());
        assertTrue(result.sources().get(0).excerpts().isEmpty());
        assertEquals("담당부서: 구미 사업단", result.sources().get(0).context().get(0).text());
    }

    @Test void blankDetailAndAttachmentRemainEmptyWithoutInventedExcerpts() throws Exception {
        when(http.html(detail)).thenReturn("<main><p> </p><a href='" + file + "'>공고문</a></main>");
        when(parser.parse(any(byte[].class), eq("application/pdf"))).thenReturn(List.of(new Part("공고문 1쪽", " \n\t")));
        UUID draftId = UUID.fromString(store.enqueue(notice, false).id());

        service.runNext(notice);

        var result = store.find(draftId);
        assertEquals("FAILED", result.status());
        assertTrue(result.sources().stream().allMatch(source -> source.status().equals("EMPTY") && source.excerpts().isEmpty()));
    }
}
