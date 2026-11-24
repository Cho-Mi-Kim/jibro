package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
class GuideRefreshIntegrationTest {
    private static final Instant REVIEWED = Instant.parse("2026-11-21T01:00:00Z");
    private static final Instant NEXT_MORNING = Instant.parse("2026-11-21T21:00:00Z");
    private static final String OLD_TEXT = "주민등록등본은 정부24 또는 행정복지센터에서 발급해요.";
    private static final String NEW_TEXT = "주민등록등본은 정부24에서 발급하며 필요한 표시 항목을 확인해요.";

    @Autowired
    GuideRefreshRepository store;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;

    private GuideCatalog catalog;
    private GuideSourceClient sources;
    private GuideContentReviewService review;
    private GuideRefreshService service;
    private Guide guide;

    @BeforeEach
    void setUp() {
        catalog = mock(GuideCatalog.class);
        sources = mock(GuideSourceClient.class);
        review = mock(GuideContentReviewService.class);
        service = new GuideRefreshService(catalog, store, sources, review, json);
        guide = guide("등본", "https://www.gov.kr/guide-refresh-test/" + UUID.randomUUID());
        when(catalog.items()).thenReturn(List.of(guide));
        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, OLD_TEXT));
    }

    @Test
    void initializationRequiresTheExactReviewedCatalogAndTheReviewDate() {
        assertThrows(IllegalArgumentException.class,
            () -> service.initializeReviewedBaseline("a-different-catalog", REVIEWED));
        assertThrows(IllegalArgumentException.class,
            () -> service.initializeReviewedBaseline(service.catalogFingerprint(), NEXT_MORNING));

        assertNull(store.entry(guide.id()));
        assertNull(store.source(guide.evidence().url()));
        verifyNoInteractions(review, sources);

        var result = bootstrap();
        assertEquals(1, result.confirmed());
        var saved = store.entry(guide.id());
        assertEquals(OLD_TEXT, saved.guide().body());
        assertEquals(REVIEWED, saved.verifiedAt());
        assertEquals("REVIEWED_CATALOG_BASELINE", saved.outcome());
        assertEquals(1, saved.verifiedSources().size());
        assertEquals(1, historyCount(guide));
        verifyNoInteractions(review);
    }

    @Test
    void ordinaryFirstRunRequiresContentReviewAndCannotPretendFetchIsApproval() {
        when(review.review(any(), anyList(), any())).thenReturn(decision(GuideContentReviewService.Status.REVIEW_REQUIRED, null));

        var result = service.refreshDue(NEXT_MORNING);

        assertEquals(1, result.pending());
        var saved = store.entry(guide.id());
        assertEquals("RECHECK", saved.status());
        assertTrue(saved.verifiedSources().isEmpty());
        assertEquals(OLD_TEXT, saved.guide().body());
        assertEquals("2026-11-21", current(guide).freshness().checkedAt());
        assertEquals("2026-11-22", current(guide).freshness().lastAttemptAt());
        assertEquals(0, historyCount(guide));
        verify(review).review(argThat(item -> item.id().equals(guide.id()) && item.body().equals(OLD_TEXT)
            && item.evidence().checkedAt().equals("2026-11-22")), argThat(changes -> changes.size() == 1
            && changes.get(0).previousText().isEmpty()), eq(NEXT_MORNING));
    }

    @Test
    void dailySixAmScheduleSurvivesRestartAndUnchangedSourceUsesNoAi() {
        bootstrap();
        clearInvocations(sources);
        assertEquals(NEXT_MORNING, store.source(guide.evidence().url()).nextCheck());
        assertEquals(0, service.refreshDue(NEXT_MORNING.minusSeconds(1)).sourcesChecked());
        verifyNoInteractions(sources, review);

        var restarted = new GuideRefreshService(catalog, store, sources, review, json);
        Instant caughtUp = NEXT_MORNING.plusSeconds(120);
        var result = restarted.refreshDue(caughtUp);
        assertEquals(1, result.sourcesChecked());
        assertEquals(1, result.confirmed());
        assertEquals(0, result.updated());
        assertEquals(caughtUp, store.entry(guide.id()).verifiedAt());
        assertEquals(NEXT_MORNING.plusSeconds(86400), store.source(guide.evidence().url()).nextCheck());
        assertEquals("2026-11-22", current(guide).evidence().checkedAt());
        assertEquals("SOURCE_UNCHANGED", store.entry(guide.id()).outcome());
        assertEquals(0, restarted.refreshDue(caughtUp.plusSeconds(60)).sourcesChecked());
        assertEquals(2, historyCount(guide));
        verify(sources).fetch(guide.evidence().url());
        verifyNoInteractions(review);
    }

    @Test
    void unavailableSourcePreservesVerifiedBodyAndDateThenRecoversWithoutAiWhenUnchanged() {
        bootstrap();
        var previous = store.entry(guide.id());
        when(sources.fetch(guide.evidence().url())).thenReturn(unreachable());

        var failure = service.refreshDue(NEXT_MORNING);
        assertEquals(1, failure.sourceFailures());
        assertEquals(1, failure.pending());
        var pending = store.entry(guide.id());
        assertEquals("UNREACHABLE", pending.status());
        assertEquals(previous.guide(), pending.guide());
        assertEquals(previous.verifiedSources(), pending.verifiedSources());
        assertEquals(previous.verifiedAt(), pending.verifiedAt());
        assertEquals("2026-11-21", current(guide).evidence().checkedAt());
        assertEquals("2026-11-22", current(guide).freshness().lastAttemptAt());
        assertEquals(REVIEWED, store.source(guide.evidence().url()).lastSuccess());
        assertEquals(NEXT_MORNING.plusSeconds(1800), store.source(guide.evidence().url()).nextCheck());

        clearInvocations(sources);
        assertEquals(0, service.refreshDue(NEXT_MORNING.plusSeconds(60)).sourcesChecked());
        verifyNoInteractions(sources, review);
        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, OLD_TEXT));
        var recovered = service.refreshDue(NEXT_MORNING.plusSeconds(1800));
        assertEquals(1, recovered.confirmed());
        assertEquals("CURRENT", store.entry(guide.id()).status());
        assertEquals(0, store.source(guide.evidence().url()).failures());
        assertEquals(OLD_TEXT, current(guide).body());
        assertEquals("2026-11-22", current(guide).evidence().checkedAt());
        verifyNoInteractions(review);
    }

    @Test
    void transportRecoveryDoesNotApproveChangedContentWhoseReviewWasStillPending() {
        bootstrap();
        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, NEW_TEXT));
        when(review.review(any(), anyList(), any())).thenReturn(decision(GuideContentReviewService.Status.DEFERRED, null));
        service.refreshDue(NEXT_MORNING);
        assertEquals("RECHECK", store.entry(guide.id()).status());

        Instant failedAt = NEXT_MORNING.plusSeconds(86400);
        when(sources.fetch(guide.evidence().url())).thenReturn(unreachable());
        service.refreshDue(failedAt);
        assertEquals("UNREACHABLE", store.entry(guide.id()).status());
        assertEquals(REVIEWED, store.entry(guide.id()).verifiedAt());

        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, NEW_TEXT));
        service.refreshDue(failedAt.plusSeconds(1800));
        assertEquals("RECHECK", store.entry(guide.id()).status());
        assertEquals(OLD_TEXT, current(guide).body());
        assertEquals("2026-11-21", current(guide).evidence().checkedAt());

        when(review.review(any(), anyList(), any())).thenReturn(decision(GuideContentReviewService.Status.UPDATED, withBody(guide, NEW_TEXT)));
        assertEquals(1, service.refreshDue(failedAt.plusSeconds(1865)).updated());
        assertEquals(NEW_TEXT, current(guide).body());
        verify(review, times(3)).review(any(), anyList(), any());
    }

    @Test
    void oneChangedSourceOnlyRewritesDependentFaqsAndIsFetchedOnceForSharedFaqs() {
        Guide shared = guide("같은 출처", guide.evidence().url());
        Guide unaffected = guide("별도 출처", "https://www.lh.or.kr/guide-refresh-test/" + UUID.randomUUID());
        when(catalog.items()).thenReturn(List.of(guide, shared, unaffected));
        when(sources.fetch(unaffected.evidence().url())).thenReturn(available(unaffected, OLD_TEXT));
        bootstrap();
        clearInvocations(sources);
        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, NEW_TEXT));
        when(review.review(any(), anyList(), any())).thenAnswer(invocation ->
            decision(GuideContentReviewService.Status.UPDATED, withBody(invocation.getArgument(0), NEW_TEXT)));

        var result = service.refreshDue(NEXT_MORNING);

        assertEquals(2, result.sourcesChecked());
        assertEquals(2, result.updated());
        assertEquals(1, result.confirmed());
        assertEquals(NEW_TEXT, current(guide).body());
        assertEquals(NEW_TEXT, current(shared).body());
        assertEquals(OLD_TEXT, current(unaffected).body());
        verify(sources).fetch(guide.evidence().url());
        verify(sources).fetch(unaffected.evidence().url());
        verify(review, times(2)).review(argThat(item -> !item.id().equals(unaffected.id())),
            argThat(changes -> changes.size() == 1 && changes.get(0).previousText().equals(OLD_TEXT)
                && changes.get(0).currentText().equals(NEW_TEXT)), eq(NEXT_MORNING));
        verifyNoMoreInteractions(review);
    }

    @Test
    void quotaDeferredReviewRetriesWithoutRefetchAndDoesNotAdvanceSourceCheckDateToReviewDate() {
        bootstrap();
        Instant checked = Instant.parse("2026-11-22T14:59:30Z");
        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, NEW_TEXT));
        when(review.review(any(), anyList(), any()))
            .thenReturn(decision(GuideContentReviewService.Status.DEFERRED, null))
            .thenReturn(decision(GuideContentReviewService.Status.UPDATED, withBody(guide, NEW_TEXT)));
        service.refreshDue(checked);
        assertEquals(checked.plusSeconds(65), store.entry(guide.id()).nextReview());
        clearInvocations(sources);

        assertEquals(1, service.refreshDue(checked.plusSeconds(64)).pending());
        verify(review).review(any(), anyList(), any());
        var retried = service.refreshDue(checked.plusSeconds(65));
        assertEquals(1, retried.updated());
        assertEquals(checked, store.entry(guide.id()).verifiedAt());
        assertEquals(checked.plusSeconds(65), store.entry(guide.id()).attemptedAt());
        assertEquals("2026-11-22", current(guide).evidence().checkedAt());
        assertEquals("2026-11-22", current(guide).freshness().checkedAt());
        assertEquals("2026-11-23", current(guide).freshness().lastAttemptAt());
        verifyNoInteractions(sources);
        verify(review, times(2)).review(any(), anyList(), any());
    }

    @Test
    void reviewRequiredFingerprintIsNotRepeatedUntilOfficialContentChangesAgain() {
        bootstrap();
        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, NEW_TEXT));
        when(review.review(any(), anyList(), any())).thenReturn(decision(GuideContentReviewService.Status.REVIEW_REQUIRED, null));
        service.refreshDue(NEXT_MORNING);
        String pendingFingerprint = store.entry(guide.id()).reviewFingerprint();
        service.refreshDue(NEXT_MORNING.plusSeconds(60));
        service.refreshDue(NEXT_MORNING.plusSeconds(86400));
        assertEquals(pendingFingerprint, store.entry(guide.id()).reviewFingerprint());
        assertEquals(OLD_TEXT, current(guide).body());
        assertEquals("2026-11-21", current(guide).evidence().checkedAt());
        verify(review).review(any(), anyList(), any());

        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, NEW_TEXT + " 발급 수수료도 확인해요."));
        service.refreshDue(NEXT_MORNING.plusSeconds(172800));
        assertNotEquals(pendingFingerprint, store.entry(guide.id()).reviewFingerprint());
        verify(review, times(2)).review(any(), anyList(), any());
    }

    @Test
    void temporarySourceFailureDoesNotForgetAReviewRequiredDecisionForTheSameContent() {
        bootstrap();
        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, NEW_TEXT));
        when(review.review(any(), anyList(), any())).thenReturn(decision(GuideContentReviewService.Status.REVIEW_REQUIRED, null));
        service.refreshDue(NEXT_MORNING);
        String pendingFingerprint = store.entry(guide.id()).reviewFingerprint();

        Instant nextDay = NEXT_MORNING.plusSeconds(86400);
        when(sources.fetch(guide.evidence().url())).thenReturn(unreachable());
        service.refreshDue(nextDay);
        assertEquals("UNREACHABLE", store.entry(guide.id()).status());

        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, NEW_TEXT));
        service.refreshDue(nextDay.plusSeconds(1800));

        assertEquals("RECHECK", store.entry(guide.id()).status());
        assertEquals("REVIEW_REQUIRED", store.entry(guide.id()).outcome());
        assertEquals(pendingFingerprint, store.entry(guide.id()).reviewFingerprint());
        assertEquals(OLD_TEXT, current(guide).body());
        assertEquals("2026-11-21", current(guide).evidence().checkedAt());
        verify(review).review(any(), anyList(), any());
    }

    @Test
    void unchangedContentAtNewOfficialAddressUpdatesItsLinkWithoutAi() {
        bootstrap();
        String moved = guide.evidence().url() + "/new-address";
        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, moved, OLD_TEXT));

        var result = service.refreshDue(NEXT_MORNING);

        assertEquals(1, result.confirmed());
        assertEquals(0, result.updated());
        assertEquals("SOURCE_RELOCATED", store.entry(guide.id()).outcome());
        assertEquals(moved, current(guide).evidence().url());
        assertEquals(OLD_TEXT, current(guide).body());
        assertEquals("2026-11-22", current(guide).evidence().checkedAt());
        assertEquals(2, historyCount(guide));
        verifyNoInteractions(review);
    }

    @Test
    void sourceVersionChangedDuringReviewCannotPublishStaleCandidate() {
        bootstrap();
        var before = store.entry(guide.id());
        var versions = before.verifiedSources();
        store.recordSource(guide.evidence().url(), available(guide, NEW_TEXT), NEXT_MORNING);

        assertFalse(store.publish(before, withBody(guide, "오래된 자료로 만든 안내"), versions,
            "stale-source", "UPDATED", "{}", NEXT_MORNING));

        assertEquals(before, store.entry(guide.id()));
        assertEquals(1, historyCount(guide));
    }

    @Test
    void olderGuideRevisionCannotOverwriteAnAlreadyPublishedReview() {
        bootstrap();
        var before = store.entry(guide.id());
        assertTrue(store.publish(before, withBody(guide, NEW_TEXT), before.verifiedSources(),
            "winning-review", "UPDATED", "{}", NEXT_MORNING));

        assertFalse(store.publish(before, guide, before.verifiedSources(),
            "stale-review", "UPDATED", "{}", NEXT_MORNING.plusSeconds(1)));
        assertFalse(store.mark(before, "RECHECK", "REVIEW_REQUIRED", "stale-review", null, NEXT_MORNING));

        assertEquals(NEW_TEXT, current(guide).body());
        assertEquals(before.revision() + 1, store.entry(guide.id()).revision());
        assertEquals(2, historyCount(guide));
    }

    @Test
    void sourceBecomingUnavailableDuringReviewCannotPublishCandidate() {
        bootstrap();
        var before = store.entry(guide.id());
        store.recordSource(guide.evidence().url(), unreachable(), NEXT_MORNING);

        assertFalse(store.publish(before, withBody(guide, NEW_TEXT), before.verifiedSources(),
            "unavailable-source", "UPDATED", "{}", NEXT_MORNING));

        assertEquals(before, store.entry(guide.id()));
        assertEquals(1, historyCount(guide));
    }

    @Test
    void unrelatedOfficialSourceCannotBeSubstitutedForTheGuidesVerifiedSources() {
        bootstrap();
        var before = store.entry(guide.id());
        Guide other = guide("다른 출처", "https://www.lh.or.kr/guide-refresh-test/" + UUID.randomUUID());
        store.synchronize(List.of(other), REVIEWED);
        store.recordSource(other.evidence().url(), available(other, NEW_TEXT), NEXT_MORNING);
        Map<String, String> unrelatedVersions = Map.of(other.evidence().url(), store.source(other.evidence().url()).version());

        assertFalse(store.publish(before, withBody(guide, NEW_TEXT), unrelatedVersions,
            "wrong-source", "UPDATED", "{}", NEXT_MORNING));

        assertEquals(before, store.entry(guide.id()));
        assertEquals(1, historyCount(guide));
    }

    @Test
    void packagedBaselineReplacementImmediatelyInvalidatesAnOldOverlayAndItsReview() {
        bootstrap();
        var previous = store.entry(guide.id());
        assertTrue(store.publish(previous, withBody(guide, NEW_TEXT), previous.verifiedSources(),
            "old-overlay", "UPDATED", "{}", REVIEWED.plusSeconds(10)));
        var overlay = store.entry(guide.id());
        Guide replacement = withBody(guide, "새로 검토한 기준 원고예요.");

        assertEquals(replacement, store.current(List.of(replacement)).get(0));
        store.synchronize(List.of(replacement), NEXT_MORNING);
        var replaced = store.entry(guide.id());

        assertEquals(replacement, replaced.guide());
        assertEquals(overlay.revision() + 1, replaced.revision());
        assertTrue(replaced.verifiedSources().isEmpty());
        assertNull(replaced.reviewFingerprint());
        assertEquals("CURRENT", replaced.status());
        assertFalse(store.publish(overlay, withBody(guide, "교체 이전 검수 결과"), overlay.verifiedSources(),
            "old-review", "UPDATED", "{}", NEXT_MORNING));
        assertEquals("PACKAGED_GUIDE_REPLACED", db.queryForObject(
            "SELECT reason FROM guide_entry_history WHERE guide_id=? AND revision=?", String.class, guide.id(), overlay.revision()));
        assertEquals(3, historyCount(guide));
    }

    @Test
    void reinitializingReviewedBaselineCannotRollBackPublishedContent() {
        bootstrap();
        var initial = store.entry(guide.id());
        assertTrue(store.publish(initial, withBody(guide, NEW_TEXT), initial.verifiedSources(),
            "approved-overlay", "UPDATED", "{}", REVIEWED.plusSeconds(10)));
        var published = store.entry(guide.id());

        var reinitialized = service.initializeReviewedBaseline(service.catalogFingerprint(), REVIEWED.plusSeconds(20));

        assertEquals(0, reinitialized.confirmed());
        assertEquals(published, store.entry(guide.id()));
        assertEquals(2, historyCount(guide));
        verifyNoInteractions(review);
    }

    @Test
    void leasePreventsOverlappingRunsAndOnlyTheCurrentOwnerCanReleaseAReclaimedLease() {
        UUID firstOwner = UUID.randomUUID();
        UUID nextOwner = UUID.randomUUID();
        assertTrue(store.claim(firstOwner, REVIEWED));
        assertFalse(store.claim(nextOwner, REVIEWED.plusSeconds(1)));
        assertTrue(service.refreshDue(REVIEWED.plusSeconds(2)).busy());
        verifyNoInteractions(sources, review);

        store.release(nextOwner, Map.of("status", "not-owner"), REVIEWED.plusSeconds(3));
        assertFalse(store.claim(nextOwner, REVIEWED.plusSeconds(4)));
        Instant reclaimed = REVIEWED.plusSeconds(3601);
        assertTrue(store.claim(nextOwner, reclaimed));
        store.release(firstOwner, Map.of("status", "expired-worker"), reclaimed.plusSeconds(1));
        assertFalse(store.claim(UUID.randomUUID(), reclaimed.plusSeconds(2)));

        store.release(nextOwner, Map.of("status", "completed"), reclaimed.plusSeconds(3));
        assertTrue(store.claim(firstOwner, reclaimed.plusSeconds(4)));
        store.release(firstOwner, Map.of("status", "completed"), reclaimed.plusSeconds(5));
    }

    @Test
    void unexpectedReviewFailureReleasesTheLeaseAndRetainsLastVerifiedContent() {
        bootstrap();
        var before = store.entry(guide.id());
        when(sources.fetch(guide.evidence().url())).thenReturn(available(guide, NEW_TEXT));
        when(review.review(any(), anyList(), any())).thenThrow(new IllegalStateException("test-review-interruption"));

        assertThrows(IllegalStateException.class, () -> service.refreshDue(NEXT_MORNING));

        assertEquals(before, store.entry(guide.id()));
        assertEquals(1, historyCount(guide));
        assertEquals("FAILED_OR_INTERRUPTED", db.queryForObject(
            "SELECT last_result->>'status' FROM guide_refresh_lease WHERE singleton=true", String.class));
        UUID retryOwner = UUID.randomUUID();
        assertTrue(store.claim(retryOwner, NEXT_MORNING.plusSeconds(1)));
        store.release(retryOwner, Map.of("status", "test-complete"), NEXT_MORNING.plusSeconds(2));
    }

    private GuideRefreshService.Report bootstrap() {
        return service.initializeReviewedBaseline(service.catalogFingerprint(), REVIEWED);
    }

    private Guide current(Guide target) {
        return store.current(catalog.items()).stream().filter(item -> item.id().equals(target.id())).findFirst().orElseThrow();
    }

    private int historyCount(Guide target) {
        return db.queryForObject("SELECT count(*) FROM guide_entry_history WHERE guide_id=?", Integer.class, target.id());
    }

    private Guide guide(String title, String url) {
        return new Guide("guide-refresh-test-" + UUID.randomUUID(), "서류 발급 안내", title, OLD_TEXT,
            new Evidence("공식 안내", url, "2026-11-21"), "발급 절차만 안내해요. 개별 공고의 제출 기준을 확인하세요.", List.of());
    }

    private static Guide withBody(Guide source, String body) {
        return new Guide(source.id(), source.category(), source.title(), body, source.evidence(), source.scope(), source.additionalEvidence());
    }

    private static GuideSourceClient.Result available(Guide target, String text) {
        return available(target, target.evidence().url(), text);
    }

    private static GuideSourceClient.Result available(Guide target, String finalUrl, String text) {
        return new GuideSourceClient.Result(GuideSourceClient.Status.AVAILABLE,
            new GuideSourceClient.Snapshot(target.evidence().url(), finalUrl, "공식 안내", text,
                GuideRefreshRepository.sha(text), REVIEWED, List.of()), "AVAILABLE");
    }

    private static GuideSourceClient.Result unreachable() {
        return new GuideSourceClient.Result(GuideSourceClient.Status.UNREACHABLE, null, "TEST_UNAVAILABLE");
    }

    private static GuideContentReviewService.Result decision(GuideContentReviewService.Status status, Guide candidate) {
        return new GuideContentReviewService.Result(status, "GUIDE_TEST_" + status, candidate, "test-reviewed-packet", "{}");
    }
}
