package org.jibro.member;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReminderRepository {
    public record Channels(boolean emailEnabled, boolean emailVerified) {
    }

    public record Device(UUID id, String endpoint) {
    }

    private final JdbcTemplate db;

    public ReminderRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Channels channels(UUID user) {
        return db.queryForObject("SELECT coalesce(a.email_enabled,false),u.email_verified FROM app_user u LEFT JOIN alert_preferences a ON a.user_id=u.id WHERE u.id=? AND NOT u.disabled",
                (r, i) -> new Channels(r.getBoolean(1), r.getBoolean(2)), user);
    }

    public void setEmailEnabled(UUID user, boolean enabled) {
        db.update("INSERT INTO alert_preferences(user_id,enabled,lead_days,include_saved,email_enabled) VALUES(?,false,3,true,?) ON CONFLICT(user_id) DO UPDATE SET email_enabled=EXCLUDED.email_enabled", user, enabled);
    }

    public void subscribe(UUID id, UUID user, String endpoint) {
        db.update("INSERT INTO push_device(id,user_id,endpoint) VALUES(?,?,?) ON CONFLICT(endpoint) DO UPDATE SET user_id=EXCLUDED.user_id", id, user, endpoint);
    }

    public boolean subscribed(UUID user, String endpoint) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM push_device WHERE user_id=? AND endpoint=?)", Boolean.class, user, endpoint));
    }

    public void unsubscribe(UUID user, String endpoint) {
        db.update("DELETE FROM push_device WHERE user_id=? AND endpoint=?", user, endpoint);
    }

    public boolean claimDelivery(UUID owner, String event, String channel) {
        return db.update("""
                INSERT INTO alert_delivery(user_id,event_key,channel,attempts,next_attempt) VALUES(?,?,?,1,now()+interval '10 minutes')
                ON CONFLICT(user_id,event_key,channel) DO UPDATE SET attempts=alert_delivery.attempts+1,next_attempt=now()+interval '10 minutes'
                WHERE NOT alert_delivery.delivered AND alert_delivery.next_attempt<=now() AND alert_delivery.attempts<4
                """, owner, event, channel) > 0;
    }

    public void markDelivered(UUID user, String key, String channel) {
        db.update("UPDATE alert_delivery SET delivered=true WHERE user_id=? AND event_key=? AND channel=?", user, key, channel);
    }

    public void stopRetry(UUID user, String key, String channel) {
        db.update("UPDATE alert_delivery SET attempts=4 WHERE user_id=? AND event_key=? AND channel=? AND NOT delivered", user, key, channel);
    }

    public boolean deliveryAllowed(UUID user, String channel) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM alert_preferences a JOIN app_user u ON u.id=a.user_id
                    WHERE a.user_id=? AND a.enabled AND NOT u.disabled AND u.trial_expires_at IS NULL
                      AND ((?='email' AND a.email_enabled AND u.email_verified)
                           OR EXISTS(SELECT 1 FROM push_device d WHERE d.user_id=u.id AND d.id::text=?)))
                """, Boolean.class, user, channel, channel));
    }

    public List<UUID> eligibleUsers() {
        return db.queryForList("SELECT a.user_id FROM alert_preferences a JOIN app_user u ON u.id=a.user_id WHERE a.enabled AND NOT u.disabled AND u.trial_expires_at IS NULL AND (a.email_enabled OR EXISTS(SELECT 1 FROM push_device d WHERE d.user_id=a.user_id))", UUID.class);
    }

    public List<Device> devices(UUID user) {
        return db.query("SELECT id,endpoint FROM push_device WHERE user_id=?",
                (rs, row) -> new Device(rs.getObject("id", UUID.class), rs.getString("endpoint")), user);
    }

    public String verifiedEmail(UUID user) {
        return db.queryForObject("SELECT email FROM app_user WHERE id=? AND email_verified AND NOT disabled", String.class, user);
    }

    public void removeDevice(UUID user, UUID id) {
        db.update("DELETE FROM push_device WHERE id=? AND user_id=?", id, user);
    }
}
