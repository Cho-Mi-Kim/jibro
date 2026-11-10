package org.jibro.notice;

import java.util.*;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GuideController {
    private final Environment env;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final tools.jackson.databind.ObjectMapper json;

    public GuideController(Environment env, org.springframework.jdbc.core.JdbcTemplate db, tools.jackson.databind.ObjectMapper json) {
        this.json = json;
        this.env = env;
        this.db = db;
    }

    @GetMapping("/meta")
    Map<String, Object> meta() {
        var result = new HashMap<String, Object>();
        result.put("sampleData", Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM notice WHERE source_key LIKE 'local-sample-%')", Boolean.class)));
        result.put("trialAvailable", env.matchesProfiles("local-sample"));
        if (env.matchesProfiles("local-sample")) db.queryForList("SELECT id FROM notice WHERE source_key LIKE 'local-sample-%' AND basic_review='PASS' AND guide_review='PASS' AND NOT change_review AND end_date >= (now() AT TIME ZONE 'Asia/Seoul')::date ORDER BY id LIMIT 1", UUID.class)
            .stream().findFirst().ifPresent(id -> result.put("trialNoticeId", id));
        return result;
    }

    @GetMapping("/guides")
    List<HousingModels.Guide> guides() {
        var result = new ArrayList<HousingModels.Guide>();
        db.query("""
            SELECT id,title,agency,(guide_review='PASS' AND NOT change_review) AS verified,content::text
            FROM notice WHERE basic_review='PASS' AND (source_key IS NULL OR source_key NOT LIKE 'local-sample-%')
            ORDER BY published_date DESC NULLS LAST,id
            """, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
            UUID id = row.getObject("id", UUID.class);
            String title = row.getString("title");
            var content = json.readValue(row.getString("content"), HousingModels.Content.class);
            result.add(new HousingModels.Guide(id + "-summary", "지원제도 알아보기", title, content.summary(), content.evidence()));
            if (content.evidence() != null && content.evidence().url() != null)
                result.add(new HousingModels.Guide(id + "-contact", "공식 문의처", row.getString("agency") + " 문의 경로", title + " · 공고 원문의 담당 기관과 문의 항목을 확인하세요.", content.evidence()));
            if (!row.getBoolean("verified")) return;
            for (var question : content.questions())
                result.add(new HousingModels.Guide(id + "-q-" + question.id(), "조건과 용어", question.title(), title + " · " + question.reason(), question.evidence()));
            for (var doc : content.documents())
                result.add(new HousingModels.Guide(id + "-d-" + doc.id(), "서류 발급 안내", doc.title(), title + " · " + doc.issuer() + " · " + doc.method(), doc.evidence()));
            for (var step : content.schedules())
                result.add(new HousingModels.Guide(id + "-s-" + step.id(), "신청 절차", step.title(), title + " · " + step.date(), step.evidence()));
        });
        if (Arrays.asList(env.getActiveProfiles()).contains("local-sample"))
            result.add(new HousingModels.Guide("sample-process", "신청 절차", "[샘플] 준비 기록과 실제 신청은 달라요",
                "집으로는 준비 기록을 관리합니다. 실제 접수는 해당 기관에서 완료하고 결과를 확인해야 합니다.", SampleData.content().evidence()));
        return result;
    }
}
