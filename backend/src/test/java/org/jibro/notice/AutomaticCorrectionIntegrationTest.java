package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.*;

import org.jibro.collection.AutomaticCorrectionService;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@ActiveProfiles("test")
class AutomaticCorrectionIntegrationTest {
    static final String A = "991100001", B = "991100002", C = "991100003";
    @Autowired
    NoticeService notices;
    @Autowired
    NoticeRepository entities;
    @Autowired
    NoticeCorrectionRepository relations;
    @Autowired
    CorrectionCheckRepository queue;
    @Autowired
    AutomaticCorrectionLinkService linker;
    @Autowired
    JdbcTemplate db;
    @MockitoBean
    AttachmentFingerprintClient http;
    final List<UUID> ids = new ArrayList<>();
    AutomaticCorrectionService worker;

    @BeforeEach
    void setup() {
        worker = new AutomaticCorrectionService(queue, linker, true);
        db.update("DELETE FROM notice_correction_check");
    }

    @AfterEach
    void cleanup() {
        for (UUID id : ids)
            db.update("DELETE FROM notice_correction WHERE original_notice_id=? OR corrected_notice_id=?", id, id);
        for (UUID id : ids) db.update("DELETE FROM notice WHERE id=?", id);
    }

    static String url(String id) {
        return OfficialCorrectionLinkTest.url(id);
    }

    static String title(String id) {
        return (id.equals(A) ? "" : "[정정공고]") + "자동 연결 검증 " + id;
    }

    static String page(String id, String latest) {
        return OfficialCorrectionTemplateTest.page(id, latest, id.equals(A) ? "" : A, id.equals(A) ? "01" : "02")
            + "<div class='bbs_ViewA'><h3>" + title(id) + "</h3></div>";
    }

    static String history(String latest) {
        List<String> all = latest.equals(B) ? List.of(B, A) : List.of(C, B, A);
        var html = new StringBuilder("<table><caption>공고이력조회</caption><tbody>");
        for (int i = 0; i < all.size(); i++) {
            String id = all.get(i), kind = id.equals(A) ? "일반공고" : "정정공고";
            html.append("<tr><th>").append(kind).append("</th><td>2026-11-0").append(9 - i)
                .append("</td><td>").append(title(id)).append("</td><td>");
            if (i > 0) html.append("<a href=\"javascript:goUrl('").append(id).append("','03')\">바로가기</a>");
            html.append("</td></tr>");
        }
        return html.append("</tbody></table>").toString();
    }

    LhNoticeClient.Item item(String id) {
        return new LhNoticeClient.Item(LhNoticeClient.sourceKey(url(id)), title(id), "서울", "국민임대", url(id), LocalDate.now(), "a".repeat(64));
    }

    UUID collect(String id) {
        var item = item(id);
        UUID n = notices.importSource(item);
        ids.add(n);
        db.update("UPDATE notice SET basic_review='PASS',end_date=?,start_date=? WHERE id=?",
            LocalDate.now().plusDays(20), LocalDate.now().minusDays(1), n);
        worker.process(n, item);
        return n;
    }

    void mock(String latest) throws Exception {
        for (String id : List.of(A, B, C))
            when(http.html(url(id))).thenAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return page(id, latest);
            });
        when(http.html(OfficialCorrectionLink.historyUrl(url(latest)))).thenReturn(history(latest));
    }

    void due(UUID id) {
        db.update("UPDATE notice_correction_check SET next_attempt_at=now()-interval '1 day' WHERE notice_id=?", id);
    }

    @Test
    void collectionAutomaticallyLinksWithoutPublishingGuidanceAndIsIdempotent() throws Exception {
        mock(B);
        UUID a = collect(A), b = collect(B);
        var before = notices.content(a);
        assertTrue(worker.runNext());
        assertEquals(b, relations.find(a).orElseThrow().correctedId());
        assertEquals("HOLD", entities.findById(b).orElseThrow().guideReview);
        assertEquals(before, notices.content(a));
        var result = notices.search(List.of(), List.of(), List.of(), "자동 연결 검증", "active", 0, 20);
        assertEquals(List.of(b.toString()), result.items().stream().map(NoticeListItem::id).toList());
        int historySize = db.queryForObject("SELECT jsonb_array_length(verification_history) FROM notice_correction WHERE original_notice_id=?", Integer.class, a);
        worker.process(b, item(B));
        due(b);
        assertTrue(worker.runNext());
        assertEquals(historySize, db.queryForObject("SELECT jsonb_array_length(verification_history) FROM notice_correction WHERE original_notice_id=?", Integer.class, a));
    }

    @Test
    void secondCorrectionRetargetsAllEarlierVersionsAndPreservesAudit() throws Exception {
        mock(B);
        UUID a = collect(A), b = collect(B);
        assertTrue(worker.runNext());
        mock(C);
        UUID c = collect(C);
        assertTrue(worker.runNext());
        assertEquals(c, relations.find(a).orElseThrow().correctedId());
        assertEquals(c, relations.find(b).orElseThrow().correctedId());
        assertEquals(1, db.queryForObject("SELECT jsonb_array_length(verification_history) FROM notice_correction WHERE original_notice_id=?", Integer.class, a));
        var page = notices.search(List.of(), List.of(), List.of(), "자동 연결 검증", "active", 0, 20);
        assertEquals(1, page.totalElements());
        assertEquals(c.toString(), page.items().get(0).id());
        // Replayed older official pages must not move the latest link backwards or create a cycle.
        mock(B);
        due(b);
        assertFalse(worker.runNext());
        assertEquals(c, relations.find(a).orElseThrow().correctedId());
    }

    @Test
    void lateOriginalCollectionIsRetriedAndConnectedWithoutAnotherManualCommand() throws Exception {
        mock(B);
        UUID b = collect(B);
        assertTrue(worker.runNext());
        assertEquals("WAITING_FOR_COLLECTION", db.queryForObject("SELECT result_code FROM notice_correction_check WHERE notice_id=?", String.class, b));
        UUID a = collect(A);
        due(b);
        assertTrue(worker.runNext());
        assertEquals(b, relations.find(a).orElseThrow().correctedId());
    }

    @Test
    void unpublishedTargetWaitsAndClosedTargetsNeverCauseHiding() throws Exception {
        mock(B);
        UUID a = collect(A), b = collect(B);
        db.update("UPDATE notice SET basic_review='HOLD' WHERE id=?", b);
        assertTrue(worker.runNext());
        assertTrue(relations.find(a).isEmpty());
        db.update("UPDATE notice SET basic_review='PASS',end_date=? WHERE id=?", LocalDate.now().minusDays(1), b);
        due(a);
        assertTrue(worker.runNext());
        assertTrue(relations.find(a).isEmpty());
        assertFalse(worker.runNext());
    }

    @Test
    void closedOriginalIsNotLinkedFromAnOpenCorrection() throws Exception {
        mock(B);
        UUID a = collect(A), b = collect(B);
        db.update("UPDATE notice SET source_closed=true WHERE id=?", a);
        var job = queue.claim(b).orElseThrow();
        assertEquals("CLOSED_EXCLUDED", linker.reconcile(job));
        assertTrue(relations.find(a).isEmpty());
        verify(http, never()).html(url(A));
    }

    @Test
    void officialMismatchNeverUsesSimilarTitlesAndTransportFailureCanRecover() throws Exception {
        mock(B);
        UUID a = collect(A), b = collect(B);
        when(http.html(url(A))).thenReturn(page(A, B).replace("<h3>" + title(A), "<h3>다른 제목"));
        assertFalse(worker.runNext());
        assertTrue(relations.find(a).isEmpty());
        mock(B);
        when(http.html(url(B))).thenThrow(new java.io.IOException("test network failure"));
        due(b);
        assertFalse(worker.runNext());
        assertTrue(relations.find(a).isEmpty());
        reset(http);
        mock(B);
        due(b);
        assertTrue(worker.runNext());
        assertEquals(b, relations.find(a).orElseThrow().correctedId());
    }

    @Test
    void staleLeaseAndChangedCollectionCannotPublish() throws Exception {
        mock(B);
        UUID a = collect(A), b = collect(B);
        var job = queue.claim().orElseThrow();
        assertEquals(b, job.noticeId());
        assertTrue(queue.claim().isPresent());
        assertTrue(queue.claim().isEmpty());
        db.update("UPDATE notice SET source_fingerprint=? WHERE id=?", "b".repeat(64), b);
        assertEquals("SOURCE_CHANGED", linker.reconcile(job));
        assertTrue(relations.find(a).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> worker.process(a,
            new LhNoticeClient.Item("LH:wrong", title(A), "서울", "국민임대", url(A), LocalDate.now(), "a".repeat(64))));
        db.update("UPDATE notice SET source_fingerprint=? WHERE id=?", "a".repeat(64), b);
        db.update("UPDATE notice_correction_check SET lease_until=now()-interval '1 second' WHERE notice_id=?", b);
        var recovered = queue.claim().orElseThrow();
        assertNotEquals(job.token(), recovered.token());
        assertEquals("SOURCE_CHANGED", linker.reconcile(job));
        assertEquals("LINKED", linker.reconcile(recovered));
        assertEquals(b, relations.find(a).orElseThrow().correctedId());
    }

    @Test
    void officialClosedMetadataIsExcludedEvenWhenCollectedDatesAreMissing() throws Exception {
        mock(B);
        UUID a = collect(A), b = collect(B);
        db.update("UPDATE notice SET start_date=NULL,end_date=NULL WHERE id=?", b);
        when(http.html(url(B))).thenReturn(page(B, B) + "<div class='bbs_ViewA'><ul class='bbsV_data'>"
            + "<li><strong>공고상태</strong><span>접수마감</span></li></ul></div>");
        var job = queue.claim(b).orElseThrow();
        assertEquals("CLOSED_EXCLUDED", linker.reconcile(job));
        assertTrue(relations.find(a).isEmpty());
        assertTrue(queue.claim(b).isEmpty());
    }

    @Test
    void reviewedFullAttachmentsRepairOnlyTheRequestedPairAndPreserveContent() throws Exception {
        var review = ReviewedLegacyCorrectionTest.review();
        var oldItem = new LhNoticeClient.Item(LhNoticeClient.sourceKey(review.originalUrl()), review.title(), "대구", "공공임대",
            review.originalUrl(), LocalDate.now(), "a".repeat(64));
        var nextItem = new LhNoticeClient.Item(LhNoticeClient.sourceKey(review.correctedUrl()), "[정정공고] " + review.title(), "대구", "공공임대",
            review.correctedUrl(), LocalDate.now(), "b".repeat(64));
        UUID old = notices.importSource(oldItem), next = notices.importSource(nextItem);
        ids.addAll(List.of(old, next));
        notices.samplePublish(old, "HOLD");
        notices.samplePublish(next, "HOLD");
        db.update("UPDATE notice SET source_closed=true WHERE id=?", old);
        var before = db.queryForList("SELECT id,content::text,revision,source_fingerprint,source_closed FROM notice WHERE id IN (?,?) ORDER BY id", old, next);
        when(http.html(review.originalUrl())).thenReturn(ReviewedLegacyCorrectionTest.page(false));
        when(http.html(review.correctedUrl())).thenReturn(ReviewedLegacyCorrectionTest.page(true));
        when(http.html(OfficialCorrectionLink.historyUrl(review.correctedUrl()))).thenReturn("<table></table>");
        ReviewedLegacyCorrectionTest.mockFiles(http);
        queue.enqueue(next, nextItem);
        var job = queue.claim(next).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> linker.reconcile(job));
        assertTrue(relations.find(old).isEmpty());
        var changed = new AttachmentFingerprintClient.Fingerprint("c".repeat(64), 500000, "application/pdf");
        when(http.fetch("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=103")).thenReturn(changed);
        assertThrows(IllegalArgumentException.class, () -> linker.reconcileReviewedLegacyAttachments(job, old, review));
        assertTrue(relations.find(old).isEmpty());
        ReviewedLegacyCorrectionTest.mockFiles(http);
        assertEquals("LINKED", linker.reconcileReviewedLegacyAttachments(job, old, review));
        assertEquals(review, relations.find(old).orElseThrow().evidence().attachmentReview());
        assertEquals("LINKED", linker.reconcileReviewedLegacyAttachments(job, old, review));
        assertEquals(before, db.queryForList("SELECT id,content::text,revision,source_fingerprint,source_closed FROM notice WHERE id IN (?,?) ORDER BY id", old, next));
    }

    @Test
    void legacyHistoryLinksOnlyItsExplicitOriginalAndKeepsSeparateRecruitmentVisible() throws Exception {
        var oldItem = new LhNoticeClient.Item(LhNoticeClient.sourceKey(LegacyCorrectionHistoryTest.url(LegacyCorrectionHistoryTest.OLD)),
            "원공고 제목", "서울", "공공임대", LegacyCorrectionHistoryTest.url(LegacyCorrectionHistoryTest.OLD), LocalDate.now(), "a".repeat(64));
        var nextItem = new LhNoticeClient.Item(LhNoticeClient.sourceKey(LegacyCorrectionHistoryTest.url(LegacyCorrectionHistoryTest.NEXT)),
            "정정 제목", "서울", "공공임대", LegacyCorrectionHistoryTest.url(LegacyCorrectionHistoryTest.NEXT), LocalDate.now(), "b".repeat(64));
        UUID old = notices.importSource(oldItem), next = notices.importSource(nextItem);
        ids.add(old);
        ids.add(next);
        notices.samplePublish(old, "HOLD");
        notices.samplePublish(next, "HOLD");
        var separateItem = new LhNoticeClient.Item(LhNoticeClient.sourceKey(LegacyCorrectionHistoryTest.url("888880001")),
            "원공고 제목 잔여세대 추가모집", "서울", "공공임대", LegacyCorrectionHistoryTest.url("888880001"), LocalDate.now(), "c".repeat(64));
        UUID separate = notices.importSource(separateItem);
        ids.add(separate);
        notices.samplePublish(separate, "HOLD");
        when(http.html(oldItem.url())).thenReturn(LegacyCorrectionHistoryTest.original());
        when(http.html(nextItem.url())).thenReturn(LegacyCorrectionHistoryTest.latest());
        when(http.html(OfficialCorrectionLink.historyUrl(nextItem.url()))).thenReturn(LegacyCorrectionHistoryTest.history());
        queue.enqueue(next, nextItem);
        var job = queue.claim(next).orElseThrow();
        db.update("UPDATE notice SET source_closed=true WHERE id=?", old);
        assertEquals("CLOSED_EXCLUDED", linker.reconcile(job));
        assertTrue(relations.find(old).isEmpty());
        assertEquals("WAITING_FOR_COLLECTION", linker.reconcileRequestedLegacyRoot(job, separate));
        assertTrue(relations.find(separate).isEmpty());
        db.update("UPDATE notice SET source_closed=true WHERE id=?", next);
        assertEquals("CLOSED_EXCLUDED", linker.reconcileRequestedLegacyRoot(job, old));
        db.update("UPDATE notice SET source_closed=false WHERE id=?", next);
        assertEquals("LINKED", linker.reconcileRequestedLegacyRoot(job, old));
        assertEquals(Boolean.TRUE, db.queryForObject("SELECT source_closed FROM notice WHERE id=?", Boolean.class, old));
        assertEquals(next, relations.find(old).orElseThrow().correctedId());
        assertTrue(relations.find(separate).isEmpty());
        assertTrue(notices.search(List.of(), List.of(), List.of(), "원공고 제목", "active", 0, 20).items().stream().anyMatch(n -> n.id().equals(separate.toString())));
        assertFalse(notices.search(List.of(), List.of(), List.of(), "원공고 제목", "active", 0, 20).items().stream().anyMatch(n -> n.id().equals(old.toString())));
        assertDoesNotThrow(() -> notices.notice(old));
    }
}
