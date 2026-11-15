package org.jibro.collection.review.summary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import org.jibro.collection.extraction.ExtractionModels.Draft;
import org.jibro.notice.AutomaticReviewEligibility;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.NoticeSummary;

/** Pure local summarization after source/rule review; existing publication owns locks, hashes and approval. */
public final class SupportSummaryCompiler {
    private SupportSummaryCompiler() {}

    public static Content apply(Draft selected, Content content, LocalDate start, LocalDate end, Instant now) {
        if (selected == null || content == null || selected.checkedAt() == null || !"READY".equals(selected.status())
            || content.applicationClosure() != null || AutomaticReviewEligibility.closed(start, end, content.schedules(), now)) return content;
        if (selected.sources().stream().anyMatch(s -> !"EXTRACTED".equals(s.status()))) return content;
        // A condition conflict cannot be converted into an unconditional audience sentence.
        if (content.reviewIssues().stream().anyMatch(issue -> !"SCHEDULE".equals(issue.area()))) return content;
        var briefs = new LinkedHashSet<SupportSummaryRules.Brief>();
        for (var source : selected.sources()) {
            var input = SupportSummarySource.read(source);
            if (input == null) continue;
            var brief = SupportSummaryRules.read(input);
            if (brief == null) return content;
            briefs.add(brief);
        }
        // Different complete program descriptions need review; attachment order must never pick a winner.
        if (briefs.size() != 1) return content;
        String summary = NoticeSummary.concise(briefs.iterator().next().text());
        if (summary.length() > 280) return content;
        return new Content(summary, content.evidence(), content.attachments(), content.questions(), content.documents(),
            content.schedules(), content.reviewIssues(), content.applicationClosure());
    }
}
