package org.jibro.notice;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import jakarta.persistence.EntityManager;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.extraction.*;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ApplicationPeriodReviewIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    AutomaticPublicationService basics;
    @Autowired
    ApplicationPeriodReviewService periods;
    @Autowired
    ExtractionStore extraction;
    @Autowired
    EntityManager entities;

    @Test
    void backgroundExtractionSkipsClosedButExplicitReviewCanStillReadIt() {
        var id = imported();
        assertTrue(periods.review(extract(id, "신청 기간: 2015-04-01 ~ 2015-04-02")));
        assertTrue(notices.applicationClosed(id));
        var queued = extraction.enqueue(id, true);
        assertNull(extraction.claim());
        assertEquals(UUID.fromString(queued.id()), extraction.claim(id).id());
    }

    UUID imported() {
        String url = "https://apply.lh.or.kr/detail.do?id=" + UUID.randomUUID();
        UUID id = notices.importSource(new LhNoticeClient.Item("LH:" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)),
            "임대 모집", "서울", "임대", url, LocalDate.of(2015, 1, 1), "a".repeat(64)));
        basics.basic(id);
        return id;
    }

    UUID extract(UUID id, String text) {
        entities.flush();
        var draft = extraction.enqueue(id, true);
        var job = extraction.claim(id);
        String url = notices.content(id).evidence().url();
        extraction.finish(job, List.of(new Source("공식 공고문", url, "b".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("본문", text)))), "READY", "");
        return UUID.fromString(draft.id());
    }

    @Test
    void expiredAttachmentClosesWithoutApprovingGuidanceAndDoesNotRepeat() {
        var id = imported();
        var original = notices.content(id);
        var draft = extract(id, "신청 기간: 2015-04-01 10:00 ~ 2015-04-02 16:00\n복잡한 자격표 미해석");
        assertTrue(periods.pending().contains(draft));
        assertTrue(periods.review(draft));
        var view = notices.notice(id);
        assertEquals("CLOSED", view.status());
        assertEquals("REVIEWING", view.guideState());
        assertFalse(notices.entity(id).sourceClosed);
        assertFalse(view.canStart());
        assertEquals(original.questions(), notices.content(id).questions());
        assertEquals(Instant.parse("2015-04-02T07:00:00Z"), view.applicationTiming().endsAt());
        assertFalse(periods.review(draft));
        assertFalse(periods.pending().contains(draft));
    }

    @Test
    void publishesAndReplacesOnlyCurrentSourceExplanations() {
        var id = imported();
        String rolling = "신청 기간: 모집 완료 시까지";
        assertTrue(periods.review(extract(id, rolling)));
        assertTrue(notices.listByIds(List.of(id)).get(0).applicationTiming().untilFilled());
        assertFalse(periods.review(extract(id, rolling)));
        String conflict = "신청기간: 2099.12.01 10:00~2099.12.02 16:00\n신청기간: 2099.11.30 10:00~2099.12.01 16:00";
        assertTrue(periods.review(extract(id, conflict)));
        var view = notices.notice(id);
        assertFalse(view.applicationTiming().untilFilled());
        assertNull(view.applicationTiming().nextChangeAt());
        assertEquals(2, view.schedules().size());
        assertTrue(view.schedules().stream().allMatch(s -> s.date().isEmpty()));
        assertTrue(periods.review(extract(id, "신청기간: 2099.12.01 10:00~2099.12.02 16:00")));
        assertTrue(notices.notice(id).schedules().stream().allMatch(s -> !s.date().isEmpty()));
        var closed = extract(id, rolling);
        notices.entity(id).sourceClosed = true;
        entities.flush();
        assertFalse(periods.pending().contains(closed));
        assertFalse(periods.review(closed));
        assertFalse(notices.notice(id).applicationTiming().untilFilled());
    }

    @Test
    void futurePeriodClosesAtTheStoredBoundary() {
        var id = imported();
        var end = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(10);
        var draft = extract(id, "신청 기간: " + end.minusDays(2) + " ~ " + end);
        assertTrue(periods.review(draft));
        assertEquals(end, notices.notice(id).endDate());
        assertEquals("CLOSED", ApplicationPeriod.status(end.minusDays(2), end, new ApplicationPeriod.Window(null, null), end.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()));
    }

    @Test
    void manualEditsAndStaleExtractionNeverOverwrite() {
        var id = imported();
        var draft = extract(id, "신청 기간: 2015-04-01 ~ 2015-04-02");
        notices.updateContent(id, notices.entity(id).revision, notices.content(id));
        assertFalse(periods.review(draft));
        assertNull(notices.entity(id).endDate);
    }

    @Test
    void persistsSessionsEvenWhenStoredBoundariesAlreadyMatchAndSkipsAfterClosure() {
        var id = imported();
        String date = LocalDate.now(ApplicationPeriod.SEOUL).plusDays(2).toString();
        assertTrue(periods.review(extract(id, "접수 기간: " + date + " 09:00 ~ " + date + " 16:00")));
        var text = "6. 모집일정\n온라인 접수: " + date + " 09:00~16:00\n현장 접수: " + date + " 10:00~15:00";
        var draft = extract(id, text);
        assertTrue(periods.review(draft));
        assertEquals(4, notices.notice(id).schedules().stream().filter(s -> s.id().startsWith("auto-scoped-")).count());
        assertFalse(periods.review(draft));
        var closedDraft = extract(id, text);
        notices.entity(id).sourceClosed = true;
        entities.flush();
        assertFalse(periods.pending().contains(closedDraft));
        assertFalse(periods.review(closedDraft));
    }

    @Test
    void fillsMissingStartTimeWhileKeepingTheAlreadyVerifiedEndClock() {
        var id = imported();
        var date = LocalDate.now(ApplicationPeriod.SEOUL).plusDays(3);
        assertTrue(periods.review(extract(id, "접수 기간: " + date + " ~ " + date + " 18:00")));
        assertTrue(periods.review(extract(id, "접수 기간: " + date + " 10:00 ~ " + date)));
        var view = notices.notice(id);
        assertEquals(date.atTime(10, 0).atZone(ApplicationPeriod.SEOUL).toInstant(), view.applicationTiming().startsAt());
        assertEquals(date.atTime(18, 0).atZone(ApplicationPeriod.SEOUL).toInstant(), view.applicationTiming().endsAt());
        assertEquals(2, view.schedules().size());
    }

    @Test
    void existingDateConflictRemainsUnchanged() {
        var id = imported();
        notices.entity(id).endDate = LocalDate.of(2099, 1, 1);
        var draft = extract(id, "신청 기간: 2015-04-01 ~ 2015-04-02");
        assertFalse(periods.review(draft));
        assertEquals(LocalDate.of(2099, 1, 1), notices.entity(id).endDate);
    }

    @Test
    void sameLhActiveResponseDoesNotReactivateVerifiedExpiredPeriod() {
        var id = imported();
        var n = notices.entity(id);
        var item = new LhNoticeClient.Item(n.sourceKey, n.title, n.region, n.supportType, notices.content(id).evidence().url(), n.publishedDate, n.sourceFingerprint);
        assertTrue(periods.review(extract(id, "신청 기간: 2015-04-01 ~ 2015-04-02")));
        notices.importSource(item);
        assertEquals("CLOSED", notices.notice(id).status());
    }

    @Test
    void replacedFingerprintAndIncompleteSourcesCannotPublish() {
        var id = imported();
        var draft = extract(id, "신청 기간: 2015-04-01 ~ 2015-04-02");
        notices.entity(id).sourceFingerprint = "c".repeat(64);
        entities.flush();
        assertFalse(periods.review(draft));
        assertNull(notices.entity(id).endDate);
    }
}
