package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.jibro.notice.HousingModels.*;

import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.jibro.auth.AuthService;
import org.jibro.global.common.ApiException;
import org.jibro.preparation.PreparationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HousingIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    PreparationService preparations;
    @Autowired
    AuthService auth;
    @Autowired
    tools.jackson.databind.ObjectMapper json;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate db;
    String owner, other;
    UUID notice;

    @BeforeEach
    void setup() {
        owner = createUser();
        other = createUser();
        notice = insert("서울", "공공임대");
        notices.samplePublish(notice, "PASS");
    }

    String createUser() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("테스트", email, "test-password-123");
        return auth.login(email, "test-password-123").user().id();
    }

    UUID insert(String region, String type) {
        return notices.insert(UUID.randomUUID().toString(), "테스트 청년 공고", "테스트 기관", region, type,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), SampleData.content());
    }

    @Test
    void reviewedOfficialGuidancePreservesConditionalDocumentsAndUnknowns() throws Exception {
        var items = json.readTree(Files.readString(Path.of("../data/notices/guidance-review-20261101.json"))).get("items");
        for (var item : items) notices.validateContent(json.treeToValue(item.get("content"), Content.class));
        Content content = json.treeToValue(items.get(0).get("content"), Content.class);
        UUID reviewed = notices.insert(UUID.randomUUID().toString(), "원문 검수 테스트", "LH", "전국", "청년 전세임대",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content);
        notices.review(reviewed, owner, 1, "PASS", "PASS", "테스트 데이터 검수");
        var p = preparations.start(owner, reviewed, 1);
        UUID id = UUID.fromString(p.id);
        assertEquals(4, p.documents.stream().filter(d -> d.necessity.equals("REQUIRED")).count());
        assertEquals(5, p.documents.stream().filter(d -> d.necessity.equals("UNKNOWN")).count());
        var answers = new HashMap<>(Map.of("student-route", "NO", "jobseeker-route", "NO",
                "parent-abstracts", "NO", "sibling-proof", "NO"));
        p = preparations.answers(owner, id, p.revision, answers);
        assertEquals(5, p.documents.stream().filter(d -> d.necessity.equals("NOT_APPLICABLE")).count());
        answers.put("parent-abstracts", "YES");
        answers.put("sibling-proof", "YES");
        p = preparations.answers(owner, id, p.revision, answers);
        assertEquals(2, p.documents.stream().filter(d -> d.necessity.equals("CONDITIONAL")).count());
        p = preparations.document(owner, id, "parent-abstracts", p.revision, DocumentState.READY, "");
        answers.put("parent-abstracts", "UNKNOWN");
        p = preparations.answers(owner, id, p.revision, answers);
        var parentDocument = p.documents.stream().filter(d -> d.id.equals("parent-abstracts")).findFirst().orElseThrow();
        assertEquals("UNKNOWN", parentDocument.necessity);
        assertEquals(DocumentState.RECHECK, parentDocument.state);
        assertTrue(p.progress < 100);
        assertEquals(1, p.schedules.size()); // Rolling selection has no invented result date.
        assertTrue(p.schedules.get(0).title.contains("18:00"));
    }

    @Test
    void questionOptionsSelectDocumentsRejectUnknownValuesAndPreserveHistory() throws Exception {
        Content content = json.treeToValue(json.readTree(Files.readString(Path.of("../data/notices/question-options-20261101.json")))
                .get("items").get(0).get("content"), Content.class);
        notices.validateContent(content);
        var old = preparations.start(owner, notice, 1);
        UUID id = UUID.fromString(old.id);
        old = preparations.answers(owner, id, old.revision, Map.of("residence", "YES"));
        old = preparations.document(owner, id, old.documents.get(0).id, old.revision, DocumentState.READY, "이전 메모");
        int oldRevision = old.revision;
        notices.updateContent(notice, 1, content);
        notices.review(notice, owner, 2, "PASS", "PASS", "질문별 선택지 검수");
        assertEquals(404, assertThrows(ApiException.class, () -> preparations.refresh(other, id, oldRevision, 2)).status);
        assertEquals(409, assertThrows(ApiException.class, () -> preparations.refresh(owner, id, oldRevision - 1, 2)).status);
        var p = preparations.refresh(owner, id, oldRevision, 2);
        assertEquals(oldRevision + 1, p.revision);
        assertEquals(2, p.version);
        assertTrue(p.answers.isEmpty());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM preparation_history WHERE preparation_id=?", Integer.class, id));
        var archived = json.readValue(db.queryForObject("SELECT snapshot::text FROM preparation_history WHERE preparation_id=?", String.class, id), Snapshot.class);
        assertEquals("YES", archived.preparation().answers.get("residence"));
        assertEquals("이전 메모", archived.preparation().documents.get(0).note);
        int revision = p.revision;
        assertThrows(IllegalArgumentException.class, () -> preparations.answers(owner, id, revision, Map.of("youth-route", "YES")));
        assertThrows(IllegalArgumentException.class, () -> preparations.answers(owner, id, revision, Map.of("not-a-question", "UNKNOWN")));
        p = preparations.answers(owner, id, p.revision, Map.of("youth-route", "STUDENT", "priority-proof", "SELF", "parent-abstracts", "NO"));
        assertEquals(List.of("student-proof"), p.documents.stream().filter(d -> d.necessity.equals("CONDITIONAL")).map(d -> d.id).toList());
        p = preparations.document(owner, id, "student-proof", p.revision, DocumentState.READY, "학교 발급 확인");
        p = preparations.answers(owner, id, p.revision, Map.of("youth-route", "JOBSEEKER", "priority-proof", "SIBLING", "parent-abstracts", "NO"));
        assertEquals(3, p.documents.stream().filter(d -> d.necessity.equals("CONDITIONAL")).count());
        assertEquals("NOT_APPLICABLE", p.documents.stream().filter(d -> d.id.equals("student-proof")).findFirst().orElseThrow().necessity);
        p = preparations.answers(owner, id, p.revision, Map.of("youth-route", "UNKNOWN", "submission-check", "PENDING"));
        assertEquals(5, p.documents.stream().filter(d -> d.necessity.equals("UNKNOWN")).count());
        assertEquals(7, p.warnings.size());
    }

    @Test
    void refreshKeepsDocumentNotesAndRequiresRecheckingPreviouslyReadyDocuments() {
        var p = preparations.start(owner, notice, 1);
        UUID id = UUID.fromString(p.id);
        p = preparations.document(owner, id, p.documents.get(0).id, p.revision, DocumentState.READY, "보존할 메모");
        notices.updateContent(notice, 1, SampleData.content());
        notices.review(notice, owner, 2, "PASS", "PASS", "안내 재검수");
        p = preparations.refresh(owner, id, p.revision, 2);
        assertEquals("보존할 메모", p.documents.get(0).note);
        assertEquals(DocumentState.RECHECK, p.documents.get(0).state);
        int revision = p.revision;
        assertEquals(409, assertThrows(ApiException.class, () -> preparations.refresh(owner, id, revision, 2)).status);
    }

    @Test
    void publicCatalogExcludesSamplesButPreservesTheirDetailsAndPreparations() {
        UUID sample = notices.insert("local-sample-" + UUID.randomUUID(), "샘플", "가상 운영기관", "샘플지역", "샘플유형",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), SampleData.content());
        notices.samplePublish(sample, "PASS");
        var preparation = preparations.start(owner, sample, 1);
        assertEquals(0, notices.search(List.of("샘플지역"), List.of(), List.of(), "", "deadline", 0, 20).totalElements());
        assertEquals(1, notices.search(List.of("샘플지역"), List.of(), List.of(), "", "deadline", 0, 20, true).totalElements());
        assertFalse(notices.filters().get("regions").contains("샘플지역"));
        assertEquals(sample.toString(), notices.notice(sample).id());
        assertEquals(preparation.id, preparations.get(owner, UUID.fromString(preparation.id)).id);
    }

    @Test
    void basicReviewedSchedulesArePublicWhilePersonalGuidanceStaysUnavailable() {
        notices.samplePublish(notice, "HOLD");
        var result = notices.notice(notice);
        assertEquals(1, result.schedules().size());
        assertTrue(result.questions().isEmpty());
        assertTrue(result.documents().isEmpty());
        assertFalse(result.canStart());
        assertEquals("REVIEWING", result.guideState());
    }

    @Test
    void filterOrWithinAndAcrossAndHideUnreviewedGuide() {
        UUID second = insert("경기", "공공임대");
        notices.samplePublish(second, "HOLD");
        UUID excluded = insert("서울", "주거비 지원");
        notices.samplePublish(excluded, "PASS");
        insert("서울", "공공임대");
        var page = notices.search(List.of("서울", "경기"), List.of("공공임대"), List.of("OPEN"), "테스트", "deadline", 0, 20);
        assertEquals(2, page.totalElements());
        assertTrue(notices.notice(second).questions().isEmpty());
        assertFalse(notices.notice(second).canStart());
        assertThrows(ApiException.class, () -> preparations.start(owner, second, 1));
    }

    @Test
    void ownerIsolationIdempotentStartRevisionAndProgress() {
        var p = preparations.start(owner, notice, 1);
        UUID id = UUID.fromString(p.id);
        assertEquals(p.id, preparations.start(owner, notice, 1).id);
        assertEquals(404, assertThrows(ApiException.class, () -> preparations.get(other, id)).status);
        p = preparations.answers(owner, id, 1, Map.of("residence", "UNKNOWN", "house", "UNKNOWN"));
        assertEquals(0, p.progress);
        assertEquals(2, p.warnings.size());
        assertEquals(409, assertThrows(ApiException.class, () -> preparations.answers(owner, id, 1, Map.of())).status);
        p = preparations.answers(owner, id, 2, Map.of("residence", "YES", "house", "NO"));
        assertEquals("NOT_APPLICABLE", p.documents.get(1).necessity);
        p = preparations.document(owner, id, p.documents.get(0).id, 3, DocumentState.READY, "서버에 저장한 메모");
        p = preparations.schedule(owner, id, "apply", 4, true);
        assertEquals(100, p.progress);
        assertEquals(5, p.houseStage);
        assertEquals("서버에 저장한 메모", preparations.get(owner, id).documents.get(0).note);
    }

    @Test
    void changedContentPreservesSnapshotAndRejectsEdits() {
        var p = preparations.start(owner, notice, 1);
        UUID id = UUID.fromString(p.id);
        notices.updateContent(notice, 1, SampleData.content());
        var preserved = preparations.get(owner, id);
        assertEquals(1, preserved.version);
        assertEquals("CHANGE_REVIEW", preserved.guideState);
        assertEquals(p.questions, preserved.questions);
        assertEquals(409, assertThrows(ApiException.class, () -> preparations.answers(owner, id, 1, Map.of())).status);
        assertEquals(404, assertThrows(ApiException.class, () -> notices.notice(notice)).status);
    }

    @Test
    void reviewPublishesBasicAndGuideSeparatelyAndAudits() {
        var review = notices.review(notice, owner, 1, "PASS", "UNSUPPORTED", "준비 규칙 지원 범위 밖");
        assertEquals("UNSUPPORTED", review.notice().guideState());
        assertFalse(notices.notice(notice).canStart());
        notices.review(notice, owner, 2, "HOLD", "HOLD", "원문 확인 필요");
        assertEquals(404, assertThrows(ApiException.class, () -> notices.notice(notice)).status);
    }

    @Test
    void unsupportedDecisionCompletesChangeReview() {
        notices.updateContent(notice, 1, SampleData.content());
        notices.review(notice, owner, 2, "PASS", "UNSUPPORTED", "변경된 규칙은 미지원으로 확정");
        assertEquals("UNSUPPORTED", notices.notice(notice).guideState());
        assertFalse(notices.notice(notice).canStart());
    }

    @Test
    void contentRejectsInvalidRuleAndUnsafeLink() {
        var c = SampleData.content();
        var bad = new Content(c.summary(), new Evidence("위험 링크", "javascript:alert(1)", "2026-10-30"), c.attachments(), c.questions(), c.documents(), c.schedules());
        assertThrows(IllegalArgumentException.class, () -> notices.updateContent(notice, 1, bad));
    }
}
