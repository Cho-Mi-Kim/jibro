package org.jibro.member;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRepository {
    public record Preferences(boolean enabled, int leadDays, boolean includeSaved) {
    }

    public record Target(UUID id, int initialVersion, Instant versionPublishedAt) {
    }

    private final JdbcTemplate db;

    public AlertRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void savePreferences(UUID owner, boolean enabled, int leadDays, boolean includeSaved) {
        db.update("INSERT INTO alert_preferences(user_id,enabled,lead_days,include_saved) VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET enabled=EXCLUDED.enabled,lead_days=EXCLUDED.lead_days,include_saved=EXCLUDED.include_saved", owner, enabled, leadDays, includeSaved);
    }

    public Optional<Preferences> findPreferences(UUID owner) {
        return db.query("SELECT enabled,lead_days,include_saved FROM alert_preferences WHERE user_id=?",
                (r, i) -> new Preferences(r.getBoolean(1), r.getInt(2), r.getBoolean(3)), owner).stream().findFirst();
    }

    public List<String> findReadKeys(UUID owner) {
        return db.queryForList("SELECT event_key FROM alert_read WHERE user_id=?", String.class, owner);
    }

    public List<Target> findTargets(UUID owner, boolean includeSaved) {
        return db.query("""
                SELECT n.id,min(w.version) AS initial,
                       (SELECT v.published_at FROM published_notice_version v
                        WHERE v.notice_id=n.id AND v.version=n.version ORDER BY v.revision LIMIT 1) AS published_at
                FROM notice n JOIN
                (SELECT notice_id,notice_version AS version FROM preparation WHERE user_id=? AND archived_at IS NULL
                 UNION ALL SELECT notice_id,saved_version FROM saved_notice WHERE user_id=? AND ?) w ON n.id=w.notice_id
                WHERE n.basic_review='PASS' AND NOT n.service_excluded AND (n.source_key IS NULL OR n.source_key NOT LIKE 'local-sample-%') GROUP BY n.id
                """, (rs, row) -> {
            var published = rs.getObject("published_at", OffsetDateTime.class);
            return new Target(rs.getObject("id", UUID.class), rs.getInt("initial"), published == null ? null : published.toInstant());
        }, owner, owner, includeSaved);
    }

    public void markRead(UUID owner, String key) {
        db.update("INSERT INTO alert_read VALUES(?,?) ON CONFLICT DO NOTHING", owner, key);
    }
}
