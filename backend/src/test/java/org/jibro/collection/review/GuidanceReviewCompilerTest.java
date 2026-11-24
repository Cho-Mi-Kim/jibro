package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import org.jibro.notice.QuestionAnswers;
import org.jibro.preparation.PreparationRules;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GuidanceReviewCompilerTest {
    record Fixture(Draft draft, Content original) {
    }

    private static Fixture fixture() {
        try (var stream = GuidanceReviewCompilerTest.class.getResourceAsStream("/review/iksan-waiver-20261104.json")) {
            return new ObjectMapper().readValue(stream, Fixture.class);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void actualOfficialNoticeProducesCompactTypedDraftButNeverApprovesItsUnresolvedEvidence() {
        var fixture = fixture();
        var result = GuidanceReviewCompiler.assess(fixture.draft(), fixture.original());
        assertEquals(NationalRentalGuidancePreview.PROFILE, result.profile());
        assertNotNull(result.candidate());
        assertEquals(14, result.candidate().questions().size());
        assertEquals(19, result.candidate().documents().size());
        assertFalse(result.ready());
        assertTrue(result.issues().stream().anyMatch(i -> i.startsWith("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:")));
        assertTrue(result.issues().contains("FORM_PUBLICATION_DATE_MISMATCH"));
        assertTrue(result.issues().contains("WAIVED_INCOME_DOCUMENT_SCOPE_UNRESOLVED"));
        assertTrue(result.issues().contains("FIRST_COME_SCORING_DOCUMENT_SCOPE_UNRESOLVED"));
        assertEquals(2, result.findings().stream().filter(f -> f.code().equals("WAIVED_INCOME_DOCUMENT_SCOPE_UNRESOLVED")).count(),
                "one finding per PDF/HWPX source; the typed preview must not add its PDF finding twice");
        assertEquals(2, result.findings().stream().filter(f -> f.code().equals("FIRST_COME_SCORING_DOCUMENT_SCOPE_UNRESOLVED")).count());
        assertEquals(LocalDate.of(2026, 11, 25), result.startDate());
        assertEquals(LocalDate.of(2026, 11, 26), result.endDate());
        assertFalse(result.candidate().schedules().isEmpty());
        result.candidate().questions().forEach(QuestionAnswers::validateQuestion);
        assertTrue(result.candidate().questions().stream().allMatch(q -> q.reason().length() <= 2000));
        assertTrue(result.candidate().questions().stream().allMatch(q -> q.evidence().label().contains("쪽")));
    }

    @Test
    void waivedIncomeAndAssetsDoNotReappearAsNumericQuestionsOrRegionalRanking() {
        var f = fixture();
        var result = NationalRentalGuidancePreview.compile(f.draft(), f.original());
        assertNotNull(result);
        assertTrue(result.candidate().questions().stream().noneMatch(q -> q.inputKind().equals("NUMBER") || q.title().contains("소득") || q.title().contains("순위")));
        String car = question(result.candidate(), "vehicle").reason();
        for (String part : List.of("세대원", "4,542만원", "최고 가액", "비영업용", "장애인", "보철용", "보조금", "합산액과 다릅니다"))
            assertTrue(car.contains(part), part);
        assertTrue(question(result.candidate(), "housing").reason().contains("모두 충족"));
        assertTrue(question(result.candidate(), "adult").options().stream().anyMatch(o -> o.value().equals("MINOR_PARENT")));
        assertTrue(result.candidate().documents().stream().anyMatch(d -> d.title().contains("금융정보") && d.conditionQuestionId() == null));
        assertTrue(result.candidate().documents().stream().noneMatch(d -> d.title().contains("임신") || d.title().contains("입양") || d.title().contains("자산보유")));
    }

    @Test
    void alternativeProxyMethodsActivateOnlyChosenDocumentBundleAndUnknownRemainsUnknown() {
        var f = fixture();
        var content = NationalRentalGuidancePreview.compile(f.draft(), f.original()).candidate();
        var seal = content.documents().stream().filter(d -> d.id().equals("nr-doc-seal-visit")).findFirst().orElseThrow();
        var signature = content.documents().stream().filter(d -> d.id().equals("nr-doc-signature-visit")).findFirst().orElseThrow();
        assertEquals("CONDITIONAL", necessity(content, seal, "SEAL"));
        assertEquals("NOT_APPLICABLE", necessity(content, signature, "SEAL"));
        assertEquals("CONDITIONAL", necessity(content, signature, "SIGNATURE"));
        assertEquals("NOT_APPLICABLE", necessity(content, seal, "SIGNATURE"));
        assertEquals("UNKNOWN", necessity(content, seal, "UNKNOWN"));
        assertEquals("UNKNOWN", PreparationRules.necessity(seal.conditionQuestionId(), seal.conditionAnswerValues(), Map.of(), content.questions()));
        assertTrue(question(content, "family-certificate").needsFollowUp("REVIEW"));
        assertTrue(question(content, "adult").needsFollowUp("MINOR_CHILD"));
    }

    @Test
    void templateDoesNotGeneralizeIncomeWaiverToRegularOrRenewalOnlyEligibility() {
        var f = fixture();
        var noWaiver = mutate(f.draft(), p -> p.replace("소득요건 배제", "소득기준 적용"));
        assertNull(NationalRentalGuidancePreview.compile(noWaiver, f.original()));
        var sources = f.draft().sources().stream().map(s -> s.label().endsWith(".pdf")
                ? new Source(s.label(), s.url(), s.sha256(), s.status(), s.message(), s.excerpts(),
                s.context().stream().map(p -> p.location().equals("공고문 7쪽") ? new Part(p.location(), p.text().replace("4,542", "4,000")) : p).toList()) : s).toList();
        assertNull(NationalRentalGuidancePreview.compile(withSources(f.draft(), sources), f.original()));
    }

    @Test
    void aFormDateMismatchIsNotConfusedWithHistoricalLegislationOrRenewalDates() {
        var f = fixture();
        var result = OfficialFormConsistency.inspect(f.draft());
        assertEquals(2, result.stream().filter(x -> x.code().equals("FORM_PUBLICATION_DATE_MISMATCH")).count());
        assertTrue(result.stream().allMatch(x -> x.message().contains("2026-10-08") && x.message().contains("2026-11-04")));
        var fixed = mutate(f.draft(), p -> p.replace("26.10.08", "26.11.04"));
        assertTrue(OfficialFormConsistency.inspect(fixed).isEmpty());
    }

    @Test
    void correctingOnlyTheFormDateDoesNotApproveOtherUnresolvedDocumentScopes() {
        var f = fixture();
        var fixed = mutate(f.draft(), p -> p.replace("26.10.08", "26.11.04"));
        var result = GuidanceReviewCompiler.assess(fixed, f.original());
        assertFalse(result.ready());
        assertFalse(result.issues().contains("FORM_PUBLICATION_DATE_MISMATCH"));
        assertTrue(result.issues().contains("WAIVED_INCOME_DOCUMENT_SCOPE_UNRESOLVED"));
    }

    @Test
    void historicalExamplePublicationDateDoesNotReplaceTheCurrentFrontMatterDate() {
        var f = fixture();
        var sources = f.draft().sources().stream().map(s -> s.label().endsWith(".pdf")
                ? new Source(s.label(), s.url(), s.sha256(), s.status(), s.message(), s.excerpts(),
                java.util.stream.Stream.concat(s.context().stream(), java.util.stream.Stream.of(new Part("공고문 37쪽", "과거 제도 설명 예시\n공고일:2025.01.01"))).toList()) : s).toList();
        var findings = OfficialFormConsistency.inspect(withSources(f.draft(), sources));
        assertFalse(findings.stream().anyMatch(finding -> finding.code().equals("PUBLICATION_DATE_CONFLICT")));
        assertTrue(findings.stream().anyMatch(finding -> finding.message().contains("2026-11-04")));
    }

    @Test
    void unknownOrUnreadSourcesCannotBeBypassedByHavingAUsefulCandidate() {
        var f = fixture();
        var sources = new java.util.ArrayList<>(f.draft().sources());
        sources.add(new Source("정정 공고.pdf", "https://apply.lh.or.kr/correction.pdf", null, "FAILED", "읽기 실패", List.of(), List.of()));
        var result = GuidanceReviewCompiler.assess(withSources(f.draft(), sources), f.original());
        assertFalse(result.ready());
        assertNotNull(result.candidate());
        assertTrue(result.issues().stream().anyMatch(i -> i.startsWith("AUTHORITATIVE_SOURCE_INCOMPLETE:")));
    }

    private static Question question(Content content, String key) {
        return content.questions().stream().filter(q -> q.id().equals("nr-" + key)).findFirst().orElseThrow();
    }

    private static String necessity(Content content, DocumentTemplate document, String answer) {
        return PreparationRules.necessity(document.conditionQuestionId(), document.conditionAnswerValues(), Map.of(document.conditionQuestionId(), answer), content.questions());
    }

    private static Draft mutate(Draft draft, UnaryOperator<String> change) {
        return withSources(draft, draft.sources().stream().map(s -> new Source(s.label(), s.url(), s.sha256(), s.status(), s.message(), s.excerpts(),
                s.context().stream().map(p -> new Part(p.location(), change.apply(p.text()))).toList())).toList());
    }

    private static Draft withSources(Draft draft, List<Source> sources) {
        return new Draft(draft.id(), draft.noticeId(), draft.revision(), draft.status(), draft.checkedAt(), draft.message(), sources);
    }
}
