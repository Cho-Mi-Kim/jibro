package org.jibro.preparation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jibro.notice.HousingModels.Snapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Snapshot storage and row locks; preparation decisions stay in the service transaction. */
@Repository
public class PreparationRepository {
    public record Existing(UUID id, boolean archived) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public PreparationRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public List<PreparationListItem> findActive(UUID owner) {
        return db.query("""
                SELECT p.id, p.notice_id, p.snapshot #>> '{preparation,title}' AS title,
                    p.notice_version, p.revision, (p.snapshot #>> '{preparation,progress}')::int AS progress
                FROM preparation p JOIN notice n ON n.id=p.notice_id
                WHERE p.user_id=? AND p.archived_at IS NULL
                    AND (n.source_key IS NULL OR n.source_key NOT LIKE 'local-sample-%')
                ORDER BY p.updated_at DESC,p.id
                """, (rs, row) -> new PreparationListItem(rs.getString("id"), rs.getString("notice_id"), rs.getString("title"),
                    rs.getInt("notice_version"), rs.getInt("revision"), rs.getInt("progress")), owner);
    }

    public boolean lockOwner(UUID owner) {
        return !db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE", owner).isEmpty();
    }

    public Optional<Existing> findByNotice(UUID owner, UUID notice) {
        return db.query("SELECT id,archived_at FROM preparation WHERE user_id=? AND notice_id=?",
                (rs, row) -> new Existing(rs.getObject("id", UUID.class), rs.getObject("archived_at") != null), owner, notice)
                .stream().findFirst();
    }

    public void insert(UUID owner, UUID notice, int version, Snapshot snapshot) {
        db.update("INSERT INTO preparation(id,user_id,notice_id,notice_version,snapshot) VALUES (?,?,?,?,?::jsonb)",
                UUID.fromString(snapshot.preparation().id), owner, notice, version, json.writeValueAsString(snapshot));
    }

    public Optional<Snapshot> findActive(UUID owner, UUID id) {
        return findActive(owner, id, false);
    }

    public Optional<Snapshot> lockActive(UUID owner, UUID id) {
        return findActive(owner, id, true);
    }

    private Optional<Snapshot> findActive(UUID owner, UUID id, boolean lock) {
        return db.query("SELECT snapshot::text FROM preparation p JOIN notice n ON n.id=p.notice_id WHERE p.user_id=? AND p.id=? AND p.archived_at IS NULL AND (n.source_key IS NULL OR n.source_key NOT LIKE 'local-sample-%')" + (lock ? " FOR UPDATE OF p" : ""),
                (rs, row) -> json.readValue(rs.getString(1), Snapshot.class), owner, id).stream().findFirst();
    }

    public void retainHistory(UUID id, int version, Snapshot previous) {
        db.update("INSERT INTO preparation_history(preparation_id,notice_version,snapshot) VALUES (?,?,?::jsonb)",
                id, version, json.writeValueAsString(previous));
    }

    public void updateNoticeVersion(UUID id, int version) {
        db.update("UPDATE preparation SET notice_version=? WHERE id=?", version, id);
    }

    public void save(Snapshot snapshot) {
        var preparation = snapshot.preparation();
        db.update("UPDATE preparation SET revision=?,snapshot=?::jsonb,updated_at=now() WHERE id=?",
                preparation.revision, json.writeValueAsString(snapshot), UUID.fromString(preparation.id));
    }
}
