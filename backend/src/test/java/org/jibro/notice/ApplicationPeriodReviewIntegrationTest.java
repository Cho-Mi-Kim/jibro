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

    @Test void backgroundExtractionSkipsClosedButExplicitReviewCanStillReadIt() {
        var id = imported();
        assertTrue(periods.review(extract(id,"신청 기간: 2015-04-01 ~ 2015-04-02")));
        assertTrue(notices.applicationClosed(id));
        var queued = extraction.enqueue(id,true);
        assertNull(extraction.claim());
        assertEquals(UUID.fromString(queued.id()),extraction.claim(id).id());
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
