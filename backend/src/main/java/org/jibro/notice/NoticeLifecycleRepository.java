package org.jibro.notice;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NoticeLifecycleRepository {
    private final JdbcTemplate db;

    public NoticeLifecycleRepository(JdbcTemplate db) { this.db = db; }

    public void record(UUID notice, UUID actor, int revision, int lifecycleRevision, String previousState,
                       String state, String reason, String evidence) {
        db.update("""
                INSERT INTO notice_lifecycle_audit(notice_id,actor_id,notice_revision,lifecycle_revision,
                  previous_state,state,reason,evidence) VALUES (?,?,?,?,?,?,?,cast(? as jsonb))
                """, notice, actor, revision, lifecycleRevision, previousState, state, reason, evidence);
    }
}
