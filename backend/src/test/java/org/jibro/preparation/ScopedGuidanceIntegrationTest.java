package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jibro.auth.AuthService;
import org.jibro.global.common.ApiException;
import org.jibro.notice.NoticeSchedules;
import org.jibro.notice.NoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScopedGuidanceIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    PreparationService saved;
    @Autowired
    PreparationHistoryService history;
    @Autowired
    OneTimeCheckService once;
    @Autowired
    AuthService auth;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;
    private String owner;
    private final Evidence source = new Evidence("공식 원문 비교", "https://apply.lh.or.kr/", "2026-11-01");

    @BeforeEach
    void setup() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("부분 안내 검증", email, "test-password-123");
        owner = auth.login(email, "test-password-123").user().id();
    }

    private Content content(boolean partial) {
        var issues = partial ? List.of(
                new ReviewIssue("income-conflict", "CONDITION", "소득 예외 확인", "공고와 첨부의 소득 예외가 달라 기관 확인이 필요합니다.",
                        List.of("income"), List.of(), List.of(source)),
                new ReviewIssue("direct-document", "DOCUMENT", "제출 대상 확인", "제출 대상의 공식 확인이 필요합니다.",
                        List.of(), List.of("disputed"), List.of(source)),
                new ReviewIssue("result-date", "SCHEDULE", "발표 일정 확인", "발표일이 서로 달라 확정 일정에서 제외했습니다.",
                        List.of(), List.of(), List.of(source))) : List.<ReviewIssue>of();
        return new Content("확인된 내용부터 준비합니다.", source, List.of(),
                List.of(new Question("age", "연령 확인", "확인된 기준", source),
                        new Question("income", "소득 확인", "공식 기준 확인", source)),
                List.of(new DocumentTemplate("common", "공통 서류", null, "발급 방법", "기관", null, source),
                        new DocumentTemplate("conditional", "연령 관련 서류", "age", "발급 방법", "기관", null, source),
                        new DocumentTemplate("income-doc", "소득 서류", "income", "기관 확인", "기관", null, source),
                        new DocumentTemplate("disputed", "제출 대상 확인 서류", null, "기관 확인", "기관", null, source)),
                List.of(new ScheduleTemplate("apply", "신청 접수 마감", LocalDate.now().plusDays(30).toString(), source)), issues);
    }

    private UUID publish(Content content) {
        notices.validateContent(content);
        UUID id = notices.insert(UUID.randomUUID().toString(), "항목별 확인 공고", "LH", "서울", "청년 임대",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content);
        notices.samplePublish(id, "PASS");
        return id;
    }

    private Document document(Preparation preparation, String id) {
        return preparation.documents.stream().filter(d -> d.id.equals(id)).findFirst().orElseThrow();
    }

    @Test
    void partialPublicGuidanceAllowsSafeAnswersAndDocumentsWithoutResolvingOfficialConflicts() {
        UUID notice = publish(content(true));
        var publicNotice = notices.notice(notice);
        assertEquals("AVAILABLE", publicNotice.guideState());
        assertTrue(publicNotice.canStart());
        assertEquals(3, publicNotice.reviewIssues().size());
        assertEquals(4, publicNotice.documentTemplates().size());
        assertEquals(List.of("income-doc"), publicNotice.reviewIssues().get(0).documentIds());

        var preparation = saved.start(owner, notice, publicNotice.version());
        UUID id = UUID.fromString(preparation.id);
        preparation = saved.answers(owner, id, preparation.revision, Map.of("age", "YES", "income", "YES"));
        preparation = saved.document(owner, id, "common", preparation.revision, DocumentState.READY, "공통 서류 준비");
        preparation = saved.document(owner, id, "conditional", preparation.revision, DocumentState.READY, "정상 조건 서류 준비");
        preparation = saved.schedule(owner, id, "apply", preparation.revision, true);
        assertEquals("CONDITIONAL", document(preparation, "conditional").necessity);
        assertFalse(document(preparation, "conditional").reviewRequired);
        assertEquals(DocumentState.READY, document(preparation, "conditional").state);
        for (String blocked : List.of("income-doc", "disputed")) {
            assertEquals("UNKNOWN", document(preparation, blocked).necessity);
            assertTrue(document(preparation, blocked).reviewRequired);
            int revision = preparation.revision;
            var failure = assertThrows(ApiException.class,
                    () -> saved.document(owner, id, blocked, revision, DocumentState.READY, ""));
            assertEquals("CONDITION_REQUIRED", failure.code);
        }
        assertTrue(preparation.questions.get(1).needsFollowUp(preparation.answers.get("income")));
        assertTrue(preparation.progress < 100);
        assertEquals("/notices/" + notice, preparation.nextAction.path());

        preparation = saved.answers(owner, id, preparation.revision, Map.of("age", "YES", "income", "NO"));
        assertEquals("UNKNOWN", document(preparation, "income-doc").necessity);
        assertEquals(DocumentState.READY, document(preparation, "conditional").state);
    }

    @Test
    void oneTimeCheckPermitsOmittedReadOnlyQuestionsAndDoesNotCreatePersonalRecords() {
        UUID notice = publish(content(true));
        long before = db.queryForObject("SELECT count(*) FROM preparation", Long.class);
        var result = once.evaluate(notice, 1, Map.of("age", "YES"));
        assertFalse(result.answers().containsKey("income"));
        assertEquals(3, result.reviewIssues().size());
        assertEquals("CONDITIONAL", result.documents().get(1).necessity);
        assertEquals("UNKNOWN", result.documents().get(2).necessity);
        assertTrue(result.documents().get(2).reviewRequired);
        assertEquals(before, db.queryForObject("SELECT count(*) FROM preparation", Long.class));
    }

    @Test
    void refreshingIntoPartialGuidancePreservesHistoricalAnswersAndReadyDocuments() {
        UUID notice = publish(content(false));
        var preparation = saved.start(owner, notice, 1);
        UUID id = UUID.fromString(preparation.id);
        preparation = saved.answers(owner, id, preparation.revision, Map.of("age", "YES", "income", "YES"));
        preparation = saved.document(owner, id, "income-doc", preparation.revision, DocumentState.READY, "이전 준비 메모");
        String before = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, id);
        int revision = notices.reviews().stream().filter(r -> r.id().equals(notice.toString())).findFirst().orElseThrow().revision();
        var draft = notices.updateContent(notice, revision, content(true));
        notices.review(notice, owner, draft.revision(), "PASS", "PASS", "충돌 항목만 공식 확인으로 공개");
        assertEquals(before, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, id));

        preparation = saved.refresh(owner, id, preparation.revision, draft.notice().version());
        assertEquals("YES", preparation.answers.get("age"));
        assertFalse(preparation.answers.containsKey("income"));
        assertEquals("UNKNOWN", document(preparation, "income-doc").necessity);
        assertEquals(DocumentState.RECHECK, document(preparation, "income-doc").state);
        assertEquals("이전 준비 메모", document(preparation, "income-doc").note);
        var historical = history.get(owner, id, 1).preparation();
        assertEquals(json.readValue(before, Snapshot.class).preparation().answers, historical.answers);
        assertEquals(DocumentState.READY, document(historical, "income-doc").state);
        assertEquals(List.of(), historical.reviewIssues);
        assertFalse(document(historical, "income-doc").reviewRequired);
    }

    @Test
    void unconfirmedScheduleIsNotAnEventOrReminderAndHeldGuidanceDoesNotLeakItems() {
        UUID notice = publish(content(true));
        assertEquals(List.of("apply"), notices.notice(notice).schedules().stream().map(ScheduleTemplate::id).toList());
        assertTrue(NoticeSchedules.reminders(notices.notice(notice)).stream().noneMatch(due -> due.title().contains("발표")));
        var preparation = saved.start(owner, notice, 1);
        assertTrue(preparation.schedules.stream().noneMatch(schedule -> schedule.title.contains("발표")));
        notices.samplePublish(notice, "HOLD");
        var held = notices.notice(notice);
        assertTrue(held.questions().isEmpty());
        assertTrue(held.documentTemplates().isEmpty());
        assertTrue(held.reviewIssues().isEmpty());
    }
}
