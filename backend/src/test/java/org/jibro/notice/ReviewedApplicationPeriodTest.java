package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.ReviewIssue;
import org.jibro.notice.HousingModels.ScheduleTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewedApplicationPeriodTest {
    private static final LocalDate DAY = LocalDate.of(2026, 12, 31);
    private static final Evidence SOURCE = new Evidence("공고문 3쪽", "https://apply.lh.or.kr/notice.pdf", "2026-11-08");
    private static final String CAPACITY_END = "접수인원이 모집호수를 초과하면 마감합니다.";

    private SourceReviewModels.Package openEnded(String disposition, LocalDate start, LocalDate end,
                                                 String issueId, String reason, Evidence evidence, String summary, String... titles) {
        var events = java.util.stream.IntStream.range(0, titles.length)
                .mapToObj(i -> new ScheduleTemplate("event-" + i, titles[i], DAY.toString(), SOURCE)).toList();
        var content = new Content(summary, SOURCE, List.of(), List.of(), List.of(), events, List.of());
        return new SourceReviewModels.Package("confirmed-closing-test", UUID.randomUUID(), 1, UUID.randomUUID(),
                disposition, "원문에서 종료 조건을 확인", List.of(), List.of(), content, start, end,
                issueId, List.of(), new SourceReviewModels.ConfirmedOpenEndedPeriod(reason, evidence));
    }

    private SourceReviewModels.Package partial(LocalDate start, LocalDate end, String... titles) {
        var issue = new ReviewIssue("application-period", "SCHEDULE", "신청 기간 확인 필요",
                "미확정 접수기간을 일정에서 제외합니다.", List.of(), List.of(), List.of(SOURCE));
        var events = java.util.stream.IntStream.range(0, titles.length)
                .mapToObj(i -> new ScheduleTemplate("event-" + i, titles[i], DAY.toString(), SOURCE)).toList();
        var content = new Content("공식 원문 대조", SOURCE, List.of(), List.of(), List.of(), events, List.of(issue));
        return new SourceReviewModels.Package("review-test", UUID.randomUUID(), 1, UUID.randomUUID(),
                "PARTIAL", "원문 대조", List.of(issue.id()), List.of(), content, start, end, issue.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"접수 종료", "신청 종료일", "신청 접수 종료일시", "접수 기간 마감",
            "신청 접수 종료 (18:00)", "신청 접수 마감 (확인 필요)", "신청 접수 종료 · 미정",
            "1순위 접수 마감 (미확정)", "가단지 접수일 종료 (확인 필요)"})
    void unknownDeadlineCannotBePublishedUnderAnAliasOrUncertaintyNote(String title) {
        var p = partial(DAY, null, "신청 접수 시작", title);
        var failure = assertThrows(IllegalArgumentException.class, () -> ReviewedApplicationPeriod.validate(p));
        assertEquals("UNCONFIRMED_APPLICATION_BOUNDARY_PRESENT", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"접수 개시", "신청 시작일", "신청 접수 개시일시", "신청 기간 시작",
            "접수 개시 (오전 10시)", "신청 접수 시작 (미정)", "1순위 접수 시작 · 확인 필요"})
    void unknownOpeningCannotBePublishedUnderAnAliasOrUncertaintyNote(String title) {
        var p = partial(null, DAY, title, "신청 접수 마감");
        assertThrows(IllegalArgumentException.class, () -> ReviewedApplicationPeriod.validate(p));
    }

    @Test
    void confirmedRankSiteAndSeparateProcedureDatesSurviveAnUnknownOverallDeadline() {
        assertDoesNotThrow(() -> ReviewedApplicationPeriod.validate(partial(DAY, null,
                "신청 접수 시작", "1순위 접수 종료", "보성트윈힐스 최초 접수일 종료 · 미충원 잔여 접수와 별개",
                "2순위 해당 접수일 종료 · 후순위 생략 가능", "서류 접수 마감", "계약 접수 종료",
                "인터넷 신청 서류 제출 대상자 발표", "동호지정·계약 마감")));
    }

    @Test
    void aRankInParenthesesIsNotStrippedAsIfItWereJustATime() {
        assertDoesNotThrow(() -> ReviewedApplicationPeriod.validate(partial(DAY, null,
                "신청 접수 시작", "접수 종료 (1순위)", "접수 종료 (1순위 18시)")));
    }

    @Test
    void bothUnknownBoundariesAllowOnlyIndependentConfirmedDates() {
        assertDoesNotThrow(() -> ReviewedApplicationPeriod.validate(partial(null, null,
                "공고 게시", "동호지정 순번 발표", "서류 제출 마감", "계약 마감")));
    }

    @Test
    void confirmedBoundaryStillRequiresTheCanonicalMatchingSchedule() {
        assertThrows(IllegalArgumentException.class, () -> ReviewedApplicationPeriod.validate(
                partial(DAY, null, "접수 시작일")));
        assertDoesNotThrow(() -> ReviewedApplicationPeriod.validate(partial(DAY, null, "접수 개시")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASS", "PARTIAL"})
    void verifiedEndConditionAllowsAnOpenDeadlineWithoutInventingAnEvent(String disposition) {
        var review = openEnded(disposition, DAY, null, null, CAPACITY_END, SOURCE,
                "단지별로 접수를 시작합니다. " + CAPACITY_END + " 현재 공식 페이지는 접수마감입니다.",
                "신청 접수 시작", "가단지 최초 접수일 종료", "서류 제출 마감");
        assertDoesNotThrow(() -> ReviewedApplicationPeriod.validate(review));
        assertNull(review.endDate());
        assertNull(review.applicationPeriodIssueId());
    }

    @Test
    void confirmedEndConditionCannotMaskMissingOpeningOrContradictKnownDeadline() {
        for (var review : List.of(
                openEnded("PASS", null, null, null, CAPACITY_END, SOURCE, CAPACITY_END),
                openEnded("PASS", DAY, DAY, null, CAPACITY_END, SOURCE, CAPACITY_END,
                        "신청 접수 시작", "신청 접수 마감"),
                openEnded("PARTIAL", DAY, null, "application-period", CAPACITY_END, SOURCE, CAPACITY_END,
                        "신청 접수 시작"))) {
            var error = assertThrows(IllegalArgumentException.class, () -> ReviewedApplicationPeriod.validate(review));
            assertEquals("INVALID_CONFIRMED_OPEN_ENDED_PERIOD", error.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HOLD", "UNSUPPORTED"})
    void confirmedClosingIsOnlyForAnExplicitlyReviewedAvailablePackage(String disposition) {
        assertThrows(IllegalArgumentException.class, () -> ReviewedApplicationPeriod.validate(
                openEnded(disposition, DAY, null, null, CAPACITY_END, SOURCE, CAPACITY_END, "신청 접수 시작")));
    }

    @Test
    void endConditionMustBeVisibleVerbatimAndHaveDatedHttpsEvidence() {
        var invalid = List.of(
                openEnded("PASS", DAY, null, null, null, SOURCE, CAPACITY_END, "신청 접수 시작"),
                openEnded("PASS", DAY, null, null, " ", SOURCE, CAPACITY_END, "신청 접수 시작"),
                openEnded("PASS", DAY, null, null, CAPACITY_END, SOURCE, "접수는 선착순입니다.", "신청 접수 시작"),
                openEnded("PASS", DAY, null, null, CAPACITY_END, null, CAPACITY_END, "신청 접수 시작"),
                openEnded("PASS", DAY, null, null, CAPACITY_END,
                        new Evidence("원문", null, "2026-11-08"), CAPACITY_END, "신청 접수 시작"),
                openEnded("PASS", DAY, null, null, CAPACITY_END,
                        new Evidence("원문", "http://apply.lh.or.kr/notice.pdf", "2026-11-08"), CAPACITY_END, "신청 접수 시작"),
                openEnded("PASS", DAY, null, null, CAPACITY_END,
                        new Evidence("원문", SOURCE.url(), "2099-01-01"), CAPACITY_END, "신청 접수 시작"));
        for (var review : invalid)
            assertThrows(IllegalArgumentException.class, () -> ReviewedApplicationPeriod.validate(review));
    }

    @Test
    void confirmedEndConditionStillRejectsFabricatedDeadlineAndMissingStartEvent() {
        assertThrows(IllegalArgumentException.class, () -> ReviewedApplicationPeriod.validate(
                openEnded("PASS", DAY, null, null, CAPACITY_END, SOURCE, CAPACITY_END, "신청 접수 시작", "접수 종료")));
        assertThrows(IllegalArgumentException.class, () -> ReviewedApplicationPeriod.validate(
                openEnded("PASS", DAY, null, null, CAPACITY_END, SOURCE, CAPACITY_END, "공고 게시")));
    }

    @Test
    void missingDeadlineWithoutConfirmedPolicyRetainsTheOriginalPartialIssueRequirement() {
        var original = partial(DAY, null, "신청 접수 시작");
        var withoutPolicy = new SourceReviewModels.Package(original.packageId(), original.noticeId(), original.revision(),
                original.extractionId(), "PASS", original.reviewNotes(), List.of(), original.sourceReviews(),
                original.content(), original.startDate(), original.endDate());
        var error = assertThrows(IllegalArgumentException.class, () -> ReviewedApplicationPeriod.validate(withoutPolicy));
        assertEquals("REVIEWED_APPLICATION_PERIOD_ISSUE_REQUIRED", error.getMessage());
    }
}
