package org.jibro.collection.review;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.AnswerDependencies;
import org.jibro.notice.QuestionAnswers;
import org.jibro.preparation.PreparationApplicability;
import org.jibro.preparation.PreparationRules;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ApplicantSituationCompilerTest {
    @Test
    void commonYouthGroupUsesItsExplicitMembersAndKeepsConditionalProofsSeparate() {
        String section = """
            4-2. 청년 계층
            청년은 ①-㉮와 ②～③, 사회초년생의 경우 ①-㉯와 ②～③을 모두 갖춘 자
            ①-㉮ (청년) 19세 이상 39세 이하인 자
            ①-㉯ (사회초년생) 소득 업무 종사 기간이 7년 이내인 자
            ② 혼인 중이 아닐 것
            ③ 자동차 기준을 충족할 것
            """;
        var questions = List.of(question("y", "청년 신청자격", "19세 이상 39세 이하인 자"),
            question("e", "사회초년생 신청자격", "소득 업무 종사 기간이 7년 이내인 자"),
            question("s", "대학생 신청자격", "대학 재학생"), question("section", "신청 기준 안내", section),
            new Question("common", "청약통장 제출 대상", "청년 계층 공통", SOURCE,
                List.of(new AnswerOption("YES", "제출 대상이에요"), new AnswerOption("NO", "해당하지 않아요"),
                    new AnswerOption("UNKNOWN", "잘 모르겠어요", true))),
            question("conditional", "추가 증빙 제출 대상", "청년 계층 해당자만 제출"),
            question("age", "청년 증빙 제출 대상", "청년 (19세이상∼39세이하)"));
        var docs = List.of(
            new DocumentTemplate("account", "청약통장", "common", "청년 계층 공통 · 안내: 입주 전 가입", "은행", null, SOURCE, List.of("YES")),
            new DocumentTemplate("extra", "추가 증빙", "conditional", "청년 계층 해당자만 제출 · 안내: 자격 변동이 있는 경우", "기관", null, SOURCE, List.of("YES")),
            new DocumentTemplate("age-proof", "청년 증빙", "age", "청년 (19세이상∼39세이하) · 안내: 본인 나이 확인", "기관", null, SOURCE, List.of("YES")));
        var result = ApplicantSituationCompiler.compile(content(questions, docs));
        assertEquals(List.of("YOUTH", "EARLY_CAREER"), result.documents().get(0).conditionAnswerValues());
        assertEquals("NOT_APPLICABLE", necessity(result, result.documents().get(0), Map.of("applicant-situation", "STUDENT")));
        assertEquals("CONDITIONAL", necessity(result, result.documents().get(0), Map.of("applicant-situation", "EARLY_CAREER")));
        assertEquals(List.of("YOUTH", "EARLY_CAREER"), result.questions().stream().filter(q -> q.id().equals("conditional")).findFirst().orElseThrow().applicability().answerValues());
        assertEquals(List.of("YOUTH"), result.questions().stream().filter(q -> q.id().equals("age")).findFirst().orElseThrow().applicability().answerValues());
        assertEquals(docs.get(1), result.documents().get(1));
        var incomplete = ApplicantSituationCompiler.compile(content(questions.stream().filter(q -> !q.id().equals("section")).toList(), docs));
        assertEquals(docs.get(0), incomplete.documents().get(0), "A group name alone cannot prove who belongs to it.");
        var exempt = new DocumentTemplate("account", "청약통장", "common",
            "청년 계층 공통 · 안내: 미가입자는 제출하지 않습니다.", "은행", null, SOURCE, List.of("YES"));
        var conditionalGroup = ApplicantSituationCompiler.compile(content(questions, List.of(exempt)));
        assertEquals(exempt, conditionalGroup.documents().get(0));
        assertEquals(List.of("YOUTH", "EARLY_CAREER"), conditionalGroup.questions().stream()
            .filter(q -> q.id().equals("common")).findFirst().orElseThrow().applicability().answerValues());
        assertEquals("NOT_APPLICABLE", necessity(conditionalGroup, exempt, Map.of("applicant-situation", "YOUTH", "common", "NO")));
    }

    private static final Evidence SOURCE = new Evidence("공고문 5쪽", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1#page=5", "2026-11-22");
    private static final List<AnswerOption> CHOICES = List.of(new AnswerOption("YES", "해당해요"),
        new AnswerOption("NO", "해당하지 않아요", true), new AnswerOption("UNKNOWN", "잘 모르겠어요", true));

    @Test
    void exactRecipientsUseSituationAnswersWithoutExpandingStudentToJobseeker() {
        var student = question("student", "대학생 신청자격", "대학에 재학 중이며 미혼인 무주택자");
        var jobseeker = question("jobseeker", "취업준비생 신청자격", "졸업한 지 2년 이내이며 미혼인 무주택자");
        var proof = question("proof", "재학증명서 제출 대상에 해당하나요?", "대학생");
        var conditional = question("parent-proof", "부모의 가족관계증명서 제출 대상에 해당하나요?", "대학생 중 부모와 등본이 다른 신청자");
        var required = new DocumentTemplate("enrollment", "재학증명서", "proof", "대학생 · 발급처: 해당 학교", "해당 학교", null, SOURCE, List.of("YES"));
        var extra = new DocumentTemplate("parent-family", "부모의 가족관계증명서", "parent-proof", "대학생 중 부모와 등본이 다른 신청자", "공식 발급처", null, SOURCE, List.of("YES"));
        var original = content(List.of(student, jobseeker, proof, conditional), List.of(required, extra));
        var result = ApplicantSituationCompiler.compile(original);
        assertEquals("applicant-situation", result.questions().get(0).id());
        assertEquals("취업준비생 · 졸업한 지 2년 이내이며 미혼인 무주택자", result.questions().get(0).options().get(1).label());
        assertTrue(result.questions().stream().noneMatch(q -> q.id().equals("proof")));
        var restricted = result.questions().stream().filter(q -> q.id().equals("parent-proof")).findFirst().orElseThrow();
        assertEquals(new QuestionApplicability("applicant-situation", List.of("STUDENT")), restricted.applicability());
        assertEquals(conditional.reason(), restricted.reason());
        assertEquals(conditional.options(), restricted.options());
        var document = result.documents().get(0);
        assertEquals("enrollment", document.id());
        assertEquals(List.of("STUDENT"), document.conditionAnswerValues());
        assertEquals("NOT_APPLICABLE", necessity(result, document, Map.of("applicant-situation", "JOBSEEKER")));
        assertEquals("CONDITIONAL", necessity(result, document, Map.of("applicant-situation", "STUDENT")));
        assertEquals("UNKNOWN", necessity(result, document, Map.of("applicant-situation", "UNKNOWN")));
        assertEquals(extra, result.documents().get(1));
        assertEquals("proof", original.documents().get(0).conditionQuestionId());
        validate(result);
        assertSame(result, ApplicantSituationCompiler.compile(result));
    }

    @Test
    void aReviewedTypeOrIncompleteCriterionCannotBecomeAConfirmedSituation() {
        var youth = question("youth", "청년 신청자격", "만 19세 이상 39세 이하인 미혼인 무주택자");
        var industrial = question("industrial", "산업단지 근로자 신청자격", "원문의 다음 기준에 해당하는 근로자");
        var incomplete = content(List.of(youth, industrial), List.of());
        assertSame(incomplete, ApplicantSituationCompiler.compile(incomplete));
        var reviewed = new Content("공식 안내", SOURCE, List.of(), List.of(youth, question("industrial", "산업단지 근로자 신청자격", "지정 산업단지에 근무하는 무주택자")),
            List.of(), List.of(), List.of(new ReviewIssue("type-review", "CONDITION", "유형 확인 필요", "자격 범위가 공식 자료에서 서로 달라요.",
            List.of("industrial"), List.of(), List.of(SOURCE))));
        assertSame(reviewed, ApplicantSituationCompiler.compile(reviewed));
    }

    @Test
    void aConflictingTypeFromTheRuleCompilerCannotBeOmittedFromTheSituationChoices() {
        var claims = List.of("대학생 신청자격: 미혼이며 대학에 재학 중인 무주택자", "청년 신청자격: 만 19세 이상 39세 이하인 미혼인 무주택자",
            "산업단지 근로자 신청자격: 지정 산업단지에 1년 이상 근무한 무주택자", "산업단지 근로자 신청자격: 지정 산업단지에 3년 이상 근무한 무주택자");
        var rules = claims.stream().map(line -> new AutomaticGuidanceCompiler.LocatedRule(GuidanceRuleGrammar.parse(line), SOURCE, line)).toList();
        var compiled = ScopedRuleGuidance.compile(rules, content(List.of(), List.of()), List.of());
        assertTrue(compiled.ready());
        assertEquals("산업단지 근로자 신청자격 확인 필요", compiled.content().questions().get(2).title());
        assertSame(compiled.content(), ApplicantSituationCompiler.compile(compiled.content()));
    }

    @Test
    void realMinorClauseCreatesOnlyItsVerifiedExceptionFollowupsAndProof() {
        String official = officialMinorClause();
        var common = new DocumentTemplate("registry", "주민등록표등본", null, "모든 신청자", "행정복지센터", null, SOURCE);
        var original = content(List.of(question("age", "성년 기준", official)), List.of(common));
        var result = ApplicantSituationCompiler.compile(original);
        assertEquals("만 19세 이상이에요", result.questions().get(0).options().get(0).label());
        assertTrue(result.questions().stream().noneMatch(q -> q.id().equals("age")), "the replaced age check must not remain an input");
        assertTrue(result.questions().get(0).reason().contains("동일한 세대별 주민등록표"));
        assertTrue(result.questions().get(0).reason().contains("외국인 부모가 대리하여 신청"));
        var p = new Preparation();
        p.questions = result.questions();
        p.answers = Map.of("applicant-situation", "ADULT");
        assertEquals(List.of("applicant-situation"), PreparationApplicability.visibleQuestions(p).stream().map(Question::id).toList());
        p.answers = Map.of("applicant-situation", "MINOR_SIBLINGS");
        assertEquals(List.of("applicant-situation", "applicant-minor-sibling-cause"), PreparationApplicability.visibleQuestions(p).stream().map(Question::id).toList());
        p.answers = Map.of("applicant-situation", "MINOR_FOREIGN_PARENT");
        assertEquals(List.of("applicant-situation", "applicant-minor-parent-proxy"), PreparationApplicability.visibleQuestions(p).stream().map(Question::id).toList());
        p.answers = Map.of("applicant-situation", "UNKNOWN");
        assertEquals(4, PreparationApplicability.visibleQuestions(p).size());
        assertEquals(common, result.documents().get(0));
        var report = result.documents().get(1);
        assertEquals("행방불명 신고접수증", report.title());
        assertEquals("CONDITIONAL", necessity(result, report, Map.of("applicant-minor-sibling-cause", "MISSING")));
        assertEquals("NOT_APPLICABLE", necessity(result, report, Map.of("applicant-minor-sibling-cause", "DEATH")));
        assertEquals("UNKNOWN", necessity(result, report, Map.of("applicant-minor-sibling-cause", "OTHER")));
        assertFalse(result.documents().stream().anyMatch(d -> d.title().contains("사망진단서")));
        validate(result);
    }

    @Test
    void bukguMinorParagraphKeepsHouseholdExceptionsAndOnlyTheirRequiredProof() {
        String official = """
            ※ 「민법」상 미성년자(19세 미만)는 공급 신청할 수 없습니다. 단, 아래의 어느 하나에 해당하는 경우에는
            미성년자도 공급 신청 가능합니다. (임대차계약 체결 시에는 법정대리인의 동의 또는 대리 필요)
            - 자녀가 있는 미성년 세대주(단, 동일한 세대별 주민등록표등본에 자녀가 등재되어야 함)
            - 직계존속의 사망, 실종선고, 행방불명(신고접수증으로 증빙) 등으로 형제·자매를 부양하여야 하는
            미성년 세대주(동일한 세대별 주민등록표등본에 형제, 자매가 등재되어 있어야 함)
            - 부 또는 모가 외국인인 한부모가족으로서 미성년 자녀(내국인)가 세대주인 경우
            (단, 이 경우 외국인 부모가 대리하여 신청)
            """;
        var common = new DocumentTemplate("registry", "주민등록표등본", null, "모든 신청자", "행정복지센터", null, SOURCE);
        var original = content(List.of(question("age", "성년 기준", official)), List.of(common));
        var result = ApplicantSituationCompiler.compile(original);
        assertEquals("applicant-situation", result.questions().get(0).id());
        assertEquals("만 19세 이상이에요", result.questions().get(0).options().get(0).label());
        assertEquals("같은 등본에 있는 자녀를 부양하는 미성년 세대주예요", result.questions().get(0).options().get(1).label());
        assertEquals("부모·조부모 등의 사망·실종·행방불명으로 같은 등본의 형제자매를 부양하는 미성년 세대주예요",
            result.questions().get(0).options().get(2).label());
        var preparation = new Preparation();
        preparation.questions = result.questions();
        preparation.answers = Map.of("applicant-situation", "ADULT");
        assertEquals(List.of("applicant-situation"), PreparationApplicability.visibleQuestions(preparation).stream().map(Question::id).toList());
        preparation.answers = Map.of("applicant-situation", "MINOR_FOREIGN_PARENT");
        assertEquals(List.of("applicant-situation", "applicant-minor-parent-proxy", "applicant-minor-consent"),
            PreparationApplicability.visibleQuestions(preparation).stream().map(Question::id).toList());
        var report = result.documents().stream().filter(d -> d.title().equals("행방불명 신고접수증")).findFirst().orElseThrow();
        assertEquals("CONDITIONAL", necessity(result, report, Map.of("applicant-situation", "MINOR_SIBLINGS", "applicant-minor-sibling-cause", "MISSING")));
        assertEquals("NOT_APPLICABLE", necessity(result, report, Map.of("applicant-situation", "MINOR_SIBLINGS", "applicant-minor-sibling-cause", "DEATH")));
        assertEquals(common, result.documents().get(0));
        validate(result);
        var denied = content(List.of(question("age", "성년 기준", official.replace("신청 가능합니다", "신청 가능하지 않습니다"))), List.of());
        assertSame(denied, ApplicantSituationCompiler.compile(denied));
        var incomplete = content(List.of(question("age", "성년 기준", official.replace("(내국인)", ""))), List.of());
        assertSame(incomplete, ApplicantSituationCompiler.compile(incomplete));
        var noReport = content(List.of(question("age", "성년 기준", official.replace("신고접수증으로 증빙", "신고접수증으로 증빙하지 않음"))), List.of());
        assertTrue(ApplicantSituationCompiler.compile(noReport).documents().isEmpty());
    }

    @Test
    void partialOrConflictingMinorTextStaysAsTheOriginalSourceCheck() {
        String official = officialMinorClause();
        var incomplete = content(List.of(question("age", "성년 기준", official.replace("외국인 부모가 대리하여 신청", "기관에 확인하여 신청"))), List.of());
        assertSame(incomplete, ApplicantSituationCompiler.compile(incomplete));
        var conflicting = content(List.of(question("age", "성년 기준", official), question("amended-age", "성년 기준", official.replace("19세", "18세"))), List.of());
        assertSame(conflicting, ApplicantSituationCompiler.compile(conflicting));
        var withoutProof = content(List.of(question("age", "성년 기준", official.replaceAll("신고\\s*접수증으로\\s*증빙", "별도 자료로 증빙"))), List.of());
        assertTrue(ApplicantSituationCompiler.compile(withoutProof).documents().isEmpty());
        var deniedProof = content(List.of(question("age", "성년 기준", official.replaceAll("신고\\s*접수증으로\\s*증빙해야하며", "신고접수증으로 증빙하지 않으며"))), List.of());
        assertTrue(ApplicantSituationCompiler.compile(deniedProof).documents().isEmpty());
        var held = new Content("공식 안내", SOURCE, List.of(), List.of(question("source", "공식 기준과 예외", official),
            question("age-review", "연령 기준 확인 필요", "공식 정정 자료의 연령 범위를 확인해야 해요.")), List.of(), List.of(),
            List.of(new ReviewIssue("age-review-issue", "CONDITION", "연령 기준 확인 필요", "정정 전후 연령 범위가 달라요.",
                List.of("age-review"), List.of(), List.of(SOURCE))));
        assertSame(held, ApplicantSituationCompiler.compile(held));
    }

    @Test
    void anExistingMissingReportCannotBeCancelledByItsReplacedSelfAssessment() {
        var report = new DocumentTemplate("kept-report", "행방불명 신고접수증", "old-report-question", "행방불명으로 같은 등본의 형제자매를 부양하는 미성년 세대주", "공식 접수 기관",
            null, SOURCE, List.of("YES"));
        var unrelated = new DocumentTemplate("other-report", "분실 신고접수증", "other-report-question", "신분증을 분실한 경우", "공식 접수 기관",
            null, SOURCE, List.of("YES"));
        var original = content(List.of(question("age", "성년 기준", officialMinorClause()),
            question("old-report-question", "행방불명 신고접수증 제출 대상에 해당하나요?", "미성년 예외를 확인하세요."),
            question("other-report-question", "분실 신고접수증이 필요한가요?", "신분증 분실 여부를 확인하세요.")), List.of(report, unrelated));
        var result = ApplicantSituationCompiler.compile(original);
        var reconnected = result.documents().get(0);
        assertEquals("kept-report", reconnected.id());
        assertEquals(report.evidence(), reconnected.evidence());
        assertEquals(report.method(), reconnected.method());
        assertEquals("CONDITIONAL", necessity(result, reconnected, Map.of("applicant-situation", "MINOR_SIBLINGS",
            "applicant-minor-sibling-cause", "MISSING", "old-report-question", "NO")));
        assertTrue(result.questions().stream().noneMatch(q -> q.id().equals("old-report-question")));
        assertEquals(unrelated, result.documents().get(1));
        validate(result);
        var otherPurpose = new DocumentTemplate(report.id(), report.title(), report.conditionQuestionId(), "행방불명 가족을 심사 대상에서 제외하려는 신청자",
            report.issuer(), report.issueUrl(), report.evidence(), report.conditionAnswerValues());
        var otherScope = content(original.questions(), List.of(otherPurpose));
        assertSame(otherScope, ApplicantSituationCompiler.compile(otherScope));
        var commonReport = new DocumentTemplate(report.id(), report.title(), null, "모든 신청자", report.issuer(), null, report.evidence());
        var commonScope = content(original.questions(), List.of(commonReport));
        assertSame(commonScope, ApplicantSituationCompiler.compile(commonScope));
    }

    private static Question question(String id, String title, String text) {
        return new Question(id, title, text, SOURCE, CHOICES);
    }

    @Test
    void alternateOfficialWordingPreservesHouseholdCourtDeclarationAndProxyRequirements() {
        String text = officialMinorClause().replace("주민등록표에 자녀", "주민등록표등본에 자녀")
            .replace("사망, 실종,", "사망, 실종선고,").replace("대리하여 신청", "대리 신청");
        var compiled = ApplicantSituationCompiler.compile(content(List.of(question("age", "성년 기준", text)), List.of()));
        assertTrue(compiled.questions().get(0).options().stream().filter(o -> o.value().equals("MINOR_CHILD"))
            .allMatch(o -> o.label().contains("같은 등본")));
        var sibling = compiled.questions().stream().filter(q -> q.id().equals("applicant-minor-sibling-cause")).findFirst().orElseThrow();
        assertEquals("부모·조부모 등에 대한 실종선고를 받았어요", sibling.options().stream()
            .filter(o -> o.value().equals("DISAPPEARANCE")).findFirst().orElseThrow().label());
        assertTrue(compiled.questions().stream().anyMatch(q -> q.id().equals("applicant-minor-parent-proxy")));
    }

    private static Content content(List<Question> questions, List<DocumentTemplate> documents) {
        return new Content("공식 안내", SOURCE, List.of(), questions, documents, List.of());
    }

    private static String necessity(Content content, DocumentTemplate document, Map<String, ?> answers) {
        return PreparationRules.necessity(document.conditionQuestionId(), document.conditionAnswerValues(), answers, content.questions());
    }

    private static void validate(Content content) {
        content.questions().forEach(QuestionAnswers::validateQuestion);
        AnswerDependencies.validate(content);
    }

    private record Fixture(Draft draft, Content original) {
    }

    @Test
    void realReextractedBookletProducesAgeSituationsFromTheCompleteOfficialSource() throws Exception {
        try (var stream = getClass().getResourceAsStream("/review/multichild-2026-source.json")) {
            var fixture = new ObjectMapper().readValue(stream, Fixture.class);
            var draft = fixture.draft();
            // The fixture preserves all seven physical pages; regenerate excerpts with the real producer.
            draft = new Draft(draft.id(), draft.noticeId(), draft.revision(), draft.status(), draft.checkedAt(), draft.message(),
                draft.sources().stream().map(s -> new Source(s.label(), s.url(), s.sha256(), s.status(), s.message(),
                    org.jibro.collection.extraction.ExcerptClassifier.extract(s.context(), s.url()), s.context())).toList());
            var result = GuidanceReviewCompiler.assess(draft, fixture.original());
            assertTrue(result.ready(), result.issues().toString());
            var content = result.candidate();
            var first = content.questions().get(0);
            assertEquals("신청자는 어떤 상황인가요?", first.title());
            assertEquals("만 19세 이상이에요", first.options().get(0).label());
            assertTrue(first.options().stream().anyMatch(o -> o.value().equals("MINOR_FOREIGN_PARENT")));
            assertFalse(content.questions().stream().anyMatch(q -> q.id().equals("applicant-minor-parent-proxy")),
                "This notice requires legal representative consent, but does not specify foreign-parent proxy submission.");
            var consent = content.questions().stream().filter(q -> q.id().equals("applicant-minor-consent")).findFirst().orElseThrow();
            assertEquals(List.of("MINOR_CHILD", "MINOR_SIBLINGS", "MINOR_FOREIGN_PARENT"), consent.applicability().answerValues());
            assertTrue(content.questions().stream().anyMatch(q -> q.reason().contains("신생아가구")), "The full ranking table must remain available.");
            assertTrue(content.questions().stream().noneMatch(q -> q.reason().contains("⟦")), "Parser metadata is not public guidance.");
            assertTrue(content.questions().stream().anyMatch(q -> q.reason().contains("표 안내 · 구분: 1순위 / 유형: 신생아가구")));
            validate(content);
        }
    }

    @Test
    void explicitTypeRowsStillGenerateSituationsInsideAnOtherwiseProseNotice() {
        var source = new Source("공식 모집공고.pdf", SOURCE.url(), "a".repeat(64), "EXTRACTED", "완료", List.of(),
            List.of(new Part("공고문 1쪽", """
                1. 신청자격
                대학생 신청자격: 대학에 재학 중이며 미혼인 무주택자
                청년 신청자격: 만 19~39세이며 미혼인 무주택자
                청년 주택 요건: 신청자 본인이 무주택이어야 합니다.
                안내를 읽기만 한 것으로 신청 자격이 확정되지는 않습니다.
                2. 제출서류
                재학증명서 제출 대상: 대학생 · 발급처: 해당 학교
                주민등록표등본 제출 대상: 모든 신청자 · 발급처: 행정복지센터
                3. 모집일정
                접수기간: 2026.11.01 ~ 2026.12.31
                """)));
        var draft = new Draft("source", "notice", 1, "READY", java.time.Instant.parse("2026-11-22T00:00:00Z"), "완료", List.of(source));
        var result = GuidanceReviewCompiler.assess(draft, content(List.of(), List.of()));
        assertTrue(result.ready(), result.issues().toString());
        assertEquals("applicant-situation", result.candidate().questions().get(0).id());
        var proof = result.candidate().documents().stream().filter(d -> d.title().equals("재학증명서")).findFirst().orElseThrow();
        assertEquals("CONDITIONAL", necessity(result.candidate(), proof, Map.of("applicant-situation", "STUDENT")));
        assertEquals("NOT_APPLICABLE", necessity(result.candidate(), proof, Map.of("applicant-situation", "YOUTH")));
        var common = result.candidate().documents().stream().filter(d -> d.title().equals("주민등록표등본")).findFirst().orElseThrow();
        assertNull(common.conditionQuestionId());
        assertEquals("REQUIRED", necessity(result.candidate(), common, Map.of("applicant-situation", "YOUTH")));
        validate(result.candidate());
    }

    private static String officialMinorClause() {
        try (var stream = ApplicantSituationCompilerTest.class.getResourceAsStream("/review/iksan-waiver-20261104.json")) {
            var fixture = new ObjectMapper().readValue(stream, Fixture.class);
            String page = fixture.draft().sources().stream().filter(s -> s.label().endsWith(".pdf")).flatMap(s -> s.context().stream())
                .filter(p -> p.location().equals("공고문 5쪽")).findFirst().orElseThrow().text();
            int start = page.indexOf("■ 성년자"), end = page.indexOf('■', start + 1);
            return page.substring(start, end < 0 ? page.length() : end).strip();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void longTypedCriterionRemainsASeparateQuestionInsteadOfAnOverlongSituationOption() {
        String full = "만 19세 이상 39세 이하이며 혼인 중이 아닌 사람. " + "세대 구성과 소득·자동차 심사 범위의 예외도 확인합니다. ".repeat(12);
        var original = content(List.of(question("long-youth", "청년 신청자격", full),
            question("married", "신혼부부 신청자격", "혼인 중인 사람")), List.of());
        var result = ApplicantSituationCompiler.compile(original);
        assertEquals("applicant-situation", result.questions().get(0).id());
        var youth = result.questions().get(0).options().stream().filter(o -> o.value().equals("YOUTH")).findFirst().orElseThrow();
        assertEquals("청년으로 신청해요", youth.label());
        var criteria = result.questions().stream().filter(q -> q.id().equals("long-youth")).findFirst().orElseThrow();
        assertEquals(full, criteria.reason());
        assertEquals(List.of("YOUTH"), criteria.applicability().answerValues());
        assertEquals(CHOICES, criteria.options());
        validate(result);
    }
}
