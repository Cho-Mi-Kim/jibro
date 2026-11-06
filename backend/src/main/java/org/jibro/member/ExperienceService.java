package org.jibro.member;

import java.time.Instant;
import java.util.*;

import org.jibro.global.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperienceService {
    public record Entry(UUID id, String title, int version, int progress, String note, Instant archivedAt) {
    }

    private final JdbcTemplate db;

    public ExperienceService(JdbcTemplate db) {
        this.db = db;
    }

    private static final String ENTRY = "SELECT p.id,p.snapshot->'preparation'->>'title',p.notice_version,(p.snapshot->'preparation'->>'progress')::int,p.takeaway,p.archived_at FROM preparation p ";

    private List<Entry> entries(String query, Object... parameters) {
        return db.query(ENTRY + query, (r, i) -> new Entry(r.getObject(1, UUID.class), r.getString(2), r.getInt(3), r.getInt(4), r.getString(5), r.getTimestamp(6).toInstant()), parameters);
    }

    public List<Entry> list(UUID user) {
        return entries("WHERE p.user_id=? AND p.archived_at IS NOT NULL ORDER BY p.archived_at DESC", user);
    }

    @Transactional
    public void archive(UUID user, UUID id, String note, int revision) {
        var rows = db.queryForList("SELECT revision,archived_at FROM preparation WHERE user_id=? AND id=? FOR UPDATE", user, id);
        if (rows.isEmpty()) throw ApiException.notFound();
        if (((Number) rows.get(0).get("revision")).intValue() != revision) throw ApiException.conflict();
        db.update("UPDATE preparation SET archived_at=coalesce(archived_at,now()),takeaway=? WHERE id=?", note.strip(), id);
    }

    @Transactional
    public void restore(UUID user, UUID id) {
        if (db.update("UPDATE preparation SET archived_at=NULL WHERE user_id=? AND id=?", user, id) == 0)
            throw ApiException.notFound();
        db.update("DELETE FROM experience_reference WHERE source_id=?", id);
    }

    public List<Entry> references(UUID user, UUID target) {
        return entries("JOIN experience_reference x ON x.source_id=p.id JOIN preparation t ON t.id=x.target_id WHERE t.id=? AND t.user_id=? AND p.user_id=? AND p.archived_at IS NOT NULL ORDER BY p.archived_at DESC", target, user, user);
    }

    @Transactional
    public void attach(UUID user, UUID target, UUID source) {
        var rows = db.queryForList("SELECT id,archived_at FROM preparation WHERE user_id=? AND id IN (?,?) ORDER BY id FOR UPDATE", user, target, source);
        if (rows.size() != 2) throw ApiException.notFound();
        for (var row : rows)
            if (row.get("id").equals(source) != (row.get("archived_at") != null)) throw ApiException.conflict();
        db.update("INSERT INTO experience_reference VALUES(?,?) ON CONFLICT DO NOTHING", target, source);
    }
}
