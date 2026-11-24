package org.jibro.preparation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import org.jibro.auth.AccountMail;
import org.jibro.auth.AuthService;
import org.jibro.global.common.ApiException;
import org.jibro.notice.NoticeService;
import org.jibro.notice.SampleData;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PreparationDeletionIntegrationTest {
    @Autowired
    PreparationDeletionService deletion;
    @Autowired
    PreparationService preparations;
    @Autowired
    NoticeService notices;
    @Autowired
    AuthService auth;
    @Autowired
    JdbcTemplate db;
    @Autowired
    MockMvc mvc;
    @MockitoBean
    AccountMail mail;
    private final String remoteAddress = "reauth-test-" + UUID.randomUUID();
    private static final String PASSWORD = "deletion-test-password";
    private final List<UUID> users = new ArrayList<>(), noticeIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        assertEquals("jibro_test", db.queryForObject("SELECT current_database()", String.class));
        when(mail.mode()).thenReturn("smtp");
    }

    @AfterEach
    void cleanup() {
        for (UUID user : users) {
            db.update("DELETE FROM preparation WHERE user_id=?", user);
            db.update("DELETE FROM refresh_token WHERE user_id=?", user);
            db.update("DELETE FROM app_user WHERE id=?", user);
        }
        for (UUID notice : noticeIds) db.update("DELETE FROM notice WHERE id=?", notice);
    }

    private AuthService.Tokens member() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("삭제 검증", email, PASSWORD);
        var token = auth.login(email, PASSWORD);
        users.add(UUID.fromString(token.user().id()));
        return token;
    }

    private String accountKey(UUID user) {
        String email = db.queryForObject("SELECT email FROM app_user WHERE id=?", String.class, user);
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec("test-only-jwt-signing-key-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("account:" + email).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }

    private UUID preparation(AuthService.Tokens owner) {
        UUID notice = notices.insert(UUID.randomUUID().toString(), "삭제할 준비", "검증 기관", "서울", "임대", LocalDate.now(), LocalDate.now().plusDays(30), LocalDate.now(), SampleData.content());
        noticeIds.add(notice);
        notices.samplePublish(notice, "PASS");
        return UUID.fromString(preparations.start(owner.user().id(), notice, 1).id);
    }

    private UUID owner(AuthService.Tokens owner) {
        return UUID.fromString(owner.user().id());
    }

    private PreparationDeletionService.Confirmation password(int revision) {
        return new PreparationDeletionService.Confirmation(revision, "준비 삭제", PASSWORD, null);
    }

    private PreparationDeletionService.Confirmation code(String code) {
        return new PreparationDeletionService.Confirmation(1, "준비 삭제", null, code);
    }

    private String issue(UUID user, UUID id) {
        deletion.requestProof(user, id, 1);
        var text = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).send(anyString(), anyString(), text.capture());
        return text.getValue().split("\n\n")[1];
    }

    @Test
    void loginFailuresAlsoBlockDeletionWithoutTouchingThePreparation() {
        var a = member();
        UUID user = owner(a), id = preparation(a);
        String email = db.queryForObject("SELECT email FROM app_user WHERE id=?", String.class, user);
        for (int i = 0; i < 5; i++)
            assertEquals(401, assertThrows(ApiException.class, () -> auth.login(email, "wrong-password", "login-" + UUID.randomUUID())).status);
        var blocked = assertThrows(ApiException.class, () -> deletion.delete(user, id, password(1), remoteAddress));
        assertEquals(429, blocked.status);
        assertEquals("REAUTHENTICATION_LIMIT", blocked.code);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM preparation WHERE id=?", Integer.class, id));
    }

    @Test
    void deletesOnlyOwnedPreparationAndHistoricalPersonalDataIncludingArchivedRecords() {
        var member = member();
        UUID user = owner(member), deleted = preparation(member), kept = preparation(member);
        db.update("UPDATE preparation SET archived_at=now(),takeaway='개인 메모' WHERE id=?", deleted);
        db.update("INSERT INTO preparation_history(preparation_id,notice_version,snapshot) SELECT id,notice_version,snapshot FROM preparation WHERE id=?", deleted);
        db.update("INSERT INTO experience_reference(target_id,source_id) VALUES(?,?)", kept, deleted);
        UUID notice = db.queryForObject("SELECT notice_id FROM preparation WHERE id=?", UUID.class, deleted);
        db.update("INSERT INTO saved_notice(user_id,notice_id,saved_version) VALUES(?,?,1)", user, notice);
        assertEquals(1, deletion.details(user, deleted).revision());
        deletion.delete(user, deleted, password(1), remoteAddress);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM preparation_history WHERE preparation_id=?", Integer.class, deleted));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM experience_reference WHERE source_id=? OR target_id=?", Integer.class, deleted, deleted));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM preparation WHERE id=?", Integer.class, kept));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM saved_notice WHERE user_id=? AND notice_id=?", Integer.class, user, notice));
        assertNotNull(notices.notice(notice));
        assertEquals(404, assertThrows(ApiException.class, () -> deletion.delete(user, deleted, password(1), remoteAddress)).status);
    }

    @Test
    void foreignOwnerMissingPreparationStaleRevisionAndMissingConfirmationCannotDelete() throws Exception {
        var a = member();
        var b = member();
        UUID id = preparation(a);
        String path = "/api/v1/preparations/" + id;
        mvc.perform(get(path + "/deletion")).andExpect(status().isUnauthorized());
        mvc.perform(delete(path).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "/deletion").header("Authorization", "Bearer " + b.accessToken())).andExpect(status().isNotFound());
        assertEquals(404, assertThrows(ApiException.class, () -> deletion.delete(owner(b), id, password(1), remoteAddress)).status);
        assertEquals(404, assertThrows(ApiException.class, () -> deletion.delete(owner(b), UUID.randomUUID(), password(1), remoteAddress)).status);
        assertEquals(400, assertThrows(ApiException.class, () -> deletion.delete(owner(a), id, new PreparationDeletionService.Confirmation(1, "삭제", PASSWORD, null), remoteAddress)).status);
        db.update("UPDATE preparation SET revision=2 WHERE id=?", id);
        assertEquals(409, assertThrows(ApiException.class, () -> deletion.delete(owner(a), id, password(1), remoteAddress)).status);
        assertNotNull(deletion.details(owner(a), id));
    }

    @Test
    void proofIsHashedOwnerPreparationRevisionAndSecurityVersionBoundAndSingleUse() {
        var a = member();
        var b = member();
        UUID id = preparation(a), another = preparation(a);
        String code = issue(owner(a), id);
        assertNotEquals(code, db.queryForObject("SELECT token_hash FROM preparation_deletion_proof WHERE preparation_id=?", String.class, id));
        assertEquals(429, assertThrows(ApiException.class, () -> deletion.requestProof(owner(a), id, 1)).status);
        assertThrows(ApiException.class, () -> deletion.delete(owner(b), id, code(code), remoteAddress));
        assertThrows(ApiException.class, () -> deletion.delete(owner(a), another, code(code), remoteAddress));
        db.update("UPDATE preparation SET revision=2 WHERE id=?", id);
        assertEquals(409, assertThrows(ApiException.class, () -> deletion.delete(owner(a), id, code(code), remoteAddress)).status);
        db.update("UPDATE preparation SET revision=1 WHERE id=?", id);
        db.update("UPDATE app_user SET security_version=security_version+1 WHERE id=?", owner(a));
        assertThrows(ApiException.class, () -> deletion.delete(owner(a), id, code(code), remoteAddress));
        db.update("UPDATE preparation_deletion_proof SET created_at=now()-interval '2 minutes' WHERE preparation_id=?", id);
        String fresh = issue(owner(a), id);
        deletion.delete(owner(a), id, code(fresh), remoteAddress);
        assertThrows(ApiException.class, () -> deletion.delete(owner(a), another, code(fresh), remoteAddress));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM preparation_deletion_proof WHERE preparation_id=?", Integer.class, id));
    }

    @Test
    void failedProofAttemptsPersistAcrossRejectedTransactionsAndExpire() {
        var a = member();
        UUID user = owner(a), id = preparation(a);
        String code = issue(user, id);
        db.update("UPDATE preparation_deletion_proof SET expires_at=now()-interval '1 second' WHERE preparation_id=?", id);
        assertEquals(400, assertThrows(ApiException.class, () -> deletion.delete(user, id, code(code), remoteAddress)).status);
        for (int n = 0; n < 4; n++)
            assertEquals(400, assertThrows(ApiException.class, () -> deletion.delete(user, id, new PreparationDeletionService.Confirmation(1, "준비 삭제", "wrong", null), remoteAddress)).status);
        assertEquals(5, db.queryForObject("SELECT count(*) FROM login_attempt WHERE account_key=? AND successful IS DISTINCT FROM true", Integer.class, accountKey(user)));
        assertEquals(429, assertThrows(ApiException.class, () -> deletion.delete(user, id, password(1), remoteAddress)).status);
        db.update("UPDATE login_attempt SET created_at=now()-interval '16 minutes' WHERE account_key=?", accountKey(user));
        deletion.delete(user, id, password(1), remoteAddress);
    }

    @Test
    void trialRequiresOnlyItsOwnValidSessionAndExplicitConfirmation() throws Exception {
        var trial = auth.startTrial(null);
        users.add(owner(trial));
        UUID id = preparation(trial);
        mvc.perform(delete("/api/v1/preparations/" + id).header("Authorization", "Bearer " + trial.accessToken())
                        .contentType("application/json").content("{\"revision\":1,\"confirmation\":\"준비 삭제\"}"))
                .andExpect(status().isNoContent());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM preparation WHERE id=?", Integer.class, id));
    }

    @Test
    void twoConcurrentDeletionRequestsCannotApplyTwice() throws Exception {
        var a = member();
        UUID user = owner(a), id = preparation(a);
        var workers = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                results.add(workers.submit(() -> {
                    start.await();
                    try {
                        deletion.delete(user, id, password(1), remoteAddress);
                        return 204;
                    } catch (ApiException e) {
                        return e.status;
                    }
                }));
            start.countDown();
            var codes = new ArrayList<Integer>();
            for (var result : results) codes.add(result.get(20, TimeUnit.SECONDS));
            Collections.sort(codes);
            assertEquals(List.of(204, 404), codes);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
