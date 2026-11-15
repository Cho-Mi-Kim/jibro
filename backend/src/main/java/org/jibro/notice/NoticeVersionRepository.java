package org.jibro.notice;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class NoticeVersionRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public NoticeVersionRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public List<NoticeVersion> findPublished(UUID id, boolean includeGuide) {
        return db.query("""
            SELECT version,revision, (CASE WHEN ? THEN facts ELSE facts - 'questions' - 'documents' - 'schedules' - 'reviewIssues' END)::text
            FROM published_notice_version WHERE notice_id=? ORDER BY version DESC,revision DESC
            """, (r, i) -> new NoticeVersion(r.getInt(1), r.getInt(2), json.readTree(r.getString(3))), includeGuide, id);
    }
}
