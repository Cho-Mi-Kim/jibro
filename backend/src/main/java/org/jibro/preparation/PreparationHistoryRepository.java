package org.jibro.preparation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jibro.notice.HousingModels.Snapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PreparationHistoryRepository {
    public record Owner(String title, boolean archived) {
    }

    public record Entry(int version, String savedAt) {
    }

    public record Detail(int version, String savedAt, Snapshot snapshot) {
    }

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public PreparationHistoryRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Optional<Owner> findOwner(UUID user, UUID preparationId) {
        return db.query("""
                        SELECT snapshot->'preparation'->>'title' AS title, archived_at IS NOT NULL AS archived
                        FROM preparation WHERE id=? AND user_id=?
                        """, (rs, row) -> new Owner(rs.getString("title"), rs.getBoolean("archived")),
                preparationId, user).stream().findFirst();
    }

    public List<Entry> findEntries(UUID preparationId) {
        return db.query("""
                SELECT notice_version, archived_at FROM preparation_history
                WHERE preparation_id=? ORDER BY notice_version DESC
                """, (rs, row) -> new Entry(rs.getInt("notice_version"),
                rs.getTimestamp("archived_at").toInstant().toString()), preparationId);
    }

    public Optional<Detail> findOwnedVersion(UUID user, UUID preparationId, int version) {
        return db.query("""
                SELECT h.notice_version, h.archived_at, h.snapshot::text
                FROM preparation_history h JOIN preparation p ON p.id=h.preparation_id
                WHERE p.id=? AND p.user_id=? AND h.notice_version=?
                """, (rs, row) -> new Detail(rs.getInt("notice_version"), rs.getTimestamp("archived_at").toInstant().toString(),
                json.readValue(rs.getString("snapshot"), Snapshot.class)), preparationId, user, version).stream().findFirst();
    }
}
