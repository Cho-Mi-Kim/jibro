package org.jibro.notice;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GuideRepository {
    private final JdbcTemplate db;

    public GuideRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<UUID> findTrialNotice() {
        return db.queryForList("""
                SELECT id FROM notice WHERE source_key LIKE 'LH:%' AND basic_review='PASS' AND NOT service_excluded AND guide_review='PASS'
                AND NOT service_closed AND NOT source_closed AND NOT change_review AND lifecycle_state='NORMAL'
                AND (application_end_at > now() OR (application_end_at IS NULL AND end_date >= (now() AT TIME ZONE 'Asia/Seoul')::date))
                ORDER BY published_date DESC NULLS LAST,id LIMIT 1
                """, UUID.class)
            .stream().findFirst();
    }
}
