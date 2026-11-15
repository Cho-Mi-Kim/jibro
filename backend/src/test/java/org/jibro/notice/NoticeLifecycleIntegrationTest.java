package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.jibro.auth.AuthService;
import org.jibro.global.common.ApiException;
import org.jibro.member.AlertService;
import org.jibro.member.SavedNoticeService;
import org.jibro.preparation.PreparationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class NoticeLifecycleIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired NoticeLifecycleService lifecycle;
    @Autowired PreparationService preparations;
    @Autowired AuthService auth;
    @Autowired AlertService alerts;
    @Autowired SavedNoticeService saved;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    UUID owner, notice;
    Evidence evidence;

    @BeforeEach
    void setup() {
        owner = user();
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        evidence = new Evidence("운영기관 상태 변경 안내", "https://apply.lh.or.kr/official-notice", today.toString());
        var content = SampleData.content();
        content = new Content(content.summary(), content.evidence(), content.attachments(), content.questions(), content.documents(),
                List.of(new ScheduleTemplate("start", "신청 접수 시작", today.plusDays(1).toString(), evidence),
                        new ScheduleTemplate("end", "신청 접수 마감", today.plusDays(2).toString(), evidence),
                        new ScheduleTemplate("docs", "서류 제출 마감", today.plusDays(3).toString(), evidence)));
        notice = notices.insert("lifecycle-test-" + UUID.randomUUID(), "공식 상태 검증", "LH", "서울", "임대",
                today.minusDays(1), today.plusDays(2), today.minusDays(2), content);
        notices.review(notice, owner.toString(), 1, "PASS", "PASS", "원문 대조");
        entities.flush();
    }

    UUID user() {
        var email = UUID.randomUUID() + "@example.invalid";
        auth.signup("상태 검증", email, "test-password-123");
        return UUID.fromString(auth.login(email, "test-password-123").user().id());
    }

    Review change(String state) {
        return lifecycle.change(notice, owner, notices.entity(notice).revision, state, "공식 안내 직접 확인", evidence, true);
    }

    @Test
    void closureKeepsPublishedGuidanceAndPrivateSnapshotButBlocksNewStarts() {
        var prep = preparations.start(owner.toString(), notice, 1);
        var prepId = UUID.fromString(prep.id);
        prep = preparations.document(owner.toString(), prepId, prep.documents.get(0).id, prep.revision, DocumentState.READY, "기존 기록");
        String snapshot = db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, prepId);
        var updated = change("EARLY_CLOSED");
        entities.flush();
        entities.clear();
        var publicNotice = notices.notice(notice);
        assertEquals("OPEN", publicNotice.status());
        assertEquals("AVAILABLE", publicNotice.guideState());
        assertEquals(1, publicNotice.version());
        assertFalse(publicNotice.canStart());
        assertEquals("PASS", updated.basic());
        assertEquals("PASS", updated.guide());
        var other = user();
        assertEquals(409, assertThrows(ApiException.class, () -> preparations.start(other.toString(), notice, 1)).status);
        assertEquals(snapshot, db.queryForObject("SELECT snapshot::text FROM preparation WHERE id=?", String.class, prepId));
        var retained = preparations.get(owner.toString(), prepId);
        assertEquals(prep.revision, retained.revision);
        assertEquals(prep.progress, retained.progress);
        assertEquals("기존 기록", retained.documents.get(0).note);
        assertTrue(retained.nextAction.label().contains("조기 마감"));
        assertEquals(prep.id, preparations.start(owner.toString(), notice, 1).id);
        assertEquals(0, notices.search(List.of(), List.of(), List.of("OPEN"), "공식 상태 검증", "deadline", 0, 20).totalElements());
        assertEquals(1, notices.search(List.of(), List.of(), List.of("EARLY_CLOSED"), "공식 상태 검증", "deadline", 0, 20).totalElements());
        assertEquals("EARLY_CLOSED", db.queryForObject("SELECT facts->'lifecycle'->>'state' FROM published_notice_version WHERE notice_id=? ORDER BY revision DESC LIMIT 1", String.class, notice));
    }

    @Test
    void cancellationStopsAllDeadlinesAndRestorationCreatesANewAuditedChange() {
        saved.set(owner, notice, true);
        alerts.configure(owner, new AlertService.Settings(true, 7, true));
        assertTrue(alerts.inbox(owner).items().stream().anyMatch(a -> a.date() != null));
        change("EARLY_CLOSED");
        entities.flush();
        var early = alerts.inbox(owner).items();
        assertEquals(List.of("서류 제출 마감"), early.stream().filter(a -> a.date() != null).map(AlertService.Alert::label).toList());
        var earlyKey = early.stream().filter(a -> a.date() == null).findFirst().orElseThrow().key();
        change("CANCELLED");
        entities.flush();
        var cancelled = alerts.inbox(owner).items();
        assertTrue(cancelled.stream().noneMatch(a -> a.date() != null));
        assertTrue(cancelled.stream().anyMatch(a -> a.label().contains("취소")));
        assertTrue(cancelled.stream().noneMatch(a -> a.key().equals(earlyKey)));
        var restored = change("NORMAL");
        entities.flush();
        assertTrue(restored.notice().canStart());
        assertEquals(3, restored.notice().lifecycle().revision());
        assertEquals(3, db.queryForObject("SELECT count(*) FROM notice_lifecycle_audit WHERE notice_id=?", Integer.class, notice));
        assertTrue(alerts.inbox(owner).items().stream().anyMatch(a -> a.date() != null));
        assertTrue(alerts.inbox(owner).items().stream().anyMatch(a -> a.label().contains("복원")));
    }

    @Test
    void missingEvidenceOrStaleRevisionCannotChangeOfficialState() {
        int revision = notices.entity(notice).revision;
        assertThrows(IllegalArgumentException.class, () -> lifecycle.change(notice, owner, revision, "CANCELLED", "추측", evidence, false));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.change(notice, owner, revision, "CANCELLED", "공식", new Evidence("원문", null, evidence.checkedAt()), true));
        change("CANCELLED");
        assertEquals(409, assertThrows(ApiException.class, () -> lifecycle.change(notice, owner, revision, "NORMAL", "복원", evidence, true)).status);
        assertEquals("CANCELLED", notices.notice(notice).lifecycle().state());
    }

    @Test
    void endpointRequiresAdministratorAndOfficialConfirmation() throws Exception {
        String path = "/api/v1/admin/reviews/" + notice + "/lifecycle";
        String body = json.writeValueAsString(new NoticeLifecycleController.Change(2, "CANCELLED", "공식 취소 안내", evidence, true));
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(patch(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(patch(path).with(csrf()).with(jwt().jwt(j -> j.subject(owner.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(patch(path).with(csrf()).with(jwt().jwt(j -> j.subject(owner.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }
}
