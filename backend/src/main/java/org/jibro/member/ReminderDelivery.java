package org.jibro.member;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jibro.auth.AccountMail;
import org.jibro.global.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class ReminderDelivery {
    private final JdbcTemplate db;
    private final AlertService alerts;
    private final AccountMail mail;
    private final BrowserPush push;
    private final String frontend;
    private final AtomicBoolean running = new AtomicBoolean();

    public record Channels(boolean emailEnabled, boolean emailVerified, String mailMode, String publicKey) {
    }

    public ReminderDelivery(JdbcTemplate db, AlertService alerts, AccountMail mail, BrowserPush push, @Value("${app.auth.frontend-url}") String frontend) {
        this.db = db;
        this.alerts = alerts;
        this.mail = mail;
        this.push = push;
        this.frontend = frontend;
    }

    public Channels channels(UUID user) {
        return db.queryForObject("SELECT coalesce(a.email_enabled,false),u.email_verified FROM app_user u LEFT JOIN alert_preferences a ON a.user_id=u.id WHERE u.id=? AND NOT u.disabled",
            (r, i) -> new Channels(r.getBoolean(1), r.getBoolean(2), mail.mode(), push.publicKey()), user);
    }

    public void email(UUID user, boolean enabled) {
        var c = channels(user);
        if (enabled && (!c.emailVerified() || c.mailMode().equals("unavailable")))
            throw new ApiException(409, "EMAIL_NOT_READY", "이메일 확인과 발송 설정이 필요합니다.");
        db.update("INSERT INTO alert_preferences(user_id,enabled,lead_days,include_saved,email_enabled) VALUES(?,false,3,true,?) ON CONFLICT(user_id) DO UPDATE SET email_enabled=EXCLUDED.email_enabled", user, enabled);
    }

    public void subscribe(UUID user, String endpoint) {
        if (push.publicKey() == null) throw new ApiException(503, "PUSH_UNAVAILABLE", "서버 푸시 키 설정이 필요합니다.");
        BrowserPush.endpoint(endpoint);
        // A new signed-in owner must explicitly subscribe this browser again.
        db.update("INSERT INTO push_device(id,user_id,endpoint) VALUES(?,?,?) ON CONFLICT(endpoint) DO UPDATE SET user_id=EXCLUDED.user_id", UUID.randomUUID(), user, endpoint);
    }

    public boolean subscribed(UUID user, String endpoint) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM push_device WHERE user_id=? AND endpoint=?)", Boolean.class, user, endpoint));
    }

    public void unsubscribe(UUID user, String endpoint) {
        db.update("DELETE FROM push_device WHERE user_id=? AND endpoint=?", user, endpoint);
    }

    private boolean claim(UUID owner, String event, String channel) {
        return db.update("""
            INSERT INTO alert_delivery(user_id,event_key,channel,attempts,next_attempt) VALUES(?,?,?,1,now()+interval '10 minutes')
            ON CONFLICT(user_id,event_key,channel) DO UPDATE SET attempts=alert_delivery.attempts+1,next_attempt=now()+interval '10 minutes'
            WHERE NOT alert_delivery.delivered AND alert_delivery.next_attempt<=now() AND alert_delivery.attempts<4
            """, owner, event, channel) > 0;
    }

    private void done(UUID user, String key, String channel) {
        db.update("UPDATE alert_delivery SET delivered=true WHERE user_id=? AND event_key=? AND channel=?", user, key, channel);
    }

    @Scheduled(initialDelayString = "${JIBRO_REMINDER_INITIAL_MS:45000}", fixedDelayString = "${JIBRO_REMINDER_PERIOD_MS:60000}")
    public void dispatch() {
        if (!running.compareAndSet(false, true)) return;
        try {
            var users = db.queryForList("SELECT a.user_id FROM alert_preferences a JOIN app_user u ON u.id=a.user_id WHERE a.enabled AND NOT u.disabled AND u.trial_expires_at IS NULL AND (a.email_enabled OR EXISTS(SELECT 1 FROM push_device d WHERE d.user_id=a.user_id))", UUID.class);
            for (UUID user : users) deliver(user);
        } finally {
            running.set(false);
        }
    }

    private void deliver(UUID user) {
        var c = channels(user);
        var devices = db.queryForList("SELECT id,endpoint FROM push_device WHERE user_id=?", user);
        for (var alert : alerts.inbox(user).items()) {
            if (alert.read()) continue;
            if (c.emailEnabled() && c.emailVerified() && !c.mailMode().equals("unavailable") && claim(user, alert.key(), "email")) {
                try {
                    var email = db.queryForObject("SELECT email FROM app_user WHERE id=? AND email_verified AND NOT disabled", String.class, user);
                    mail.send(email, "집으로 · 공고 알림", alert.label() + "\n" + alert.title() + "\n" + (alert.date() == null ? "" : alert.date()) + "\n" + frontend + "/alerts");
                    done(user, alert.key(), "email");
                } catch (RuntimeException ex) {/* Retry in the persisted delivery window. */}
            }
            if (push.publicKey() == null) continue;
            for (var device : devices) {
                String channel = device.get("id").toString();
                if (!claim(user, alert.key(), channel)) continue;
                try {
                    int status = push.send((String) device.get("endpoint"));
                    if (status == 404 || status == 410)
                        db.update("DELETE FROM push_device WHERE id=? AND user_id=?", device.get("id"), user);
                    if (status >= 200 && status < 300 || status == 404 || status == 410)
                        done(user, alert.key(), channel);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {/* Expiring or failed deliveries do not change read state. */}
            }
        }
    }
}
