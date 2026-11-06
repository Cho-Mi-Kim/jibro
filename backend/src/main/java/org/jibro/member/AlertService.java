package org.jibro.member;

import java.time.*;
import java.util.*;

import jakarta.validation.constraints.*;
import org.jibro.global.common.ApiException;
import org.jibro.notice.HousingModels.Notice;
import org.jibro.notice.NoticeService;
import org.jibro.notice.NoticeSchedules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {
    public record Settings(boolean enabled, @Min(1) @Max(7) int leadDays, boolean includeSaved) {
    }

    public record Alert(String key, UUID noticeId, String title, String label, LocalDate date, boolean read) {
    }

    public record Inbox(Settings settings, List<Alert> items) {
    }

    private final JdbcTemplate db;
    private final NoticeService notices;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public AlertService(JdbcTemplate db, NoticeService notices) {
        this.db = db;
        this.notices = notices;
    }

    public void configure(UUID owner, Settings s) {
        if (!List.of(1, 3, 7).contains(s.leadDays()))
            throw new ApiException(400, "INVALID_LEAD", "1일, 3일, 7일 중 선택해 주세요.");
        db.update("INSERT INTO alert_preferences(user_id,enabled,lead_days,include_saved) VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET enabled=EXCLUDED.enabled,lead_days=EXCLUDED.lead_days,include_saved=EXCLUDED.include_saved", owner, s.enabled(), s.leadDays(), s.includeSaved());
    }

    @Transactional(readOnly = true)
    public Inbox inbox(UUID owner) {
        var settings = db.query("SELECT enabled,lead_days,include_saved FROM alert_preferences WHERE user_id=?", (r, i) -> new Settings(r.getBoolean(1), r.getInt(2), r.getBoolean(3)), owner).stream().findFirst().orElse(new Settings(false, 3, true));
        if (!settings.enabled()) return new Inbox(settings, List.of());
        var read = new HashSet<>(db.queryForList("SELECT event_key FROM alert_read WHERE user_id=?", String.class, owner));
        var rows = db.queryForList("""
            SELECT n.id,min(w.version) AS initial FROM notice n JOIN
            (SELECT notice_id,notice_version AS version FROM preparation WHERE user_id=? AND archived_at IS NULL
             UNION ALL SELECT notice_id,saved_version FROM saved_notice WHERE user_id=? AND ?) w ON n.id=w.notice_id
            WHERE n.basic_review='PASS' GROUP BY n.id
            """, owner, owner, settings.includeSaved());
        var result = new ArrayList<Alert>();
        var today = LocalDate.now(SEOUL);
        for (var row : rows) {
            UUID id = (UUID) row.get("id");
            Notice notice = notices.notice(id);
            if (notice.version() > ((Number) row.get("initial")).intValue())
                add(result, read, notice, "version-" + notice.version(), "공고 안내가 변경됐어요", null);
            for (var due : NoticeSchedules.reminders(notice))
                addDue(result, read, notice, due.id(), due.title(), due.date(), today, settings.leadDays());
        }
        result.sort(Comparator.comparing(Alert::date, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(Alert::key));
        return new Inbox(settings, result);
    }

    private void addDue(List<Alert> out, Set<String> read, Notice n, String id, String label, LocalDate date, LocalDate today, int days) {
        if (date != null && !date.isBefore(today) && !date.isAfter(today.plusDays(days)))
            add(out, read, n, "date-" + id + "-" + date, label, date);
    }

    private void add(List<Alert> result, Set<String> read, Notice n, String suffix, String label, LocalDate date) {
        String key = UUID.nameUUIDFromBytes((n.id() + ":" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        result.add(new Alert(key, UUID.fromString(n.id()), n.title(), label, date, read.contains(key)));
    }

    @Transactional
    public void read(UUID owner, List<String> keys) {
        var allowed = new HashSet<>(inbox(owner).items().stream().map(Alert::key).toList());
        for (String key : new HashSet<>(keys))
            if (allowed.contains(key))
                db.update("INSERT INTO alert_read VALUES(?,?) ON CONFLICT DO NOTHING", owner, key);
    }
}
