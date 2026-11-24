package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.jibro.notice.HousingModels.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class NoticeDocumentSummaryTest {
    private final Evidence source = new Evidence("공고문 7쪽", "https://apply.lh.or.kr/notice", "2026-11-11");
    private final Question question = new Question("family", "가구 유형", "원문 기준", source);

    private DocumentTemplate doc(String id, String title, String condition) {
        return new DocumentTemplate(id, title, condition, "발급 기준", "기관", null, source, List.of("YES"));
    }

    private Content content(List<DocumentTemplate> documents, List<ReviewIssue> issues) {
        return new Content("요약", source, List.of(), List.of(question), documents, List.of(), issues);
    }

    @Test
    void separatesCommonAndConditionalWithoutAnsweringForTheApplicant() {
        var docs = List.of(doc("identity", "신청자 주민등록표등본", null),
                doc("family", "가족관계증명서(상세)", "family"));
        var result = NoticeDocumentSummary.from(content(docs, List.of()));
        assertEquals(List.of("신청자 주민등록등본"), result.common().items().stream().map(NoticeDocumentSummary.Item::title).toList());
        assertEquals("가족관계증명서(상세)", result.conditional().items().get(0).title());
        assertEquals(source, result.conditional().items().get(0).evidence());
        assertEquals("신청자 주민등록표등본", docs.get(0).title());
    }

    @Test
    void previewsThreeCoreDocumentsWithAccurateRemainingCountAndStableOrder() {
        var docs = List.of(doc("exception", "중복선정 확인서", null),
                doc("consent", "개인정보 수집·이용 및 제3자 제공 동의서", null),
                doc("resident", "신청자 주민등록표등본", null),
                doc("history", "신청자 주민등록표초본", null),
                doc("application", "공급신청서", null));
        var result = NoticeDocumentSummary.from(content(docs, List.of()));
        assertEquals(List.of("resident", "history", "consent"),
                result.common().items().stream().map(NoticeDocumentSummary.Item::id).toList());
        assertEquals("개인정보 동의서", result.common().items().get(2).title());
        assertEquals(2, result.common().remainingCount());
        assertEquals(List.of("application", "exception"), result.common().remainingIds());
        assertEquals("exception", docs.get(0).id());
    }

    @Test
    void preservesSubjectDetailedTypeAndAlternativeRequirements() {
        var docs = List.of(doc("spouse", "분리배우자 주민등록표등본(상세)", "family"),
                doc("child", "신청자 자녀의 상세 가족관계증명서", "family"),
                doc("pregnancy", "임신진단서 또는 임신확인서", "family"));
        var titles = NoticeDocumentSummary.from(content(docs, List.of())).conditional().items().stream()
                .map(NoticeDocumentSummary.Item::title).toList();
        assertTrue(titles.containsAll(List.of("분리배우자 주민등록등본(상세)",
                "신청자 자녀의 상세 가족관계증명서", "임신진단서 또는 임신확인서")));
    }

    @Test
    void conflictedDocumentsAndDependentConditionsStayOutOfBothPreviews() {
        var docs = List.of(doc("normal", "주민등록표등본", null),
                doc("pending", "공급신청서", null), doc("dependent", "가족 증빙", "family"));
        var issues = List.of(new ReviewIssue("doc-issue", "DOCUMENT", "서류", "원문 충돌", List.of(), List.of("pending"), List.of(source)),
                new ReviewIssue("condition-issue", "CONDITION", "가구", "원문 충돌", List.of("family"), List.of(), List.of(source)));
        var result = NoticeDocumentSummary.from(content(docs, issues));
        assertEquals(1, result.common().items().size());
        assertTrue(result.conditional().items().isEmpty());
        assertEquals(0, result.common().remainingCount());
        assertEquals(2, result.reviewRequiredCount());
    }

    @Test
    void missingSourceOrUnresolvedConditionNeverBecomesCommonRequired() {
        var docs = List.of(doc("unknown", "서류", "missing-question"),
                new DocumentTemplate("unsourced", "추가 서류", null, "", "", null, null));
        var result = NoticeDocumentSummary.from(content(docs, List.of()));
        assertTrue(result.common().items().isEmpty());
        assertTrue(result.conditional().items().isEmpty());
        assertEquals(2, result.reviewRequiredCount());
    }

    @Test
    void doesNotInventDocumentsForAnEmptyCatalog() {
        var result = NoticeDocumentSummary.from(content(List.of(), List.of()));
        assertTrue(result.common().items().isEmpty());
        assertTrue(result.conditional().items().isEmpty());
        assertEquals(0, result.reviewRequiredCount());
    }
}
