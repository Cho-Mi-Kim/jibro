package org.jibro.collection.review.local;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.jibro.collection.review.local.LocalRuleBook.*;

class CompactReviewPlannerTest {
    private static final String URL = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
    private static final String HASH = "a".repeat(64);

    Clause clause(String id, String quote, List<String> related) {
        return new Clause(id, URL, HASH, "공고문 4쪽", 0, quote.length(), quote, "신청 자격",
            List.of(Topic.APPLICANT), List.of("신청자"), List.of("공고일 기준"), List.of(), List.of(), List.of(), related);
    }
    LocalRuleBook book(List<Clause> clauses, List<Fact> facts, List<Unresolved> issues) {
        return new LocalRuleBook("test-v1", clauses, facts, issues, 100_000);
    }
    Unresolved issue(String id) { return new Unresolved("SCOPE_REVIEW", List.of(id)); }

    @Test void independentSmallIssuesShareOnePacketAndJsonMetricsIncludeAllMetadata() {
        var a = clause("a", "신청자는 성년자이어야 합니다.\n‘성년’은 원문 기준입니다.", List.of());
        var b = clause("b", "미성년자는 예외에 해당하는지 확인합니다.", List.of());
        var result = CompactReviewPlanner.plan(book(List.of(a, b), List.of(), List.of(issue("a"), issue("b"))));
        assertEquals(1, result.packets().size());
        var packet = result.packets().get(0);
        assertEquals(2, packet.issues().size());
        assertEquals(a.quote(), packet.sourceTexts().stream().filter(s -> s.text().equals(a.quote())).findFirst().orElseThrow().text());
        assertEquals(packet.payloadJson().length(), result.metrics().transmittedCharacters());
        assertEquals(a.quote().length() + b.quote().length(), result.metrics().selectedCharacters());
        assertEquals(100_000, result.metrics().rawCharacters());
        assertTrue(packet.transmittedCharacters() <= 6000);
        assertTrue(result.blocked().isEmpty());
    }

    @Test void linkedScopeExceptionAndFactSupportStayTogetherAcrossPages() {
        var main = clause("main", "신청자는 성년자여야 합니다.", List.of("exception"));
        var exception = new Clause("exception", URL, HASH, "공고문 5쪽", 0, 18, "단, 미성년 세대주는 예외입니다.", "성년자",
            List.of(Topic.APPLICANT), List.of("미성년 세대주"), List.of(), List.of(Marker.EXCEPTION), List.of(), List.of(), List.of());
        var household = clause("household", "부양 가족은 같은 주민등록표에 있어야 합니다.", List.of());
        var fact = new Fact("adult", "AGE_SCOPE", Topic.APPLICANT, "신청자", Map.of("condition", "성년"), List.of("main", "household"));
        var result = CompactReviewPlanner.plan(book(List.of(main, exception, household), List.of(fact), List.of(issue("main"))));
        assertEquals(1, result.packets().size());
        assertEquals(Set.of("main", "exception", "household"), new HashSet<>(result.packets().get(0).issues().get(0).clauseIds()));
        assertEquals(3, result.packets().get(0).clauses().size());
        assertEquals(List.of(fact), result.packets().get(0).facts());
    }

    @Test void missingDependencyOrReferencedPageIsBlockedRatherThanInferred() {
        var missing = clause("a", "별지의 예외를 함께 적용합니다.", List.of("absent"));
        var a = CompactReviewPlanner.plan(book(List.of(missing), List.of(), List.of(issue("a"))));
        assertTrue(a.packets().isEmpty());
        assertEquals("DEPENDENCY_MISSING", a.blocked().get(0).reason());
        var b = CompactReviewPlanner.plan(book(List.of(clause("b", "4쪽 별도 기준을 적용합니다.", List.of())), List.of(),
            List.of(new Unresolved("REFERENCE_TARGET_MISSING", List.of("b")))));
        assertTrue(b.packets().isEmpty());
        assertEquals("INCOMPLETE_LOCAL_CONTEXT", b.blocked().get(0).reason());
    }

    @Test void aHeadingAndAnotherIssueCannotBypassAKnownMissingReferenceOnTheSameClause() {
        var heading = clause("heading", "신청 자격", List.of());
        var original = clause("rule", "자동차 예외는 공고문 9쪽을 참조하세요.", List.of("heading"));
        var rule = new Clause(original.id(), original.sourceUrl(), original.sourceSha256(), original.location(),
            original.start(), original.end(), original.quote(), original.heading(), original.topics(),
            original.subjects(), original.scopes(), List.of(Marker.REFERENCE), original.quantities(),
            List.of("page:9"), original.relatedClauseIds());
        var result = CompactReviewPlanner.plan(book(List.of(heading, rule), List.of(), List.of(
            new Unresolved("REFERENCE_TARGET_MISSING", List.of("rule")),
            new Unresolved("RULE_COMPLETENESS_REVIEW", List.of("rule")))));

        assertTrue(result.packets().isEmpty());
        assertEquals(2, result.blocked().size());
        assertTrue(result.blocked().stream().allMatch(blocked -> blocked.reason().equals("INCOMPLETE_LOCAL_CONTEXT")));
        assertEquals(0, result.metrics().transmittedCharacters());
    }

    @Test void missingContextPropagatesThroughRelatedClausesAndFactSupport() {
        for (String code : List.of("REFERENCE_TARGET_MISSING", "SOURCE_CONTEXT_INCOMPLETE", "SOURCE_UNREADABLE",
            "SOURCE_FAILED", "EXCEPTION_PARENT_UNRESOLVED")) {
            var unresolved = clause("missing", "누락된 예외와 함께 읽어야 합니다.", List.of("heading"));
            var heading = clause("heading", "신청 자격", List.of());
            var related = clause("related", "기본 요건입니다.", List.of("missing"));
            var supported = clause("supported", "신청자 기준입니다.", List.of());
            var fact = new Fact("f", "SCOPE", Topic.APPLICANT, "신청자", Map.of(), List.of("supported", "missing"));
            var result = CompactReviewPlanner.plan(book(List.of(unresolved, heading, related, supported), List.of(fact),
                List.of(new Unresolved(code, List.of("missing")), issue("related"), issue("supported"))));

            assertTrue(result.packets().isEmpty(), code);
            assertEquals(3, result.blocked().size(), code);
            assertTrue(result.blocked().stream().allMatch(blocked -> blocked.reason().equals("INCOMPLETE_LOCAL_CONTEXT")), code);
        }
    }

    @Test void oversizedExceptionCannotBeDroppedToFitTheApiBudget() {
        var main = clause("a", "소득 요건을 완화합니다.", List.of("exception"));
        var exception = clause("exception", "다만 ".repeat(1800), List.of());
        var result = CompactReviewPlanner.plan(book(List.of(main, exception), List.of(), List.of(issue("a"))));
        assertTrue(result.packets().isEmpty());
        assertEquals("CONTEXT_EXCEEDS_PACKET_LIMIT", result.blocked().get(0).reason());
        assertTrue(result.blocked().get(0).requiredCharacters() > 6000);
        assertEquals(0, result.metrics().transmittedCharacters());
    }

    @Test void identicalQuotesInDifferentSourcesOrScopesAreNotCollapsed() {
        var a = clause("a", "소득 100% 이하", List.of());
        var b = new Clause("b", URL + "4", "b".repeat(64), a.location(), a.start(), a.end(), a.quote(), a.heading(),
            a.topics(), List.of("다른 가구 범위"), a.scopes(), a.markers(), a.quantities(), a.references(), a.relatedClauseIds());
        var result = CompactReviewPlanner.plan(book(List.of(a, b), List.of(), List.of(new Unresolved("SOURCE_CONFLICT", List.of("a", "b")))));
        var packet = result.packets().get(0);
        assertEquals(2, packet.sources().size());
        assertEquals(2, packet.clauses().size());
        assertEquals(Set.of("a", "b"), new HashSet<>(packet.issues().get(0).clauseIds()));
    }

    @Test void onePassIsBoundedAndRemainingPacketsCanAdvanceWithoutResendingCompletedWork() {
        var clauses = new ArrayList<Clause>();
        var issues = new ArrayList<Unresolved>();
        for (int i = 0; i < 4; i++) {
            clauses.add(clause("c" + i, "적용 범위를 보존합니다. ".repeat(210), List.of()));
            issues.add(issue("c" + i));
        }
        var input = book(clauses, List.of(), issues);
        var first = CompactReviewPlanner.plan(input);
        assertEquals(3, first.packets().size());
        assertEquals(1, first.deferredPacketIds().size());
        var completed = new HashSet<>(first.packets().stream().map(CompactReviewPacket::id).toList());
        var next = CompactReviewPlanner.plan(input, completed);
        assertEquals(1, next.packets().size());
        assertEquals(first.deferredPacketIds().get(0), next.packets().get(0).id());
        assertEquals(3, next.metrics().completedPacketCount());
        assertTrue(next.deferredPacketIds().isEmpty());
        assertTrue(next.packets().get(0).transmittedCharacters() <= 6000);
    }

    @Test void deterministicPayloadCacheBindsModelPolicyAndExactEvidence() {
        var a = clause("a", "신청자 개인에게 적용됩니다.", List.of());
        var values = new LinkedHashMap<String, String>(); values.put("z", "last"); values.put("a", "first");
        var fact = new Fact("f", "SCOPE", Topic.APPLICANT, "신청자", values, List.of("a"));
        var input = book(List.of(a), List.of(fact), List.of(issue("a")));
        var packet = CompactReviewPlanner.plan(input).packets().get(0);
        var reverse = new Fact("f", "SCOPE", Topic.APPLICANT, "신청자", Map.of("a", "first", "z", "last"), List.of("a"));
        assertEquals(packet.payloadJson(), CompactReviewPlanner.plan(book(List.of(a), List.of(reverse), input.unresolved())).packets().get(0).payloadJson());
        assertNotEquals(packet.cacheKey("model-a"), packet.cacheKey("model-b"));
        var changedHash = new Clause(a.id(), a.sourceUrl(), "b".repeat(64), a.location(), a.start(), a.end(), a.quote(),
            a.heading(), a.topics(), a.subjects(), a.scopes(), a.markers(), a.quantities(), a.references(), a.relatedClauseIds());
        assertNotEquals(packet.cacheKey("model-a"), CompactReviewPlanner.plan(book(List.of(changedHash), List.of(fact), input.unresolved())).packets().get(0).cacheKey("model-a"));
    }

    @Test void resolvedLocalBookUsesNoApiAndUntrustedEvidenceNeverLeavesThePlanner() {
        var input = book(List.of(clause("a", "신청자를 기준으로 확인합니다.", List.of())), List.of(), List.of());
        assertTrue(CompactReviewPlanner.plan(input).packets().isEmpty());
        var c = input.clauses().get(0);
        var untrusted = new Clause(c.id(), "https://other.example/doc", HASH, c.location(), c.start(), c.end(), c.quote(),
            c.heading(), c.topics(), c.subjects(), c.scopes(), c.markers(), c.quantities(), c.references(), c.relatedClauseIds());
        var result = CompactReviewPlanner.plan(book(List.of(untrusted), List.of(), List.of(issue("a"))));
        assertTrue(result.packets().isEmpty());
        assertEquals("EVIDENCE_INVALID", result.blocked().get(0).reason());
    }

    @Test void excessiveIssuesSharingContextStillRespectTheResponseSchemaLimit() {
        var c = clause("a", "신청자를 기준으로 확인합니다.", List.of());
        var issues = new ArrayList<Unresolved>();
        for (int i = 0; i < 40; i++) issues.add(new Unresolved("SCOPE_" + i, List.of("a")));
        var result = CompactReviewPlanner.plan(book(List.of(c), List.of(), issues));
        assertEquals(40, result.packets().stream().mapToInt(packet -> packet.issues().size()).sum());
        assertTrue(result.packets().stream().allMatch(packet -> packet.issues().size() <= 32 && packet.transmittedCharacters() <= 6000));
    }

    @Test void genericUnsupportedProseStaysPendingLocallyWithoutSpendingTheApiBudget() {
        var a = clause("a", "소득 기준은 원문을 확인합니다.", List.of());
        var result = CompactReviewPlanner.plan(book(List.of(a), List.of(),
            List.of(new Unresolved("LOCAL_SCOPE_UNRESOLVED", List.of("a")))));
        assertTrue(result.packets().isEmpty());
        assertEquals("LOCAL_REVIEW_PENDING", result.blocked().get(0).reason());
        assertEquals(1, result.metrics().blockedIssueCount());
    }

    @Test void compactClauseAliasesRetainSourceSpansAndEveryEvidenceReference() {
        String full = "clause-" + "a".repeat(64);
        var a = clause(full, "예외 조건의 적용 범위를 검토합니다.", List.of());
        var fact = new Fact("f", "SCOPE", Topic.APPLICANT, "신청자", Map.of("scope", "exception"), List.of(full));
        var packet = CompactReviewPlanner.plan(book(List.of(a), List.of(fact), List.of(issue(full)))).packets().get(0);
        var evidence = packet.clauses().get(0);
        assertEquals("c" + "a".repeat(16), evidence.id());
        assertEquals(a.quote(), evidence.quote());
        assertEquals(a.start(), evidence.start());
        assertEquals(a.end(), evidence.end());
        assertEquals(List.of(evidence.id()), packet.issues().get(0).clauseIds());
        assertEquals(List.of(evidence.id()), packet.facts().get(0).clauseIds());
        assertFalse(packet.payloadJson().contains(full));
    }

    @Test void identicalCompleteReviewQuestionsAreSentOnceWithAllSourceClausesPreserved() {
        var a = clause("a", "성년 신청자를 기준으로 합니다.", List.of("b"));
        var b = clause("b", "다만 미성년 세대주는 예외입니다.", List.of("a"));
        var packet = CompactReviewPlanner.plan(book(List.of(a, b), List.of(), List.of(issue("a"), issue("b")))).packets().get(0);
        assertEquals(1, packet.issues().size());
        assertEquals(Set.of("a", "b"), new HashSet<>(packet.issues().get(0).clauseIds()));
        assertEquals(2, packet.clauses().size());
    }
}
