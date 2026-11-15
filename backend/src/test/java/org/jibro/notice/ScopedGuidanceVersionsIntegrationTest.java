package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jibro.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScopedGuidanceVersionsIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired NoticeVersionService versions;
    @Autowired NoticeRepository repository;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;

    @Test void futureRevisionsRetainScopedUncertaintyWithoutRewritingOlderFactsAndHoldHidesIt() {
        var evidence = new Evidence("공식 기준 비교", "https://apply.lh.or.kr/", "2026-11-01");
        var base = new Content("확인된 안내", evidence, List.of(),
            List.of(new Question("common", "공통 조건", "확인된 기준", evidence),
                new Question("income", "소득 기준", "확인 대상 기준", evidence)),
            List.of(new DocumentTemplate("common-doc", "공통 서류", null, "발급 방법", "기관", null, evidence),
                new DocumentTemplate("income-doc", "소득 서류", "income", "발급 방법", "기관", null, evidence)),
            List.of(new ScheduleTemplate("end", "신청 접수 마감", LocalDate.now().plusDays(30).toString(), evidence)));
        var id = notices.insert(UUID.randomUUID().toString(), "비교 이력 범위 검증", "LH", "서울", "공공임대",
            LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), base);
        notices.samplePublish(id, "PASS");
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("이력 검증", email, "test-password-123");
        String owner = auth.login(email, "test-password-123").user().id();
        // Flush the initial public revision, then retain its literal facts for later comparison.
        repository.flush();
        var original = versions.versions(id).get(0);
        String before = db.queryForObject("SELECT facts::text FROM published_notice_version WHERE notice_id=? AND version=? AND revision=?",
            String.class, id, original.version(), original.revision());
        var issue = new ReviewIssue("income", "CONDITION", "소득 예외 확인", "기관 확인이 필요한 예외입니다.",
            List.of("income"), List.of(), List.of(evidence));
        var partial = new Content(base.summary(), evidence, base.attachments(), base.questions(), base.documents(), base.schedules(), List.of(issue));
        var edited = notices.updateContent(id, notices.entity(id).revision, partial);
        notices.review(id, owner, edited.revision(), "PASS", "PASS", "일부 확인 필요 안내");
        repository.flush();
        var latest = versions.versions(id).get(0).facts();
        assertEquals("제공 · 일부 확인 필요", latest.get("guidance").asText());
        assertFalse(latest.get("questions").get(0).asText().contains("[확인 필요]"));
        assertTrue(latest.get("questions").get(1).asText().contains("[확인 필요]"));
        assertFalse(latest.get("documents").get(0).asText().contains("[확인 필요]"));
        assertTrue(latest.get("documents").get(1).asText().contains("[확인 필요]"));
        assertTrue(latest.get("reviewIssues").get(0).asText().contains("기관 확인이 필요한 예외"));
        assertEquals(before, db.queryForObject("SELECT facts::text FROM published_notice_version WHERE notice_id=? AND version=? AND revision=?",
            String.class, id, original.version(), original.revision()));

        notices.samplePublish(id, "HOLD");
        repository.flush();
        assertTrue(versions.versions(id).stream().allMatch(version -> !version.facts().has("reviewIssues")));
    }
}
