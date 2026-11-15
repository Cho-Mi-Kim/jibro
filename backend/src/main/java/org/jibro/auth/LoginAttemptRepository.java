package org.jibro.auth;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Login admission reservations share the limiter's independent transaction. */
@Repository
public class LoginAttemptRepository {
    private final JdbcTemplate db;

    public LoginAttemptRepository(JdbcTemplate db) {
        this.db = db;
    }

    void lockBuckets(long account, long address) {
        // Lock in the same order across instances so concurrent account/IP checks cannot deadlock.
        lock(Math.min(account, address));
        if (account != address) lock(Math.max(account, address));
    }

    private void lock(long key) {
        db.query("SELECT pg_advisory_xact_lock(?)", (rs, row) -> 0, key);
    }

    void deleteOldAttempts() {
        db.update("DELETE FROM login_attempt WHERE id IN (SELECT id FROM login_attempt WHERE created_at<clock_timestamp()-interval '1 day' ORDER BY created_at LIMIT 500)");
    }

    int countAccountFailuresAndPending(String account) {
        return db.queryForObject("SELECT count(*) FROM login_attempt WHERE account_key=? AND created_at>clock_timestamp()-interval '15 minutes' AND successful IS DISTINCT FROM true", Integer.class, account);
    }

    int countAddressFailuresAndPending(String address) {
        return db.queryForObject("SELECT count(*) FROM login_attempt WHERE address_key=? AND created_at>clock_timestamp()-interval '15 minutes' AND successful IS DISTINCT FROM true", Integer.class, address);
    }

    void reserveAttempt(UUID id, String account, String address) {
        db.update("INSERT INTO login_attempt(id,account_key,address_key) VALUES (?,?,?)", id, account, address);
    }

    void completeAttempt(UUID id, boolean successful) {
        db.update("UPDATE login_attempt SET successful=? WHERE id=? AND successful IS NULL", successful, id);
    }
}
