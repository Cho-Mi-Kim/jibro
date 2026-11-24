package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.jibro.auth.AuthService;
import org.jibro.notice.NoticeService;
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
class PreparationListProgressIntegrationTest {
    @Test
    void applicationRecordIsSharedAndReceptionInformationIsNotCompletableOrRequiredForProgress() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("신청 기록 연동 테스트", email, "test-password-123");
        String owner = auth.login(email, "test-password-123").user().id();
        var evidence = new Evidence("공식 근거", null, "2026-11-23");
        String date = LocalDate.now().plusDays(20).toString();
        var content = new Content("신청 기록", evidence, List.of(), List.of(new Question("q", "거주", "", evidence)),
                List.of(new DocumentTemplate("doc", "공통 서류", null, "기관 발급", "기관", null, evidence)),
                List.of(new ScheduleTemplate("start", "신청 접수 시작", date, evidence),
                        new ScheduleTemplate("session", "접수 회차 · 현장 접수", date, evidence),
                        new ScheduleTemplate("end", "신청 접수 마감", date, evidence),
                        new ScheduleTemplate("apply", "공식 신청 여부 확인", date, evidence)));
        UUID notice = notices.insert(UUID.randomUUID().toString(), "신청 기록 공고", "기관", "서울", "임대",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content);
        notices.samplePublish(notice, "PASS");
        var initial = preparations.start(owner, notice, 1);
        UUID id = UUID.fromString(initial.id);
        var snapshot = repository.findActive(UUID.fromString(owner), id).orElseThrow();
        snapshot.preparation().answers = Map.of("q", "YES");
        snapshot.preparation().documents.get(0).state = DocumentState.READY;
        repository.save(snapshot);
        var ready = preparations.get(owner, id);
        assertEquals(80, ready.progress);
        for (String event : List.of("start", "session", "end"))
            assertThrows(IllegalArgumentException.class, () -> preparations.schedule(owner, id, event, ready.revision, true));
        assertEquals(ready.revision, preparations.get(owner, id).revision);
        var applied = preparations.schedule(owner, id, "apply", ready.revision, true);
        assertEquals(100, applied.progress);
        assertEquals(100, preparations.list(owner).get(0).progress());
        assertFalse(applied.schedules.get(0).recorded);
        var cancelled = preparations.schedule(owner, id, "apply", applied.revision, false);
        assertEquals(80, cancelled.progress);
        assertEquals(80, preparations.list(owner).get(0).progress());
    }

    @Autowired
    AuthService auth;
    @Autowired
    NoticeService notices;
    @Autowired
    PreparationService preparations;
    @Autowired
    PreparationRepository repository;
    @Autowired
    PreparationHistoryService history;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;
    @Autowired
    EntityManager entities;

    @Test
    void listAndDetailExcludeOnlyCertainlyIrrelevantRoundsWithoutChangingStoredCompletion() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("맞춤 일정 테스트", email, "test-password-123");
        String owner = auth.login(email, "test-password-123").user().id();
        var evidence = new Evidence("테스트 공식 근거", null, "2026-11-22");
        String date = LocalDate.now().plusDays(20).toString();
        var content = new Content("접수 방법별 일정", evidence, List.of(), List.of(
                new Question("method", "어떤 방법으로 신청하나요?", "선택한 접수 방식", evidence,
                        List.of(new AnswerOption("ONLINE", "인터넷 신청"), new AnswerOption("VISIT", "현장 신청"),
                                new AnswerOption("UNKNOWN", "잘 모르겠어요", true)))),
                List.of(new DocumentTemplate("doc", "공통 서류", null, "기관 발급", "기관", null, evidence)),
                List.of(new ScheduleTemplate("online", "접수 회차 · 인터넷 접수", date, evidence),
                        new ScheduleTemplate("visit", "접수 회차 · 현장 접수", date, evidence),
                        new ScheduleTemplate("result", "결과 발표", date, evidence)));
        UUID notice = notices.insert(UUID.randomUUID().toString(), "맞춤 일정 공고", "기관", "서울", "임대",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content);
        notices.samplePublish(notice, "PASS");
        var current = preparations.start(owner, notice, 1);
        UUID id = UUID.fromString(current.id);
        var snapshot = repository.findActive(UUID.fromString(owner), id).orElseThrow();
        var saved = snapshot.preparation();
        saved.answers = Map.of("method", "ONLINE");
        saved.documents.get(0).state = DocumentState.READY;
        saved.schedules.forEach(s -> s.recorded = !s.id.equals("visit"));
        repository.save(snapshot);
        String before = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, id);

        assertEquals(100, preparations.list(owner).get(0).progress());
        assertEquals(100, preparations.get(owner, id).progress);
        assertEquals(before, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, id));
        assertFalse(repository.findActive(UUID.fromString(owner), id).orElseThrow().preparation().schedules.get(1).recorded);

        var completed = preparations.get(owner, id);
        assertTrue(completed.nextAction.completed());
        assertEquals("준비 기록 완료", completed.nextAction.label());
        var reopened = preparations.schedule(owner, id, "result", completed.revision, false);
        assertFalse(reopened.nextAction.completed());
        assertEquals("신청 일정 확인하기", reopened.nextAction.label());
        var restored = preparations.schedule(owner, id, "result", reopened.revision, true);
        assertTrue(restored.nextAction.completed());
        assertTrue(preparations.get(owner, id).nextAction.completed());

        saved.answers = Map.of("method", "UNKNOWN");
        repository.save(snapshot);
        assertEquals(70, preparations.list(owner).get(0).progress());
        assertEquals(70, preparations.get(owner, id).progress);
        assertFalse(preparations.get(owner, id).nextAction.completed());

        saved.answers = Map.of("method", "ONLINE");
        repository.save(snapshot);
        db.update("UPDATE notice SET version=version+1 WHERE id=?", notice);
        entities.clear();
        assertEquals(100, preparations.list(owner).get(0).progress());
        assertEquals(100, preparations.get(owner, id).progress);
    }

    @Test
    void currentListUsesTheSamePolicyAsDetailWithoutLoadingExplanationsOrRewritingHistory() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("목록 테스트", email, "test-password-123");
        String owner = auth.login(email, "test-password-123").user().id();
        var evidence = new Evidence("테스트 공식 근거", null, "2026-11-22");
        String longExplanation = "목록에 필요하지 않은 설명 ".repeat(1000);
        var content = new Content("준비 목록 정책 테스트", evidence, List.of(), List.of(
                new Question("guide", "신청 안내", longExplanation, evidence,
                        List.of(new AnswerOption("YES", "확인했어요", false),
                                new AnswerOption("REVIEW", "추가 확인이 필요해요", true),
                                new AnswerOption("UNKNOWN", "잘 모르겠어요", true)), "SINGLE", null),
                new Question("count", "가구원 수", longExplanation, evidence, null, "NUMBER",
                        new InputConstraints(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, null, null, null, "명"))),
                List.of(new DocumentTemplate("doc", "공통 서류", null, longExplanation, "기관", null, evidence)),
                List.of(new ScheduleTemplate("date", "공통 접수 일정", LocalDate.now().plusDays(30).toString(), evidence, longExplanation)));
        UUID notice = notices.insert(UUID.randomUUID().toString(), "목록 진행률 공고", "기관", "서울", "임대",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content);
        notices.samplePublish(notice, "PASS");
        var current = preparations.start(owner, notice, 1);
        UUID id = UUID.fromString(current.id);
        var snapshot = repository.findActive(UUID.fromString(owner), id).orElseThrow();
        var saved = snapshot.preparation();
        saved.answers = Map.of("guide", "UNKNOWN", "count", 2);
        saved.documents.get(0).state = DocumentState.READY;
        saved.documents.get(0).note = "기존 메모 유지";
        saved.schedules.get(0).recorded = true;
        saved.progress = 85;
        repository.save(snapshot);
        repository.retainHistory(id, 1, snapshot);
        String before = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, id);

        assertEquals(100, preparations.list(owner).get(0).progress());
        assertEquals(100, preparations.get(owner, id).progress);
        var projected = repository.findActive(UUID.fromString(owner)).get(0).progressInput();
        assertFalse(json.writeValueAsString(projected).contains(longExplanation));
        assertEquals(BigDecimal.TEN, projected.questions.get(1).constraints().max());
        assertEquals(before, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, id));
        assertEquals(85, history.get(owner, id, 1).preparation().progress);
        assertEquals("UNKNOWN", history.get(owner, id, 1).preparation().answers.get("guide"));

        // Legacy out-of-bounds input must remain unresolved in the thin list as well as the detail.
        saved.answers = Map.of("guide", "UNKNOWN", "count", 11);
        repository.save(snapshot);
        assertEquals(70, preparations.list(owner).get(0).progress());
        assertEquals(70, preparations.get(owner, id).progress);
    }
}
