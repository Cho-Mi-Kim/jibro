package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;

import org.jibro.auth.AuthService;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.review.GuidanceReviewCompiler;
import org.jibro.notice.NoticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OneTimeCheckIntegrationTest {
    @Autowired
    NoticeService notices;
    @Autowired
    PreparationService saved;
    @Autowired
    OneTimeCheckService once;
    @Autowired
    AuthService auth;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper json;
    @Autowired
    MockMvc mvc;
    UUID notice;
    String owner;
    final Evidence evidence = new Evidence("미저장 검증 근거", null, "2026-11-07");

    @BeforeEach
    void setup() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("미저장 테스트", email, "test-password-123");
        owner = auth.login(email, "test-password-123").user().id();
        notice = notices.insert(UUID.randomUUID().toString(), "미저장 검증 공고", "기관", "서울", "테스트",
            LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content());
        notices.samplePublish(notice, "PASS");
    }

    Content content() {
        return new Content("검증용", evidence, List.of(), List.of(new Question("house", "조건 확인", "공식 기준 확인", evidence)),
            List.of(new DocumentTemplate("common", "공통 서류", null, "방법", "기관", null, evidence),
                new DocumentTemplate("conditional", "조건별 서류", "house", "방법", "기관", null, evidence)),
            List.of(new ScheduleTemplate("apply", "신청 접수 마감", LocalDate.now().plusDays(30).toString(), evidence)));
    }

    String personalState() {
        return db.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(p) ORDER BY id), '[]'::jsonb)::text FROM preparation p", String.class)
            + db.queryForObject("SELECT count(*) FROM preparation_history", Long.class)
            + db.queryForObject("SELECT count(*) FROM app_user", Long.class);
    }

    @Test
    void automaticallyCompiledSituationsReachSavedAndAnonymousFlowsWithoutLosingScope() throws Exception {
        String url = "https://apply.lh.or.kr/test-applicant-guidance.pdf";
        var source = new Source("연결 시험.pdf", url, "a".repeat(64), "EXTRACTED", "test fixture", List.of(), List.of(
            new Part("공고문 1쪽", "청년 신청자격: 만 19세 이상 39세 이하인 미혼자\n산업단지 근로자 신청자격: 해당 산업단지에서 근무 중인 사람\n청년 주택요건: 신청자 본인이 무주택자여야 합니다.\n산업단지 근로자 주택요건: 세대구성원이 무주택자여야 합니다.\n공통 제출서류: 주민등록등본\n재직증명서 제출 대상: 산업단지 근로자\n부모 가족관계증명서 제출 대상: 청년 중 부모와 등본이 다른 신청자\n접수기간: 2026.11.10 ~ 2026.12.08")));
        var hwp = new Source("연결 시험.hwpx", url.replace(".pdf", ".hwpx"), "b".repeat(64), "EXTRACTED", "test fixture", List.of(),
            List.of(new Part("문서 구간 1", source.context().get(0).text().replace("등본이 다른", "등본이\n다른"))));
        var original = new Content("자동 질문 연결 시험", new Evidence("원문", url, "2026-11-22"),
            List.of(new Evidence(hwp.label(), hwp.url(), "2026-11-22")), List.of(), List.of(), List.of());
        var compiled = GuidanceReviewCompiler.assess(new Draft("test", "test", 1, "READY", java.time.Instant.parse("2026-11-22T00:00:00Z"), "", List.of(source, hwp)), original);
        assertTrue(compiled.ready(), compiled.issues().toString());
        var generated = notices.insert(UUID.randomUUID().toString(), "자동 질문 연결 시험", "기관", "서울", "테스트",
            LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), compiled.candidate());
        notices.samplePublish(generated, "PASS");
        var started = saved.start(owner, generated, 1);
        assertEquals("applicant-situation", started.questions.get(0).id());
        var answers = Map.<String, Object>of("applicant-situation", "YOUTH");
        var recorded = saved.answers(owner, UUID.fromString(started.id), started.revision, answers);
        String before = personalState();
        var response = mvc.perform(post("/api/v1/notices/" + generated + "/check-once").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("version", 1, "answers", answers))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var actual = json.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        var onceQuestions = json.treeToValue(actual.get("questions"), Question[].class);
        assertEquals(recorded.questions, Arrays.asList(onceQuestions));
        var onceDocuments = Arrays.asList(json.treeToValue(actual.get("documents"), Document[].class));
        assertEquals("NOT_APPLICABLE", recorded.documents.stream().filter(d -> d.title.equals("재직증명서")).findFirst().orElseThrow().necessity);
        assertEquals("NOT_APPLICABLE", onceDocuments.stream().filter(d -> d.title.equals("재직증명서")).findFirst().orElseThrow().necessity);
        assertEquals("REQUIRED", onceDocuments.stream().filter(d -> d.title.equals("주민등록등본")).findFirst().orElseThrow().necessity);
        var family = onceDocuments.stream().filter(d -> d.title.equals("부모 가족관계증명서")).findFirst().orElseThrow();
        assertEquals("UNKNOWN", family.necessity);
        String familyQuestion = compiled.candidate().documents().stream().filter(d -> d.id().equals(family.id)).findFirst().orElseThrow().conditionQuestionId();
        assertTrue(recorded.questions.stream().anyMatch(q -> q.id().equals(familyQuestion)
            && q.applicability().answerValues().equals(List.of("YOUTH"))));
        assertTrue(recorded.questions.stream().anyMatch(q -> q.title().contains("산업단지 근로자 주택")
            && q.applicability() != null && q.applicability().answerValues().equals(List.of("INDUSTRIAL"))));
        assertEquals(before, personalState());
    }

    @Test
    void anonymousCheckHasNoStoreAndDoesNotCreateAccountsOrPersonalRecords() throws Exception {
        String before = personalState();
        mvc.perform(post("/api/v1/notices/" + notice + "/check-once").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("version", 1, "answers", Map.of("house", "YES")))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.answers.house").value("YES"))
            .andExpect(jsonPath("$.documents[1].necessity").value("CONDITIONAL"))
            .andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.nextAction").doesNotExist());
        assertEquals(before, personalState());
    }

    @Test
    void actualBookletSituationsUseTheSameBranchingInSavedAndAnonymousChecks() throws Exception {
        var fixture = json.readTree(getClass().getResourceAsStream("/review/multichild-2026-source.json"));
        var raw = json.treeToValue(fixture.get("draft"), Draft.class);
        var draft = new Draft(raw.id(), raw.noticeId(), raw.revision(), raw.status(), raw.checkedAt(), raw.message(),
            raw.sources().stream().map(s -> new Source(s.label(), s.url(), s.sha256(), s.status(), s.message(),
                org.jibro.collection.extraction.ExcerptClassifier.extract(s.context(), s.url()), s.context())).toList());
        var compiled = GuidanceReviewCompiler.assess(draft, json.treeToValue(fixture.get("original"), Content.class));
        assertTrue(compiled.ready(), compiled.issues().toString());
        var generated = notices.insert(UUID.randomUUID().toString(), "실제 다자녀 공고 발췌 연결 검사", "LH", "전국", "전세임대",
            LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), compiled.candidate());
        notices.samplePublish(generated, "PASS");
        var started = saved.start(owner, generated, 1);
        assertEquals("신청자는 어떤 상황인가요?", started.questions.get(0).title());
        var minor = saved.answers(owner, UUID.fromString(started.id), started.revision,
            Map.of("applicant-situation", "MINOR_SIBLINGS", "applicant-minor-sibling-cause", "DEATH"));
        assertTrue(PreparationApplicability.visibleQuestions(minor).stream().anyMatch(q -> q.id().equals("applicant-minor-consent")));
        var adult = saved.answers(owner, UUID.fromString(started.id), minor.revision, Map.of("applicant-situation", "ADULT"));
        assertFalse(PreparationApplicability.visibleQuestions(adult).stream().anyMatch(q -> q.id().startsWith("applicant-minor-")));
        String before = personalState();
        var result = once.evaluate(generated, 1, Map.of("applicant-situation", "ADULT"));
        assertEquals(adult.questions, result.questions());
        assertEquals(adult.documents.stream().map(d -> d.id + ":" + d.necessity).toList(),
            result.documents().stream().map(d -> d.id + ":" + d.necessity).toList());
        assertEquals(before, personalState());
    }

    @Test
    void completeBundleHoldsUnreconciledListingAndVerifiedAttachmentsReachBothFlows() throws Exception {
        var fixture = json.readTree(getClass().getResourceAsStream("/review/sangok-a1-source.json"));
        var captured = json.treeToValue(fixture.get("draft"), Draft.class);
        var sources = new ArrayList<Source>();
        for (var source : captured.sources()) {
            String resource = source.url().endsWith("fileid=69385652") ? "/extraction/lh-sangok-69385652.hwpx"
                : source.url().endsWith("fileid=69385666") ? "/extraction/lh-sangok-69385666.pdf" : null;
            if (resource == null) sources.add(source);
            else try (var stream = getClass().getResourceAsStream(resource)) {
                var parts = new org.jibro.collection.extraction.BoundedDocumentParser().parse(stream.readAllBytes(),
                    resource.endsWith(".pdf") ? "application/pdf" : "application/zip");
                sources.add(new Source(source.label(), source.url(), source.sha256(), "EXTRACTED", "공식 원형 재판독",
                    org.jibro.collection.extraction.ExcerptClassifier.extract(parts, source.url()), parts));
            }
        }
        var draft = new Draft(captured.id(), captured.noticeId(), captured.revision(), captured.status(), captured.checkedAt(), captured.message(), sources);
        assertEquals(7, draft.sources().size());
        var original = json.treeToValue(fixture.get("original"), Content.class);
        var complete = GuidanceReviewCompiler.assess(draft, original);
        assertFalse(complete.ready());
        assertTrue(complete.issues().contains("EVIDENCE_MULTIPLE_GOVERNING_SOURCES_REQUIRE_REVIEW"), complete.issues().toString());
        assertFalse(complete.issues().stream().anyMatch(issue -> issue.startsWith("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED")), complete.issues().toString());
        // The listing paraphrases requirements and is still awaiting reconciliation. It must not
        // be automatically approved. Separately verify the actual six mutually reconciled files
        // through the consumer boundary, without pretending that this publishes the full bundle.
        var attachments = sources.stream().filter(source -> !source.url().equals(original.evidence().url())).toList();
        assertEquals(6, attachments.size());
        var main = attachments.stream().filter(source -> source.url().endsWith("fileid=69385652")).findFirst().orElseThrow();
        var verified = new Draft(draft.id(), draft.noticeId(), draft.revision(), draft.status(), draft.checkedAt(), draft.message(), attachments);
        var fileContent = new Content(original.summary(), new Evidence(main.label(), main.url(), original.evidence().checkedAt()),
            original.attachments(), original.questions(), original.documents(), original.schedules(), original.reviewIssues());
        var compiled = GuidanceReviewCompiler.assess(verified, fileContent);
        assertTrue(compiled.ready(), compiled.issues().toString());
        var generated = notices.insert(UUID.randomUUID().toString(), "전체 공식 첨부 연결 검사", "LH", "인천", "행복주택",
            LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), compiled.candidate());
        notices.samplePublish(generated, "PASS");
        var started = saved.start(owner, generated, 1);
        assertEquals("applicant-situation", started.questions.get(0).id());
        var married = saved.answers(owner, UUID.fromString(started.id), started.revision,
            Map.of("applicant-situation", "NEWLYWED"));
        var form = married.documents.stream().filter(d -> d.title.contains("예비신혼부부 신청 확인서")).findFirst().orElseThrow();
        assertEquals("NOT_APPLICABLE", form.necessity);
        var future = saved.answers(owner, UUID.fromString(started.id), married.revision,
            Map.of("applicant-situation", "PREMARITAL"));
        assertEquals("CONDITIONAL", future.documents.stream().filter(d -> d.id.equals(form.id)).findFirst().orElseThrow().necessity);
        assertFalse(PreparationApplicability.visibleQuestions(future).stream().anyMatch(q -> q.title().contains("신청 기준 ②")));
        assertTrue(PreparationApplicability.visibleQuestions(future).stream().anyMatch(q -> q.title().contains("신청 기준 ⑥")));
        String before = personalState();
        var response = mvc.perform(post("/api/v1/notices/" + generated + "/check-once").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("version", 1, "answers", Map.of("applicant-situation", "PREMARITAL")))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var actual = json.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(json.valueToTree(future.questions), actual.get("questions"));
        var documents = Arrays.asList(json.treeToValue(actual.get("documents"), Document[].class));
        assertEquals(future.documents.stream().map(d -> d.id + ":" + d.necessity).toList(),
            documents.stream().map(d -> d.id + ":" + d.necessity).toList());
        assertEquals("REQUIRED", documents.stream().filter(d -> d.title.equals("가족관계증명서")).findFirst().orElseThrow().necessity);
        assertEquals(before, personalState());
    }

    @Test
    void actualBukguDetachedTablesCompileTheCompleteBundleIntoSavedAndAnonymousPreparation() throws Exception {
        var fixture = json.readTree(getClass().getResourceAsStream("/review/bukgu-source.json"));
        var captured = json.treeToValue(fixture.get("draft"), Draft.class);
        var sources = new ArrayList<Source>();
        for (var source : captured.sources()) {
            String file = source.url().endsWith("fileid=69296396") ? "lh-bukgu-69296396.hwpx"
                : source.url().endsWith("fileid=69296405") ? "lh-bukgu-69296405.pdf"
                : source.url().endsWith("fileid=69296407") ? "lh-bukgu-69296407.pdf" : null;
            if (file == null) sources.add(source);
            else try (var stream = getClass().getResourceAsStream("/extraction/" + file)) {
                assertNotNull(stream);
                var parts = new org.jibro.collection.extraction.BoundedDocumentParser().parse(stream.readAllBytes(),
                    file.endsWith(".pdf") ? "application/pdf" : "application/zip");
                sources.add(new Source(source.label(), source.url(), source.sha256(), "EXTRACTED", "공식 원형 재판독",
                    org.jibro.collection.extraction.ExcerptClassifier.extract(parts, source.url()), parts));
            }
        }
        assertEquals(4, sources.size());
        var draft = new Draft(captured.id(), captured.noticeId(), captured.revision(), captured.status(), captured.checkedAt(), captured.message(), sources);
        var original = json.treeToValue(fixture.get("original"), Content.class);
        assertTrue(original.questions().isEmpty());
        assertTrue(original.documents().isEmpty());
        var compiled = GuidanceReviewCompiler.assess(draft, original);
        assertTrue(compiled.ready(), compiled.issues().toString());
        var candidate = compiled.candidate();
        assertEquals("applicant-situation", candidate.questions().get(0).id());
        var announcements = candidate.schedules().stream().filter(event -> event.title().contains("결과 발표"))
            .sorted(java.util.Comparator.comparing(ScheduleTemplate::date)).toList();
        assertEquals(List.of("2026-12-22T17:00", "2027-01-27T17:00"), announcements.stream().map(ScheduleTemplate::date).toList());
        assertTrue(announcements.get(0).title().contains("1순위") && announcements.get(0).title().contains("우선"));
        assertTrue(announcements.get(1).title().contains("1순위") && announcements.get(1).title().contains("일반")
            && announcements.get(1).title().contains("2순위"));
        assertTrue(announcements.stream().allMatch(event -> event.timingDescription() != null && event.timingDescription().contains("이후")));
        assertEquals(List.of("ADULT", "MINOR_CHILD", "MINOR_SIBLINGS", "MINOR_FOREIGN_PARENT", "NONE", "UNKNOWN"),
            candidate.questions().get(0).options().stream().map(AnswerOption::value).toList());
        String visibleText = candidate.questions().stream().map(Question::reason).collect(java.util.stream.Collectors.joining("\n"))
            + candidate.documents().stream().map(DocumentTemplate::method).collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(visibleText.matches("(?s).*(?:⟦(?:PDF|HWPX):|rowbands=|colbands=|doubleleft=|doubleright=|doubletop=|doublebottom=).*"));
        // Test the captured active period without changing dates anywhere in the official sources.
        UUID generated = notices.insert(UUID.randomUUID().toString(), "실제 북구 전체 원형 자동 연결 검사", "LH", "광주", "매입임대",
            LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), candidate);
        notices.samplePublish(generated, "PASS");
        var recorded = saved.start(owner, generated, 1);
        var family = candidate.documents().stream().filter(d -> d.title().equals("신청자의 가족관계증명서")).findFirst().orElseThrow();
        assertNotNull(family.conditionQuestionId());
        List<Map<String, Object>> situations = List.of(Map.of("applicant-situation", "ADULT", family.conditionQuestionId(), "NO"),
            Map.of("applicant-situation", "MINOR_SIBLINGS", "applicant-minor-sibling-cause", "MISSING", family.conditionQuestionId(), "YES"),
            Map.of("applicant-situation", "MINOR_FOREIGN_PARENT", family.conditionQuestionId(), "UNKNOWN"),
            Map.of("applicant-situation", "UNKNOWN", "applicant-minor-sibling-cause", "UNKNOWN", family.conditionQuestionId(), "UNKNOWN"));
        for (var answers : situations) {
            recorded = saved.answers(owner, UUID.fromString(recorded.id), recorded.revision, answers);
            String before = personalState();
            var response = mvc.perform(post("/api/v1/notices/" + generated + "/check-once").with(csrf()).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("version", 1, "answers", recorded.answers))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
            var actual = json.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(json.valueToTree(recorded.questions), actual.get("questions"));
            var documents = Arrays.asList(json.treeToValue(actual.get("documents"), Document[].class));
            assertEquals(recorded.documents.stream().map(d -> d.id + ":" + d.necessity).toList(),
                documents.stream().map(d -> d.id + ":" + d.necessity).toList());
            for (String title : List.of("매입임대공급 신청서", "개인정보 수집･이용 및 제3자 제공 동의서", "신청자의 주민등록표등본"))
                assertEquals("REQUIRED", documents.stream().filter(d -> d.title.equals(title)).findFirst().orElseThrow().necessity);
            assertEquals(switch ((String) answers.get(family.conditionQuestionId())) {
                case "YES" -> "CONDITIONAL";
                case "NO" -> "NOT_APPLICABLE";
                default -> "UNKNOWN";
            }, documents.stream().filter(d -> d.id.equals(family.id())).findFirst().orElseThrow().necessity);
            String situation = (String) answers.get("applicant-situation");
            var resultDates = PreparationApplicability.visibleSchedules(recorded).stream()
                .filter(event -> event.title.contains("결과 발표")).map(event -> event.date).sorted().toList();
            assertEquals(List.of("2026-12-22T17:00", "2027-01-27T17:00"), resultDates);
            var questions = PreparationApplicability.visibleQuestions(recorded);
            assertEquals(Set.of("MINOR_SIBLINGS", "UNKNOWN").contains(situation),
                questions.stream().anyMatch(q -> q.id().equals("applicant-minor-sibling-cause")));
            var visibleDocuments = PreparationApplicability.visibleDocuments(recorded);
            assertEquals(Set.of("MINOR_SIBLINGS", "UNKNOWN").contains(situation),
                visibleDocuments.stream().anyMatch(d -> d.id.equals("applicant-minor-missing-report")));
            if (situation.equals("MINOR_SIBLINGS"))
                assertEquals("CONDITIONAL", documents.stream().filter(d -> d.id.equals("applicant-minor-missing-report")).findFirst().orElseThrow().necessity);
            if (situation.equals("UNKNOWN"))
                assertEquals("UNKNOWN", documents.stream().filter(d -> d.id.equals("applicant-minor-missing-report")).findFirst().orElseThrow().necessity);
            assertEquals(before, personalState());
        }
    }

    @Test
    void fullyAutomaticSevenSourceGuideReachesBothConsumersWithApplicantAndFormScopes() throws Exception {
        var assessment = org.jibro.collection.review.SuwonSupplementarySources.assess(org.jibro.collection.review.SuwonSupplementarySources.sources());
        assertTrue(assessment.ready(), assessment.issues().toString());
        var candidate = assessment.candidate();
        var generated = notices.insert(UUID.randomUUID().toString(), "자동 대조한 수원 안내", "LH", "경기", "행복주택",
            assessment.startDate(), assessment.endDate(), LocalDate.of(2026, 11, 18), candidate);
        notices.samplePublish(generated, "PASS");
        var recorded = saved.start(owner, generated, 1);
        for (String type : List.of("STUDENT", "YOUTH", "HOUSING_BENEFIT", "UNKNOWN")) {
            var answers = Map.<String, Object>of("applicant-situation", type, "application-channel", "ONLINE", "application-attendee", "SELF", "returning-student", "YES", "accessibility-request", "NO");
            recorded = saved.answers(owner, UUID.fromString(recorded.id), recorded.revision, answers);
            String before = personalState();
            var response = mvc.perform(post("/api/v1/notices/" + generated + "/check-once").with(csrf()).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("version", 1, "answers", answers))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
            var actual = json.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            var documents = Arrays.asList(json.treeToValue(actual.get("documents"), Document[].class));
            assertEquals(recorded.documents.stream().map(d -> d.id + ":" + d.necessity).toList(), documents.stream().map(d -> d.id + ":" + d.necessity).toList());
            var visible = PreparationApplicability.visibleQuestions(recorded);
            assertEquals(!type.equals("HOUSING_BENEFIT"), visible.stream().anyMatch(q -> q.id().equals("applicant-own-home")));
            assertEquals(Set.of("STUDENT", "UNKNOWN").contains(type), visible.stream().anyMatch(q -> q.id().equals("returning-student")));
            // Original answers stay in the snapshot; visibility, rather than deleting the saved
            // YES answer of another type, owns the personalized document list.
            assertEquals("CONDITIONAL", documents.stream().filter(d -> d.id.equals("form-return-study")).findFirst().orElseThrow().necessity);
            assertEquals(Set.of("STUDENT", "UNKNOWN").contains(type), PreparationApplicability.visibleDocuments(recorded).stream().anyMatch(d -> d.id.equals("form-return-study")));
            assertEquals("NOT_APPLICABLE", documents.stream().filter(d -> d.id.equals("accessibility-form")).findFirst().orElseThrow().necessity);
            assertEquals(before, personalState());
        }
        recorded = saved.answers(owner, UUID.fromString(recorded.id), recorded.revision, Map.of("applicant-situation", "YOUTH", "application-attendee", "PROXY", "accessibility-request", "YES"));
        var proxy = recorded.documents.stream().filter(d -> d.method.contains("위임인은 자필")).findFirst().orElseThrow();
        assertEquals("CONDITIONAL", proxy.necessity);
        assertEquals("CONDITIONAL", recorded.documents.stream().filter(d -> d.id.equals("accessibility-resident")).findFirst().orElseThrow().necessity);
        assertTrue(recorded.reviewIssues.stream().anyMatch(i -> i.id().startsWith("auto-visit-source-hours")));
        var application = recorded.documents.stream().filter(d -> d.title.equals("공급신청서")).findFirst().orElseThrow();
        assertTrue(application.reviewRequired); // A source conflict remains visible even for an online answer.
    }

    @Test
    void sourceReviewedSuwonSupplementaryFormsReachSavedAndAnonymousResultsWithoutWritingAnonymousAnswers() throws Exception {
        // This is the explicitly source-reviewed publication, not proof of whole-bundle automatic approval.
        var reviewed = json.readTree(java.nio.file.Files.readString(
            java.nio.file.Path.of("../data/notices/suwon-supplementary-review-20261123.json")));
        var content = json.treeToValue(reviewed.get("content"), Content.class);
        var generated = notices.insert(UUID.randomUUID().toString(), "수원 공식 부속서식 소비 검사", "LH", "경기", "행복주택",
            LocalDate.now().minusDays(1), LocalDate.now().plusDays(30), LocalDate.now(), content);
        notices.samplePublish(generated, "PASS");
        var recorded = saved.start(owner, generated, 1);
        recorded = saved.answers(owner, UUID.fromString(recorded.id), recorded.revision,
            Map.of("application-type", "STUDENT", "application-route", "ONLINE", "contract-visitor", "SELF", "accessibility-request", "YES"));
        var ids = Set.of("accessibility-form", "accessibility-proof", "accessibility-resident");
        for (var doc : recorded.documents.stream().filter(d -> ids.contains(d.id)).toList())
            assertEquals("CONDITIONAL", doc.necessity);
        assertEquals(3, PreparationApplicability.visibleDocuments(recorded).stream().filter(d -> ids.contains(d.id)).count());
        var resident = recorded.documents.stream().filter(d -> d.id.equals("accessibility-resident")).findFirst().orElseThrow();
        assertTrue(resident.method.contains("뒤 7자리"));
        assertTrue(recorded.documents.stream().filter(d -> d.id.equals("privacy")).findFirst().orElseThrow().reviewRequired);
        assertEquals(3, recorded.reviewIssues.size());
        recorded = saved.document(owner, UUID.fromString(recorded.id), resident.id, recorded.revision, DocumentState.READY, "등본 가림 확인");
        for (String answer : List.of("NO", "UNKNOWN", "YES")) {
            String before = personalState();
            var answers = new HashMap<String, Object>(recorded.answers);
            answers.put("accessibility-request", answer);
            var response = mvc.perform(post("/api/v1/notices/" + generated + "/check-once").with(csrf()).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("version", 1, "answers", answers))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
            var actual = json.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            var documents = Arrays.asList(json.treeToValue(actual.get("documents"), Document[].class));
            String necessity = switch (answer) {
                case "YES" -> "CONDITIONAL";
                case "NO" -> "NOT_APPLICABLE";
                default -> "UNKNOWN";
            };
            assertEquals(List.of(necessity, necessity, necessity), documents.stream().filter(d -> ids.contains(d.id)).map(d -> d.necessity).toList());
            assertTrue(documents.stream().filter(d -> ids.contains(d.id)).allMatch(d -> d.note.isEmpty() && d.state != DocumentState.READY));
            assertEquals(before, personalState());
            recorded = saved.answers(owner, UUID.fromString(recorded.id), recorded.revision, Map.of("accessibility-request", answer));
            assertEquals(documents.stream().filter(d -> ids.contains(d.id)).map(d -> d.id + ":" + d.necessity).toList(),
                recorded.documents.stream().filter(d -> ids.contains(d.id)).map(d -> d.id + ":" + d.necessity).toList());
            assertEquals("등본 가림 확인", recorded.documents.stream().filter(d -> d.id.equals(resident.id)).findFirst().orElseThrow().note);
        }
    }

    @Test
    void sameRulesAsSavedFlowAndExistingAnswersNotesRemainUntouched() {
        var preparation = saved.start(owner, notice, 1);
        preparation = saved.answers(owner, UUID.fromString(preparation.id), preparation.revision, Map.of("house", "YES"));
        preparation = saved.document(owner, UUID.fromString(preparation.id), "conditional", preparation.revision, DocumentState.READY, "기존 메모");
        String before = personalState();
        var result = once.evaluate(notice, 1, Map.of("house", "NO"));
        assertEquals("NOT_APPLICABLE", result.documents().get(1).necessity);
        assertEquals(before, personalState());
        var matching = once.evaluate(notice, 1, Map.of("house", "YES"));
        assertEquals(preparation.answers, matching.answers());
        assertEquals(preparation.documents.get(1).necessity, matching.documents().get(1).necessity);
        assertEquals("", matching.documents().get(1).note);
        assertEquals(before, personalState());
    }

    @Test
    void requiresCsrfAndRejectsInvalidAnswersAndStaleVersionWithoutWrites() throws Exception {
        String path = "/api/v1/notices/" + notice + "/check-once";
        String before = personalState();
        mvc.perform(post(path).contentType("application/json").content("{\"version\":1,\"answers\":{}}"))
            .andExpect(status().isForbidden());
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("house", 10), Map.<String, Object>of("fake", "YES")))
            mvc.perform(post(path).with(csrf()).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("version", 1, "answers", invalid))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(csrf()).contentType("application/json").content("{\"version\":2,\"answers\":{}}"))
            .andExpect(status().isConflict());
        assertEquals(before, personalState());
    }

    @Test
    void unreviewedPrivateAndClosedNoticesCannotBeEvaluated() throws Exception {
        int revision = notices.reviews().stream().filter(review -> review.id().equals(notice.toString())).findFirst().orElseThrow().revision();
        notices.review(notice, owner, revision, "PASS", "HOLD", "재검수");
        mvc.perform(post("/api/v1/notices/" + notice + "/check-once").with(csrf()).contentType("application/json").content("{\"version\":1,\"answers\":{}}"))
            .andExpect(status().isConflict());
        revision = notices.reviews().stream().filter(review -> review.id().equals(notice.toString())).findFirst().orElseThrow().revision();
        notices.review(notice, owner, revision, "HOLD", "HOLD", "비공개");
        mvc.perform(post("/api/v1/notices/" + notice + "/check-once").with(csrf()).contentType("application/json").content("{\"version\":1,\"answers\":{}}"))
            .andExpect(status().isNotFound());
        UUID closed = notices.insert(UUID.randomUUID().toString(), "마감된 검증 공고", "기관", "서울", "테스트",
            LocalDate.now().minusDays(3), LocalDate.now().minusDays(1), LocalDate.now().minusDays(3), content());
        notices.samplePublish(closed, "PASS");
        mvc.perform(post("/api/v1/notices/" + closed + "/check-once").with(csrf()).contentType("application/json").content("{\"version\":1,\"answers\":{}}"))
            .andExpect(status().isConflict());
    }
}
