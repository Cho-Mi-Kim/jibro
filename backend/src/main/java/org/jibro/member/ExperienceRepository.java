package org.jibro.member;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExperienceRepository {
    public record Entry(UUID id, String title, int version, int progress, String note, Instant archivedAt) {}
    public record LinkTarget(UUID id, boolean archived) {}
    public record ArchiveState(int revision, boolean archived) {}

    private static final String ENTRY = "SELECT p.id,p.snapshot->'preparation'->>'title',p.notice_version,(p.snapshot->'preparation'->>'progress')::int,p.takeaway,p.archived_at FROM preparation p ";
    private final JdbcTemplate db;
    private static final String ADVANCE_REVISION = """
            revision=revision+1,
            snapshot=jsonb_set(snapshot,'{preparation,revision}',to_jsonb(revision+1)),
            updated_at=now()
            """;

    public ExperienceRepository(JdbcTemplate db) {
        this.db = db;
    }

    private List<Entry> entries(String query, Object... parameters) {
        return db.query(ENTRY + query, (r, i) -> new Entry(r.getObject(1, UUID.class), r.getString(2), r.getInt(3), r.getInt(4), r.getString(5), r.getTimestamp(6).toInstant()), parameters);
    }

    public List<Entry> findArchived(UUID user) {
        return entries("WHERE p.user_id=? AND p.archived_at IS NOT NULL ORDER BY p.archived_at DESC", user);
    }

    public Optional<ArchiveState> lockArchiveState(UUID user, UUID id) {
        return db.query("SELECT revision,archived_at FROM preparation WHERE user_id=? AND id=? FOR UPDATE",
                (rs, row) -> new ArchiveState(rs.getInt("revision"), rs.getTimestamp("archived_at") != null),
                user, id).stream().findFirst();
    }

    public void archive(UUID id, String note) {
        db.update("UPDATE preparation SET archived_at=now(),takeaway=?,"
                + ADVANCE_REVISION + "WHERE id=?", note, id);
    }

    public void restore(UUID id) {
        db.update("UPDATE preparation SET archived_at=NULL," + ADVANCE_REVISION + "WHERE id=?", id);
    }

    public void removeReferences(UUID source) {
        db.update("DELETE FROM experience_reference WHERE source_id=?", source);
    }

    public List<Entry> findReferences(UUID user, UUID target) {
        return entries("JOIN experience_reference x ON x.source_id=p.id JOIN preparation t ON t.id=x.target_id WHERE t.id=? AND t.user_id=? AND p.user_id=? AND p.archived_at IS NOT NULL ORDER BY p.archived_at DESC", target, user, user);
    }

    public List<LinkTarget> lockLinkTargets(UUID user, UUID target, UUID source) {
        return db.query("SELECT id,archived_at FROM preparation WHERE user_id=? AND id IN (?,?) ORDER BY id FOR UPDATE",
                (rs, row) -> new LinkTarget(rs.getObject("id", UUID.class), rs.getObject("archived_at") != null), user, target, source);
    }

    public void attach(UUID target, UUID source) {
        db.update("INSERT INTO experience_reference VALUES(?,?) ON CONFLICT DO NOTHING", target, source);
    }
}
