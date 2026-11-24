package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import jakarta.persistence.EntityManager;
import org.jibro.auth.AuthService;
import org.jibro.global.common.ApiException;
import org.jibro.preparation.PreparationService;
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
class ListPayloadIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    PreparationService preparations;
    @Autowired
    AuthService auth;
    @Autowired
    EntityManager entities;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;

    @Test
    void catalogAndPreviewKeepAuthoritativeMetadataWithoutGuidancePayloads() {
        String group = "list-payload-" + UUID.randomUUID();
        var id = notice(group);
        var c = notices.content(id);
        var issue = new ReviewIssue("held", "CONDITION", "확인 필요", "기관 확인", List.of(), List.of(), List.of(c.evidence()));
        notices.entity(id).content = json.writeValueAsString(new Content("긴 요약 ".repeat(10000), c.evidence(), c.attachments(),
                c.questions(), c.documents(), c.schedules(), List.of(issue)));
        entities.flush();
        entities.clear();
        var page = notices.search(List.of(), List.of(), List.of(), group, "active", 0, 20);
        assertEquals(1, page.totalElements());
        var item = page.items().get(0);
        var detail = notices.notice(id);
        assertEquals(detail.title(), item.title());
        assertEquals(detail.status(), item.status());
        assertEquals(detail.guideState(), item.guideState());
        assertEquals(detail.lifecycle(), item.lifecycle());
        assertEquals(detail.applicationTiming(), item.applicationTiming());
        assertTrue(item.partialGuidance());
        var fields = json.readTree(json.writeValueAsString(item));
        for (var key : List.of("questions", "documents", "documentTemplates", "summary", "attachments", "reviewIssues", "schedules"))
            assertFalse(fields.has(key), key);
        assertTrue(json.writeValueAsBytes(item).length < json.writeValueAsBytes(detail).length / 10);
        assertFalse(detail.questions().isEmpty());
        notices.entity(id).changeReview = true;
        entities.flush();
        var changed = notices.search(List.of(), List.of(), List.of(), group, "active", 0, 20).items().get(0);
        assertEquals("CHANGE_REVIEW", changed.guideState());
        assertFalse(changed.partialGuidance(), "unpublished issues must not appear as available partial guidance");
    }

    @Test
    void untilFilledScheduleIsVisibleInTheListAndDetail() {
        var id = notice("rolling-" + UUID.randomUUID());
        var c = notices.content(id);
        var session = new ScheduleTemplate("rolling", ApplicationTiming.UNTIL_FILLED_SESSION, "", c.evidence(), "평일 방문 10:00~16:00");
        var entity = notices.entity(id);
        entity.endDate = null;
        entity.content = json.writeValueAsString(new Content(c.summary(), c.evidence(), c.attachments(),
                c.questions(), c.documents(), List.of(session), List.of()));
        entities.flush();
        entities.clear();
        assertTrue(notices.notice(id).applicationTiming().untilFilled());
        assertTrue(notices.listByIds(List.of(id)).get(0).applicationTiming().untilFilled());
    }

    @Test
    void preparationListContainsOnlyOwnedActiveMetadataWhileSelectedDetailKeepsAnswersAndNotes() {
        String owner = user();
        String other = user();
        UUID first = notice("first"), second = notice("second"), foreign = notice("foreign");
        var p = preparations.start(owner, first, 1);
        var id = UUID.fromString(p.id);
        p = preparations.answers(owner, id, p.revision, Map.of("residence", "YES"));
        p = preparations.document(owner, id, p.documents.get(0).id, p.revision, DocumentState.READY, "선택한 준비의 메모");
        var archived = preparations.start(owner, second, 1);
        preparations.start(other, foreign, 1);
        db.update("UPDATE preparation SET archived_at=now() WHERE id=?", UUID.fromString(archived.id));
        entities.flush();
        var list = preparations.list(owner);
        assertEquals(1, list.size());
        assertEquals(p.id, list.get(0).id());
        assertEquals(p.revision, list.get(0).revision());
        assertEquals(p.progress, list.get(0).progress());
        var fields = json.readTree(json.writeValueAsString(list.get(0)));
        assertEquals(6, fields.size());
        for (var key : List.of("questions", "answers", "documents", "schedules", "rules", "warnings"))
            assertFalse(fields.has(key), key);
        var full = preparations.get(owner, id);
        assertEquals("YES", full.answers.get("residence"));
        assertEquals("선택한 준비의 메모", full.documents.get(0).note);
        assertEquals(404, assertThrows(ApiException.class, () -> preparations.get(other, id)).status);
        // The small list is not an authority to edit an outdated preparation.
        notices.entity(first).changeReview = true;
        entities.flush();
        assertEquals("CHANGE_REVIEW", preparations.get(owner, id).guideState);
        int revision = p.revision;
        assertEquals(409, assertThrows(ApiException.class, () -> preparations.answers(owner, id, revision, Map.of())).status);
        System.out.printf("LIST_PAYLOAD preparation summary=%d detail=%d bytes%n",
                json.writeValueAsBytes(list.get(0)).length, json.writeValueAsBytes(full).length);
    }

    private String user() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("검증 회원", email, "integration-password");
        return auth.login(email, "integration-password").user().id();
    }

    private UUID notice(String title) {
        var day = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var id = notices.insert("test-" + UUID.randomUUID(), title, "LH", "서울", "임대", day.minusDays(1), day.plusDays(30), day, SampleData.content());
        notices.samplePublish(id, "PASS");
        entities.flush();
        return id;
    }
}
