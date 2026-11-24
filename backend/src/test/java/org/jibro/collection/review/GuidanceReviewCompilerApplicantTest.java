package org.jibro.collection.review;

import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jibro.notice.QuestionAnswers;
import org.jibro.preparation.PreparationApplicability;
import org.jibro.preparation.PreparationRules;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GuidanceReviewCompilerApplicantTest {
    private static final String URL = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69143584";
    private static final Instant CHECKED = Instant.parse("2026-11-21T00:00:00Z");
    // A reduced grammar fixture, not a full extraction or approval of the actual notice.
    // Values come from Pohang Blue Valley's official PDF pages 8, 10, 14, 16 and 18.
    // Headings and explicit column separators model normalized extraction; the raw-source audit is separate.
    private static final String YOUTH = "19세 이상 39세 이하인 사람으로 혼인 중이 아닐 것";
    private static final String INDUSTRIAL = "포항시 또는 연접지역(경주시, 영천시, 청송군, 영덕군)에 소재한 산업단지의 입주기업 및 교육·연구기관에 근무 중일 것";

    @Test
    void explicitOfficialClausesReachSelectedQuestionsDocumentsAndProgress() throws Exception {
        assertSelectedGuidance("청년 신청자격: " + YOUTH + "\n산업단지 근로자 신청자격: " + INDUSTRIAL);
    }

    @Test
    void explicitTypeEligibilityTableUsesTheSameProductionPath() throws Exception {
        assertSelectedGuidance("유형 | 신청자격\n청년 | " + YOUTH + "\n산업단지 근로자 | " + INDUSTRIAL);
    }

    @Test
    void changedSourceAgeIsUsedInTheSituationOption() {
        var content = assessed("청년 신청자격: " + YOUTH.replace("39세", "45세") + "\n산업단지 근로자 신청자격: " + INDUSTRIAL);
        var youth = content.questions().get(0).options().stream().filter(o -> o.value().equals("YOUTH")).findFirst().orElseThrow();
        assertTrue(youth.label().contains("45세"));
        assertFalse(youth.label().contains("39세"));
    }

    @Test
    void generatedSituationQuestionsRemainInsidePublicationLimit() {
        var clauses = new StringBuilder("청년 신청자격: " + YOUTH + "\n산업단지 근로자 신청자격: " + INDUSTRIAL);
        for (int i = 1; i <= 98; i++) clauses.append("\n배점 항목 · 항목").append(i).append(": 10점");
        var result = assessment(List.of(new Part("조건", clauses.toString()),
                new Part("서류", "공통 제출서류: 주민등록등본"),
                new Part("일정", "접수기간: 2026.11.10 ~ 2026.12.08")));
        assertNotNull(result.candidate());
        assertEquals(102, result.candidate().questions().size());
        assertFalse(result.ready());
        assertTrue(result.issues().contains("EVIDENCE_GUIDANCE_TOO_MANY_QUESTIONS"));
    }

    @Test
    void unknownTypeRetainsEveryScopeAndUnknownDocuments() {
        var content = assessed("청년 신청자격: " + YOUTH + "\n산업단지 근로자 신청자격: " + INDUSTRIAL);
        var p = preparation(content, "UNKNOWN");
        assertTrue(PreparationApplicability.visibleQuestions(p).stream().anyMatch(q -> q.title().contains("청년 주택")));
        assertTrue(PreparationApplicability.visibleQuestions(p).stream().anyMatch(q -> q.title().contains("산업단지 근로자 주택")));
        var industrial = PreparationApplicability.visibleDocuments(p).stream().filter(d -> d.title.equals("입주업체확인서")).findFirst().orElseThrow();
        assertEquals("UNKNOWN", industrial.necessity);
        assertEquals("/preparations/generated/conditions/edit", PreparationRules.assess(p).nextAction().path());
    }

    private static void assertSelectedGuidance(String eligibility) throws Exception {
        var content = assessed(eligibility);
        var first = content.questions().get(0);
        assertEquals("applicant-situation", first.id());
        assertEquals(Set.of("YOUTH", "INDUSTRIAL", "NONE", "UNKNOWN"), first.options().stream().map(AnswerOption::value).collect(Collectors.toSet()));
        assertTrue(first.options().stream().filter(o -> o.value().equals("YOUTH")).findFirst().orElseThrow().label().contains("19세"));
        content.questions().forEach(QuestionAnswers::validateQuestion);
        var json = new ObjectMapper();
        String original = json.writeValueAsString(content);
        for (String type : List.of("YOUTH", "INDUSTRIAL")) {
            var p = preparation(content, type);
            var selected = PreparationApplicability.visibleQuestions(p);
            assertEquals(type.equals("YOUTH"), selected.stream().anyMatch(q -> q.title().contains("청년 주택")));
            assertEquals(type.equals("INDUSTRIAL"), selected.stream().anyMatch(q -> q.title().contains("산업단지 근로자 주택")));
            var documents = PreparationApplicability.visibleDocuments(p);
            assertTrue(documents.stream().anyMatch(d -> d.title.equals("주민등록등본") && d.necessity.equals("REQUIRED")));
            assertEquals(type.equals("INDUSTRIAL"), documents.stream().anyMatch(d -> d.title.equals("입주업체확인서")));
            if (type.equals("INDUSTRIAL"))
                assertEquals("CONDITIONAL", documents.stream().filter(d -> d.title.equals("입주업체확인서")).findFirst().orElseThrow().necessity);
            assertEquals(80, PreparationRules.assess(p).progress(), "all relevant questions/documents complete; schedules remain unrecorded");
            assertEquals("/preparations/generated/schedule", PreparationRules.assess(p).nextAction().path());
            assertEquals(original, json.writeValueAsString(content));
        }
    }

    private static Content assessed(String eligibility) {
        var result = assessment(List.of(
                new Part("공고문 10·14쪽", eligibility),
                new Part("공고문 8·14쪽", "청년 주택요건: 신청자 본인에 한하여 주택 소유여부를 검증합니다.\n산업단지 근로자 주택요건: 무주택세대구성원(혼인 중이 아닌 경우 무주택자)이어야 합니다."),
                new Part("공고문 16·18쪽", "공통 제출서류: 주민등록등본\n입주업체확인서 제출 대상: 산업단지 근로자"),
                new Part("공고문 1쪽", "접수기간: 2026.11.10 ~ 2026.12.08")));
        assertTrue(result.ready(), result.issues().toString());
        assertNotNull(result.candidate());
        return result.candidate();
    }

    private static GuidanceReviewCompiler.Assessment assessment(List<Part> parts) {
        var source = new Source("포항블루밸리 모집공고.pdf", URL,
                "f5a88f267a5931d8423e14d62f35ee6cff02ac19e8bbf2404e05c48542567a19", "EXTRACTED", "test-only normalized excerpt", List.of(), parts);
        var draft = new Draft("normalized-applicant-fixture", "pohang", 1, "READY", CHECKED, "", List.of(source));
        var original = new Content("공식 원문 회귀 검사", new Evidence("공고 원문", URL, "2026-11-21"), List.of(), List.of(), List.of(), List.of());
        return GuidanceReviewCompiler.assess(draft, original);
    }

    private static Preparation preparation(Content content, String type) {
        var p = new Preparation();
        p.id = "generated";
        p.noticeId = "pohang";
        p.guideState = "AVAILABLE";
        p.questions = content.questions();
        p.reviewIssues = content.reviewIssues();
        p.answers = new LinkedHashMap<>();
        p.questions.forEach(q -> p.answers.put(q.id(), q.id().equals("applicant-situation") ? type
                : q.answerOptions().stream().filter(o -> !o.requiresFollowUp()).findFirst().map(AnswerOption::value).orElse("UNKNOWN")));
        p.documentConditions = new LinkedHashMap<>();
        p.documentConditionValues = new LinkedHashMap<>();
        p.documents = new ArrayList<>();
        for (var template : content.documents()) {
            var d = new Document();
            d.id = template.id();
            d.title = template.title();
            d.state = DocumentState.READY;
            d.necessity = PreparationRules.necessity(template.conditionQuestionId(), template.conditionAnswerValues() == null ? List.of("YES") : template.conditionAnswerValues(), p.answers, p.questions);
            p.documents.add(d);
            if (template.conditionQuestionId() != null) {
                p.documentConditions.put(d.id, template.conditionQuestionId());
                p.documentConditionValues.put(d.id, template.conditionAnswerValues());
            }
        }
        return p;
    }
}
