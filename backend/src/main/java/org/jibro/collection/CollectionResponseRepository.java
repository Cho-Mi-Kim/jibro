package org.jibro.collection;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionResponseRepository {
    public record Receipt(String id, String runId, int runAttempt, LocalDate queryDate, int page,
                          Instant requestedAt, Instant receivedAt, Integer httpStatus, String sha256,
                          boolean redacted, String bodyStatus, String outcome, String categoryCode,
                          LocalDate publishedFrom, LocalDate publishedThrough) {
    }

    public record ArchivedResponse(Receipt receipt, String bodyJson) {
    }

    private final JdbcTemplate db;

    public CollectionResponseRepository(JdbcTemplate db) {
        this.db = db;
    }

    UUID begin(CollectionRunStore.Work work, int page, CollectionQueryPlan.Query query, Instant now) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO collection_response_attempt(id,run_id,lease_token,run_attempt,query_date,page,requested_at,category_code,published_from,published_through) "
                        + "SELECT ?,id,?,attempt,?,?,?,?,?,? FROM collection_run WHERE id=?", id, work.token(), work.queryDate(), page, Timestamp.from(now),
                query.categoryCode(), query.publishedFrom(), query.publishedThrough(), work.id());
        return id;
    }

    void complete(UUID id, SourceResponse response, String outcome, Instant now) {
        if (response != null && response.bodyJson() != null)
            db.update("INSERT INTO collection_response_payload(sha256,body_json,first_received_at) VALUES(?,?,?) ON CONFLICT(sha256) DO NOTHING",
                    response.sha256(), response.bodyJson(), Timestamp.from(response.receivedAt()));
        db.update("UPDATE collection_response_attempt SET received_at=?,http_status=?,sha256=?,redacted=?,body_status=?,outcome=?,completed_at=? WHERE id=? AND outcome='REQUESTED'",
                response == null ? null : Timestamp.from(response.receivedAt()), response == null ? null : response.status(),
                response == null ? null : response.sha256(), response != null && response.redacted(),
                response == null ? (outcome.equals("TRANSPORT_FAILED") ? "NO_RESPONSE" : "UNAVAILABLE") : response.bodyStatus(), outcome, Timestamp.from(now), id);
    }

    public List<Receipt> list(UUID run) {
        return db.query("SELECT * FROM collection_response_attempt WHERE run_id=? ORDER BY requested_at,category_code,page LIMIT 4000", (r, i) -> receipt(r), run);
    }

    public Optional<ArchivedResponse> find(UUID run, UUID id) {
        return db.query("SELECT a.*,p.body_json FROM collection_response_attempt a LEFT JOIN collection_response_payload p ON p.sha256=a.sha256 WHERE a.run_id=? AND a.id=?",
                (r, i) -> new ArchivedResponse(receipt(r), r.getString("body_json")), run, id).stream().findFirst();
    }

    private Receipt receipt(java.sql.ResultSet row) throws java.sql.SQLException {
        Timestamp received = row.getTimestamp("received_at");
        return new Receipt(row.getString("id"), row.getString("run_id"), row.getInt("run_attempt"), row.getDate("query_date").toLocalDate(), row.getInt("page"),
                row.getTimestamp("requested_at").toInstant(), received == null ? null : received.toInstant(), row.getObject("http_status", Integer.class),
                row.getString("sha256"), row.getBoolean("redacted"), row.getString("body_status"), row.getString("outcome"), row.getString("category_code"),
                row.getDate("published_from").toLocalDate(), row.getDate("published_through").toLocalDate());
    }
}
