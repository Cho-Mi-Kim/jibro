package org.jibro.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import jakarta.servlet.http.Cookie;
import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Committed transactions exercise the same limiter/session boundaries as HTTP requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginSecurityIntegrationTest {
    @Autowired
    AuthService auth;
    @Autowired
    LoginAttemptLimiter limits;
    @Autowired
    JdbcTemplate db;
    @Autowired
    JwtDecoder decoder;
    @Autowired
    JwtEncoder encoder;
    @Autowired
    MockMvc mvc;
    private final List<UUID> users = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();
    private final List<String> addresses = new ArrayList<>();
    private static final String PASSWORD = "login-test-password-123";

    @BeforeEach
    void isolatedDatabaseOnly() {
        assertEquals("jibro_test", db.queryForObject("SELECT current_database()", String.class));
    }

    @AfterEach
    void cleanupOnlyOwnFixtures() {
        for (UUID user : users) {
            db.update("DELETE FROM refresh_token WHERE user_id=?", user);
            db.update("DELETE FROM app_user WHERE id=?", user);
        }
        for (String email : emails)
            db.update("DELETE FROM login_attempt WHERE account_key=?", limits.key("account:" + email.trim().toLowerCase(Locale.ROOT)));
        for (String address : addresses)
            db.update("DELETE FROM login_attempt WHERE address_key=?", limits.key("address:" + address));
    }

    private String email() {
        String email = "login-security-" + UUID.randomUUID() + "@example.invalid";
        emails.add(email);
        return email;
    }

    private String address() {
        String address = "test-" + UUID.randomUUID();
        addresses.add(address);
        return address;
    }

    private String member() {
        String email = email();
        auth.signup("세션 검증", email, PASSWORD);
        users.add(db.queryForObject("SELECT id FROM app_user WHERE email=?", UUID.class, email));
        return email;
    }

    private String sid(AuthService.Tokens tokens) {
        return decoder.decode(tokens.accessToken()).getClaimAsString("sid");
    }

    @Test
    void rejectedRefreshClearsLegacyTrialCookieSoFreshSignInCanRecover() throws Exception {
        var previous = auth.startTrial(null);
        UUID previousUser = UUID.fromString(previous.user().id());
        users.add(previousUser);
        // V13 invalidates legacy refresh tokens without inventing a server-side session.
        db.update("UPDATE refresh_token SET revoked=true,session_id=NULL WHERE user_id=?", previousUser);
        db.update("DELETE FROM auth_session WHERE user_id=?", previousUser);
        assertThrows(JwtException.class, () -> decoder.decode(previous.accessToken()));
        assertThrows(ApiException.class, () -> auth.startTrial(previous.refresh()));
        mvc.perform(post("/api/v1/auth/refresh").with(csrf()).cookie(new Cookie("jibro_refresh", previous.refresh())))
            .andExpect(status().isUnauthorized())
            .andExpect(cookie().maxAge("jibro_refresh", 0))
            .andExpect(cookie().path("jibro_refresh", "/api/v1/auth"))
            .andExpect(cookie().httpOnly("jibro_refresh", true));
        // Once the browser honors the deletion cookie, a new trial has a distinct valid session.
        var fresh = auth.startTrial(null);
        users.add(UUID.fromString(fresh.user().id()));
        assertNotEquals(previous.user().id(), fresh.user().id());
        decoder.decode(fresh.accessToken());
        assertThrows(ApiException.class, () -> auth.refresh(previous.refresh()));
    }

    @Test
    void logoutImmediatelyRejectsCurrentAccessAndRefreshButKeepsAnotherDevice() throws Exception {
        String email = member();
        var a = auth.login(email, PASSWORD, address());
        var b = auth.login(email, PASSWORD, address());
        String session = sid(a);
        assertNotEquals(session, sid(b));
        var renewed = auth.refresh(a.refresh());
        assertEquals(session, sid(renewed));
        decoder.decode(a.accessToken());

        mvc.perform(post("/api/v1/auth/logout").with(csrf()).cookie(new Cookie("jibro_refresh", renewed.refresh())))
            .andExpect(status().isNoContent()).andExpect(cookie().maxAge("jibro_refresh", 0));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + a.accessToken()))
            .andExpect(status().isUnauthorized());
        assertThrows(JwtException.class, () -> decoder.decode(renewed.accessToken()));
        assertThrows(ApiException.class, () -> auth.refresh(renewed.refresh()));
        assertEquals(b.user().id(), auth.refresh(b.refresh()).user().id());
    }

    @Test
    void rotatedMemberCookieStillRevokesTheSameSessionAndLogoutRequiresCsrf() throws Exception {
        var a = auth.login(member(), PASSWORD, address());
        var next = auth.refresh(a.refresh());
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("jibro_refresh", a.refresh())))
            .andExpect(status().isForbidden());
        decoder.decode(next.accessToken());
        // Supports the case where refresh won a race before logout acquired the owner lock.
        auth.logout(a.refresh());
        assertThrows(JwtException.class, () -> decoder.decode(next.accessToken()));
        assertThrows(ApiException.class, () -> auth.refresh(next.refresh()));
    }

    @Test
    void concurrentLogoutAndRefreshCannotLeaveAUsableCurrentSession() throws Exception {
        String email = member();
        var current = auth.login(email, PASSWORD, address());
        var otherDevice = auth.login(email, PASSWORD, address());
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<AuthService.Tokens> refreshed = workers.submit(() -> {
                start.await();
                try {
                    return auth.refresh(current.refresh());
                } catch (ApiException failure) {
                    assertEquals(401, failure.status);
                    return null;
                }
            });
            Future<?> loggedOut = workers.submit(() -> {
                start.await();
                auth.logout(current.refresh());
                return null;
            });
            start.countDown();
            var next = refreshed.get(20, TimeUnit.SECONDS);
            loggedOut.get(20, TimeUnit.SECONDS);
            assertThrows(JwtException.class, () -> decoder.decode(current.accessToken()));
            if (next != null) {
                assertThrows(JwtException.class, () -> decoder.decode(next.accessToken()));
                assertThrows(ApiException.class, () -> auth.refresh(next.refresh()));
            }
            decoder.decode(otherDevice.accessToken());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void validBearerCanLogoutItsOwnSessionWithoutACookie() throws Exception {
        var a = auth.login(member(), PASSWORD, address());
        mvc.perform(post("/api/v1/auth/logout").with(csrf()).header("Authorization", "Bearer " + a.accessToken()))
            .andExpect(status().isNoContent());
        assertThrows(JwtException.class, () -> decoder.decode(a.accessToken()));
    }

    @Test
    void oldUnboundJwtAndAnotherOwnersSessionAreRejected() {
        var a = auth.login(member(), PASSWORD, address());
        var b = auth.login(member(), PASSWORD, address());
        var issued = decoder.decode(a.accessToken());
        for (String sid : Arrays.asList(null, sid(b), "not-a-session")) {
            var claims = JwtClaimsSet.builder().issuer("jibro").audience(List.of("jibro-web"))
                .subject(a.user().id()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900))
                .claim("role", "USER").claim("sv", issued.getClaim("sv"));
            if (sid != null) claims.claim("sid", sid);
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
            assertThrows(JwtException.class, () -> decoder.decode(token));
        }
    }

    @Test
    void knownAndUnknownAccountsHaveSameFailuresAndFiveAttemptLimit() {
        String existing = member(), missing = email();
        for (String email : List.of(existing, missing)) {
            String ip = address();
            for (int i = 0; i < 5; i++) {
                var failure = assertThrows(ApiException.class, () -> auth.login(" " + email.toUpperCase(Locale.ROOT) + " ", "wrong-password", ip));
                assertEquals(401, failure.status);
                assertEquals("UNAUTHORIZED", failure.code);
                assertEquals("로그인 정보를 확인해 주세요.", failure.getMessage());
            }
            var blocked = assertThrows(ApiException.class, () -> auth.login(email, PASSWORD, ip));
            assertEquals(429, blocked.status);
            assertEquals("LOGIN_RATE_LIMIT", blocked.code);
            assertEquals(5, db.queryForObject("SELECT count(*) FROM login_attempt WHERE account_key=? AND successful=false", Integer.class, limits.key("account:" + email)));
        }
    }

    @Test
    void accountLimitSurvivesChangedIpAndExpiresAfterFifteenMinutes() {
        String email = member();
        for (int i = 0; i < 5; i++) {
            String ip = address();
            assertThrows(ApiException.class, () -> auth.login(email, "wrong", ip));
        }
        assertEquals(429, assertThrows(ApiException.class, () -> auth.login(email, PASSWORD, address())).status);
        db.update("UPDATE login_attempt SET created_at=clock_timestamp()-interval '16 minutes' WHERE account_key=?", limits.key("account:" + email));
        assertEquals(email, auth.login(email, PASSWORD, address()).user().email());
    }

    @Test
    void successfulLoginsDoNotConsumeFailureBudgetAndStoredKeysAreNotPersonalPlaintext() {
        String email = member(), ip = address();
        for (int i = 0; i < 7; i++) auth.login(email, PASSWORD, ip);
        var row = db.queryForMap("SELECT account_key,address_key FROM login_attempt WHERE account_key=? LIMIT 1", limits.key("account:" + email));
        assertNotEquals(email, row.get("account_key"));
        assertNotEquals(ip, row.get("address_key"));
        assertEquals(64, row.get("account_key").toString().length());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM login_attempt WHERE account_key=? AND successful IS DISTINCT FROM true", Integer.class, limits.key("account:" + email)));
    }

    @Test
    void ipBudgetCannotBeBypassedWithSpoofedForwardingHeaders() throws Exception {
        String ip = "203.0.113.87";
        addresses.add(ip);
        for (int i = 0; i < 30; i++) limits.begin(email(), ip);
        String candidate = email();
        mvc.perform(post("/api/v1/auth/login").with(csrf()).with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .header("X-Forwarded-For", "198.51.100.80").header("X-Real-IP", "198.51.100.81")
                .contentType("application/json").content("{\"email\":\"" + candidate + "\",\"password\":\"wrong\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMIT"));
        // The same unknown account on a different real address is not IP-blocked.
        assertEquals(401, assertThrows(ApiException.class, () -> auth.login(candidate, "wrong", address())).status);
    }

    @Test
    void concurrentRequestsReserveOnlyFiveChecksAcrossDatabaseTransactions() throws Exception {
        String email = email();
        List<String> ips = new ArrayList<>();
        for (int i = 0; i < 10; i++) ips.add(address());
        var workers = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String ip : ips)
                results.add(workers.submit(() -> {
                    start.await();
                    try {
                        auth.login(email, "wrong", ip);
                        return 200;
                    } catch (ApiException e) {
                        return e.status;
                    }
                }));
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (var result : results) codes.add(result.get(20, TimeUnit.SECONDS));
            assertEquals(5, codes.stream().filter(code -> code == 401).count());
            assertEquals(5, codes.stream().filter(code -> code == 429).count());
            assertEquals(5, db.queryForObject("SELECT count(*) FROM login_attempt WHERE account_key=?", Integer.class, limits.key("account:" + email)));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
