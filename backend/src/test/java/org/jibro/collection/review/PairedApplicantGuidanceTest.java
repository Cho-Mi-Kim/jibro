package org.jibro.collection.review;

import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;

import org.jibro.preparation.PreparationApplicability;
import org.jibro.preparation.PreparationRules;
import org.junit.jupiter.api.Test;

class PairedApplicantGuidanceTest {
    private static final String BODY = """
        1. 신청자격
        신청자와 주소가 다른 배우자도 함께 확인합니다.
        청년 신청자격: 19세 이상 39세 이하인 미혼인 무주택자
        대학생 신청자격: 대학에 재학 중이거나 다음 학기에 입학 예정인 무주택자
        다만 원격대학은 제외합니다. 예외와 제출 기한을 함께 확인합니다.
        2. 제출서류
        주민등록표등본 제출 대상: 모든 신청자
        재학증명서 제출 대상: 대학생 · 안내: 입학 예정자는 입학증명서로 대신 제출합니다.
        부모 가족관계증명서 제출 대상: 대학생 중 부모와 등본이 다른 신청자 · 안내: 부모와 관계를 확인합니다.
        3. 모집일정
        접수기간: 2026.11.30 ~ 2026.12.04
        """;

    @Test
    void pairedCompleteItemsProduceTypeScopedDocumentsWithoutDroppingExceptions() {
        var result = assess(BODY.replace("주소가 다른", "주소가\n다른").replace("예외와 제출", "예외와\n제출"));
        assertTrue(result.ready(), result.issues().toString());
        var content = result.candidate();
        assertEquals("applicant-situation", content.questions().get(0).id());
        var enrollment = content.documents().stream().filter(d -> d.title().equals("재학증명서")).findFirst().orElseThrow();
        assertEquals(List.of("STUDENT"), enrollment.conditionAnswerValues());
        assertTrue(enrollment.method().contains("입학증명서로 대신"));
        var parent = content.documents().stream().filter(d -> d.title().equals("부모 가족관계증명서")).findFirst().orElseThrow();
        var question = content.questions().stream().filter(q -> q.id().equals(parent.conditionQuestionId())).findFirst().orElseThrow();
        assertEquals(List.of("STUDENT"), question.applicability().answerValues());
        var p = new Preparation();
        p.questions = content.questions();
        p.answers = Map.of("applicant-situation", "YOUTH");
        assertFalse(PreparationApplicability.visibleQuestions(p).stream().anyMatch(q -> q.id().equals(question.id())));
        p.answers = Map.of("applicant-situation", "UNKNOWN");
        assertTrue(PreparationApplicability.visibleQuestions(p).stream().anyMatch(q -> q.id().equals(question.id())));
        assertEquals("UNKNOWN", PreparationRules.necessity(parent.conditionQuestionId(), parent.conditionAnswerValues(), p.answers, p.questions));
        assertTrue(content.questions().stream().anyMatch(q -> q.reason().contains("원격대학은 제외")));
    }

    @Test
    void changedNumericCriterionAndMissingExceptionCannotBecomeAnApprovedSelector() {
        var numeric = assess(BODY.replace("39세", "49세")).candidate();
        assertTrue(numeric.questions().stream().noneMatch(q -> q.id().equals("applicant-situation")));
        assertTrue(numeric.reviewIssues().stream().anyMatch(i -> i.area().equals("CONDITION")
            && i.detail().contains("39세") && i.detail().contains("49세")));
        var omitted = assess(BODY.replace("다만 원격대학은 제외합니다. ", ""));
        assertFalse(omitted.ready());
        assertTrue(omitted.issues().stream().anyMatch(i -> i.startsWith("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:")));
        var recipient = assess(BODY.replace("대학생 중 부모와 등본이 다른 신청자", "모든 신청자")).candidate();
        var document = recipient.documents().stream().filter(d -> d.title().equals("부모 가족관계증명서")).findFirst().orElseThrow();
        assertTrue(recipient.reviewIssues().stream().anyMatch(i -> i.area().equals("DOCUMENT") && i.documentIds().contains(document.id())));
        var unresolved = recipient.questions().stream().filter(q -> q.id().equals(document.conditionQuestionId())).findFirst().orElseThrow();
        assertTrue(unresolved.reviewRequired());
        assertNull(unresolved.applicability());
    }

    @Test
    void sameRankDoesNotEraseDistinctBenefitCategoriesOrTheirExceptions() {
        assertEquals("장애인", OfficialGuidanceItem.rankedApplicant("1순위", "바. 장애인등록증이 교부된 사람(그 배우자를 포함)"));
        assertEquals(List.of("LIVELIHOOD_MEDICAL_BENEFIT"), OfficialGuidanceItem.applicantTypes("생계·의료급여 수급자 신청자격"));
        assertTrue(OfficialGuidanceItem.missingApplicantTypes(List.of("1순위 자격: 가. 새 지원 대상"), List.of("장애인 신청자격"))
            .contains("UNRECOGNIZED:순위별 신청 대상"));
        assertEquals("HOUSING_BENEFIT", OfficialGuidanceItem.applicantType(OfficialGuidanceItem.applicantSection("4-3. 주거급여수급자") + " 신청자격"));
    }

    @Test
    void explicitNumberedScopesAcceptParticlesButStillRequireEveryReferencedClause() {
        String section = """
            대학생은 ①-㉮와 ②～③, 취업준비생의 경우 ①-㉯와 ②～③을 모두 갖춘 자
            ①-㉮ (대학생) 대학에 재학 중이거나 다음 학기에 입학 또는 복학 예정일 것
            ①-㉯ (취업준비생) 대학을 졸업 또는 중퇴한 지 2년 이내일 것
            ② 혼인 중이 아닐 것
            ③ 자동차를 소유하지 않을 것
            """;
        assertEquals(4, OfficialGuidanceItem.numberedApplicantFields(section).statements().size());
        assertTrue(OfficialGuidanceItem.numberedApplicantFields(section.replace("③ 자동차를 소유하지 않을 것", "")).statements().isEmpty());
    }

    @Test
    void blankIssuerKeepsRecipientAndAlternativeDocumentInstructions() {
        var row = OfficialGuidanceRows.row(List.of("공급대상", "공급대상", "제출서류", "발급처"),
            List.of("대학생 계층", "대학생", "재학증명서. 입학 예정자는 입학증명서 또는 등록금납부확인서", ""), null, false);
        assertNotNull(row);
        assertTrue(row.contains("대학생 · 안내:"));
        assertTrue(row.contains("또는 등록금납부확인서"));
        assertFalse(row.contains("모든 신청자"));
        assertNull(OfficialGuidanceRows.row(List.of("대상자", "제출서류", "발급처"), List.of("", "재학증명서", "학교"), null, false));
    }

    private static GuidanceReviewCompiler.Assessment assess(String second) {
        var pdf = source("공고.pdf", "pdf", BODY);
        var hwpx = source("공고.hwpx", "hwpx", second);
        var draft = new Draft("pair", "notice", 1, "READY", Instant.parse("2026-11-23T00:00:00Z"), "", List.of(pdf, hwpx));
        var original = new Content("원문", new Evidence(pdf.label(), pdf.url(), "2026-11-23"),
            List.of(new Evidence(hwpx.label(), hwpx.url(), "2026-11-23")), List.of(), List.of(), List.of());
        return GuidanceReviewCompiler.assess(draft, original);
    }

    private static Source source(String label, String id, String text) {
        return new Source(label, "https://apply.lh.or.kr/" + id, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("공고문 1쪽", text)));
    }
}
