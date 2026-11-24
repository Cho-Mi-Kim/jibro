package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.jibro.auth.AuthService;
import org.jibro.collection.LhNoticeClient;
import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NoticeReviewTimesIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    AuthService auth;
    @Autowired
    EntityManager entities;
    String actor;

    @BeforeEach
    void setup() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("시각 검증", email, "test-password-123");
        actor = auth.login(email, "test-password-123").user().id();
    }

    @Test
    void firstCollectionDoesNotInventHumanReviewTimes() {
        UUID id = notices.importSource(item(UUID.randomUUID().toString(), "original"));
        var view = persisted(id);
        assertNotNull(view.collectedAt());
        assertNull(view.basicCheckedAt());
        assertNull(view.guideCheckedAt());
        assertNotNull(view.evidence().checkedAt());
        assertThrows(ApiException.class, () -> notices.notice(id));
    }

    @Test
    void basicReviewAndGuidanceReviewAreSeparateFromCollectionAndSourceEvidence() {
        UUID id = notices.insert(UUID.randomUUID().toString(), "검수 시각 공고", "LH", "서울", "청년 매입임대",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), SampleData.content());
        var basic = notices.review(id, actor, notices.entity(id).revision, "PASS", "HOLD", "기본 정보만 대조 완료");
        assertNotNull(basic.notice().basicCheckedAt());
        assertNull(basic.notice().guideCheckedAt());
        assertNull(basic.notice().collectedAt());
        var guide = notices.review(id, actor, basic.revision(), "PASS", "PASS", "질문·서류·일정 대조 완료");
        var stored = persisted(id);
        assertNotNull(stored.basicCheckedAt());
        assertNotNull(stored.guideCheckedAt());
        assertNull(stored.collectedAt());
        assertEquals("AVAILABLE", guide.notice().guideState());

        var held = notices.review(id, actor, notices.entity(id).revision, "PASS", "UNSUPPORTED", "안내 미지원으로 재검수");
        assertNotNull(held.notice().basicCheckedAt());
        assertNull(held.notice().guideCheckedAt());
        assertNull(held.notice().collectedAt());
    }

    @Test
    void unchangedCollectionUpdatesOnlyCollectionTimeAndPreservesVerifiedVersion() {
        var source = item(UUID.randomUUID().toString(), "original");
        UUID id = reviewedSource(source);
        var n = notices.entity(id);
        Instant basic = Instant.parse("2026-01-01T01:00:00Z");
        Instant guide = Instant.parse("2026-01-02T02:00:00Z");
        Instant previousCollection = Instant.parse("2026-01-03T03:00:00Z");
        n.basicCheckedAt = basic;
        n.guideCheckedAt = guide;
        n.collectedAt = previousCollection;
        int version = n.version;
        int revision = n.revision;
        persisted(id);

        notices.importSource(source);
        var current = persisted(id);
        assertEquals(basic, current.basicCheckedAt());
        assertEquals(guide, current.guideCheckedAt());
        assertTrue(current.collectedAt().isAfter(previousCollection));
        assertEquals(version, current.version());
        assertEquals(revision, notices.entity(id).revision);
        assertEquals("AVAILABLE", current.guideState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "content", "attachment"})
    void materialChangesClearBothCurrentReviewTimesWithoutInventingCollection(String change) {
        var source = item(UUID.randomUUID().toString(), "original");
        UUID id = reviewedSource(source);
        Instant collected = Instant.parse("2026-01-03T03:00:00Z");
        notices.entity(id).collectedAt = collected;
        int version = notices.entity(id).version;
        int revision = notices.entity(id).revision;
        switch (change) {
            case "source" -> notices.importSource(item(source.key(), "changed"));
            case "content" -> notices.updateContent(id, revision, SampleData.content());
            case "attachment" -> notices.attachmentChanged(id, revision, "같은 주소의 첨부 내용 변경");
            default -> throw new AssertionError();
        }
        var changed = persisted(id);
        assertNull(changed.basicCheckedAt());
        assertNull(changed.guideCheckedAt());
        assertEquals(version + 1, changed.version());
        assertEquals("CHANGE_REVIEW", changed.guideState());
        if (change.equals("source")) assertTrue(changed.collectedAt().isAfter(collected));
        else assertEquals(collected, changed.collectedAt());
        assertThrows(ApiException.class, () -> notices.notice(id));
    }

    private UUID reviewedSource(LhNoticeClient.Item source) {
        UUID id = notices.importSource(source);
        var draft = notices.draft(id);
        notices.updateDraft(id, notices.entity(id).revision, new NoticeService.Draft(draft.title(), draft.agency(),
                draft.region(), draft.type(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(30),
                draft.publishedDate(), SampleData.content()));
        notices.review(id, actor, notices.entity(id).revision, "PASS", "PASS", "원문 대조 완료");
        return id;
    }

    private LhNoticeClient.Item item(String key, String fingerprint) {
        return new LhNoticeClient.Item(key, "시각 검증 공고", "서울", "청년 매입임대",
                "https://apply.lh.or.kr/example", LocalDate.now(), fingerprint);
    }

    private HousingModels.Notice persisted(UUID id) {
        entities.flush();
        entities.clear();
        return notices.view(notices.entity(id), true);
    }
}
