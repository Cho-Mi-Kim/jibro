package org.jibro.collection.review.ai;

import java.time.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable global caps survive restarts and serialize concurrent workers.
 */
@Component
public class GeminiBudgetRepository {
    public record Reservation(boolean granted, String cached, String reason) {
    }

    private final JdbcTemplate db;

    public GeminiBudgetRepository(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public Reservation reserve(String hash, String model, Instant now, int dailyLimit, int intervalSeconds) {
        db.queryForObject("SELECT pg_advisory_xact_lock(718421006)", Object.class);
        var rows = db.queryForList("SELECT status,result_json,updated_at FROM gemini_review_cache WHERE fingerprint=?", hash);
        if (!rows.isEmpty()) {
            var row = rows.get(0);
            if (!row.get("status").equals("RUNNING"))
                return new Reservation(false, (String) row.get("result_json"), "CACHED");
            if (((java.sql.Timestamp) row.get("updated_at")).toInstant().plusSeconds(120).isAfter(now))
                return new Reservation(false, null, "IN_FLIGHT");
            // A lost request may already have consumed provider quota. Never silently repeat it.
            return new Reservation(false, null, "INTERRUPTED_REVIEW");
        }
        Instant day = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM gemini_review_cache WHERE status IN ('RATE_LIMITED','AUTH_ERROR') AND updated_at>=?)", Boolean.class, java.sql.Timestamp.from(day))))
            return new Reservation(false, null, "PROVIDER_PAUSED");
        long count = db.queryForObject("SELECT count(*) FROM gemini_review_attempt WHERE attempted_at>=?", Long.class, java.sql.Timestamp.from(day));
        if (count >= Math.max(0, Math.min(10, dailyLimit))) return new Reservation(false, null, "DAILY_LIMIT");
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM gemini_review_attempt WHERE attempted_at>?)", Boolean.class,
                java.sql.Timestamp.from(now.minusSeconds(Math.max(60, intervalSeconds))))))
            return new Reservation(false, null, "RATE_WAIT");
        db.update("INSERT INTO gemini_review_cache(fingerprint,model,updated_at) VALUES(?,?,?)", hash, model, java.sql.Timestamp.from(now));
        db.update("INSERT INTO gemini_review_attempt(fingerprint,attempted_at) VALUES(?,?)", hash, java.sql.Timestamp.from(now));
        return new Reservation(true, null, "RESERVED");
    }

    @Transactional
    public void finish(String hash, String status, String result) {
        db.update("UPDATE gemini_review_cache SET status=?,result_json=?,updated_at=now() WHERE fingerprint=? AND status='RUNNING'", status, result, hash);
    }
}
