package org.jibro.collection.review.local;

import java.util.*;

import org.jibro.notice.AutomaticReviewRepository.ExtractionSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/**
 * Local interpretation is a separate audit artifact, never published guidance.
 */
@Repository
public class LocalAnalysisRepository {
    private static final String CURRENT = """
            e.notice_id=n.id AND e.source_revision=n.revision AND e.status IN ('READY','PARTIAL','FAILED') AND e.finished_at IS NOT NULL
            AND e.id=(SELECT x.id FROM notice_extraction x WHERE x.notice_id=n.id AND x.source_revision=n.revision ORDER BY x.created_at DESC,x.id DESC LIMIT 1)
            AND encode(sha256(convert_to(jsonb_build_object('sources',e.sources,'status',e.status,'checkedAt',extract(epoch FROM e.finished_at))::text,'UTF8')),'hex')=a.extraction_fingerprint
            """;
    private static final String ELIGIBLE = """
            n.source_key LIKE 'LH:%' AND n.collected_at IS NOT NULL AND n.source_fingerprint IS NOT NULL
            AND n.basic_review='PASS' AND n.guide_review='HOLD' AND n.lifecycle_state='NORMAL' AND NOT n.automatic_review_blocked
            AND NOT EXISTS(SELECT 1 FROM review_audit r WHERE r.notice_id=n.id)
            """ + " AND " + org.jibro.notice.AutomaticReviewEligibility.NOT_CLOSED_SQL;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public LocalAnalysisRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record Entry(UUID extractionId, String fingerprint, String policy, UUID noticeId, int revision,
                        LocalRuleBook ruleBook, CompactReviewPlanner.Plan plan, List<String> coverageIssues,
                        JsonNode apiResults) {
    }

    public Optional<Entry> find(UUID extractionId, String fingerprint, String policy) {
        return db.query("SELECT * FROM notice_local_analysis WHERE extraction_id=? AND extraction_fingerprint=? AND policy=?",
                (rs, row) -> entry(rs), extractionId, fingerprint, policy).stream().findFirst();
    }

    @Transactional
    public void save(ExtractionSnapshot snapshot, String policy, LocalRuleBook book, CompactReviewPlanner.Plan plan, List<String> issues) {
        var draft = snapshot.draft();
        db.update("""
                        INSERT INTO notice_local_analysis(extraction_id,extraction_fingerprint,policy,notice_id,source_revision,rule_book,packet_plan,coverage_issues)
                        VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb) ON CONFLICT DO NOTHING
                        """, UUID.fromString(draft.id()), snapshot.fingerprint(), policy, UUID.fromString(draft.noticeId()), draft.revision(),
                json.writeValueAsString(book), json.writeValueAsString(plan), json.writeValueAsString(issues));
    }

    public List<Entry> pending(String policy) {
        return db.query("SELECT a.* FROM notice_local_analysis a JOIN notice_extraction e ON e.id=a.extraction_id JOIN notice n ON n.id=a.notice_id WHERE a.policy=? AND "
                        + CURRENT + " AND " + ELIGIBLE
                        + " AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements_text(a.coverage_issues) c WHERE c NOT LIKE 'DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:%')"
                        + " AND EXISTS(SELECT 1 FROM jsonb_array_elements(a.packet_plan->'packets') p WHERE NOT jsonb_exists(a.api_results,p->>'id'))"
                        + " ORDER BY a.updated_at,a.extraction_id LIMIT 30",
                (rs, row) -> entry(rs), policy);
    }

    public List<UUID> unprocessed(String policy) {
        return db.queryForList("""
                SELECT e.id FROM notice_extraction e JOIN notice n ON n.id=e.notice_id
                WHERE e.source_revision=n.revision AND e.status IN ('READY','PARTIAL','FAILED') AND e.finished_at IS NOT NULL
                AND e.id=(SELECT x.id FROM notice_extraction x WHERE x.notice_id=n.id AND x.source_revision=n.revision ORDER BY x.created_at DESC,x.id DESC LIMIT 1)
                AND n.source_key LIKE 'LH:%%' AND n.collected_at IS NOT NULL AND NOT n.automatic_review_blocked
                AND n.basic_review='PASS' AND n.guide_review='HOLD' AND n.lifecycle_state='NORMAL'
                AND NOT EXISTS(SELECT 1 FROM review_audit r WHERE r.notice_id=n.id)
                AND NOT EXISTS(SELECT 1 FROM notice_local_analysis a WHERE a.extraction_id=e.id AND a.policy=?
                  AND a.extraction_fingerprint=encode(sha256(convert_to(jsonb_build_object('sources',e.sources,'status',e.status,'checkedAt',extract(epoch FROM e.finished_at))::text,'UTF8')),'hex'))
                AND %s
                ORDER BY e.finished_at,e.id LIMIT 3
                """.formatted(org.jibro.notice.AutomaticReviewEligibility.NOT_CLOSED_SQL), UUID.class, policy);
    }

    public boolean current(Entry entry) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM notice_local_analysis a JOIN notice_extraction e ON e.id=a.extraction_id JOIN notice n ON n.id=a.notice_id WHERE a.extraction_id=? AND a.extraction_fingerprint=? AND a.policy=? AND "
                        + CURRENT + " AND " + ELIGIBLE + ")", Boolean.class,
                entry.extractionId(), entry.fingerprint(), entry.policy()));
    }

    @Transactional
    public void result(Entry entry, String packetId, Object result) {
        db.queryForList("SELECT id FROM notice WHERE id=? FOR UPDATE", entry.noticeId());
        db.update("UPDATE notice_local_analysis a SET api_results=jsonb_set(api_results,ARRAY[?]::text[],?::jsonb,true),updated_at=now() FROM notice_extraction e, notice n WHERE a.extraction_id=e.id AND a.notice_id=n.id AND a.extraction_id=? AND a.extraction_fingerprint=? AND a.policy=? AND "
                        + CURRENT + " AND " + ELIGIBLE,
                packetId, json.writeValueAsString(result), entry.extractionId(), entry.fingerprint(), entry.policy());
    }

    private Entry entry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getObject("extraction_id", UUID.class), rs.getString("extraction_fingerprint"), rs.getString("policy"),
                rs.getObject("notice_id", UUID.class), rs.getInt("source_revision"), json.readValue(rs.getString("rule_book"), LocalRuleBook.class),
                json.readValue(rs.getString("packet_plan"), CompactReviewPlanner.Plan.class), List.of(json.readValue(rs.getString("coverage_issues"), String[].class)),
                json.readTree(rs.getString("api_results")));
    }
}
