package org.jibro.member;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.jibro.auth.*;
import org.jibro.global.common.ApiException;
import org.jibro.notice.*;
import org.jibro.preparation.PreparationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "JIBRO_REMINDER_INITIAL_MS=3600000")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class ReminderSecurityIntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    jakarta.persistence.EntityManager entities;
    @Autowired
    AccountService accounts;
    @Autowired
    AuthService auth;
    @Autowired
    NoticeService notices;
    @Autowired
    PreparationService preparations;
    @Autowired
    ReminderDelivery delivery;
    @Autowired
    AlertService alerts;
    @Autowired
    ReminderRepository reminders;
    @MockitoBean
    AccountMail mail;

    @Test
    void onlyVerifiedOptedInAccountsReceiveEachEventOnce() {
        String email = UUID.randomUUID() + "@example.invalid";
        when(mail.mode()).thenReturn("local");
        auth.signup("알림 검증", email, "integration-password");
        UUID user = UUID.fromString(auth.login(email, "integration-password").user().id());
        assertThrows(ApiException.class, () -> delivery.email(user, true));
        String token = verificationToken(user, email);
        accounts.verify(token);
        assertThrows(ApiException.class, () -> accounts.verify(token));
        var day = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var id = notices.insert("test-" + UUID.randomUUID(), "검증 공고", "검증기관", "서울", "임대", day.minusDays(3), day.plusDays(1), day.minusDays(4), SampleData.content());
        notices.samplePublish(id, "PASS");
        entities.flush();
        preparations.start(user.toString(), id, 1);
        alerts.configure(user, new AlertService.Settings(true, 3, false));
        delivery.email(user, true);
        delivery.dispatch();
        delivery.dispatch();
        int count = alerts.inbox(user).items().size();
        assertTrue(count > 0);
        verify(mail, times(count)).send(eq(email), eq("집으로 · 공고 알림"), anyString());
    }

    @Test
    void browserPushRejectsNonProviderAndCredentialUrls() {
        assertThrows(ApiException.class, () -> BrowserPush.endpoint("https://example.com/receive"));
        assertThrows(ApiException.class, () -> BrowserPush.endpoint("https://fcm.googleapis.com@127.0.0.1/receive"));
        assertThrows(ApiException.class, () -> BrowserPush.endpoint("https://fcm.googleapis.com:8443/receive"));
        assertEquals("fcm.googleapis.com", BrowserPush.endpoint("https://fcm.googleapis.com/fcm/send/sample").getHost());
    }

    @Test
    void subscriptionCannotBeRetriedUnderADifferentSignedInOwner() throws Exception {
        UUID initiator = UUID.randomUUID();
        UUID signedIn = UUID.randomUUID();
        String endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
        assertEquals(409, assertThrows(ApiException.class,
            () -> delivery.subscribe(signedIn, initiator, endpoint)).status);
        mvc.perform(put("/api/v1/member/channels/push").with(csrf())
                .with(jwt().jwt(token -> token.subject(signedIn.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + endpoint + "\",\"expectedUserId\":\"" + initiator + "\"}"))
            .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/member/channels/push").with(csrf())
                .with(jwt().jwt(token -> token.subject(signedIn.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + endpoint + "\"}"))
            .andExpect(status().isBadRequest());
        assertFalse(reminders.subscribed(signedIn, endpoint));
        assertFalse(reminders.subscribed(initiator, endpoint));
    }

    @Test
    void persistedClaimsAndCurrentConsentAreCheckedBeforeEachChannelSend() {
        String email = UUID.randomUUID() + "@example.invalid";
        UUID owner = UUID.fromString(auth.social("google", UUID.randomUUID().toString(), "구독 검증", email).user().id());
        UUID nextOwner = UUID.fromString(auth.social("google", UUID.randomUUID().toString(), "다른 사용자", UUID.randomUUID() + "@example.invalid").user().id());
        alerts.configure(owner, new AlertService.Settings(true, 3, true));
        reminders.setEmailEnabled(owner, true);
        assertFalse(reminders.deliveryAllowed(owner, "email"));
        accounts.verify(verificationToken(owner, email));
        assertTrue(reminders.deliveryAllowed(owner, "email"));
        assertTrue(reminders.claimDelivery(owner, "event", "email"));
        assertFalse(reminders.claimDelivery(owner, "event", "email"));
        reminders.markDelivered(owner, "event", "email");
        assertFalse(reminders.claimDelivery(owner, "event", "email"));
        reminders.setEmailEnabled(owner, false);
        assertFalse(reminders.deliveryAllowed(owner, "email"));

        UUID device = UUID.randomUUID();
        String endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
        reminders.subscribe(device, owner, endpoint);
        assertTrue(reminders.deliveryAllowed(owner, device.toString()));
        reminders.subscribe(UUID.randomUUID(), nextOwner, endpoint);
        assertFalse(reminders.deliveryAllowed(owner, device.toString()));
        assertFalse(reminders.subscribed(owner, endpoint));
        assertTrue(reminders.subscribed(nextOwner, endpoint));
        reminders.unsubscribe(owner, endpoint);
        assertTrue(reminders.subscribed(nextOwner, endpoint));
        reminders.unsubscribe(nextOwner, endpoint);
        assertFalse(reminders.subscribed(nextOwner, endpoint));
        alerts.configure(owner, new AlertService.Settings(false, 3, true));
        reminders.setEmailEnabled(owner, true);
        assertFalse(reminders.deliveryAllowed(owner, "email"));
    }

    private String verificationToken(UUID user, String email) {
        when(mail.mode()).thenReturn("local");
        accounts.issue(user, "verify");
        var delivered = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(email), contains("이메일 확인"), delivered.capture());
        return delivered.getValue().split("#verify=")[1].split("\\s")[0];
    }
}
