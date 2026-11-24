package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;
import static org.jibro.collection.review.OfficialFollowupComparisonTest.*;

import java.time.LocalDate;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OfficialDetailParser;
import org.jibro.collection.review.followup.OfficialFollowupModels.*;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class AutomaticItemReconciliationTest {
    private static final String AGE = "연령 기준: 만 19세 이상, 자녀를 부양하는 미성년 세대주도 신청할 수 있습니다.";
    private static final String DOC = "가족관계증명서 제출 대상: 배우자와 주소가 다른 신청자";
    private static final String UPDATED = "주소가 다른 배우자가 있거나 미성년 자녀를 부양하는 신청자";
    private static final Content ORIGINAL = new Content("공식 모집", new Evidence("원문", PRIMARY, "2026-11-09"),
        List.of(), List.of(), List.of(), List.of());

    private static OfficialFollowupComparison.Result compare(Draft draft, Related... corrections) {
        return OfficialFollowupComparison.compare(draft, ORIGINAL, new Discovery(CHECKED, List.of(corrections), List.of()));
    }

    private static Related amendment(String text, String url, int day) {
        return related("CORRECTION", url, LocalDate.of(2026, 11, day), text);
    }

    private static String primary() {
        return VALID.replace("신청자는 무주택자이어야 합니다.", AGE).replace("공통 제출서류: 주민등록등본, 가족관계증명서", DOC);
    }

    @Test
    void officialTableReconcilesChangedDocumentAndRetainsAgeException() {
        var html = "<main><table><thead><tr><th>항목</th><th>정정 전</th><th>정정 후</th></tr></thead>"
            + "<tbody><tr><td>가족관계증명서 제출 대상</td><td>배우자와 주소가 다른 신청자</td><td>" + UPDATED + "</td></tr></tbody></table></main>";
        var parts = OfficialDetailParser.parse(html, NEXT).parts();
        var corrected = new Source("정정공고", NEXT, "b".repeat(64), "EXTRACTED", "", List.of(), parts);
        var notice = new Related("CORRECTION", NEXT, "공식 정정", LocalDate.of(2026, 11, 6), "같은 모집", List.of(corrected));
        var result = compare(draft(source(PRIMARY, primary())), notice);
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        var content = result.assessment().candidate();
        assertTrue(content.questions().stream().anyMatch(q -> q.reason().contains("만 19세") && q.reason().contains("미성년")));
        var doc = content.documents().stream().filter(d -> d.title().equals("가족관계증명서")).findFirst().orElseThrow();
        assertEquals(UPDATED, doc.method());
        assertEquals(NEXT, doc.evidence().url());
        assertNotNull(doc.conditionQuestionId());
        assertTrue(content.reviewIssues().isEmpty());
    }

    @Test
    void completeReissueMustAccountForEveryOtherClause() {
        var revised = primary().replace("배우자와 주소가 다른 신청자", UPDATED);
        var base = draft(source(PRIMARY, primary()));
        var result = compare(base, amendment(revised, NEXT, 6));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertEquals(1, result.resolutions().size());
        var extra = compare(base, amendment(revised + "\n외국인 신청에는 별도의 예외가 있습니다.", NEXT, 6));
        assertFalse(extra.assessment().ready());
        assertTrue(extra.invalidatesPublishedGuide());
    }

    @Test
    void laterAmendmentResolvesAnItemAndConcurrentDisagreementAffectsOnlyThatDocument() {
        var base = draft(source(PRIMARY, primary()));
        String changed = "가족관계증명서 제출 대상: " + UPDATED;
        var first = amendment("정정 전: " + DOC + "\n정정 후: " + changed, NEXT, 5);
        var last = amendment("정정 전: " + changed + "\n정정 후: 가족관계증명서 제출 대상: 신청자 전원", LATER, 6);
        var ordered = compare(base, last, first);
        assertTrue(ordered.assessment().ready(), ordered.assessment().issues().toString());
        assertTrue(ordered.assessment().candidate().documents().stream().anyMatch(d -> d.method().equals("신청자 전원") && d.evidence().url().equals(LATER)));
        var concurrent = amendment("정정 전: " + DOC + "\n정정 후: 가족관계증명서 제출 대상: 신청자 전원", LATER, 5);
        var disputed = compare(base, first, concurrent);
        assertTrue(disputed.assessment().ready(), disputed.assessment().issues().toString());
        assertEquals(1, disputed.assessment().candidate().reviewIssues().size());
        assertEquals("DOCUMENT", disputed.assessment().candidate().reviewIssues().get(0).area());
        assertTrue(disputed.assessment().candidate().questions().stream().anyMatch(q -> q.reason().contains("만 19세") && !q.reviewRequired()));
    }

    @Test
    void fullReissuesOnTheSameDayUseTheVerifiedPredecessor() {
        String middle = primary().replace("배우자와 주소가 다른 신청자", UPDATED);
        String last = middle.replace(UPDATED, "신청자 전원");
        var first = new Related("CORRECTION", NEXT, "1차 정정", LocalDate.of(2026, 11, 6),
            "공식 정정 이력", List.of(source(NEXT, middle)), PRIMARY);
        var second = new Related("CORRECTION", LATER, "2차 정정", first.publishedDate(),
            "공식 정정 이력", List.of(source(LATER, last)), NEXT);
        var result = compare(draft(source(PRIMARY, primary())), first, second);
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertTrue(result.assessment().candidate().reviewIssues().isEmpty());
        assertTrue(result.assessment().candidate().documents().stream().anyMatch(d ->
            d.method().equals("신청자 전원") && d.evidence().url().equals(LATER)));
    }

    @Test
    void originalHwpxTableUsesTheSameAmendmentAsProse() {
        var cells = List.of("서류명", "제출 대상", "가족관계증명서", "배우자와 주소가 다른 신청자");
        var table = new StringBuilder("⟦HWPX:TABLE id=1 rows=2 cols=2⟧\n");
        for (int i = 0; i < cells.size(); i++)
            table.append("⟦HWPX:CELL table=1 row=").append(1 + i / 2).append(" col=").append(1 + i % 2)
                .append(" rowspan=1 colspan=1⟧\n").append(cells.get(i)).append("\n⟦HWPX:/CELL⟧\n");
        table.append("⟦HWPX:/TABLE⟧");
        var src = new Source("원문.hwpx", PRIMARY, "b".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("HWPX Contents/section0.xml (쪽 번호 확인 필요)", primary().replace(DOC, table))));
        var result = compare(draft(src), amendment("정정 전: " + DOC + "\n정정 후: 가족관계증명서 제출 대상: " + UPDATED, NEXT, 6));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertTrue(result.assessment().candidate().documents().stream().anyMatch(d -> d.method().equals(UPDATED)));
    }

    @Test
    void namedFieldsMayChangeOrderWithinTheSameScopeButCannotLoseAnException() {
        String tax = "소득 기준: 가구 월평균 소득의 70% 이하";
        var first = source(PRIMARY, primary().replace(AGE, AGE + "\n" + tax));
        var other = source(OTHER, primary().replace(AGE, tax + "\n" + AGE));
        var same = compare(draft(first, other));
        assertTrue(same.assessment().ready(), same.assessment().issues().toString());
        assertTrue(same.assessment().candidate().reviewIssues().isEmpty());
        var different = source(OTHER, primary().replace(AGE, tax.replace("70%", "80%") + "\n" + AGE));
        var partial = compare(draft(first, different));
        assertTrue(partial.assessment().ready(), partial.assessment().issues().toString());
        assertEquals(1, partial.assessment().candidate().reviewIssues().size());
        var unknown = source(OTHER, primary().replace(AGE, tax + "\n" + AGE + "\n외국인 가구에는 추가 예외가 있습니다."));
        assertFalse(compare(draft(first, unknown)).assessment().ready());
    }

    @Test
    void exemptFormIsNotPreparedAndAReferralIsLimitedToTheNamedItem() {
        var result = compare(draft(source(PRIMARY, primary().replace(DOC, "가족관계증명서 제출 대상: 전원 제출 면제")
            .replace(AGE, "거주 요건: 모집기관에 개별 문의"))));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertFalse(result.assessment().candidate().documents().stream().anyMatch(d -> d.title().equals("가족관계증명서")));
        assertTrue(result.assessment().candidate().documents().stream().anyMatch(d -> d.title().equals("재학증명서")));
        assertEquals(1, result.assessment().candidate().reviewIssues().size());
        assertEquals("CONDITION", result.assessment().candidate().reviewIssues().get(0).area());
    }

    @Test
    void pdfWithNamedColumnsKeepsThePageAndTheIssuingOffice() {
        var body = primary().replace(DOC, "서류명 | 제출 대상 | 발급처\n가족관계증명서 | 배우자와 주소가 다른 신청자 | 행정복지센터");
        var source = new Source("모집공고.pdf", PRIMARY, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("공고문 7쪽", body)));
        var result = compare(draft(source));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        var doc = result.assessment().candidate().documents().stream().filter(d -> d.title().equals("가족관계증명서")).findFirst().orElseThrow();
        assertTrue(doc.evidence().url().contains("#page=7"));
        assertEquals("행정복지센터", doc.issuer());
    }
}
