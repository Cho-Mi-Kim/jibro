package org.jibro.collection.attachments;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import org.jibro.auth.AuthService;
import org.jibro.collection.LhNoticeClient;
import org.jibro.global.common.ApiException;
import org.jibro.notice.NoticeService;
import org.jibro.notice.SampleData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Committed fixtures and separate worker transactions verify the actual network/locking boundary. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttachmentCheckConcurrencyIntegrationTest {
    @Autowired AttachmentCheckService checks;
    @Autowired NoticeService notices;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean AttachmentFingerprintClient client;
    @MockitoBean LhNoticeClient lh;

    private record Fixture(UUID user, UUID notice, Content content) {}

    @Test
    void downloadDoesNotHoldDatabaseLockAndConcurrentDraftEditDiscardsTheStaleBaseline() throws Exception {
        // This test commits and explicitly deletes only its own records. Never run against the application DB.
        assertEquals("jibro_test", db.queryForObject("SELECT current_database()", String.class));
        String url = "https://apply.lh.or.kr/download?file=" + UUID.randomUUID();
        Fixture fixture = new TransactionTemplate(transactions).execute(status -> {
            String email = "attachment-race-" + UUID.randomUUID() + "@example.invalid";
            auth.signup("첨부 동시성", email, "attachment-race-password-123");
            UUID user = db.queryForObject("SELECT id FROM app_user WHERE email=?", UUID.class, email);
            var sample = SampleData.content();
            var content = new Content(sample.summary(), sample.evidence(),
                    List.of(new Evidence("동시성 검증 PDF", url, "2026-11-01")),
                    sample.questions(), sample.documents(), sample.schedules());
            UUID notice = notices.insert("attachment-race-" + UUID.randomUUID(), "첨부 동시성 공고", "LH", "서울", "임대",
                    LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content);
            return new Fixture(user, notice, content);
        });
        assertNotNull(fixture);
        CountDownLatch downloading = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "attachment-race-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            when(client.fetch(url)).thenAnswer(call -> {
                downloading.countDown();
                if (!releaseDownload.await(30, TimeUnit.SECONDS))
                    throw new IllegalStateException("Test download was not released");
                return new AttachmentFingerprintClient.Fingerprint("a".repeat(64), 100, "application/pdf");
            });
            Future<AttachmentCheckStore.Run> scan = workers.submit(() -> checks.check(fixture.notice(), fixture.user().toString()));
            assertTrue(downloading.await(10, TimeUnit.SECONDS), "The first scan must reach the blocked download");
            assertEquals(1, db.queryForObject("SELECT count(*) FROM attachment_check_run WHERE notice_id=? AND status='RUNNING'",
                    Integer.class, fixture.notice()));

            // This competing request must fail promptly while the first network operation is still blocked.
            Future<AttachmentCheckStore.Run> duplicate = workers.submit(() -> checks.check(fixture.notice(), fixture.user().toString()));
            ExecutionException conflict = assertThrows(ExecutionException.class, () -> duplicate.get(5, TimeUnit.SECONDS));
            ApiException error = assertInstanceOf(ApiException.class, conflict.getCause());
            assertEquals(409, error.status);

            var edited = new Content("다운로드 중 수정한 원고", fixture.content().evidence(), fixture.content().attachments(),
                    fixture.content().questions(), fixture.content().documents(), fixture.content().schedules());
            Future<Review> update = workers.submit(() -> notices.updateContent(fixture.notice(), 1, edited));
            assertEquals(2, update.get(5, TimeUnit.SECONDS).revision(),
                    "Draft edit must commit before the download is released; no scan DB lock may be held");
            assertEquals(1, releaseDownload.getCount());

            releaseDownload.countDown();
            var result = scan.get(10, TimeUnit.SECONDS);
            assertEquals("SUPERSEDED", result.status());
            assertEquals(0, db.queryForObject("SELECT count(*) FROM attachment_fingerprint WHERE notice_id=?", Integer.class, fixture.notice()));
            assertEquals(2, db.queryForObject("SELECT version FROM notice WHERE id=?", Integer.class, fixture.notice()));
            assertEquals("다운로드 중 수정한 원고", notices.content(fixture.notice()).summary());
            assertEquals(1, db.queryForObject("SELECT count(*) FROM attachment_check_run WHERE notice_id=?", Integer.class, fixture.notice()));
            verify(client, times(1)).fetch(url);
        } finally {
            releaseDownload.countDown();
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Concurrency workers must stop before cleanup");
            } finally {
                // The fixture never creates preparations or reviews. Attachment/history rows cascade from this notice.
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    db.update("DELETE FROM notice WHERE id=?", fixture.notice());
                    db.update("DELETE FROM app_user WHERE id=?", fixture.user());
                });
            }
        }
    }
}
