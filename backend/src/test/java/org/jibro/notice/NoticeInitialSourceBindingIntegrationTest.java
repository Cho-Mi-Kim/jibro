package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.jibro.collection.LhNoticeClient;
import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.jibro.notice.HousingModels.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NoticeInitialSourceBindingIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired NoticeAuditRepository audit;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;
    @Autowired ObjectMapper json;
    UUID id;
    LhNoticeClient.Item source;
    LocalDate published = LocalDate.of(2026, 2, 24);
    LocalDate closing = LocalDate.of(2026, 12, 31);

    @BeforeEach void setup() {
        String url = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId="
            + Math.abs(UUID.randomUUID().getMostSignificantBits()) + "&ccrCnntSysDsCd=03&uppAisTpCd=13&aisTpCd=17";
        source = new LhNoticeClient.Item(LhNoticeClient.sourceKey(url), "청년 전세임대 수시모집", "서울특별시 외", "전세임대",
            url, published, "a".repeat(64));
        var evidence = new Evidence("공식 모집공고", url, LocalDate.now().toString());
        // Legacy approved notices may have only the closing schedule, with dates in notice metadata.
        var content = new Content("원문 대조 안내", evidence, List.of(evidence),
            List.of(new Question("housing", "무주택 기준을 확인했나요?", "공식 원문의 세대 범위를 확인합니다.", evidence)),
            List.of(new DocumentTemplate("consent", "개인정보 동의서", null, "공고 첨부 양식", "LH", url, evidence)),
            List.of(new ScheduleTemplate("closing", "신청 접수 마감 (18:00, 조기 중단 가능)", closing.toString(), evidence)));
        id = notices.insert(source.key(), source.title(), "LH", "전국", "청년 전세임대", published, closing, published, content);
        var n = notices.entity(id);
        n.basicReview = "PASS";
        n.guideReview = "PASS";
        n.automaticReviewBlocked = true;
        n.version = 3;
        n.revision = 3;
        n.reason = "기존 원문 대조 원고";
        persist();
    }

    @Test void firstOfficialBindingPreservesLegacyApprovalCuratedMetadataAndUnknownReviewTimes() {
        var before = notices.entity(id);
        String content = before.content;
        assertEquals(0, auditCount());
        assertNull(before.sourceFingerprint);
        assertNull(before.collectedAt);
        assertEquals(id, notices.importSource(source));
        var n = persist();
        assertEquals(source.fingerprint(), n.sourceFingerprint);
        assertNotNull(n.collectedAt);
        assertEquals("전국", n.region);
        assertEquals("청년 전세임대", n.supportType);
        assertEquals(content, n.content);
        assertEquals(3, n.version);
        assertEquals(3, n.revision);
        assertEquals("PASS", n.basicReview);
        assertEquals("PASS", n.guideReview);
        assertEquals("기존 원문 대조 원고", n.reason);
        assertFalse(n.changeReview);
        assertTrue(n.automaticReviewBlocked);
        assertNull(n.basicCheckedAt);
        assertNull(n.guideCheckedAt);
        assertEquals(0, auditCount());
        assertEquals("AVAILABLE", notices.notice(id).guideState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASS", "UNSUPPORTED"})
    void currentExplicitReviewIsPreservedWithoutFabricatingAnotherDecision(String guide) {
        UUID reviewer = reviewer();
        notices.review(id, reviewer.toString(), notices.entity(id).revision, "PASS", guide, "명시적 원문 검토");
        persist();
        var before = notices.notice(id);
        int version = notices.entity(id).version;
        int revision = notices.entity(id).revision;
        notices.importSource(source);
        var n = persist();
        assertEquals(version, n.version);
        assertEquals(revision, n.revision);
        assertEquals(guide, n.guideReview);
        assertEquals(before.basicCheckedAt(), n.basicCheckedAt);
        assertEquals(before.guideCheckedAt(), n.guideCheckedAt);
        assertEquals("전국", n.region);
        assertEquals(1, auditCount());
        assertEquals(guide.equals("PASS") ? "AVAILABLE" : "UNSUPPORTED", notices.notice(id).guideState());
    }

    @Test void laterChangesToAnEstablishedFingerprintStillRequireReview() {
        notices.importSource(source);
        persist();
        notices.importSource(new LhNoticeClient.Item(source.key(), source.title(), source.region(), source.type(), source.url(),
            source.published(), "b".repeat(64)));
        assertHeld();
        assertEquals("b".repeat(64), notices.entity(id).sourceFingerprint);
        assertEquals(source.region(), notices.entity(id).region);
    }

    @Test void repeatOfFirstBoundFingerprintDoesNotInvalidateThePreservedContent() {
        notices.importSource(source);
        Instant previous = Instant.parse("2026-01-01T00:00:00Z");
        notices.entity(id).collectedAt = previous;
        persist();
        notices.importSource(source);
        var n = persist();
        assertTrue(n.collectedAt.isAfter(previous));
        assertEquals("PASS", n.guideReview);
        assertEquals("전국", n.region);
        assertEquals(3, n.revision);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "published", "unknownPublished", "differentEvidence", "wrongSourceKey", "collected",
        "blocked", "changeReview", "basicHold", "guideHold", "cancelled", "incomplete", "invalidContent", "missingPeriod", "conflictingBoundary"})
    void uncertainOrNonInitialCasesKeepTheExistingReviewGate(String scenario) {
        var n = notices.entity(id);
        switch (scenario) {
            case "title" -> n.title = "다른 공고";
            case "published" -> n.publishedDate = published.minusDays(1);
            case "unknownPublished" -> n.publishedDate = null;
            case "differentEvidence" -> {
                var c = notices.content(id);
                n.content = json.writeValueAsString(new Content(c.summary(),
                    new Evidence("다른 공식 원문", source.url() + "&other=1", LocalDate.now().toString()),
                    c.attachments(), c.questions(), c.documents(), c.schedules()));
            }
            case "wrongSourceKey" -> {
                n.sourceKey = "LH:" + UUID.randomUUID();
                source = new LhNoticeClient.Item(n.sourceKey, source.title(), source.region(), source.type(), source.url(),
                    source.published(), source.fingerprint());
            }
            case "collected" -> n.collectedAt = Instant.parse("2026-01-01T00:00:00Z");
            case "blocked" -> n.automaticReviewBlocked = false;
            case "changeReview" -> n.changeReview = true;
            case "basicHold" -> n.basicReview = "HOLD";
            case "guideHold" -> n.guideReview = "HOLD";
            case "cancelled" -> n.lifecycleState = "CANCELLED";
            case "missingPeriod" -> n.startDate = null;
            case "conflictingBoundary" -> n.endDate = closing.minusDays(1);
            case "invalidContent" -> n.content = "{}";
            case "incomplete" -> {
                var c = notices.content(id);
                n.content = json.writeValueAsString(new Content(c.summary(), c.evidence(), c.attachments(), List.of(), c.documents(), c.schedules()));
            }
            default -> throw new AssertionError();
        }
        persist();
        notices.importSource(source);
        assertHeld();
    }

    @ParameterizedTest
    @ValueSource(strings = {"earlierRevision", "heldDecision", "newerDecision"})
    void existingHumanAuditMustAgreeWithTheCurrentApproval(String scenario) {
        UUID reviewer = reviewer();
        audit.recordReview(id, reviewer, scenario.equals("earlierRevision") ? 2 : 3, "PASS",
            scenario.equals("heldDecision") ? "HOLD" : "PASS", "이전 검토 기록");
        if (scenario.equals("newerDecision")) audit.recordReview(id, reviewer, 3, "PASS", "HOLD", "후속 보류 결정");
        notices.importSource(source);
        assertHeld();
        assertEquals(scenario.equals("newerDecision") ? 2 : 1, auditCount());
    }

    private void assertHeld() {
        var n = persist();
        assertEquals("HOLD", n.basicReview);
        assertEquals("HOLD", n.guideReview);
        assertTrue(n.changeReview);
        assertEquals(4, n.version);
        assertEquals(4, n.revision);
        assertNull(n.basicCheckedAt);
        assertNull(n.guideCheckedAt);
        assertThrows(ApiException.class, () -> notices.notice(id));
    }
    private UUID reviewer() {
        UUID reviewer = UUID.randomUUID();
        db.update("INSERT INTO app_user(id,name,email) VALUES(?,?,?)", reviewer, "연결 검증", reviewer + "@example.invalid");
        return reviewer;
    }
    private int auditCount() {
        return db.queryForObject("SELECT count(*) FROM review_audit WHERE notice_id=?", Integer.class, id);
    }
    private NoticeEntity persist() {
        entities.flush();
        entities.clear();
        return notices.entity(id);
    }
}
