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

@SpringBootTest(properties = "JIBRO_REMINDER_INITIAL_MS=3600000")
@ActiveProfiles("test")
@Transactional
class ReminderSecurityIntegrationTest {
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
    @MockitoBean
    AccountMail mail;

    @Test
    void onlyVerifiedOptedInAccountsReceiveEachEventOnce() {
        String email = UUID.randomUUID() + "@example.invalid";
        when(mail.mode()).thenReturn("local");
        auth.signup("알림 검증", email, "integration-password");
        UUID user = UUID.fromString(auth.login(email, "integration-password").user().id());
        assertThrows(ApiException.class, () -> delivery.email(user, true));
        accounts.issue(user, "verify");
        var delivered = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(email), contains("이메일 확인"), delivered.capture());
        String token = delivered.getValue().split("#verify=")[1].split("\\s")[0];
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
}
