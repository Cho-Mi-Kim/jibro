package org.jibro.member;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jibro.auth.AccountMail;
import org.jibro.global.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@EnableScheduling
public class ReminderDelivery {
    private static final Logger log = LoggerFactory.getLogger(ReminderDelivery.class);
    private final ReminderRepository repository;
    private final AlertService alerts;
    private final AccountMail mail;
    private final BrowserPush push;
    private final String frontend;
    private final AtomicBoolean running = new AtomicBoolean();

    public record Channels(boolean emailEnabled, boolean emailVerified, String mailMode, String publicKey) {
    }

    public ReminderDelivery(ReminderRepository repository, AlertService alerts, AccountMail mail, BrowserPush push, @Value("${app.auth.frontend-url}") String frontend) {
        this.repository = repository;
        this.alerts = alerts;
        this.mail = mail;
        this.push = push;
        this.frontend = frontend;
    }

    public Channels channels(UUID user) {
        var channels = repository.channels(user);
        return new Channels(channels.emailEnabled(), channels.emailVerified(), mail.mode(), push.publicKey());
    }

    public void email(UUID user, boolean enabled) {
        var c = channels(user);
        if (enabled && (!c.emailVerified() || c.mailMode().equals("unavailable")))
            throw new ApiException(409, "EMAIL_NOT_READY", "이메일 확인과 발송 설정이 필요합니다.");
        repository.setEmailEnabled(user, enabled);
    }

    public void subscribe(UUID user, String endpoint) {
        if (push.publicKey() == null) throw new ApiException(503, "PUSH_UNAVAILABLE", "서버 푸시 키 설정이 필요합니다.");
        BrowserPush.endpoint(endpoint);
        // A new signed-in owner must explicitly subscribe this browser again.
        repository.subscribe(UUID.randomUUID(), user, endpoint);
    }

    public void subscribe(UUID user, UUID expectedUser, String endpoint) {
        if (!user.equals(expectedUser))
            throw new ApiException(409, "SESSION_CHANGED", "로그인 계정이 바뀌었습니다. 현재 계정에서 다시 구독해 주세요.");
        subscribe(user, endpoint);
    }

    public boolean subscribed(UUID user, String endpoint) {
        return repository.subscribed(user, endpoint);
    }

    public void unsubscribe(UUID user, String endpoint) {
        repository.unsubscribe(user, endpoint);
    }

    @Scheduled(initialDelayString = "${JIBRO_REMINDER_INITIAL_MS:45000}", fixedDelayString = "${JIBRO_REMINDER_PERIOD_MS:60000}")
    public void dispatch() {
        if (!running.compareAndSet(false, true)) return;
        try {
            var users = repository.eligibleUsers();
            int failures = 0;
            for (UUID user : users) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    deliver(user);
                } catch (RuntimeException error) {
                    // A concurrently closed account or one invalid record cannot starve other recipients.
                    failures++;
                }
            }
            if (failures > 0) log.warn("Reminder dispatch could not process {} account(s)", failures);
        } finally {
            running.set(false);
        }
    }

    private void deliver(UUID user) {
        var c = channels(user);
        var devices = repository.devices(user);
        for (var alert : alerts.inbox(user).items()) {
            if (alert.read()) continue;
            if (c.emailEnabled() && c.emailVerified() && !c.mailMode().equals("unavailable") && repository.claimDelivery(user, alert.key(), "email")
                && repository.deliveryAllowed(user, "email")) {
                try {
                    var email = repository.verifiedEmail(user);
                    mail.send(email, "집으로 · 공고 알림", alert.label() + "\n" + alert.title() + "\n" + (alert.date() == null ? "" : alert.date()) + "\n" + frontend + "/alerts");
                    repository.markDelivered(user, alert.key(), "email");
                } catch (RuntimeException ex) {/* Retry in the persisted delivery window. */}
            }
            if (push.publicKey() == null) continue;
            for (var device : devices) {
                String channel = device.id().toString();
                if (!repository.claimDelivery(user, alert.key(), channel) || !repository.deliveryAllowed(user, channel))
                    continue;
                try {
                    switch (PushDeliveryPolicy.outcome(push.send(device.endpoint()))) {
                        case ACCEPTED -> repository.markDelivered(user, alert.key(), channel);
                        case EXPIRED -> {
                            repository.removeDevice(user, device.id());
                            repository.stopRetry(user, alert.key(), channel);
                        }
                        case REJECTED -> repository.stopRetry(user, alert.key(), channel);
                        case RETRY -> { /* The persisted attempt window remains eligible. */ }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {/* Expiring or failed deliveries do not change read state. */}
            }
        }
    }
}
