package org.jibro.collection.review;

import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.jibro.notice.AnswerDependencies;
import org.jibro.notice.QuestionAnswers;
import org.jibro.preparation.PreparationApplicability;
import org.jibro.preparation.PreparationRules;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ApplicantSituationBookletTest {
    private record Fixture(Draft draft, Content original) {
    }

    @Test
    void wholeOfficialBookletConnectsNumberedSituationsToQuestionsAndRecipientRows() throws Exception {
        var fixture = fixture();
        // Complete official HWPX, not a shortened or manually reworded grammar example.
        // The full multi-attachment notice's independent coverage decision is tested separately.
        var source = fixture.draft().sources().stream().filter(s -> s.label().contains("공고문") && s.label().endsWith(".hwpx"))
                .findFirst().orElseThrow();
        var draft = new Draft("booklet", fixture.draft().noticeId(), 1, "READY", fixture.draft().checkedAt(), "공식 전체 원문", List.of(source));
        var original = new Content("공식 전체 원문", new Evidence(source.label(), source.url(), "2026-11-22"), List.of(), List.of(), List.of(), List.of());
        var assessment = GuidanceReviewCompiler.assess(draft, original);
        assertTrue(assessment.ready(), assessment.issues().toString());
        var content = assessment.candidate();
        content.questions().forEach(QuestionAnswers::validateQuestion);
        AnswerDependencies.validate(content);
        var first = content.questions().get(0);
        assertEquals("applicant-situation", first.id());
        assertEquals(Set.of("NEWLYWED", "PREMARITAL", "SINGLE_PARENT", "NONE", "UNKNOWN"),
                first.options().stream().map(AnswerOption::value).collect(Collectors.toSet()));
        assertTrue(first.options().stream().filter(o -> o.value().equals("SINGLE_PARENT")).findFirst().orElseThrow().label().contains("6세이하"));

        var married = selected(content, "NEWLYWED");
        var future = selected(content, "PREMARITAL");
        var single = selected(content, "SINGLE_PARENT");
        assertTrue(married.stream().anyMatch(q -> q.title().contains("신청 기준 ②") && q.reason().contains("이전 혼인기간 포함")));
        assertFalse(future.stream().anyMatch(q -> q.title().contains("신청 기준 ②")));
        assertFalse(single.stream().anyMatch(q -> q.title().contains("신청 기준 ②")));
        assertTrue(future.stream().anyMatch(q -> q.title().contains("신청 기준 ⑥") && q.reason().contains("세대구성원 모두 무주택")));
        assertFalse(married.stream().anyMatch(q -> q.title().contains("신청 기준 ⑥")));
        assertTrue(content.questions().stream().anyMatch(q -> q.reason().contains("맞벌이") && q.reason().contains("130")));
        assertTrue(content.questions().stream().anyMatch(q -> q.reason().contains("579,278")));
        var sourceReference = content.questions().stream().filter(q -> q.id().startsWith("e-section") && q.reason().contains("3-1. 신혼부부·한부모가족 계층"))
                .findFirst().orElseThrow();
        assertTrue(QuestionAnswers.isAcknowledgementQuestion(sourceReference),
                "The old whole-section confirmation must not ask other types to re-confirm the replaced condition.");

        var premaritalForm = content.documents().stream().filter(d -> d.title().contains("예비신혼부부 신청 확인서")).findFirst().orElseThrow();
        assertEquals("applicant-situation", premaritalForm.conditionQuestionId());
        assertEquals("CONDITIONAL", necessity(content, premaritalForm, "PREMARITAL"));
        assertEquals("NOT_APPLICABLE", necessity(content, premaritalForm, "NEWLYWED"));
        assertEquals("NOT_APPLICABLE", necessity(content, premaritalForm, "SINGLE_PARENT"));
        assertEquals("UNKNOWN", necessity(content, premaritalForm, "UNKNOWN"));
        var common = content.documents().stream().filter(d -> d.title().equals("가족관계증명서")).findFirst().orElseThrow();
        assertEquals("REQUIRED", necessity(content, common, "SINGLE_PARENT"));
        assertTrue(common.method().contains("배우자가 없는 경우에도 반드시 제출"));
        var assetForm = content.documents().stream().filter(d -> d.title().equals("자산보유사실확인서")).findFirst().orElseThrow();
        assertEquals("UNKNOWN", necessity(content, assetForm, "SINGLE_PARENT"));
        assertTrue(content.questions().stream().anyMatch(q -> q.reason().contains("임차보증금") && q.reason().contains("제출 불필요")),
                "The main form and supporting-proof exemption remain in the original instructions until their scopes are distinguished.");
        assertTrue(content.questions().stream().anyMatch(q -> q.reason().contains("소득 근거지")),
                "A type name must not make the conditional employment-location evidence universal.");

    }

    @Test
    void numberedScopeRequiresCompleteExplicitReferences() {
        String source = """
                신혼부부인 경우 ➀-㉮와 ②～③를, 예비신혼부부인 경우 ➀-㉯와 ③～④를 모두 갖춘 자
                ➀-㉮ (신혼부부) 혼인중인 자
                ➀-㉯ (예비신혼부부) 입주 전까지 혼인사실을 증명할 수 있을 것
                ② 혼인기간이 7년 이내 또는 6세 이하 자녀를 둔 자
                ③ 소득 기준을 충족할 것
                ④ 결혼 후 세대원 모두 무주택자일 것
                """;
        assertEquals(5, OfficialGuidanceItem.numberedApplicantFields(source).statements().size());
        assertTrue(OfficialGuidanceItem.numberedApplicantFields(source.replace("③～④", "별도 안내")).statements().isEmpty());
        assertTrue(OfficialGuidanceItem.numberedApplicantFields(source.replace("④ 결혼 후 세대원 모두 무주택자일 것", "")).statements().isEmpty());
    }

    @Test
    void officialNumberedFormsHaveTheirOwnRoleAndRetainUniqueInstructions() throws Exception {
        var fixture = fixture();
        var coverage = SourceCoveragePolicy.assessScoped(fixture.draft(), fixture.original());
        assertEquals(3, coverage.formBlocks().stream().map(b -> b.source().url()).distinct().count());
        assertTrue(coverage.formBlocks().stream().anyMatch(b -> String.join(" ", b.instructions()).contains("각자가 자필") && String.join(" ", b.instructions()).contains("만 14세 미만")));
        assertTrue(coverage.formBlocks().stream().anyMatch(b -> String.join(" ", b.instructions()).contains("해당사항이 없어도") && String.join(" ", b.instructions()).contains("반드시 제출")));
        assertTrue(coverage.formBlocks().stream().anyMatch(b -> b.text().contains("본인발급분") && b.text().contains("본인서명사실확인서")));
        assertTrue(coverage.draft().sources().stream().noneMatch(s -> s.label().contains("양식") || s.label().contains("팸플릿")));
        // This isolates the form/facility boundary. The listing's substantive instructions and
        // PDF/HWPX equivalence still require their own whole-notice reconciliation.
        var sources = fixture.draft().sources().stream().filter(s -> !s.label().equals("공식 상세 본문"))
                .filter(s -> !s.label().contains("공고문") || !s.label().endsWith(".pdf")).toList();
        var original = sources.stream().filter(s -> s.label().contains("공고문")).findFirst().orElseThrow();
        var draft = new Draft("forms", fixture.draft().noticeId(), 1, "READY", fixture.draft().checkedAt(), "", sources);
        var content = new Content("원문", new Evidence(original.label(), original.url(), "2026-11-22"), List.of(), List.of(), List.of(), List.of());
        var result = GuidanceReviewCompiler.assess(draft, content);
        assertTrue(result.ready(), result.issues().toString());
        var asset = result.candidate().documents().stream().filter(d -> d.title().equals("자산보유사실확인서")).findFirst().orElseThrow();
        assertEquals("UNKNOWN", necessity(result.candidate(), asset, "SINGLE_PARENT"));
        assertTrue(result.candidate().reviewIssues().stream().anyMatch(issue -> issue.documentIds().contains(asset.id())),
                "A general form header cannot override the recruitment row's unresolved supporting-proof exemption.");
        assertTrue(asset.issueUrl().contains("69385802"));
        assertTrue(result.candidate().documents().stream().anyMatch(d -> d.method().contains("추가 세대원") && d.method().contains("1장 추가")));
        assertTrue(result.candidate().documents().stream().anyMatch(d -> d.method().contains("대표신청자는") && d.method().contains("변경이 불가")));
        result.candidate().questions().forEach(QuestionAnswers::validateQuestion);
        AnswerDependencies.validate(result.candidate());
        // Both actual input screens consume this generated fixture. Detect stale scope/answer/document
        // contracts here instead of allowing a hand-written manuscript to stand in for the producer.
        var json = new ObjectMapper();
        var consumer = json.readTree(Files.readString(Path.of("../frontend/src/app/fixtures/applicant-generated-sangok.json")));
        assertEquals(json.valueToTree(result.candidate().questions()), consumer.get("questions"));
        assertEquals(json.valueToTree(result.candidate().documents()), consumer.get("documents"));
        assertEquals(json.valueToTree(result.candidate().reviewIssues()), consumer.get("reviewIssues"));
    }

    @Test
    void aDeclaredStandaloneCategoryCannotBeLostBesideRecognizedTypeClauses() throws Exception {
        Source actual;
        try (var stream = getClass().getResourceAsStream("/review/samrye-applicant-source.json")) {
            actual = new ObjectMapper().readValue(stream, Source.class);
        }
        // The untouched Samrye source has compound student/youth sections and standalone elder/benefit sections.
        var missing = OfficialGuidanceItem.missingApplicantTypes(actual.context().stream().map(Part::text).toList(),
                List.of("대학생 신청자격", "청년 신청자격"));
        assertEquals(Set.of("ELDER", "HOUSING_BENEFIT"), missing);
        String raw = actual.context().get(0).text();
        int start = raw.indexOf("입주자모집공고일(2026.10.19.) 현재, 무주택세대구성원으로서 아래의 요건을 모두 갖춘 65세 이상인 자");
        assertTrue(start >= 0);
        String elder = raw.substring(start, raw.indexOf('⟦', start)).strip();
        // Reduced grammar boundary: two recognized types beside the actual unparsed elder clause.
        String text = """
                1. 신청자격
                3-1. 신혼부부 계층
                신혼부부인 경우 ➀-㉮와 ②～③를, 예비신혼부부인 경우 ➀-㉯와 ③～④를 모두 갖춘 자
                ➀-㉮ (신혼부부) 혼인중인 자
                ➀-㉯ (예비신혼부부) 입주 전까지 혼인사실을 증명할 수 있을 것
                ② 혼인기간이 7년 이내 또는 6세 이하 자녀를 둔 자
                ③ 소득 기준을 충족할 것
                ④ 결혼 후 세대원 모두 무주택자일 것
                3-2. 고령자 계층
                """ + elder + "\n2. 제출서류\n주민등록표등본 제출 대상: 모든 신청자\n3. 모집일정\n접수기간: 2026.11.01 ~ 2026.12.31";
        var source = new Source(actual.label(), actual.url(), actual.sha256(), "EXTRACTED", "reduced grammar boundary", List.of(),
                List.of(new Part("공고문 1쪽", text)));
        var draft = new Draft("boundary", "boundary", 1, "READY", java.time.Instant.parse("2026-11-22T12:00:00Z"), "", List.of(source));
        var content = new Content("범위 검사", new Evidence(actual.label(), actual.url(), "2026-11-22"), List.of(), List.of(), List.of(), List.of());
        var result = GuidanceReviewCompiler.assess(draft, content);
        assertTrue(result.ready(), result.issues().toString());
        assertFalse(result.candidate().questions().stream().anyMatch(q -> q.id().equals("applicant-situation")));
        assertTrue(result.candidate().questions().stream().anyMatch(q -> q.reason().contains("65세 이상인 자")));
        assertTrue(result.candidate().questions().stream().filter(q -> q.reason().contains("➀-㉮ (신혼부부)"))
                        .anyMatch(q -> q.answerOptions().stream().anyMatch(o -> o.value().equals("NO") && o.requiresFollowUp())),
                "An incomplete selector must not turn its original confirmation question into an acknowledgement.");
    }

    private static List<Question> selected(Content content, String value) {
        var p = new Preparation();
        p.guideState = "AVAILABLE";
        p.questions = content.questions();
        p.answers.put("applicant-situation", value);
        return PreparationApplicability.visibleQuestions(p);
    }

    private static String necessity(Content content, DocumentTemplate document, String type) {
        return PreparationRules.necessity(document.conditionQuestionId(), document.conditionAnswerValues(),
                Map.of("applicant-situation", type), content.questions());
    }

    private static Fixture fixture() throws Exception {
        try (var stream = ApplicantSituationBookletTest.class.getResourceAsStream("/review/sangok-a1-source.json")) {
            return new ObjectMapper().readValue(stream, Fixture.class);
        }
    }
}
