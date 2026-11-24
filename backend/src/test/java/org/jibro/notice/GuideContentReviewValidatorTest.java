package org.jibro.notice;

import java.util.List;
import java.util.Map;

import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.Guide;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.jibro.notice.GuideContentReviewService.SourceChange;
import static org.jibro.notice.GuideContentReviewService.Status;
import static org.junit.jupiter.api.Assertions.*;

class GuideContentReviewValidatorTest {
    private final JsonMapper json = JsonMapper.builder().build();
    static final String URL = "https://apply.lh.or.kr/lhapply/cm/cntnts/cntntsView.do?cntntsId=1";
    static final String OLD = "상담은 평일 9시부터 18시까지 가능해요.";
    static final String NEW = "상담은 평일 9시부터 17시까지 가능해요.";

    @Test
    void acceptsSupportedTimeChangePreservingIdentityLinksAndUntouchedParagraphs() {
        var guide = guide(OLD + "\n\n상담 전에 공고 이름을 준비해요.");
        var plan = plan(guide, OLD, NEW);
        var result = validate(plan, "UPDATED", List.of(patch(OLD, NEW)), List.of(proof(OLD, NEW)));
        assertEquals(Status.UPDATED, result.status());
        assertEquals(NEW + "\n\n상담 전에 공고 이름을 준비해요.", result.candidate().body());
        assertEquals(guide.id(), result.candidate().id());
        assertEquals(guide.title(), result.candidate().title());
        assertEquals(guide.category(), result.candidate().category());
        assertEquals(guide.evidence(), result.candidate().evidence());
        assertEquals(guide.additionalEvidence(), result.candidate().additionalEvidence());
        assertEquals(guide.scope(), result.candidate().scope());
    }

    @Test
    void initialReviewCanConfirmWithoutInventingPreviousText() {
        var guide = guide(NEW);
        var result = validate(plan(guide, "", NEW), "CONFIRMED", List.of(), List.of(proof("", NEW)));
        assertEquals(Status.CONFIRMED, result.status());
        assertSame(guide, result.candidate());
    }

    @Test
    void conflictKeepsCurrentContentAndDoesNotAcceptPatches() {
        var plan = plan(guide(OLD), OLD, NEW);
        assertNull(validate(plan, "REVIEW_REQUIRED", List.of(), List.of()).candidate());
        assertThrows(IllegalArgumentException.class,
            () -> validate(plan, "REVIEW_REQUIRED", List.of(patch(OLD, NEW)), List.of()));
    }

    @Test
    void rejectsFabricatedAndPreviousOnlyQuotes() {
        var plan = plan(guide(OLD), OLD, NEW);
        for (var invalid : List.of(proof(OLD, OLD), proof("invented before", NEW),
            Map.of("sourceId", "unknown", "previousQuote", OLD, "currentQuote", NEW))) {
            assertThrows(IllegalArgumentException.class,
                () -> validate(plan, "UPDATED", List.of(patch(OLD, NEW)), List.of(invalid)));
        }
    }

    @Test
    void rejectsMissingCorroboratingSourceOrAdditionalSchemaFields() {
        var secondary = new Evidence("정부24", "https://www.gov.kr/portal/guide", "2026-11-21");
        var guide = new Guide("guide-test", "문의", "상담 시간은 언제인가요?", NEW, evidence(), "상담 시간 안내예요.", List.of(secondary));
        var plan = GuideContentReviewPlan.create(guide, List.of(source(OLD, NEW),
            new SourceChange("s2", secondary.url(), "b".repeat(64), OLD, NEW)), json);
        assertThrows(IllegalArgumentException.class, () -> validate(plan, "CONFIRMED", List.of(), List.of(proof(OLD, NEW))));
        var fields = new java.util.HashMap<>(Map.of("decision", "CONFIRMED", "reason", "확인", "patches", List.of(), "evidence", List.of(proof(OLD, NEW))));
        fields.put("url", "https://evil.example/");
        assertThrows(IllegalArgumentException.class, () -> GuideContentReviewValidator.validate(plan, json.writeValueAsString(fields), json));
    }

    @Test
    void refusesToEraseExceptionsQuantitiesNegationOrAudienceEvenWithExactQuotes() {
        for (String old : List.of("상담은 평일 9시부터 18시까지 가능해요. 공휴일은 예외예요.",
            "무주택 세대만 상담을 받을 수 있어요.", "상담을 대리 신청할 수 없어요.",
            "상담은 1인 30분까지 가능해요.")) {
            String after = "상담을 받을 수 있어요.";
            var plan = plan(guide(old), old, after);
            assertThrows(IllegalArgumentException.class,
                () -> validate(plan, "UPDATED", List.of(patch(old, after, old, after)), List.of(proof(old, after))));
        }
    }

    @Test
    void unsupportedNewClaimOrNumberCannotBeHiddenBehindAnAuthenticQuote() {
        var plan = plan(guide(OLD), OLD, NEW);
        for (String after : List.of(NEW + " 자동 지원금을 지급해요.", OLD.replace("18시", "22시"),
            NEW + " 모든 신청자가 지원받아요.")) {
            assertThrows(IllegalArgumentException.class,
                () -> validate(plan, "UPDATED", List.of(patch(OLD, after)), List.of(proof(OLD, NEW))));
        }
    }

    @Test
    void refusesUnverifiedThresholdElsewhereInConfirmedOrUpdatedBody() {
        var guide = guide(OLD + "\n\n상담비는 5000원이에요.");
        var plan = plan(guide, OLD, NEW);
        assertThrows(IllegalArgumentException.class, () -> validate(plan, "CONFIRMED", List.of(), List.of(proof(OLD, NEW))));
        assertThrows(IllegalArgumentException.class, () -> validate(plan, "UPDATED", List.of(patch(OLD, NEW)), List.of(proof(OLD, NEW))));
    }

    @Test
    void rejectsFormattingNotSupportedByGuideRendererAndAnyGeneratedLink() {
        for (String after : List.of("**상담**은 평일 9시부터 17시까지 가능해요.",
            "# " + NEW, "| 상담 | 평일 9시부터 17시까지 |", "<p>" + NEW + "</p>",
            NEW + " https://www.gov.kr", "[상담](https://www.gov.kr)", "`" + NEW + "`")) {
            var plan = plan(guide(OLD), OLD, after);
            assertThrows(IllegalArgumentException.class,
                () -> validate(plan, "UPDATED", List.of(patch(OLD, after, OLD, after)), List.of(proof(OLD, after))));
        }
    }

    @Test
    void disallowsPartialAmbiguousOverlappingAndDuplicateReplacements() {
        var partial = plan(guide(OLD), OLD, NEW);
        assertThrows(IllegalArgumentException.class, () -> validate(partial, "UPDATED", List.of(patch("18시까지 가능해요.", "17시까지 가능해요.")), List.of(proof(OLD, NEW))));
        var duplicate = plan(guide(OLD + "\n" + OLD), OLD, NEW);
        assertThrows(IllegalArgumentException.class, () -> validate(duplicate, "UPDATED", List.of(patch(OLD, NEW)), List.of(proof(OLD, NEW))));
        assertThrows(IllegalArgumentException.class, () -> validate(partial, "UPDATED", List.of(patch(OLD, NEW), patch(OLD, NEW)), List.of(proof(OLD, NEW))));
    }

    private GuideContentReviewValidator.Validated validate(GuideContentReviewPlan plan, String decision, List<?> patches, List<?> proof) {
        return GuideContentReviewValidator.validate(plan, json.writeValueAsString(Map.of("decision", decision, "reason", "공식 자료 대조", "patches", patches, "evidence", proof)), json);
    }

    private GuideContentReviewPlan plan(Guide guide, String previous, String current) {
        return GuideContentReviewPlan.create(guide, List.of(source(previous, current)), json);
    }

    static Guide guide(String body) {
        return new Guide("guide-test", "문의", "상담 시간은 언제인가요?", body, evidence(), "상담 시간 안내예요.", List.of());
    }

    static Evidence evidence() {
        return new Evidence("LH 상담", URL, "2026-11-21");
    }

    static SourceChange source(String previous, String current) {
        return new SourceChange("s1", URL, "a".repeat(64), previous, current);
    }

    static Map<String, Object> proof(String previous, String current) {
        return Map.of("sourceId", "s1", "previousQuote", previous, "currentQuote", current);
    }

    static Map<String, Object> patch(String before, String after) {
        return patch(before, after, OLD, NEW);
    }

    static Map<String, Object> patch(String before, String after, String previousQuote, String currentQuote) {
        return Map.of("field", "body", "before", before, "after", after, "evidence", List.of(proof(previousQuote, currentQuote)));
    }
}
