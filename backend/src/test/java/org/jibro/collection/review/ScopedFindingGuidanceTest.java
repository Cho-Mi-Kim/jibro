package org.jibro.collection.review;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.GuidanceIssues;
import org.junit.jupiter.api.Test;

class ScopedFindingGuidanceTest {
    private static final String URL = "https://apply.lh.or.kr/notice.pdf";
    private static final Evidence PAGE = new Evidence("모집공고.pdf · 공고문 8쪽", URL, "2026-11-08");
    private static final Evidence OTHER_PAGE = new Evidence("모집공고.pdf · 공고문 9쪽", URL, "2026-11-08");
    private static final String INCOME = "WAIVED_INCOME_DOCUMENT_SCOPE_UNRESOLVED";
    private static final String FORM = "FORM_PUBLICATION_DATE_MISMATCH";
    private static final String SCORING = "FIRST_COME_SCORING_DOCUMENT_SCOPE_UNRESOLVED";

    private Draft draft(String text) {
        return new Draft("draft", "notice", 1, "READY", Instant.parse("2026-11-08T00:00:00Z"), "",
                List.of(new Source("모집공고.pdf", URL, "a".repeat(64), "EXTRACTED", "", List.of(),
                        List.of(new Part("공고문 8쪽", text), new Part("공고문 9쪽", "다른 페이지의 같은 문서명")))));
    }

    private DocumentTemplate doc(String id, String name, String question) {
        return new DocumentTemplate(id, name, question, "공식 안내", "LH", null, PAGE);
    }

    private Content content(List<Question> questions, List<DocumentTemplate> docs) {
        return new Content("공식 준비 안내", PAGE, List.of(), questions, docs, List.of());
    }

    private GuidanceFinding finding(String code) {
        return new GuidanceFinding(code, "공식 원문의 적용 범위가 불명확합니다.", PAGE);
    }

    @Test
    void formMismatchScopesOnlyExactExistingFormOnTheBoundPage() {
        var candidate = content(List.of(), List.of(doc("waiver", "입주자격완화 입주자 확인서", null),
                doc("finance", "금융정보 제공 동의서", null), doc("other-form", "다른 확인서", null)));
        var result = ScopedFindingGuidance.scope(draft("입주자격완화 입주자 확인서\n공고(26.10.08)에 따른 입주자로서"), candidate, List.of(finding(FORM)));
        assertTrue(result.blockers().isEmpty());
        assertEquals(List.of("waiver"), result.reviewIssues().get(0).documentIds());
        assertEquals(List.of(PAGE), result.reviewIssues().get(0).evidence());
    }

    @Test
    void similarFormNameIsNotAnExactSourceMatch() {
        var candidate = content(List.of(), List.of(doc("waiver", "입주자격완화 확인서", null)));
        var result = ScopedFindingGuidance.scope(draft("입주자격완화 적용 확인서"), candidate, List.of(finding(FORM)));
        assertEquals(List.of(FORM), result.blockers());
        assertTrue(result.reviewIssues().isEmpty());
    }

    @Test
    void incompleteCandidateDocumentCoverageCannotDiscardTheOtherUnresolvedDocument() {
        var candidate = content(List.of(), List.of(doc("pregnancy", "임신진단서", null)));
        var result = ScopedFindingGuidance.scope(draft("태아·양자의 소득 가구원수: 임신진단서, 입양관계증명서"), candidate, List.of(finding(INCOME)));
        assertEquals(List.of(INCOME), result.blockers());
        assertTrue(result.reviewIssues().isEmpty());
    }

    @Test
    void exactScopeIncludesTheSourceQuestionAndDependentDocumentsButNotUnrelatedQuestions() {
        var questions = List.of(new Question("applies", "임신진단서 제출 대상인가요?", "공식 범위를 확인합니다.", PAGE),
                new Question("section", "서류 전체 구간 확인", "소득 가구원수의 임신진단서와 입양관계증명서 제출 기준", PAGE),
                new Question("unrelated", "주택 기준 확인", "주택 가액 기준", PAGE),
                new Question("elsewhere", "다른 구간 확인", "임신진단서 예시", OTHER_PAGE));
        var candidate = content(questions, List.of(doc("pregnancy", "임신진단서", "applies"),
                doc("adoption", "입양관계증명서", null), doc("extra", "추가 증빙", "section"), doc("registry", "주민등록등본", null)));
        var result = ScopedFindingGuidance.scope(draft("임신진단서·입양관계증명서"), candidate, List.of(finding(INCOME)));
        assertTrue(result.blockers().isEmpty());
        var issue = result.reviewIssues().get(0);
        assertEquals(List.of("applies", "section"), issue.questionIds());
        assertEquals(List.of("pregnancy", "adoption", "extra"), issue.documentIds());
        var scoped = new Content(candidate.summary(), PAGE, List.of(), questions, candidate.documents(), List.of(), result.reviewIssues());
        assertDoesNotThrow(() -> GuidanceIssues.validate(scoped));
        assertFalse(scoped.questions().get(2).reviewRequired());
        assertFalse(scoped.questions().get(3).reviewRequired());
    }

    @Test
    void explicitlySplitGeneralAndBonusDocumentsKeepGeneralProofAvailable() {
        var questions = List.of(new Question("q-general", "일반 가족관계증명서 제출 대상인가요?", "가족관계를 증명합니다.", PAGE),
                new Question("q-bonus", "가산점용 가족관계증명서 제출 대상인가요?", "가산점 신청 대상입니다.", PAGE));
        var candidate = content(questions, List.of(doc("general", "일반 가족관계증명서", "q-general"),
                doc("bonus", "가산점용 가족관계증명서", "q-bonus")));
        var result = ScopedFindingGuidance.scope(draft("가족관계증명서는 육아지원세대배점을 받고자 할 때 제출"), candidate, List.of(finding(SCORING)));
        assertTrue(result.blockers().isEmpty());
        assertEquals(List.of("bonus"), result.reviewIssues().get(0).documentIds());
        assertEquals(List.of("q-bonus"), result.reviewIssues().get(0).questionIds());
    }

    @Test
    void genericCombinedFamilyDocumentRemainsUnresolvedInFull() {
        var candidate = content(List.of(), List.of(doc("family", "가족관계증명서", null)));
        var result = ScopedFindingGuidance.scope(draft("가족관계증명서: 일반 가족관계 및 가산점 제출 대상"), candidate, List.of(finding(SCORING)));
        assertEquals(List.of("family"), result.reviewIssues().get(0).documentIds());
    }

    @Test
    void differentSourceOrUnboundLocationCannotProduceScopedApproval() {
        var candidate = content(List.of(), List.of(doc("pregnancy", "임신진단서", null)));
        for (Evidence evidence : List.of(new Evidence(PAGE.label(), "https://apply.lh.or.kr/other.pdf", PAGE.checkedAt()),
                new Evidence("모집공고.pdf", URL, PAGE.checkedAt()), new Evidence(PAGE.label(), URL, "2026-11-07"))) {
            var result = ScopedFindingGuidance.scope(draft("임신진단서"), candidate, List.of(new GuidanceFinding(INCOME, "범위 불명확", evidence)));
            assertEquals(List.of(INCOME), result.blockers());
            assertTrue(result.reviewIssues().isEmpty());
        }
    }

    @Test
    void fragmentMustIdentifyTheExactPageOrParagraphOfTheCurrentAttachment() {
        String url = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
        var part = new Part("공고문 8쪽", "이번 모집의 임신진단서 제출 대상");
        for (String label : List.of("모집공고.pdf", "모집공고.hwpx")) {
            var source = new Source(label, url, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(part));
            var draft = new Draft("d", "n", 1, "READY", Instant.parse("2026-11-08T00:00:00Z"), "", List.of(source));
            String anchor = EvidenceLocationUrl.forSource(source, part.location(), part.text());
            assertNotEquals(url, anchor);
            for (boolean fresh : List.of(true, false)) {
                String link = fresh ? anchor : anchor.replace("a".repeat(64), "b".repeat(64));
                var evidence = new Evidence(label + " · " + part.location(), link, PAGE.checkedAt());
                var result = ScopedFindingGuidance.scope(draft, content(List.of(), List.of(doc("preg", "임신진단서", null))),
                        List.of(new GuidanceFinding(INCOME, "제출 대상 불일치", evidence)));
                assertEquals(fresh, result.blockers().isEmpty());
                assertEquals(fresh ? 1 : 0, result.reviewIssues().size());
            }
        }
    }

    @Test
    void globalPublicationConflictAndUnknownFindingStayGlobalBlockers() {
        var candidate = content(List.of(), List.of(doc("form", "입주자 확인서", null)));
        var result = ScopedFindingGuidance.scope(draft("입주자 확인서"), candidate,
                List.of(finding("PUBLICATION_DATE_CONFLICT"), finding("UNKNOWN_NEW_FINDING")));
        assertEquals(List.of("PUBLICATION_DATE_CONFLICT", "UNKNOWN_NEW_FINDING"), result.blockers());
        assertTrue(result.reviewIssues().isEmpty());
    }

    @Test
    void duplicateFindingHasAStableSingleIssueWithoutLosingDistinctDetails() {
        var candidate = content(List.of(), List.of(doc("pregnancy", "임신진단서", null)));
        var different = new GuidanceFinding(INCOME, "두 번째 원문 불일치 설명", PAGE);
        var result = ScopedFindingGuidance.scope(draft("임신진단서"), candidate, List.of(finding(INCOME), finding(INCOME), different));
        assertEquals(1, result.reviewIssues().size());
        assertTrue(result.reviewIssues().get(0).detail().contains(different.message()));
        assertEquals(result.reviewIssues().get(0).id(), ScopedFindingGuidance.scope(draft("임신진단서"), candidate,
                List.of(finding(INCOME))).reviewIssues().get(0).id());
    }
}
