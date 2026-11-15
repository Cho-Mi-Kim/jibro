package org.jibro.collection.review.ai;

import java.util.*;
import org.jibro.collection.review.local.CompactReviewPacket;
import static org.jibro.collection.review.ai.ScopedReviewModels.*;

/** Validates citation boundaries only. Even NO_CONFLICT cannot clear source coverage or publish guidance. */
public final class ScopedReviewValidator {
    private ScopedReviewValidator() {}

    public static boolean valid(CompactReviewPacket packet, List<Decision> decisions) {
        if (packet == null || decisions == null || packet.issues().isEmpty()
                || packet.issues().size() > ScopedReviewSchema.MAX_DECISIONS
                || decisions.size() != packet.issues().size()) return false;

        var clauseIds = new HashSet<String>();
        for (var clause : packet.clauses()) {
            if (!ScopedReviewSchema.validId(clause.id()) || !clauseIds.add(clause.id())) return false;
        }
        var issueContexts = new HashMap<String, Set<String>>();
        for (var issue : packet.issues()) {
            if (!ScopedReviewSchema.validId(issue.id()) || issue.clauseIds().isEmpty()) return false;
            var context = new HashSet<>(issue.clauseIds());
            if (context.size() != issue.clauseIds().size() || !clauseIds.containsAll(context)
                    || issueContexts.putIfAbsent(issue.id(), context) != null) return false;
        }

        var seenIssues = new HashSet<String>();
        for (var decision : decisions) {
            if (decision == null || decision.disposition() == null || !seenIssues.add(decision.issueId())) return false;
            var context = issueContexts.get(decision.issueId());
            var evidence = decision.evidenceClauseIds();
            if (context == null || evidence.isEmpty() || evidence.size() > ScopedReviewSchema.MAX_EVIDENCE_IDS) return false;
            var cited = new HashSet<>(evidence);
            if (cited.size() != evidence.size() || !context.containsAll(cited)) return false;
            if (decision.disposition() == Disposition.CONFIRMED_CONFLICT && cited.size() < 2) return false;
        }
        return seenIssues.equals(issueContexts.keySet());
    }
}
