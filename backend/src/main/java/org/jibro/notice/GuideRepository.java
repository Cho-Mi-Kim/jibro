package org.jibro.notice;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.jibro.notice.HousingModels.Content;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class GuideRepository {
    public record Source(UUID id, String title, String agency, boolean verified, Content content) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public GuideRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
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

    public List<Source> findPublishedSources(int offset, int size, boolean verifiedOnly, List<String> sourceTerms) {
        var args = new ArrayList<Object>();
        args.add(verifiedOnly);
        args.addAll(sourceTerms);
        args.add(size);
        args.add(offset);
        String candidate = sourceTerms.isEmpty() ? "" : " AND " + String.join(" AND ", Collections.nCopies(sourceTerms.size(),
            "strpos(lower(normalize(concat_ws(' ', title, agency, content::text))), ?) > 0"));
        return db.query("""
            SELECT id,title,agency,(guide_review='PASS' AND NOT change_review) AS verified,content::text
            FROM notice WHERE basic_review='PASS' AND NOT service_excluded AND (source_key IS NULL OR source_key NOT LIKE 'local-sample-%')
            AND (NOT ? OR (guide_review='PASS' AND NOT change_review))
            """ + candidate + " ORDER BY published_date DESC NULLS LAST,id LIMIT ? OFFSET ?",
            (row, index) -> new Source(row.getObject("id", UUID.class), row.getString("title"), row.getString("agency"),
                row.getBoolean("verified"), json.readValue(row.getString("content"), Content.class)), args.toArray());
    }
}
