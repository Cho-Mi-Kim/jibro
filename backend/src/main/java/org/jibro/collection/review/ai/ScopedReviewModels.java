package org.jibro.collection.review.ai;

import java.util.List;
import static org.jibro.collection.review.ai.GeminiReviewModels.*;

/** Advisory decisions about a bounded source issue, never approval of a notice or an applicant. */
public final class ScopedReviewModels {
    private ScopedReviewModels() {}

    public enum Disposition { CONFIRMED_CONFLICT, NO_CONFLICT, UNRESOLVED }

    public record Decision(String issueId, Disposition disposition, List<String> evidenceClauseIds) {
        public Decision { evidenceClauseIds = List.copyOf(evidenceClauseIds); }
    }

    public record ScopedResult(Status status, String code, List<Decision> decisions, Usage usage) {
        public ScopedResult { decisions = List.copyOf(decisions); }
    }
}
