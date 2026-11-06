package org.jibro.member;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.jibro.auth.AuthService;
import org.jibro.global.common.ApiException;
import org.jibro.notice.*;
import org.jibro.preparation.PreparationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemberFeaturesIntegrationTest {
    @Autowired
    jakarta.persistence.EntityManager entities;
    @Autowired
    AuthService auth;
    @Autowired
    NoticeService notices;
    @Autowired
    PreparationService preparations;
    @Autowired
    SavedNoticeService saved;
    @Autowired
    ExperienceService experiences;
    @Autowired
    AlertService alerts;

    private UUID user() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("검증회원", email, "integration-password");
        return UUID.fromString(auth.login(email, "integration-password").user().id());
    }

    private UUID notice() {
        var day = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        var id = notices.insert("test-" + UUID.randomUUID(), "검증 공고", "검증기관", "서울", "임대", day.minusDays(1), day.plusDays(1), day.minusDays(2), SampleData.content());
        notices.samplePublish(id, "PASS");
        entities.flush();
        return id;
    }

    @Test
    void savedAndReadAreIsolated() {
        var a = user();
        var b = user();
        var n = notice();
        saved.set(a, n, true);
        saved.set(a, n, true);
        assertEquals(1, saved.list(a).size());
        assertTrue(saved.list(b).isEmpty());
        alerts.configure(a, new AlertService.Settings(true, 3, true));
        var inbox = alerts.inbox(a);
        assertFalse(inbox.items().isEmpty());
        alerts.read(b, List.of(inbox.items().get(0).key()));
        assertFalse(alerts.inbox(a).items().get(0).read());
        alerts.read(a, inbox.items().stream().map(AlertService.Alert::key).toList());
        assertTrue(alerts.inbox(a).items().stream().allMatch(AlertService.Alert::read));
        saved.set(a, n, false);
        assertTrue(alerts.inbox(a).items().isEmpty());
    }

    @Test
    void publicDetailedDeadlineWhileGuidanceIsHeldCreatesOneReminderAndRetainsReadState() {
        var owner = user();
        var day = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1);
        var base = SampleData.content();
        var content = new HousingModels.Content(base.summary(), base.evidence(), base.attachments(),
                base.questions(), base.documents(), List.of(
                new HousingModels.ScheduleTemplate("closing", "접수 마감 (18:00, 조기 중단 가능)", day.toString(), base.evidence()),
                new HousingModels.ScheduleTemplate("documents", "서류 제출 마감", day.toString(), base.evidence())));
        var id = notices.insert("test-" + UUID.randomUUID(), "중복 일정 검증", "검증기관", "서울", "임대",
                day.minusDays(2), day, day.minusDays(3), content);
        notices.samplePublish(id, "HOLD");
        entities.flush();
        saved.set(owner, id, true);
        alerts.configure(owner, new AlertService.Settings(true, 3, true));
        var items = alerts.inbox(owner).items();
        assertEquals(2, items.size());
        var closing = items.stream().filter(item -> item.label().startsWith("접수 마감")).findFirst().orElseThrow();
        alerts.read(owner, List.of(closing.key()));
        var reread = alerts.inbox(owner).items();
        assertTrue(reread.stream().filter(item -> item.key().equals(closing.key())).findFirst().orElseThrow().read());
        assertFalse(reread.stream().filter(item -> item.label().equals("서류 제출 마감")).findFirst().orElseThrow().read());
    }

    @Test
    void archiveHidesActiveAndReusesOnlyAnOwnersMemo() {
        var a = user();
        var b = user();
        var first = preparations.start(a.toString(), notice(), 1);
        var target = preparations.start(a.toString(), notice(), 1);
        var id = UUID.fromString(first.id);
        var other = UUID.fromString(target.id);
        assertThrows(ApiException.class, () -> experiences.archive(b, id, "", first.revision));
        experiences.archive(a, id, "발급 날짜를 확인", first.revision);
        assertEquals(1, preparations.list(a.toString()).size());
        assertThrows(ApiException.class, () -> preparations.answers(a.toString(), id, first.revision, Map.of("residence", "YES")));
        assertThrows(ApiException.class, () -> experiences.attach(b, other, id));
        experiences.attach(a, other, id);
        assertEquals("발급 날짜를 확인", experiences.references(a, other).get(0).note());
        assertEquals(target.progress, preparations.get(a.toString(), other).progress);
        experiences.restore(a, id);
        assertEquals(2, preparations.list(a.toString()).size());
    }
}
