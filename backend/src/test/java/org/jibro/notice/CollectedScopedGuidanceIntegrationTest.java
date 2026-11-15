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
class CollectedScopedGuidanceIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired AutomaticPublicationService publication;
    @Autowired AutomaticReviewService review;
    @Autowired AutomaticReviewRepository reviews;
    @Autowired ExtractionStore extraction;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;

    private record Imported(UUID notice, UUID extraction) {}
    private Imported imported(String extra) {
        String url="https://apply.lh.or.kr/detail.do?id="+UUID.randomUUID();
        UUID id=notices.importSource(new LhNoticeClient.Item("LH:"+UUID.nameUUIDFromBytes(url.getBytes(StandardCharsets.UTF_8)),
            "신규 수집 공고", "서울", "청년 매입임대", url, LocalDate.now().minusDays(1),"a".repeat(64)));
        assertTrue(publication.basic(id));
        entities.flush();
        var queued=extraction.enqueue(id,true);
        var job=extraction.claim();
        String text="신청자는 무주택자이어야 합니다.\n신청자는 만 19세 이상 39세 이하이어야 합니다.\n"
            +"신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.\n신청자의 월평균 소득은 4,000,000원 이하이어야 합니다.\n"
            +"공통 제출서류: 주민등록등본, 가족관계증명서\n공통 제출서류: 주민등록등본\n"
            +"신청 기간: 2026-11-01 ~ 2026-12-31\n결과 발표: 2027-01-10\n결과 발표: 2027-01-12\n"+extra;
        var parts=List.of(new Part("공고문 1쪽",text));
        extraction.finish(job,List.of(new Source("공식 상세 본문",url,"b".repeat(64),"EXTRACTED","완료",
            ExcerptClassifier.extract(parts,url),parts)),"READY","발췌 완료");
        return new Imported(id,UUID.fromString(queued.id()));
    }

    @Test void newCollectedNoticeAutomaticallyPublishesIndependentGuidanceAndKeepsOnlyConflictsUnknown() {
        var item=imported("");
        assertTrue(reviews.pendingGuidance().contains(item.extraction()));
        assertTrue(review.review(item.extraction()));
        var notice=notices.notice(item.notice());
        var content=notices.content(item.notice());
        assertEquals("AVAILABLE",notice.guideState());
        assertTrue(notice.canStart());
        assertEquals(3,content.reviewIssues().size());
        assertTrue(content.questions().stream().filter(q->q.id().equals("auto-income")).allMatch(q->q.reviewRequired()));
        assertTrue(content.questions().stream().filter(q->q.id().equals("auto-housing")||q.id().equals("auto-age")).noneMatch(q->q.reviewRequired()));
        var normal=content.documents().stream().filter(d->d.title().equals("주민등록등본")).findFirst().orElseThrow();
        var affected=content.documents().stream().filter(d->d.title().equals("가족관계증명서")).findFirst().orElseThrow();
        assertFalse(GuidanceIssues.documentRequiresReview(content.reviewIssues(),normal.id(),normal.conditionQuestionId()));
        assertTrue(GuidanceIssues.documentRequiresReview(content.reviewIssues(),affected.id(),affected.conditionQuestionId()));
        assertEquals(List.of("주민등록등본"), notice.documentSummary().common().items().stream().map(NoticeDocumentSummary.Item::title).toList());
        assertEquals(1, notice.documentSummary().reviewRequiredCount());
        assertNotNull(notice.qualificationSummary());
        assertEquals(2,content.schedules().size(),"uncertain result date must not enter calendars or notifications");
        assertEquals("PARTIAL",db.queryForObject("SELECT decision FROM automatic_notice_review WHERE notice_id=? AND phase='GUIDANCE'",String.class,item.notice()));
        assertEquals(3,db.queryForObject("SELECT jsonb_array_length(review_issues) FROM automatic_notice_review WHERE notice_id=? AND phase='GUIDANCE'",Integer.class,item.notice()));
        int version=notice.version();
        assertFalse(review.review(item.extraction()));
        assertEquals(version,notices.notice(item.notice()).version());
    }

    @Test void unrecognizedExceptionStillBlocksTheWholeCandidateInsteadOfPublishingKnownSubset() {
        var item=imported("다만 별도 예외와 가구 범위는 확인되지 않은 표를 적용합니다.");
        assertFalse(review.review(item.extraction()));
        assertEquals("REVIEWING",notices.notice(item.notice()).guideState());
        assertNull(notices.notice(item.notice()).qualificationSummary());
        assertNull(notices.notice(item.notice()).documentSummary());
        assertTrue(notices.content(item.notice()).reviewIssues().isEmpty());
        assertEquals("HOLD",db.queryForObject("SELECT decision FROM automatic_notice_review WHERE notice_id=? AND phase='GUIDANCE'",String.class,item.notice()));
    }

    @Test void previousPolicyHoldIsReassessedButCurrentPolicyIsIdempotent() {
        var item=imported("");
        var snapshot=reviews.snapshot(item.extraction());
        reviews.record(notices.entity(item.notice()),snapshot.draft().revision(),item.extraction(),snapshot.fingerprint(),"GUIDANCE",false,"old policy hold");
        db.update("UPDATE automatic_notice_review SET policy='official-rules-v4' WHERE notice_id=? AND phase='GUIDANCE'",item.notice());
        assertTrue(reviews.pendingGuidance().contains(item.extraction()));
        assertTrue(review.review(item.extraction()));
        assertFalse(reviews.pendingGuidance().contains(item.extraction()));
    }
}
