package org.jibro.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;

import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordChangeSecurityIntegrationTest {
    private static final String PASSWORD = "password-change-test-123";
    @Autowired
    AuthService auth;
    @Autowired
    AccountService accounts;
    @Autowired
    AccountConnections connections;
    @Autowired
    LoginAttemptLimiter limits;
    @Autowired
    JdbcTemplate db;
    @Autowired
    MockMvc mvc;
    @Autowired
    JwtDecoder decoder;
    private String email;
    private AuthService.Tokens session;

    @BeforeEach
    void ownCommittedAccountInTestDatabase() {
        assertEquals("jibro_test", db.queryForObject("SELECT current_database()", String.class));
        email = "password-limit-" + UUID.randomUUID() + "@example.invalid";
        auth.signup("보안 검사", email, PASSWORD);
        session = auth.login(email, PASSWORD, "fixture-" + UUID.randomUUID());
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        if (session != null) {
            UUID id = UUID.fromString(session.user().id());
            db.update("DELETE FROM refresh_token WHERE user_id=?", id);
            db.update("DELETE FROM app_user WHERE id=?", id);
        }
        if (email != null) db.update("DELETE FROM login_attempt WHERE account_key=?", limits.key("account:" + email));
    }

    @Test
    void connectionFailuresAndPasswordChangesCannotUseSeparateGuessingBudgets() {
        UUID user = UUID.fromString(session.user().id());
        for (int i = 0; i < 4; i++)
            assertEquals(400, assertThrows(ApiException.class, () -> connections.disconnect(user, "google", new AccountConnections.Proof("wrong-password", null), "connection-" + UUID.randomUUID())).status);
        assertEquals(400, assertThrows(ApiException.class, () -> accounts.password(user, "wrong-password", "new-password-test-123", "change-" + UUID.randomUUID())).status);
        var blocked = assertThrows(ApiException.class, () -> connections.disconnect(user, "google", new AccountConnections.Proof(PASSWORD, null), "correct-" + UUID.randomUUID()));
        assertEquals(429, blocked.status);
        assertEquals("REAUTHENTICATION_LIMIT", blocked.code);
        assertEquals(429, assertThrows(ApiException.class, () -> auth.login(email, PASSWORD, "another-" + UUID.randomUUID())).status);
    }

    @Test
    void passwordFailuresRemainCommittedAndCorrectPasswordCannotBypassTheLimit() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(put("/api/v1/member/password").with(csrf())
                            .header("Authorization", "Bearer " + session.accessToken())
                            .contentType("application/json")
                            .content("{\"current\":\"wrong-password\",\"password\":\"new-password-test-123\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/member/password").with(csrf())
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType("application/json")
                        .content("{\"current\":\"" + PASSWORD + "\",\"password\":\"new-password-test-123\"}"))
                .andExpect(status().isTooManyRequests());
        decoder.decode(session.accessToken());
    }

    @Test
    void loginAndPasswordChangeShareTheAccountLimitEvenAcrossDifferentAddresses() {
        UUID user = UUID.fromString(session.user().id());
        for (int i = 0; i < 4; i++)
            assertEquals(401, assertThrows(ApiException.class, () -> auth.login(email, "wrong-password", "login-" + UUID.randomUUID())).status);
        assertEquals(400, assertThrows(ApiException.class, () -> accounts.password(user, "wrong-password", "new-password-test-123", "change-" + UUID.randomUUID())).status);
        assertEquals(429, assertThrows(ApiException.class, () -> accounts.password(user, PASSWORD, "new-password-test-123", "another-" + UUID.randomUUID())).status);
        assertEquals(429, assertThrows(ApiException.class, () -> auth.login(email, PASSWORD, "login-again-" + UUID.randomUUID())).status);
    }

    @Test
    void correctPasswordStillChangesCredentialsAndRevokesPreviousSessions() throws Exception {
        mvc.perform(put("/api/v1/member/password").with(csrf())
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType("application/json")
                        .content("{\"current\":\"" + PASSWORD + "\",\"password\":\"new-password-test-123\"}"))
                .andExpect(status().isOk());
        assertThrows(JwtException.class, () -> decoder.decode(session.accessToken()));
        assertEquals(401, assertThrows(ApiException.class, () -> auth.refresh(session.refresh())).status);
        var next = auth.login(email, "new-password-test-123", "new-login-" + UUID.randomUUID());
        assertEquals(session.user().id(), decoder.decode(next.accessToken()).getSubject());
    }

    @Test
    void malformedNewPasswordDoesNotConsumeTheCredentialFailureBudget() {
        UUID user = UUID.fromString(session.user().id());
        for (int i = 0; i < 6; i++)
            assertEquals(400, assertThrows(ApiException.class, () -> accounts.password(user, PASSWORD, "가".repeat(25), "192.0.2.31")).status);
        accounts.password(user, PASSWORD, "valid-new-password-123", "192.0.2.31");
        assertEquals(0, db.queryForObject("SELECT count(*) FROM login_attempt WHERE account_key=? AND successful IS DISTINCT FROM true", Integer.class, limits.key("account:" + email)));
    }

    @Test
    void concurrentFailuresCannotExceedTheReservedAccountBudget() throws Exception {
        UUID user = UUID.fromString(session.user().id());
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(6);
        try {
            var outcomes = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 6; i++)
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        accounts.password(user, "wrong-password", "new-password-test-123", "parallel-" + UUID.randomUUID());
                        return 200;
                    } catch (ApiException failure) {
                        return failure.status;
                    }
                }));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var outcome : outcomes) statuses.add(outcome.get(20, TimeUnit.SECONDS));
            assertEquals(5, Collections.frequency(statuses, 400));
            assertEquals(1, Collections.frequency(statuses, 429));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
