package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.LhNoticeClient;
import org.jibro.global.common.ApiException;
import org.jibro.preparation.PreparationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NoticeCorrectionIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    NoticeCorrectionService corrections;
    @Autowired
    PreparationService preparations;
    @Autowired
    JdbcTemplate db;
    @Autowired
    MockMvc mvc;
    @Autowired
    NoticeQueryRepository queries;
    @MockitoBean
    AttachmentFingerprintClient http;
    final List<UUID> created = new ArrayList<>();
    UUID original, corrected, owner;

    @BeforeEach
    void setup() throws Exception {
        original = create(OfficialCorrectionLinkTest.OLD);
        corrected = create(OfficialCorrectionLinkTest.TARGET);
        when(http.html(OfficialCorrectionLinkTest.url(OfficialCorrectionLinkTest.OLD))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "No DB transaction during official HTTP reads");
            return OfficialCorrectionLinkTest.html(OfficialCorrectionLinkTest.OLD, OfficialCorrectionLinkTest.TARGET, "");
        });
        when(http.html(OfficialCorrectionLinkTest.url(OfficialCorrectionLinkTest.TARGET))).thenReturn(
                OfficialCorrectionLinkTest.html(OfficialCorrectionLinkTest.TARGET, OfficialCorrectionLinkTest.TARGET, OfficialCorrectionLinkTest.OLD));
    }

    private UUID create(String panId) {
        var sample = SampleData.content();
        var content = new Content(sample.summary(), new Evidence("공식 공고", OfficialCorrectionLinkTest.url(panId), LocalDate.now().toString()),
                sample.attachments(), sample.questions(), sample.documents(), sample.schedules());
        UUID id = notices.insert(LhNoticeClient.sourceKey(OfficialCorrectionLinkTest.url(panId)), "정정 연결 검증 " + panId, "LH", "인천", "국민임대",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now().minusDays(2), content);
        created.add(id);
        notices.samplePublish(id, "PASS");
        return id;
    }

    @AfterEach
    void cleanup() {
        for (UUID id : created)
            db.update("DELETE FROM notice_correction WHERE original_notice_id=? OR corrected_notice_id=?", id, id);
        for (UUID id : created) {
            db.update("DELETE FROM preparation WHERE notice_id=?", id);
            db.update("DELETE FROM notice WHERE id=?", id);
        }
        if (owner != null) db.update("DELETE FROM app_user WHERE id=?", owner);
    }

    @Test
    void verifiedReplacementExcludesOriginalBeforeEverySortCountAndPaginationButPreservesDetail() throws Exception {
        var before = notices.content(original);
        corrections.link(original, corrected, 1, 1);
        // Editorial guidance changes do not change the verified official notice identities.
        db.update("UPDATE notice SET revision=revision+1 WHERE id IN (?,?)", original, corrected);
        db.update("UPDATE notice SET guide_review='HOLD',change_review=true WHERE id=?", corrected);
        for (String sort : List.of("active", "newest", "deadline")) {
            var page = notices.search(List.of(), List.of(), List.of(), "정정 연결 검증", sort, 0, 1);
            assertEquals(1, page.totalElements());
            assertEquals(1, page.totalPages());
            assertEquals(corrected.toString(), page.items().get(0).id());
            assertTrue(notices.search(List.of(), List.of(), List.of(), "정정 연결 검증", sort, 1, 1).items().isEmpty());
        }
        assertEquals(before, notices.content(original));
        mvc.perform(get("/api/v1/notices/" + original)).andExpect(status().isOk());
    }

    @Test
    void unpublishedReplacementRestoresOriginalAndFiltersUseTheSameListingRule() throws Exception {
        String region = "원공고전용-" + original;
        db.update("UPDATE notice SET region=? WHERE id=?", region, original);
        corrections.link(original, corrected, 1, 1);
        assertFalse(queries.filters().get("regions").contains(region));
        assertTrue(notices.search(List.of(region), List.of(), List.of(), "", "active", 0, 20).items().isEmpty());
        db.update("UPDATE notice SET basic_review='HOLD' WHERE id=?", corrected);
        assertTrue(queries.filters().get("regions").contains(region));
        var page = notices.search(List.of(), List.of(), List.of(), "정정 연결 검증", "active", 0, 20);
        assertEquals(List.of(original.toString()), page.items().stream().map(NoticeListItem::id).toList());
    }

    @Test
    void similarUnlinkedNoticesAreBothKept() {
        db.update("UPDATE notice SET title='같은 제목 정정 표시 검증' WHERE id IN (?,?)", original, corrected);
        assertEquals(2, notices.search(List.of(), List.of(), List.of(), "같은 제목 정정 표시 검증", "active", 0, 20).totalElements());
    }

    @Test
    void legacyThirtyDigitDetailIdentityUsesTheBoundSourceAndPreservesNoticeData() throws Exception {
        String oldPan = OfficialCorrectionTemplateTest.LEGACY_ORIGINAL;
        String newPan = OfficialCorrectionTemplateTest.LEGACY_TARGET;
        UUID oldId = create(oldPan), newId = create(newPan);
        when(http.html(OfficialCorrectionLinkTest.url(oldPan))).thenReturn(
                OfficialCorrectionTemplateTest.page(oldPan, newPan, "", "01"));
        when(http.html(OfficialCorrectionLinkTest.url(newPan))).thenReturn(
                OfficialCorrectionTemplateTest.page(newPan, newPan, oldPan, "02"));
        var before = notices.content(oldId);
        String sourceKey = notices.entity(oldId).sourceKey;

        var linked = corrections.link(oldId, newId, 1, 1);

        assertEquals(newId.toString(), linked.noticeId());
        assertEquals(before, notices.content(oldId));
        assertEquals(sourceKey, notices.entity(oldId).sourceKey);
        assertEquals(1, notices.entity(oldId).revision);
        assertEquals(1, notices.entity(newId).revision);
        assertEquals(newId.toString(), corrections.correction(oldId).orElseThrow().noticeId());
    }

    @Test
    void defaultTemplateWithoutAReverseOriginalIdDoesNotStoreACorrection() throws Exception {
        when(http.html(OfficialCorrectionLinkTest.url(OfficialCorrectionLinkTest.OLD))).thenReturn(
                OfficialCorrectionTemplateTest.page(OfficialCorrectionLinkTest.OLD, OfficialCorrectionLinkTest.TARGET, "", "01"));
        when(http.html(OfficialCorrectionLinkTest.url(OfficialCorrectionLinkTest.TARGET))).thenReturn(
                OfficialCorrectionTemplateTest.page(OfficialCorrectionLinkTest.TARGET, OfficialCorrectionLinkTest.TARGET, "", "02"));

        assertThrows(IllegalArgumentException.class, () -> corrections.link(original, corrected, 1, 1));

        assertEquals(0, db.queryForObject("SELECT count(*) FROM notice_correction WHERE original_notice_id=?", Integer.class, original));
        assertEquals(1, notices.entity(original).revision);
        assertTrue(corrections.correction(original).isEmpty());
    }

    @Test
    void verifiedLinkPreservesContentRevisionAndExistingPreparationAndIsPubliclyReadable() throws Exception {
        owner = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,name,email) VALUES(?,?,?)", owner, "정정 테스트", owner + "@example.invalid");
        var prep = preparations.start(owner.toString(), original, 1);
        prep = preparations.document(owner.toString(), UUID.fromString(prep.id), prep.documents.get(0).id, prep.revision, DocumentState.READY, "보존할 메모");
        String snapshot = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, UUID.fromString(prep.id));
        var before = notices.content(original);
        var linked = corrections.link(original, corrected, 1, 1);
        assertEquals(corrected.toString(), linked.noticeId());
        assertEquals(before, notices.content(original));
        assertEquals(1, notices.entity(original).revision);
        assertEquals(1, notices.entity(corrected).revision);
        assertEquals(snapshot, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, UUID.fromString(prep.id)));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM notice_correction WHERE original_notice_id=?", Integer.class, original));
        assertTrue(db.queryForObject("SELECT evidence->>'originalHtmlSha256' FROM notice_correction WHERE original_notice_id=?", String.class, original).matches("[a-f0-9]{64}"));
        mvc.perform(get("/api/v1/notices/" + original + "/correction")).andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeId").value(corrected.toString())).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/notices/" + corrected + "/correction")).andExpect(status().isNoContent());
    }

    @Test
    void noLinkIs204ButUnpublishedOriginalIs404() throws Exception {
        mvc.perform(get("/api/v1/notices/" + original + "/correction")).andExpect(status().isNoContent());
        db.update("UPDATE notice SET basic_review='HOLD' WHERE id=?", original);
        mvc.perform(get("/api/v1/notices/" + original + "/correction")).andExpect(status().isNotFound());
    }

    @Test
    void targetMustRemainPublishedApprovedAndNormal() throws Exception {
        for (String state : List.of("HOLD", "UNSUPPORTED")) {
            db.update("UPDATE notice SET guide_review=? WHERE id=?", state, corrected);
            assertThrows(ApiException.class, () -> corrections.link(original, corrected, 1, 1));
        }
        db.update("UPDATE notice SET guide_review='PASS',lifecycle_state='CANCELLED' WHERE id=?", corrected);
        assertThrows(ApiException.class, () -> corrections.link(original, corrected, 1, 1));
        db.update("UPDATE notice SET lifecycle_state='NORMAL' WHERE id=?", corrected);
        corrections.link(original, corrected, 1, 1);
        db.update("UPDATE notice SET guide_review='HOLD' WHERE id=?", corrected);
        assertTrue(corrections.correction(original).isEmpty());
        mvc.perform(get("/api/v1/notices/" + original + "/correction")).andExpect(status().isNoContent());
        db.update("UPDATE notice SET guide_review='PASS',basic_review='HOLD' WHERE id=?", corrected);
        mvc.perform(get("/api/v1/notices/" + original + "/correction")).andExpect(status().isNoContent());
    }

    @Test
    void eitherRevisionChangeHidesLinkAndStaleCommandCannotWrite() throws Exception {
        assertThrows(ApiException.class, () -> corrections.link(original, corrected, 2, 1));
        corrections.link(original, corrected, 1, 1);
        db.update("UPDATE notice SET revision=2 WHERE id=?", original);
        assertTrue(corrections.correction(original).isEmpty());
        mvc.perform(get("/api/v1/notices/" + original + "/correction")).andExpect(status().isNoContent());
        db.update("UPDATE notice SET revision=1 WHERE id=?", original);
        db.update("UPDATE notice SET revision=2 WHERE id=?", corrected);
        assertTrue(corrections.correction(original).isEmpty());
        mvc.perform(get("/api/v1/notices/" + original + "/correction")).andExpect(status().isNoContent());
    }

    @Test
    void networkFailureOrChangedTargetDuringFetchNeverCreatesLink() throws Exception {
        when(http.html(OfficialCorrectionLinkTest.url(OfficialCorrectionLinkTest.TARGET))).thenThrow(new java.io.IOException("unavailable"));
        assertThrows(java.io.IOException.class, () -> corrections.link(original, corrected, 1, 1));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM notice_correction WHERE original_notice_id=?", Integer.class, original));
        doAnswer(call -> {
            db.update("UPDATE notice SET revision=revision+1 WHERE id=?", corrected);
            return OfficialCorrectionLinkTest.html(OfficialCorrectionLinkTest.TARGET, OfficialCorrectionLinkTest.TARGET, OfficialCorrectionLinkTest.OLD);
        }).when(http).html(OfficialCorrectionLinkTest.url(OfficialCorrectionLinkTest.TARGET));
        assertThrows(ApiException.class, () -> corrections.link(original, corrected, 1, 1));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM notice_correction WHERE original_notice_id=?", Integer.class, original));
    }

    @Test
    void explicitReverificationRestoresOnlyTheSamePairAndRetainsPreviousProof() throws Exception {
        corrections.link(original, corrected, 1, 1);
        String before = db.queryForObject("SELECT evidence::text FROM notice_correction WHERE original_notice_id=?", String.class, original);
        var content = notices.content(original);
        db.update("UPDATE notice SET revision=2 WHERE id IN (?,?)", original, corrected);
        assertTrue(corrections.correction(original).isEmpty());
        assertThrows(ApiException.class, () -> corrections.reverify(original, 1, 2));
        var refreshed = corrections.reverify(original, 2, 2);
        assertEquals(corrected.toString(), refreshed.noticeId());
        assertEquals(corrected.toString(), corrections.correction(original).orElseThrow().noticeId());
        assertEquals(content, notices.content(original));
        assertEquals(2, notices.entity(original).revision);
        assertEquals(before, db.queryForObject("SELECT (verification_history->0->'evidence')::text FROM notice_correction WHERE original_notice_id=?", String.class, original));
        assertEquals(1, db.queryForObject("SELECT jsonb_array_length(verification_history) FROM notice_correction WHERE original_notice_id=?", Integer.class, original));
    }

    @Test
    void lostReverseProofOrConcurrentRevisionNeverRevalidatesAStaleLink() throws Exception {
        corrections.link(original, corrected, 1, 1);
        db.update("UPDATE notice SET revision=2 WHERE id IN (?,?)", original, corrected);
        when(http.html(OfficialCorrectionLinkTest.url(OfficialCorrectionLinkTest.TARGET))).thenReturn(
                OfficialCorrectionTemplateTest.page(OfficialCorrectionLinkTest.TARGET, OfficialCorrectionLinkTest.TARGET, "", "02"));
        assertThrows(IllegalArgumentException.class, () -> corrections.reverify(original, 2, 2));
        doAnswer(call -> {
            db.update("UPDATE notice SET revision=3 WHERE id=?", corrected);
            return OfficialCorrectionLinkTest.html(OfficialCorrectionLinkTest.TARGET, OfficialCorrectionLinkTest.TARGET, OfficialCorrectionLinkTest.OLD);
        }).when(http).html(OfficialCorrectionLinkTest.url(OfficialCorrectionLinkTest.TARGET));
        assertThrows(ApiException.class, () -> corrections.reverify(original, 2, 2));
        assertTrue(corrections.correction(original).isEmpty());
        assertEquals(0, db.queryForObject("SELECT jsonb_array_length(verification_history) FROM notice_correction WHERE original_notice_id=?", Integer.class, original));
        assertEquals(1, db.queryForObject("SELECT original_revision FROM notice_correction WHERE original_notice_id=?", Integer.class, original));
    }

    @Test
    void selfLinksDuplicatesAndChainsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> corrections.link(original, original, 1, 1));
        corrections.link(original, corrected, 1, 1);
        assertThrows(ApiException.class, () -> corrections.link(original, corrected, 1, 1));
        UUID third = create("999");
        when(http.html(OfficialCorrectionLinkTest.url(OfficialCorrectionLinkTest.TARGET))).thenReturn(
                OfficialCorrectionLinkTest.html(OfficialCorrectionLinkTest.TARGET, "999", ""));
        when(http.html(OfficialCorrectionLinkTest.url("999"))).thenReturn(
                OfficialCorrectionLinkTest.html("999", "999", OfficialCorrectionLinkTest.TARGET));
        assertThrows(ApiException.class, () -> corrections.link(corrected, third, 1, 1));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM notice_correction WHERE original_notice_id IN (?,?)", Integer.class, original, corrected));
    }

    @Test
    void pdfGuideEvidenceUsesOnlyTheCollectedDetailIdentityFromExtractionHistory() throws Exception {
        String detail = OfficialCorrectionLinkTest.url(OfficialCorrectionLinkTest.TARGET);
        recordDetail(corrected, detail);
        db.update("UPDATE notice SET content=jsonb_set(content,'{evidence,url}',to_jsonb(?::text)) WHERE id=?",
                "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69314336", corrected);
        var linked = corrections.link(original, corrected, 1, 1);
        assertEquals(detail, linked.evidence().url());
        assertEquals(corrected.toString(), linked.noticeId());
        verify(http).html(detail);
        verify(http, never()).html("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69314336");
        assertEquals("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69314336", notices.content(corrected).evidence().url());
    }

    @Test
    void anUnboundExtractedOrContentDetailCannotReplaceTheCollectedIdentity() throws Exception {
        String unrelated = OfficialCorrectionLinkTest.url("999");
        recordDetail(corrected, unrelated);
        db.update("UPDATE notice SET content=jsonb_set(content,'{evidence,url}',to_jsonb(?::text)) WHERE id=?", unrelated, corrected);
        assertThrows(IllegalArgumentException.class, () -> corrections.link(original, corrected, 1, 1));
        verifyNoInteractions(http);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM notice_correction WHERE original_notice_id=?", Integer.class, original));
    }

    private void recordDetail(UUID notice, String detail) {
        db.update("""
                INSERT INTO notice_extraction(id,notice_id,source_revision,status,finished_at,sources)
                VALUES(?,?,1,'READY',now(),jsonb_build_array(jsonb_build_object(
                    'label','공식 상세 본문','url',?::text,'status','EXTRACTED','context','[]'::jsonb)))
                """, UUID.randomUUID(), notice, detail);
    }
}
