package org.jibro.collection.review.ai;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.jibro.collection.review.local.CompactReviewPacket;
import static org.jibro.collection.review.local.CompactReviewPacket.*;
import static org.jibro.collection.review.ai.ScopedReviewModels.*;
import static org.jibro.collection.review.ai.ScopedReviewValidator.valid;
import static org.junit.jupiter.api.Assertions.*;

class ScopedReviewValidatorTest {
    @Test void acceptsOneDecisionForEveryIssueWithReferencesInsideItsClosedContext() {
        var packet = packet(List.of(issue("income", "pdf-income", "hwp-income"), issue("waiver", "waiver")),
                "pdf-income", "hwp-income", "waiver");
        assertTrue(valid(packet, List.of(
                decision("waiver", Disposition.UNRESOLVED, "waiver"),
                decision("income", Disposition.CONFIRMED_CONFLICT, "pdf-income", "hwp-income"))));
        assertTrue(valid(packet, List.of(
                decision("income", Disposition.NO_CONFLICT, "pdf-income", "hwp-income"),
                decision("waiver", Disposition.NO_CONFLICT, "waiver"))));
    }

    @Test void rejectsMissingInventedAndRepeatedIssues() {
        var packet = packet(List.of(issue("income", "c1"), issue("asset", "c2")), "c1", "c2");
        var income = decision("income", Disposition.NO_CONFLICT, "c1");
        assertFalse(valid(packet, List.of(income)));
        assertFalse(valid(packet, List.of(income, income)));
        assertFalse(valid(packet, List.of(income, decision("invented", Disposition.NO_CONFLICT, "c2"))));
        assertFalse(valid(packet, List.of(income, decision("asset", Disposition.NO_CONFLICT, "c2"), income)));
    }

    @Test void cannotBorrowEvidenceFromAnUnrelatedIssueOrInventClauseIds() {
        var packet = packet(List.of(issue("income", "c1"), issue("asset", "c2")), "c1", "c2");
        var asset = decision("asset", Disposition.NO_CONFLICT, "c2");
        assertFalse(valid(packet, List.of(decision("income", Disposition.NO_CONFLICT, "missing"), asset)));
        assertFalse(valid(packet, List.of(decision("income", Disposition.NO_CONFLICT, "c2"), asset)));
        assertFalse(valid(packet, List.of(decision("income", Disposition.UNRESOLVED), asset)));
    }

    @Test void conflictNeedsAtLeastTwoDifferentExistingClauses() {
        var packet = packet(List.of(issue("income", "c1", "c2")), "c1", "c2");
        assertFalse(valid(packet, List.of(decision("income", Disposition.CONFIRMED_CONFLICT, "c1"))));
        assertFalse(valid(packet, List.of(decision("income", Disposition.CONFIRMED_CONFLICT, "c1", "c1"))));
        assertFalse(valid(packet, List.of(decision("income", Disposition.CONFIRMED_CONFLICT, "c1", "missing"))));
        assertTrue(valid(packet, List.of(decision("income", Disposition.CONFIRMED_CONFLICT, "c1", "c2"))));
    }

    @Test void rejectsAmbiguousPacketIdsAndMissingContextRatherThanOverwritingThem() {
        var decision = List.of(decision("income", Disposition.NO_CONFLICT, "c1"));
        assertFalse(valid(packet(List.of(issue("income", "c1")), "c1", "c1"), decision));
        assertFalse(valid(packet(List.of(issue("income", "c1", "missing")), "c1"), decision));
        assertFalse(valid(packet(List.of(issue("income", "c1", "c1")), "c1"), decision));
        assertFalse(valid(packet(List.of(issue("income")), "c1"), decision));
        assertFalse(valid(packet(List.of(issue("income", "c1"), issue("income", "c2")), "c1", "c2"),
                List.of(decision.get(0), decision("income", Disposition.NO_CONFLICT, "c2"))));
    }

    @Test void rejectsEmptyOrMalformedAdvisoryResults() {
        var packet = packet(List.of(issue("income", "c1")), "c1");
        assertFalse(valid(null, List.of()));
        assertFalse(valid(packet, null));
        assertFalse(valid(packet(List.of()), List.of()));
        assertFalse(valid(packet, Arrays.asList((Decision) null)));
        assertFalse(valid(packet, List.of(new Decision("income", null, List.of("c1")))));
        assertFalse(valid(packet, List.of(new Decision(null, Disposition.NO_CONFLICT, List.of("c1")))));
    }

    private static CompactReviewPacket packet(List<Issue> issues, String... ids) {
        return new CompactReviewPacket("packet-test", "local-compact-test", List.of(
                new SourceRef("source", "https://apply.lh.or.kr/notice.pdf", "sha256")),
                Arrays.stream(ids).map(ScopedReviewValidatorTest::clause).toList(), List.of(), issues);
    }

    private static EvidenceClause clause(String id) {
        return new EvidenceClause(id, "source", "1쪽", 0, 4, "공식 근거", "신청 조건", List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static Issue issue(String id, String... clauseIds) {
        return new Issue(id, "SCOPED_CONTEXT", List.of(clauseIds));
    }

    private static Decision decision(String id, Disposition disposition, String... evidence) {
        return new Decision(id, disposition, List.of(evidence));
    }
}
