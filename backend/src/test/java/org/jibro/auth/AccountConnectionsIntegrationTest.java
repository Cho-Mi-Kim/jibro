package org.jibro.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;

import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"GOOGLE_CLIENT_ID=fixture-google", "GOOGLE_CLIENT_SECRET=fixture-secret", "KAKAO_CLIENT_ID=fixture-kakao", "KAKAO_CLIENT_SECRET=fixture-secret", "OAUTH_BASE_URL=http://localhost:8080"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountConnectionsIntegrationTest {
    @Autowired
    LoginAttemptLimiter limits;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired
    AccountConnections connections;
    @Autowired
    AuthService auth;
    @Autowired
    AccountService accounts;
    @Autowired
    JdbcTemplate db;
    @Autowired
    JwtDecoder decoder;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    AccountMail mail;
    @MockitoSpyBean
    OAuthConfig config;
    private final String remoteAddress = "reauth-test-" + UUID.randomUUID();
    private static final String PASSWORD = "fixture-password-123";
    private static final AccountConnections.Proof PASSWORD_PROOF = new AccountConnections.Proof(PASSWORD, null);

    @BeforeEach
    void configureMail() {
        when(mail.mode()).thenReturn("smtp");
    }

    private AuthService.Tokens member() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("연결 테스트", email, PASSWORD);
        return auth.login(email, PASSWORD);
    }

    private UUID id(AuthService.Tokens tokens) {
        return UUID.fromString(tokens.user().id());
    }

    private UUID intent(UUID user, String provider) {
        UUID intent = connections.begin(user, provider, "browser-binding", PASSWORD_PROOF, remoteAddress);
        connections.authorize(intent, provider, "browser-binding", "oauth-state");
        return intent;
    }

    private void linked(UUID user, String provider, String subject) {
        connections.complete(intent(user, provider), provider, subject, "browser-binding", "oauth-state");
    }

    @Test
    void linksExplicitOwnerWithoutMatchingEmailAndPreservesCurrentLogin() {
        var member = member();
        linked(id(member), "google", "new-provider-subject");
        assertEquals(id(member), db.queryForObject("SELECT user_id FROM login_identity WHERE provider='google' AND subject='new-provider-subject'", UUID.class));
        assertEquals(member.user().id(), auth.social("google", "new-provider-subject", "다른 이름", "different@example.invalid").user().id());
        decoder.decode(member.accessToken());
        assertTrue(connections.overview(id(member)).providers().get(0).connected());
    }

    @Test
    void neverStealsIdentityAlreadyLinkedToAnotherOwner() {
        var a = member();
        var b = member();
        linked(id(a), "google", "owned-subject");
        var intent = intent(id(b), "google");
        assertEquals(409, assertThrows(ApiException.class, () -> connections.complete(intent, "google", "owned-subject", "browser-binding", "oauth-state")).status);
        assertEquals(id(a), db.queryForObject("SELECT user_id FROM login_identity WHERE subject='owned-subject'", UUID.class));
        assertThrows(ApiException.class, () -> connections.complete(intent, "google", "different-subject", "browser-binding", "oauth-state"));
    }

    @Test
    void wrongBindingStateProviderExpiryAndReplayAreRejectedAndConsumed() {
        UUID user = id(member());
        for (String failure : List.of("binding", "state", "provider", "expiry", "security")) {
            var intent = intent(user, "google");
            if (failure.equals("expiry"))
                db.update("UPDATE account_connection_intent SET expires_at=now()-interval '1 second' WHERE id=?", intent);
            if (failure.equals("security"))
                db.update("UPDATE app_user SET security_version=security_version+1 WHERE id=?", user);
            assertThrows(ApiException.class, () -> connections.complete(intent, failure.equals("provider") ? "kakao" : "google", "subject", failure.equals("binding") ? "another-browser" : "browser-binding", failure.equals("state") ? "another-state" : "oauth-state"));
            assertThrows(ApiException.class, () -> connections.complete(intent, "google", "subject", "browser-binding", "oauth-state"));
        }
        assertEquals(0, db.queryForObject("SELECT count(*) FROM login_identity WHERE user_id=?", Integer.class, user));
        var success = intent(user, "google");
        connections.complete(success, "google", "subject", "browser-binding", "oauth-state");
        assertThrows(ApiException.class, () -> connections.complete(success, "google", "subject", "browser-binding", "oauth-state"));
    }

    @Test
    void emailProofIsOwnerBoundSingleUseExpiringAndNotStoredPlaintext() {
        UUID a = id(member()), b = id(member());
        connections.requestProof(a);
        var body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(anyString(), anyString(), body.capture());
        String code = body.getValue().split("\n\n")[1];
        assertNotEquals(code, db.queryForObject("SELECT token_hash FROM account_connection_proof WHERE user_id=?", String.class, a));
        var proof = new AccountConnections.Proof(null, code);
        assertThrows(ApiException.class, () -> connections.begin(b, "google", "binding", proof, remoteAddress));
        assertEquals(429, assertThrows(ApiException.class, () -> connections.requestProof(a)).status);
        connections.begin(a, "google", "binding", proof, remoteAddress);
        assertThrows(ApiException.class, () -> connections.begin(a, "kakao", "binding", proof, remoteAddress));
        assertTrue(db.queryForObject("SELECT email_verified FROM app_user WHERE id=?", Boolean.class, a));
        connections.requestProof(a);
        db.update("UPDATE account_connection_proof SET expires_at=now()-interval '1 second' WHERE user_id=?", a);
        assertThrows(ApiException.class, () -> connections.begin(a, "kakao", "binding", proof, remoteAddress));
    }

    @Test
    void rejectsWrongPasswordAndLimitsRepeatedReauthentication() {
        UUID user = id(member());
        for (int n = 0; n < 5; n++)
            assertThrows(ApiException.class, () -> connections.begin(user, "google", "browser", new AccountConnections.Proof("wrong", null), remoteAddress));
        assertEquals(429, assertThrows(ApiException.class, () -> connections.begin(user, "google", "browser", PASSWORD_PROOF, remoteAddress)).status);
        String accountKey = limits.key("account:" + db.queryForObject("SELECT email FROM app_user WHERE id=?", String.class, user));
        var expiry = new org.springframework.transaction.support.TransactionTemplate(transactions);
        expiry.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        expiry.executeWithoutResult(ignored -> db.update("UPDATE login_attempt SET created_at=now()-interval '16 minutes' WHERE account_key=?", accountKey));
        assertNotNull(connections.begin(user, "google", "browser", PASSWORD_PROOF, remoteAddress));
    }

    @Test
    void disconnectPreservesOtherSignInAndRevokesSessionsAndPendingChanges() {
        var member = member();
        UUID user = id(member);
        linked(user, "google", "subject");
        var pending = intent(user, "kakao");
        connections.disconnect(user, "google", PASSWORD_PROOF, remoteAddress);
        assertThrows(JwtException.class, () -> decoder.decode(member.accessToken()));
        assertThrows(ApiException.class, () -> auth.refresh(member.refresh()));
        assertThrows(ApiException.class, () -> connections.complete(pending, "kakao", "second", "browser-binding", "oauth-state"));
        assertEquals(member.user().id(), auth.login(member.user().email(), PASSWORD).user().id());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM login_identity WHERE user_id=?", Integer.class, user));
    }

    @Test
    void socialOnlyAccountCannotRemoveLastUsableLogin() {
        var member = member();
        UUID user = id(member);
        linked(user, "google", "only-subject");
        db.update("UPDATE app_user SET password_hash=NULL WHERE id=?", user);
        assertFalse(connections.overview(user).providers().get(0).canDisconnect());
        connections.requestProof(user);
        var body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(anyString(), anyString(), body.capture());
        String code = body.getValue().split("\n\n")[1];
        assertEquals(409, assertThrows(ApiException.class, () -> connections.disconnect(user, "google", new AccountConnections.Proof(null, code), remoteAddress)).status);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM login_identity WHERE user_id=?", Integer.class, user));
    }

    @Test
    void passwordChangeAndAccountDeletionInvalidatePendingIntentAndProof() {
        var member = member();
        UUID user = id(member);
        var pending = intent(user, "google");
        connections.requestProof(user);
        accounts.password(user, PASSWORD, "changed-password-123", remoteAddress);
        assertThrows(ApiException.class, () -> connections.complete(pending, "google", "subject", "browser-binding", "oauth-state"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM account_connection_proof WHERE user_id=?", Integer.class, user));
        accounts.issue(user, "delete");
        var body = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).send(anyString(), anyString(), body.capture());
        String token = body.getValue().split("#delete=")[1].split("\n")[0];
        var finalIntent = connections.begin(user, "google", "binding", new AccountConnections.Proof("changed-password-123", null), remoteAddress);
        accounts.delete(user, token, "회원 탈퇴");
        assertThrows(ApiException.class, () -> connections.authorize(finalIntent, "google", "binding", "state"));
    }

    @Test
    void disconnectCannotLeaveOnlyAnUnconfiguredProvider() {
        UUID user = id(member());
        linked(user, "google", "google-subject");
        linked(user, "kakao", "kakao-subject");
        db.update("UPDATE app_user SET password_hash=NULL WHERE id=?", user);
        doReturn(List.of("google")).when(config).providers();
        assertFalse(connections.overview(user).providers().get(0).canDisconnect());
        assertTrue(connections.overview(user).providers().get(1).canDisconnect());
        connections.requestProof(user);
        var body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(anyString(), anyString(), body.capture());
        var proof = new AccountConnections.Proof(null, body.getValue().split("\n\n")[1]);
        assertEquals(409, assertThrows(ApiException.class, () -> connections.disconnect(user, "google", proof, remoteAddress)).status);
        assertEquals(2, db.queryForObject("SELECT count(*) FROM login_identity WHERE user_id=?", Integer.class, user));
    }

    @Test
    void endpointsRequireMemberCsrfAndReauthenticationAndNeverExposeLoginTokens() throws Exception {
        var member = member();
        var trial = auth.startTrial(null);
        mvc.perform(get("/api/v1/member/connections")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/member/connections").header("Authorization", "Bearer " + trial.accessToken())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/member/connections/google/start").header("Authorization", "Bearer " + member.accessToken()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()); // Bearer-authenticated API calls do not use cookie authentication.
        mvc.perform(post("/api/v1/member/connections/google/start").with(csrf()).header("Authorization", "Bearer " + member.accessToken()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        var response = mvc.perform(post("/api/v1/member/connections/google/start").with(csrf()).header("Authorization", "Bearer " + member.accessToken()).contentType("application/json").content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authorizationUrl").value("/oauth2/authorization/google?connect=1")).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("Token"));
        var browser = response.getRequest().getSession(false);
        assertNotNull(browser);
        mvc.perform(post("/api/v1/auth/logout").session((MockHttpSession) browser).with(csrf())).andExpect(status().isNoContent());
        assertTrue(((MockHttpSession) browser).isInvalid());
    }

    @Test
    void oauthRequestAndCallbackAreStateAndBrowserBoundAndKeepNormalLoginSeparate() throws Exception {
        var member = member();
        UUID user = id(member);
        var flow = flow();
        var start = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        start.setServletPath("/oauth2/authorization/google");
        start.setParameter("connect", "1");
        var pending = connections.begin(user, "google", "binding", PASSWORD_PROOF, remoteAddress);
        start.getSession().setAttribute(ConnectionOAuthFlow.PENDING, new ConnectionOAuthFlow.Link(pending, "google"));
        start.getSession().setAttribute(ConnectionOAuthFlow.BINDING, "binding");
        var authorization = flow.resolve(start);
        assertNotNull(authorization);
        flow.saveAuthorizationRequest(authorization, start, new MockHttpServletResponse());
        var otherBrowser = new MockHttpServletRequest();
        otherBrowser.setParameter("state", authorization.getState());
        assertNull(flow.removeAuthorizationRequest(otherBrowser, new MockHttpServletResponse()));
        var wrongState = new MockHttpServletRequest();
        wrongState.setSession((MockHttpSession) start.getSession());
        wrongState.setParameter("state", "wrong");
        assertNull(flow.removeAuthorizationRequest(wrongState, new MockHttpServletResponse()));
        var callback = new MockHttpServletRequest();
        callback.setSession((MockHttpSession) start.getSession());
        callback.setParameter("state", authorization.getState());
        assertNotNull(flow.removeAuthorizationRequest(callback, new MockHttpServletResponse()));
        assertNull(flow.removeAuthorizationRequest(callback, new MockHttpServletResponse()));
        var response = new MockHttpServletResponse();
        handler().onAuthenticationSuccess(callback, response, oauthToken("google", "linked-subject"));
        assertEquals("http://localhost/settings?connection=success", response.getRedirectedUrl());
        assertNull(response.getCookie("jibro_refresh"));
        assertEquals(user, db.queryForObject("SELECT user_id FROM login_identity WHERE subject='linked-subject'", UUID.class));
        var normalResponse = new MockHttpServletResponse();
        handler().onAuthenticationSuccess(new MockHttpServletRequest(), normalResponse, oauthToken("google", "linked-subject"));
        assertEquals("http://localhost/auth/callback", normalResponse.getRedirectedUrl());
        assertNotNull(normalResponse.getCookie("jibro_refresh"));
    }

    @Test
    void providerFailureCancelsIntentWithoutCreatingAnIdentityOrReplacingLogin() throws Exception {
        UUID user = id(member());
        var pending = intent(user, "kakao");
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(ConnectionOAuthFlow.PENDING, new ConnectionOAuthFlow.Link(pending, "kakao"));
        var response = new MockHttpServletResponse();
        handler().onAuthenticationFailure(request, response, new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        assertEquals("http://localhost/settings?connection=failed", response.getRedirectedUrl());
        assertNull(response.getCookie("jibro_refresh"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM account_connection_intent WHERE id=?", Integer.class, pending));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM login_identity WHERE user_id=?", Integer.class, user));
    }

    @Test
    void configuredProviderCallbackCannotBeChangedByRequestHost() throws Exception {
        for (String provider : List.of("google", "kakao")) {
            var response = mvc.perform(get("/oauth2/authorization/" + provider)
                            .with(request -> {
                                request.setServerName("untrusted.example.invalid");
                                return request;
                            })
                            .header("Host", "untrusted.example.invalid")
                            .header("X-Forwarded-Host", "untrusted.example.invalid"))
                    .andExpect(status().is3xxRedirection()).andReturn().getResponse();
            String target = java.net.URLDecoder.decode(response.getRedirectedUrl(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(target.contains("redirect_uri=http://localhost:8080/login/oauth2/code/" + provider));
            assertFalse(target.contains("untrusted.example.invalid"));
            assertTrue(target.contains("state="));
            assertFalse(target.contains("client_secret"));
        }
    }

    @Test
    void kakaoVerifiedIdentityUsesTheServiceSessionWithoutReturningTokensInTheUrl() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = UUID.randomUUID() + "@example.invalid";
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", subject, "kakao_account", Map.of("email", email, "is_email_valid", true,
                        "is_email_verified", true, "profile", Map.of("nickname", "카카오 검증"))), "id");
        var token = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao");
        var response = new MockHttpServletResponse();
        handler().onAuthenticationSuccess(new MockHttpServletRequest(), response, token);
        assertEquals("http://localhost/auth/callback", response.getRedirectedUrl());
        assertNotNull(response.getCookie("jibro_refresh"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM login_identity WHERE provider='kakao' AND subject=?", Integer.class, subject));
    }

    private OAuthCompletionHandler handler() {
        return new OAuthCompletionHandler(auth, new RefreshCookie(false), connections, "http://localhost");
    }

    private OAuth2AuthenticationToken oauthToken(String provider, String subject) {
        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("sub", subject, "email_verified", true, "email", "provider@example.invalid", "name", "연결 사용자"), "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), provider);
    }

    private ConnectionOAuthFlow flow() {
        var registration = ClientRegistration.withRegistrationId("google").clientId("fixture").clientSecret("fixture")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("http://localhost/login/oauth2/code/google")
                .authorizationUri("https://provider.invalid/authorize").tokenUri("https://provider.invalid/token")
                .userInfoUri("https://provider.invalid/me").userNameAttributeName("sub").build();
        return new ConnectionOAuthFlow(new InMemoryClientRegistrationRepository(registration), connections);
    }
}
