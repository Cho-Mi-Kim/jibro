package org.jibro.notice;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.extraction.*;
import org.jibro.collection.review.AutomaticReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AutomaticPublicationIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired AutomaticPublicationService publication;
    @Autowired AutomaticReviewService review;
    @Autowired ExtractionStore extraction;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;
    @Autowired AutomaticReviewRepository queue;

    UUID imported() {
        String url = "https://apply.lh.or.kr/detail.do?id=" + UUID.randomUUID();
        return notices.importSource(new LhNoticeClient.Item("LH:" + UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)),
            "청년 모집", "서울", "청년 매입임대", url, LocalDate.now().minusDays(1), "a".repeat(64)));
    }

    @Test void importedBasicPublishesOnceWithoutGuessingPeriodOrGuidance() {
        var id = imported();
        assertTrue(publication.basic(id));
        assertFalse(publication.basic(id));
        var notice = notices.notice(id);
        assertEquals("REVIEWING", notice.guideState());
        assertNull(notice.startDate());
        assertFalse(notice.canStart());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM automatic_notice_review WHERE notice_id=?", Integer.class, id));
    }

    @Test void manualDraftAndNonApiSourcesAreNeverOverwritten() {
        var id = imported();
        notices.updateDraft(id, 1, notices.draft(id));
        assertFalse(publication.basic(id));
        UUID sample = notices.insert("manual-" + UUID.randomUUID(), "직접 작성", "LH", "서울", "임대", null, null, LocalDate.now(), SampleData.content());
        assertFalse(publication.basic(sample));
    }

    @Test void missingMetadataIsHeldAndDecisionIsIdempotent() {
        var id = imported();
        notices.entity(id).region = "확인 필요";
        assertFalse(publication.basic(id));
        assertFalse(publication.basic(id));
        assertEquals("HOLD", notices.entity(id).basicReview);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM automatic_notice_review WHERE notice_id=?", Integer.class, id));
    }

    UUID draft(UUID id, boolean supported) {
        entities.flush();
        var draft = extraction.enqueue(id, true);
        var job = extraction.claim();
        String url = notices.content(id).evidence().url();
        String text = supported ? "신청자는 무주택자이어야 합니다.\n공통 제출서류: 주민등록등본\n신청 기간: 2026-11-01 ~ 2026-12-31" : "가구원 범위와 예외는 별도 표를 확인하세요.";
        var parts = List.of(new Part("공고문 1쪽", text));
        var source = new Source("공식 상세 본문", url, "b".repeat(64), "EXTRACTED", "원문", ExcerptClassifier.extract(parts, url), parts);
        extraction.finish(job, List.of(source), "READY", "발췌 완료");
        return UUID.fromString(draft.id());
    }

    @Test void fullySupportedGuidancePublishesAndRepeatDoesNotChangeVersion() {
        UUID id = imported();
        publication.basic(id);
        UUID draft = draft(id, true);
        assertTrue(review.review(draft));
        int version = notices.notice(id).version();
        assertEquals("AVAILABLE", notices.notice(id).guideState());
        assertEquals(1, notices.notice(id).questions().size());
        assertFalse(review.review(draft));
        assertEquals(version, notices.notice(id).version());
    }

    @Test void unsupportedAndStaleDraftsCannotLeakTentativeContent() {
        UUID id = imported(); publication.basic(id);
        var original = notices.content(id);
        UUID draft = draft(id, false);
        assertFalse(review.review(draft));
        assertEquals(original, notices.content(id));
        assertEquals("REVIEWING", notices.notice(id).guideState());
        UUID fresh = draft(id, true);
        notices.updateContent(id, notices.entity(id).revision, original);
        assertFalse(review.review(fresh));
        assertEquals(original, notices.content(id));
    }

    @Test void closedNoticesAreExcludedFromAllAutomaticQueuesAndDirectPublication() {
        UUID basic = imported();
        notices.entity(basic).endDate = LocalDate.now(ApplicationPeriod.SEOUL).minusDays(1);
        entities.flush();
        assertFalse(queue.pendingBasic().contains(basic));
        assertFalse(publication.basic(basic));
        assertEquals(1, notices.entity(basic).revision);

        UUID id = imported(); publication.basic(id);
        UUID draft = draft(id, true);
        var n = notices.entity(id);
        n.startDate = LocalDate.now(ApplicationPeriod.SEOUL).minusDays(2);
        n.endDate = LocalDate.now(ApplicationPeriod.SEOUL);
        n.applicationEndAt = Instant.now().minusSeconds(1);
        entities.flush();
        String original = n.content; int revision = n.revision;
        assertFalse(queue.pendingExtraction().contains(id));
        assertFalse(queue.pendingGuidance().contains(draft));
        assertFalse(review.review(draft));
        assertEquals(original, notices.entity(id).content);
        assertEquals(revision, notices.entity(id).revision);
        assertEquals(0, db.queryForObject("SELECT count(*) FROM automatic_notice_review WHERE notice_id=? AND phase='GUIDANCE'", Integer.class, id));
    }

    @Test void freshlyExtractedPastPeriodCannotPublishEvenIfStoredDatesWereUnknown() {
        UUID id = imported(); publication.basic(id); entities.flush();
        var draft = extraction.enqueue(id, false);
        String url = notices.content(id).evidence().url();
        var parts = List.of(new Part("공고문 1쪽", "신청자는 무주택자이어야 합니다.\n공통 제출서류: 주민등록등본\n신청 기간: 2020.11.01 ~ 2020.11.30\n신청 시 제출서류를 함께 제출해야 합니다."));
        extraction.finish(extraction.claim(id), List.of(new Source("공식 상세 본문", url, "b".repeat(64), "EXTRACTED", "", ExcerptClassifier.extract(parts, url), parts)), "READY", "완료");
        var original = notices.content(id); int revision = notices.entity(id).revision;
        assertFalse(review.review(UUID.fromString(draft.id())));
        assertEquals(original, notices.content(id));
        assertEquals(revision, notices.entity(id).revision);
    }

    @Test void individualResultReachesPublicApiThroughAutomaticReviewWithoutCalendarDate() {
        UUID id = imported(); publication.basic(id); entities.flush();
        var draft = extraction.enqueue(id, false);
        String url = notices.content(id).evidence().url();
        LocalDate first = LocalDate.now(ApplicationPeriod.SEOUL), last = first.plusDays(30);
        var parts = List.of(new Part("공고문 1쪽", "1. 신청 자격\n신청자는 무주택자이어야 합니다.\n2. 제출서류\n공통 제출서류: 가족관계증명서\n3. 모집 일정\n신청 기간: "
            + first + " ~ " + last + " 18:00"), new Part("공고문 4쪽", "■ 결과 발표: 자격검증 및 소명완료 후 개별통보 (2027.1월예정)"));
        extraction.finish(extraction.claim(id), List.of(new Source("공식 상세 본문", url, "b".repeat(64), "EXTRACTED", "", ExcerptClassifier.extract(parts, url), parts)), "READY", "완료");
        assertTrue(review.review(UUID.fromString(draft.id())), () -> notices.entity(id).reason);
        var result = notices.notice(id).schedules().stream().filter(s -> s.id().equals("auto-individual-result")).findFirst().orElseThrow();
        assertEquals("", result.date());
        assertTrue(result.timingDescription().contains("2027.1월예정"));
        assertEquals(url, result.evidence().url());
        assertNull(ApplicationPeriod.day(result.date()));
        int version = notices.notice(id).version();
        assertFalse(review.review(UUID.fromString(draft.id())));
        assertEquals(version, notices.notice(id).version());
    }

    @Test void applicationTimeDocumentDeadlineReachesPublicSchedulesThroughAutomaticReview() {
        UUID id = imported(); publication.basic(id); entities.flush();
        var draft = extraction.enqueue(id, false);
        String url = notices.content(id).evidence().url();
        LocalDate first = LocalDate.now(ApplicationPeriod.SEOUL), last = first.plusDays(30);
        var parts = List.of(new Part("공고문 3쪽", "1. 신청 자격\n신청자는 무주택자이어야 합니다.\n2. 제출서류\n공통 제출서류: 가족관계증명서\n3. 모집 일정\n신청 기간: "
            + first + " ~ " + last + " 18:00"),
            new Part("공고문 4쪽", "■ 신청 시 제출 서류 : 신청서 접수 시 스캔하여 첨부"));
        extraction.finish(extraction.claim(id), List.of(new Source("공식 상세 본문", url, "b".repeat(64), "EXTRACTED", "", ExcerptClassifier.extract(parts, url), parts)), "READY", "완료");
        assertTrue(review.review(UUID.fromString(draft.id())), () -> notices.entity(id).reason);
        var doc = notices.notice(id).schedules().stream().filter(s -> s.id().equals("auto-documents-end")).findFirst().orElseThrow();
        assertEquals(last + "T18:00", doc.date());
        assertEquals(url, doc.evidence().url());
        assertTrue(doc.evidence().label().contains("4쪽"));
        int version = notices.notice(id).version();
        assertFalse(review.review(UUID.fromString(draft.id())));
        assertEquals(version, notices.notice(id).version());
    }

    @Test void newlyCollectedOfficialTextPublishesQualificationAndCoreDocumentSummaries() {
        UUID id = imported(); publication.basic(id); entities.flush();
        assertNull(notices.notice(id).qualificationSummary());
        assertNull(notices.notice(id).documentSummary());
        var draft = extraction.enqueue(id, false);
        String url = notices.content(id).evidence().url();
        LocalDate first = LocalDate.now(ApplicationPeriod.SEOUL), last = first.plusDays(30);
        String order = "동일순위에서 남원시 거주자를 우선합니다.";
        var parts = List.of(new Part("공고문 1쪽", """
            새마을 국민임대주택 예비입주자 모집공고
            3. 임대조건
            임대보증금 및 월 임대료
            4. 신청자격
            공고일 현재 성년자인 무주택세대구성원 중 공고의 소득·자산 기준을 충족하는 가구가 대상입니다.
            5. 선정기준
            """ + order + "\n6. 모집일정\n신청 기간: " + first + " ~ " + last + " 18:00"),
            new Part("공고문 3쪽", """
            8. 제출서류
            주민등록표등본: 배우자와 세대분리한 경우 배우자 등본 추가 제출
            가족관계증명서: 등본에서 배우자 관계를 확인할 수 있으면 생략 가능합니다.
            9. 계약안내
            계약 장소는 공식 원문에서 확인합니다.
            """));
        extraction.finish(extraction.claim(id), List.of(new Source("공식 상세 본문", url, "b".repeat(64), "EXTRACTED", "",
            ExcerptClassifier.extract(parts, url), parts)), "READY", "완료");
        assertTrue(review.review(UUID.fromString(draft.id())), () -> notices.entity(id).reason);
        var notice = notices.notice(id);
        assertEquals("AVAILABLE", notice.guideState());
        assertTrue(notice.qualificationSummary().eligibility().contains("무주택세대구성원"));
        assertEquals(order, notice.qualificationSummary().selection(), () -> notice.questions().stream().map(q -> q.reason()).toList().toString());
        assertEquals(url, notice.qualificationSummary().evidence().url());
        assertTrue(notice.documentSummary().common().items().isEmpty(), "conditional evidence must not become mandatory");
        assertTrue(notice.documentSummary().conditional().items().stream().anyMatch(item -> item.title().equals("가족관계증명서")));
        assertTrue(notice.documentSummary().conditional().items().stream().allMatch(item -> item.evidence().url().equals(url)));
        assertEquals(0, notice.documentSummary().reviewRequiredCount());
        var content = notices.content(id);
        assertTrue(content.questions().stream().anyMatch(q -> q.reason().contains("생략 가능합니다")));
        assertFalse(review.review(UUID.fromString(draft.id())));
        assertEquals(notice.version(), notices.notice(id).version());
        assertEquals(content, notices.content(id));
    }
}
