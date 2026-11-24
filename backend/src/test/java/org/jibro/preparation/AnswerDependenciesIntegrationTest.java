package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import org.jibro.auth.AuthService;
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
class AnswerDependenciesIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    PreparationService preparations;
    @Autowired
    PreparationHistoryService history;
    @Autowired
    OneTimeCheckService oneTime;
    @Autowired
    AuthService auth;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate db;
    String owner;

    @BeforeEach
    void user() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("답변 연결 검사", email, "test-password-123");
        owner = auth.login(email, "test-password-123").user().id();
    }

    private Content content(boolean linked) throws Exception {
        var node = json.readTree(Files.readString(Path.of("../data/contracts/answer-dependencies.json")));
        var questions = Arrays.asList(json.treeToValue(node.get("questions"), Question[].class));
        if (!linked) questions = questions.stream().map(q -> new Question(q.id(), q.title(), q.reason(), q.evidence(),
                q.options(), q.inputKind(), q.constraints(), q.reviewRequired())).toList();
        var documents = Arrays.asList(json.treeToValue(node.get("documents"), DocumentTemplate[].class));
        return new Content("공식 자료로 확인한 서류 연결", questions.get(0).evidence(), List.of(), questions, documents,
                List.of(new ScheduleTemplate("application", "접수 마감", LocalDate.now().plusDays(30).toString(), questions.get(0).evidence())));
    }

    private UUID publish(boolean linked) throws Exception {
        var id = notices.insert(UUID.randomUUID().toString(), "답변 연결 공고", "테스트 기관", "경북", "행복주택",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content(linked));
        notices.samplePublish(id, "PASS");
        return id;
    }

    @Test
    void savedAndOneTimeChecksKeepMandatoryDocumentsAndTheOriginalConflictingAnswers() throws Exception {
        UUID noticeId = publish(true);
        var answers = Map.<String, Object>of("category", "YOUTH", "applies-family", "NONE");
        var once = oneTime.evaluate(noticeId, 1, answers);
        assertEquals(answers, once.answers());
        assertEquals("CONDITIONAL", once.documents().get(0).necessity);
        assertEquals(2, once.warnings().size());
        assertTrue(once.warnings().get(1).contains("신청 유형과 가족관계 답변"));
        var p = preparations.start(owner, noticeId, 1);
        UUID preparationId = UUID.fromString(p.id);
        p = preparations.answers(owner, preparationId, p.revision, answers);
        assertEquals(once.answers(), p.answers);
        assertEquals(once.warnings(), p.warnings);
        assertEquals("CONDITIONAL", p.documents.get(0).necessity);
        assertEquals("서로 다른 답변을 다시 확인해 주세요.", p.nextAction.reason());
        assertEquals(answers, preparations.get(owner, preparationId).answers);
        assertEquals(2, preparations.get(owner, preparationId).questions.get(1).answerDependencies().size());

        p = preparations.document(owner, preparationId, "family", p.revision, DocumentState.READY, "준비한 증명서");
        assertTrue(p.progress < 100, "An unresolved answer conflict cannot count as all conditions completed");
        p = preparations.answers(owner, preparationId, p.revision, Map.of("category", "YOUTH", "applies-family", "REQUIRED_TYPE"));
        assertTrue(p.warnings.isEmpty());
        assertEquals("준비한 증명서", p.documents.get(0).note);
        assertEquals(DocumentState.READY, p.documents.get(0).state);
    }

    @Test
    void unknownCategoryCannotHideFamilyButConfirmedNonApplicableCategoryCan() throws Exception {
        UUID noticeId = publish(true);
        var unknown = oneTime.evaluate(noticeId, 1, Map.of("category", "UNKNOWN", "applies-family", "NONE"));
        assertEquals("UNKNOWN", unknown.documents().get(0).necessity);
        assertEquals(List.of(unknown.questions().get(0).title()), unknown.warnings());
        var absent = oneTime.evaluate(noticeId, 1, Map.of("applies-family", "NONE"));
        assertEquals("UNKNOWN", absent.documents().get(0).necessity);
        var no = oneTime.evaluate(noticeId, 1, Map.of("category", "ELDER", "applies-family", "NONE"));
        assertEquals("NOT_APPLICABLE", no.documents().get(0).necessity);
        assertTrue(no.warnings().isEmpty());
        var opposite = oneTime.evaluate(noticeId, 1, Map.of("category", "ELDER", "applies-family", "REQUIRED_TYPE"));
        assertEquals("CONDITIONAL", opposite.documents().get(0).necessity);
        assertTrue(opposite.warnings().get(1).contains("신청 유형이 달라요"));
    }

    @Test
    void fixedScopeReplacementReasksOldYesAndPreservesHistoryNotesAndListProgress() throws Exception {
        var e = content(false).evidence();
        var oldQuestions = List.of(new Question("category", "신청 유형을 확인했나요?", "이전 공통 기준", e),
                new Question("household", "집이 없나요?", "신청 유형별 범위", e));
        var oldContent = new Content("기존 공통 안내", e, List.of(), oldQuestions,
                List.of(new DocumentTemplate("family", "가족 증빙", "household", "기관 발급", "기관", null, e, List.of("YES"))),
                content(false).schedules());
        UUID noticeId = notices.insert(UUID.randomUUID().toString(), "유형별 질문 교체", "테스트 기관", "경기", "행복주택",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), oldContent);
        notices.samplePublish(noticeId, "PASS");
        var p = preparations.start(owner, noticeId, 1);
        UUID id = UUID.fromString(p.id);
        p = preparations.answers(owner, id, p.revision, Map.of("category", "YES", "household", "YES"));
        p = preparations.document(owner, id, "family", p.revision, DocumentState.READY, "보존할 원래 메모");
        String original = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, id);
        var parent = new Question("application-type", "어떤 신청 유형인가요?", "원문 모집 유형", e,
                List.of(new AnswerOption("NEWLYWED", "신혼부부"), new AnswerOption("PREMARITAL", "예비신혼부부"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true)));
        var married = new Question("married-household", "함께 심사할 가족에게 집이 없나요?", "혼인 세대 기준", e, null,
                "SINGLE", null, false, List.of(), new QuestionApplicability(parent.id(), List.of("NEWLYWED")));
        var future = new Question("future-household", "결혼 후 살 가족에게 집이 없나요?", "결혼 후 세대 기준", e, null,
                "SINGLE", null, false, List.of(), new QuestionApplicability(parent.id(), List.of("PREMARITAL")));
        var next = new Content("검수한 고정 범위", e, List.of(), List.of(parent, married, future),
                List.of(new DocumentTemplate("family", "가족 증빙", married.id(), "기관 발급", "기관", null, e, List.of("YES"))), oldContent.schedules());
        var changed = notices.updateContent(noticeId, 1, next);
        notices.review(noticeId, owner, changed.revision(), "PASS", "PASS", "유형별 공식 범위 확인");
        p = preparations.refresh(owner, id, p.revision, changed.notice().version());
        assertTrue(p.answers.isEmpty(), "Old YES must not answer a replacement scope");
        assertEquals(married.applicability(), p.questions.get(1).applicability());
        assertEquals("보존할 원래 메모", p.documents.get(0).note);
        assertEquals(DocumentState.RECHECK, p.documents.get(0).state);
        assertEquals(Map.of("category", "YES", "household", "YES"), history.get(owner, id, 1).preparation().answers);
        assertEquals(json.readTree(original), json.readTree(db.queryForObject(
                "SELECT snapshot::text FROM preparation_history WHERE preparation_id=? AND notice_version=1", String.class, id)));
        p = preparations.answers(owner, id, p.revision, Map.of(parent.id(), "PREMARITAL", future.id(), "YES"));
        assertEquals(30, p.progress); // Only conditions contribute when no documents apply and no schedule is recorded.
        assertEquals("/preparations/" + id + "/schedule", p.nextAction.path());
        assertEquals(p.progress, preparations.list(owner).get(0).progress());
        assertEquals(p.progress, preparations.get(owner, id).progress);
        assertEquals(List.of(parent.id(), future.id()), PreparationApplicability.visibleQuestions(p).stream().map(Question::id).toList());
        assertTrue(PreparationApplicability.visibleDocuments(p).isEmpty());
        assertEquals("UNKNOWN", p.documents.get(0).necessity);
        assertEquals("보존할 원래 메모", p.documents.get(0).note);
        var once = oneTime.evaluate(noticeId, changed.notice().version(), p.answers);
        assertTrue(once.warnings().isEmpty());
        assertEquals(married.applicability(), once.questions().get(1).applicability());
    }

    @Test
    void explicitVersionRefreshUsesNewRulesAndKeepsOldAnswersRulesAndNotesInHistory() throws Exception {
        UUID noticeId = publish(false);
        var p = preparations.start(owner, noticeId, 1);
        UUID preparationId = UUID.fromString(p.id);
        p = preparations.answers(owner, preparationId, p.revision, Map.of("category", "YOUTH", "applies-family", "REQUIRED_TYPE"));
        p = preparations.document(owner, preparationId, "family", p.revision, DocumentState.READY, "이전 준비 메모");
        p = preparations.answers(owner, preparationId, p.revision, Map.of("category", "YOUTH", "applies-family", "NONE"));
        assertEquals("NOT_APPLICABLE", p.documents.get(0).necessity);
        assertTrue(p.warnings.isEmpty());
        String original = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, preparationId);
        assertFalse(original.contains("answerDependencies"));

        var changed = notices.updateContent(noticeId, 1, content(true));
        notices.review(noticeId, owner, changed.revision(), "PASS", "PASS", "공식 서류 필수 대상의 답변 연결 확인");
        p = preparations.refresh(owner, preparationId, p.revision, changed.notice().version());
        assertEquals("YOUTH", p.answers.get("category"));
        assertFalse(p.answers.containsKey("applies-family"), "A changed question contract must be answered again");
        assertEquals("CONDITIONAL", p.documents.get(0).necessity);
        assertEquals("이전 준비 메모", p.documents.get(0).note);
        assertEquals(DocumentState.RECHECK, p.documents.get(0).state);
        var old = history.get(owner, preparationId, 1).preparation();
        assertEquals("NONE", old.answers.get("applies-family"));
        assertEquals("NOT_APPLICABLE", old.documents.get(0).necessity);
        assertTrue(old.questions.get(1).answerDependencies().isEmpty());
        assertEquals(json.readTree(original), json.readTree(db.queryForObject(
                "SELECT snapshot::text FROM preparation_history WHERE preparation_id=? AND notice_version=1", String.class, preparationId)));
        p = preparations.answers(owner, preparationId, p.revision, Map.of("category", "YOUTH", "applies-family", "NONE"));
        assertEquals("CONDITIONAL", p.documents.get(0).necessity);
        assertEquals(2, p.warnings.size());
        assertEquals("NONE", history.get(owner, preparationId, 1).preparation().answers.get("applies-family"));
    }
}
