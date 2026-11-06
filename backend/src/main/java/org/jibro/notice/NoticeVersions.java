package org.jibro.notice;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/notices")
public class NoticeVersions {
    public record Edition(int version, int revision, JsonNode facts) {
    }

    private final NoticeService notices;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public NoticeVersions(NoticeService notices, JdbcTemplate db, ObjectMapper json) {
        this.notices = notices;
        this.db = db;
        this.json = json;
    }

    @GetMapping("/{id}/versions")
    @Transactional(readOnly = true)
    List<Edition> versions(@PathVariable UUID id) {
        var current = notices.notice(id);
        return db.query("""
            SELECT version,revision, (CASE WHEN ? THEN facts ELSE facts - 'questions' - 'documents' - 'schedules' END)::text
            FROM published_notice_version WHERE notice_id=? ORDER BY version DESC,revision DESC
            """, (r, i) -> new Edition(r.getInt(1), r.getInt(2), json.readTree(r.getString(3))), current.guideState().equals("AVAILABLE"), id);
    }
}
