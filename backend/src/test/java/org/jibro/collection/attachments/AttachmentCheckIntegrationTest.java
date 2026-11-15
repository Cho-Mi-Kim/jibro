package org.jibro.collection.attachments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.jibro.notice.HousingModels.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

import org.jibro.auth.AuthService;
import org.jibro.collection.CollectionService;
import org.jibro.collection.CollectionQueryPlan;
import org.jibro.collection.LhNoticeClient;
import org.jibro.global.common.ApiException;
import org.jibro.notice.NoticeService;
import org.jibro.notice.SampleData;
import org.jibro.preparation.PreparationService;
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

@SpringBootTest(properties = "app.collection.category-codes=06")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttachmentCheckIntegrationTest {
    @Autowired AttachmentCheckService checks;
    @Autowired AttachmentCheckStore store;
    @Autowired org.jibro.collection.extraction.ExtractionStore extraction;
    @Autowired org.jibro.collection.extraction.NoticeExtractionService extractionWorker;
    @Autowired NoticeService notices;
    @Autowired PreparationService preparations;
    @Autowired AuthService auth;
    @Autowired CollectionService collections;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @MockitoBean AttachmentFingerprintClient client;
    @MockitoBean LhNoticeClient lh;
    @MockitoBean org.jibro.collection.extraction.BoundedDocumentParser parser;
    String actor;
    UUID notice;
    final String url = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=100";

    @BeforeEach
    void setup() throws Exception {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("첨부 검증", email, "test-password-123");
        actor = auth.login(email, "test-password-123").user().id();
        notice = insert(List.of(new Evidence("공고문", url, "2026-11-01")));
        when(client.fetch(anyString())).thenReturn(file("a"));
    }

    UUID insert(List<Evidence> attachments) {
        var c = SampleData.content();
        return notices.insert("attachment-test-" + UUID.randomUUID(), "검사 공고", "LH", "서울", "임대", LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(30), LocalDate.now(), new Content(c.summary(), c.evidence(), attachments, c.questions(), c.documents(), c.schedules()));
    }

    AttachmentFingerprintClient.Fingerprint file(String hex) {
        return new AttachmentFingerprintClient.Fingerprint(hex.repeat(64), 100L, "application/pdf");
    }

    Review review() { return notices.reviews().stream().filter(r -> r.id().equals(notice.toString())).findFirst().orElseThrow(); }

    @Test
    void firstCheckIsBaselineAndRepeatedIdenticalBytesDoNotChangeVersions() {
        notices.samplePublish(notice, "PASS");
        var initial = checks.check(notice, actor);
        assertEquals("BASELINE", initial.results().get(0).status());
        assertEquals("SUCCEEDED", initial.status());
        assertEquals(1, review().notice().version());
        assertEquals("PASS", review().basic());
        assertEquals("UNCHANGED", checks.check(notice, actor).results().get(0).status());
        assertEquals(1, review().revision());
        assertEquals(2, store.recent(notice).size());
    }

    @Test
    void sameAddressChangedBytesHoldPublicationAndKeepPersonalSnapshot() throws Exception {
        notices.samplePublish(notice, "PASS");
        var prep = preparations.start(actor, notice, 1);
        String snapshot = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, UUID.fromString(prep.id));
        checks.check(notice, actor);
        when(client.fetch(url)).thenReturn(file("b"));
        var changed = checks.check(notice, actor);
        assertEquals("CHANGED", changed.results().get(0).status());
        assertEquals("a".repeat(64), changed.results().get(0).previousHash());
        assertEquals("b".repeat(64), changed.results().get(0).currentHash());
        assertEquals(2, review().notice().version());
        assertEquals("HOLD", review().basic());
        assertEquals("HOLD", review().guide());
        assertEquals("CHANGE_REVIEW", review().notice().guideState());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM notice_extraction WHERE notice_id=? AND source_revision=? AND status='QUEUED'", Integer.class, notice, review().revision()));
        assertEquals(snapshot, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, UUID.fromString(prep.id)));
        assertEquals(404, assertThrows(ApiException.class, () -> notices.notice(notice)).status);
        assertEquals("UNCHANGED", checks.check(notice, actor).results().get(0).status());
        assertEquals(2, review().notice().version());
    }

    @Test
    void changedAttachmentIsRelocatedByBackgroundCheckpointWithoutAViewerRequest() throws Exception {
        String official = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
        notice = insert(List.of(new Evidence("공고문", official, "2026-11-01")));
        notices.samplePublish(notice, "PASS");
        when(client.fetch(official)).thenReturn(file("a"));
        checks.check(notice, actor);
        when(client.fetch(official)).thenReturn(file("b"));
        checks.check(notice, actor);
        assertEquals("PENDING", db.queryForObject("SELECT state FROM evidence_location_resolution WHERE file_id='123'", String.class));
        var job = extraction.claim(notice);
        assertNotNull(job);
        var part = new org.jibro.collection.extraction.ExtractionModels.Part("새 위치", "verified unchanged source text with sufficient context");
        var source = new org.jibro.collection.extraction.ExtractionModels.Source("공고.hwp", official, "b".repeat(64), "EXTRACTED", "", List.of(), List.of(part));
        assertTrue(extraction.checkpoint(job, List.of(source)));
        assertEquals("MATCH", db.queryForObject("SELECT state FROM evidence_location_resolution WHERE file_id='123'", String.class));
        assertEquals("새 위치", db.queryForObject("SELECT current_location FROM evidence_location_resolution WHERE file_id='123'", String.class));
        when(client.fetch(official)).thenReturn(file("c"));
        checks.check(notice, actor);
        assertFalse(extraction.checkpoint(job, List.of(source)));
        assertEquals("PENDING", db.queryForObject("SELECT state FROM evidence_location_resolution WHERE file_id='123'", String.class));
        assertEquals("c".repeat(64), db.queryForObject("SELECT current_sha FROM evidence_location_resolution WHERE file_id='123'", String.class).strip());
    }

    @Test
    void firstDetectionOfChangedKnownFileIsProcessedByTheWorker() throws Exception {
        String official = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
        notice = insert(List.of(new Evidence("공고.hwp", official, "2026-11-01")));
        notices.samplePublish(notice, "PASS");
        when(client.fetch(official)).thenReturn(file("b"));
        assertEquals("CHANGED", checks.check(notice, actor).results().get(0).status());
        assertEquals("PENDING", db.queryForObject("SELECT state FROM evidence_location_resolution WHERE file_id='123'", String.class));
        when(client.html(anyString())).thenReturn("<main><p>공식 안내 문서</p></main>");
        when(client.download(official)).thenReturn(new AttachmentFingerprintClient.Download(file("b"), new byte[]{1}));
        when(parser.parse(any(), anyString())).thenReturn(List.of(new org.jibro.collection.extraction.ExtractionModels.Part(
            "새 위치", "verified unchanged source text with sufficient context")));
        extractionWorker.runNext(notice);
        assertEquals("MATCH", db.queryForObject("SELECT state FROM evidence_location_resolution WHERE file_id='123'", String.class));
        assertEquals("새 위치", db.queryForObject("SELECT current_location FROM evidence_location_resolution WHERE file_id='123'", String.class));
        String digest = org.jibro.notice.EvidencePartMatcher.digest("verified unchanged source text with sufficient context");
        mvc.perform(get("/api/v1/notices/official-pdf/123/relocate").param("baselineSha", "a".repeat(64)).param("partDigest", digest))
            .andExpect(status().isOk()).andExpect(jsonPath("$.location").value("새 위치"))
            .andExpect(jsonPath("$.sha256").value("b".repeat(64)));
    }

    @Test
    void previouslyFingerprintedChangedFileIsQueuedOnceForBackgroundReconciliation() throws Exception {
        String official = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
        notice = insert(List.of(new Evidence("공고.hwp", official, "2026-11-01")));
        notices.samplePublish(notice, "PASS");
        notices.attachmentSnapshot(notice);
        db.update("INSERT INTO attachment_fingerprint(notice_id,url_key,url,sha256,byte_size,media_type,checked_at) VALUES(?,?,?,?,?,?,now())",
            notice, AttachmentCheckStore.key(official), official, "b".repeat(64), 100, "application/pdf");
        when(client.fetch(official)).thenReturn(file("b"));
        assertEquals("CHANGED", checks.check(notice, actor).results().get(0).status());
        assertEquals("PENDING", db.queryForObject("SELECT state FROM evidence_location_resolution WHERE file_id='123'", String.class));
        assertEquals("UNCHANGED", checks.check(notice, actor).results().get(0).status());
    }

    @Test
    void ambiguousNewDocumentKeepsLocationInReview() throws Exception {
        String official = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
        notice = insert(List.of(new Evidence("공고.hwp", official, "2026-11-01")));
        notices.samplePublish(notice, "PASS");
        when(client.fetch(official)).thenReturn(file("b"));
        checks.check(notice, actor);
        var job = extraction.claim(notice);
        var one = new org.jibro.collection.extraction.ExtractionModels.Part("첫 위치", "verified unchanged source text with sufficient context");
        var two = new org.jibro.collection.extraction.ExtractionModels.Part("두 번째 위치", "verified unchanged source text with sufficient context");
        var source = new org.jibro.collection.extraction.ExtractionModels.Source("공고.hwp", official, "b".repeat(64), "EXTRACTED", "", List.of(), List.of(one, two));
        assertTrue(extraction.checkpoint(job, List.of(source)));
        assertEquals("REVIEW", db.queryForObject("SELECT state FROM evidence_location_resolution WHERE file_id='123'", String.class));
    }

    @Test
    void exhaustedExtractionFailureLeavesLocationForReview() throws Exception {
        String official = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
        notice = insert(List.of(new Evidence("공고.hwp", official, "2026-11-01")));
        notices.samplePublish(notice, "PASS");
        when(client.fetch(official)).thenReturn(file("b"));
        checks.check(notice, actor);
        var job = extraction.claim(notice);
        db.update("UPDATE notice_extraction SET retries_used=2 WHERE id=?", job.id());
        extraction.finish(job, List.of(), "FAILED", "자료 읽기 실패");
        assertEquals("REVIEW", db.queryForObject("SELECT state FROM evidence_location_resolution WHERE file_id='123'", String.class));
    }

    @Test
    void failureKeepsBaselineAndPublicStateAndDoesNotExposeRawException() throws Exception {
        notices.samplePublish(notice, "PASS");
        checks.check(notice, actor);
        when(client.fetch(url)).thenThrow(new IOException("secret-private-response"));
        var failed = checks.check(notice, actor);
        assertEquals("FAILED", failed.status());
        assertFalse(failed.results().get(0).message().contains("secret"));
        assertEquals("a".repeat(64), db.queryForObject("SELECT sha256 FROM attachment_fingerprint WHERE notice_id=?", String.class, notice));
        assertEquals("PASS", review().basic());
        assertEquals(1, review().revision());
    }

    @Test
    void severalChangedFilesAdvanceOneVersionEvenWhenAnotherFails() throws Exception {
        notice = insert(List.of(new Evidence("one", url, "2026-11-01"), new Evidence("two", url + "1", "2026-11-01"), new Evidence("three", url + "2", "2026-11-01")));
        checks.check(notice, actor);
        when(client.fetch(anyString())).thenReturn(file("b"));
        when(client.fetch(url + "2")).thenThrow(new IOException("ATTACHMENT_SIZE_REJECTED"));
        var run = checks.check(notice, actor);
        assertEquals("PARTIAL", run.status());
        assertEquals(2, run.results().stream().filter(r -> r.status().equals("CHANGED")).count());
        assertEquals(2, review().notice().version());
        assertTrue(run.results().stream().anyMatch(r -> r.message().contains("20MiB")));
    }

    @Test
    void revisionRaceDiscardsDownloadedBaselineAndRunningScanCannotBeDuplicated() {
        var scan = store.begin(notice, actor);
        assertEquals(409, assertThrows(ApiException.class, () -> store.begin(notice, actor)).status);
        notices.updateContent(notice, 1, notices.content(notice));
        var run = store.finish(scan, List.of(new AttachmentCheckStore.Download(scan.targets().get(0), file("a"), null)));
        assertEquals("SUPERSEDED", run.status());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM attachment_fingerprint WHERE notice_id=?", Integer.class, notice));
        assertEquals("BASELINE", checks.check(notice, actor).results().get(0).status());
    }

    @Test
    void fragmentsAreDeduplicatedAndMissingAddressesAreNotFetched() throws Exception {
        notice = insert(List.of(new Evidence("p1", url + "#page=1", "2026-11-01"), new Evidence("p2", url + "#page=2", "2026-11-01"), new Evidence("missing", null, "2026-11-01")));
        var run = checks.check(notice, actor);
        assertEquals(1, run.results().size());
        verify(client, times(1)).fetch(url);
    }

    @Test
    void abandonedRunCanBeRetriedAndOldWorkerCannotOverwriteNewResult() {
        var old = store.begin(notice, actor);
        db.update("UPDATE attachment_check_run SET started_at=now()-interval '6 minutes' WHERE id=?", old.id());
        assertEquals("FAILED", store.recent(notice).get(0).status());
        var fresh = checks.check(notice, actor);
        assertEquals("BASELINE", fresh.results().get(0).status());
        assertEquals("FAILED", store.finish(old, List.of(new AttachmentCheckStore.Download(old.targets().get(0), file("b"), null))).status());
        assertEquals("a".repeat(64), db.queryForObject("SELECT sha256 FROM attachment_fingerprint WHERE notice_id=?", String.class, notice));
    }

    @Test
    void boundedRunRetriesUnattemptedFilesBeforePreviouslyCheckedFiles() throws Exception {
        List<Evidence> files = new ArrayList<>();
        for (int i = 0; i < 12; i++) files.add(new Evidence("file" + i, url + i, "2026-11-01"));
        notice = insert(files);
        var first = checks.check(notice, actor);
        assertEquals("PARTIAL", first.status());
        assertEquals(2, first.results().stream().filter(r -> r.status().equals("DEFERRED")).count());
        var second = checks.check(notice, actor);
        assertEquals(url + "10", second.results().get(0).url());
        assertEquals("BASELINE", second.results().get(0).status());
        assertEquals(12, db.queryForObject("SELECT count(*) FROM attachment_fingerprint WHERE notice_id=? AND sha256 IS NOT NULL", Integer.class, notice));
    }

    @Test
    void administratorEndpointEnforcesAuthAndReturnsFileEvidence() throws Exception {
        String path = "/api/v1/admin/reviews/" + notice + "/attachment-checks";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(csrf()).with(jwt().jwt(j -> j.subject(actor)).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(client);
        mvc.perform(post(path).with(csrf()).with(jwt().jwt(j -> j.subject(actor)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("BASELINE"));
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].results[0].bytes").value(100));
    }

    @Test
    void unchangedApiListStillChecksRegisteredAttachment() throws Exception {
        var item = new LhNoticeClient.Item("attachment-import-" + UUID.randomUUID(), "공고", "서울", "임대", "https://apply.lh.or.kr/detail", LocalDate.now(), "list-unchanged");
        notice = notices.importSource(item);
        var c = SampleData.content();
        notices.updateContent(notice, 1, new Content(c.summary(), c.evidence(), List.of(new Evidence("PDF", url, "2026-11-01")), c.questions(), c.documents(), c.schedules()));
        when(lh.configured()).thenReturn(true);
        when(lh.fetch(eq(1), any(CollectionQueryPlan.Query.class))).thenReturn(new LhNoticeClient.Batch(List.of(item), 1));
        assertEquals("SUCCEEDED", collections.collect(actor, 1).status());
        when(client.fetch(url)).thenReturn(file("c"));
        collections.collect(actor, 1);
        assertEquals(3, review().notice().version());
        assertEquals("CHANGED", store.recent(notice).get(0).results().get(0).status());
    }
}
