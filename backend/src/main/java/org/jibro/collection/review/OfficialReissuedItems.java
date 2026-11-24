package org.jibro.collection.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.collection.review.followup.OfficialFollowupModels.Related;

import static org.jibro.collection.review.FollowupClaimReader.*;

/**
 * Diff a full official reissue only if every other clause, exception and scope stays in order.
 */
final class OfficialReissuedItems {
    private record Line(String location, String text) {
    }

    private OfficialReissuedItems() {
    }

    static Optional<List<Change>> changes(List<Source> originals, Source revised, Related related) {
        if (!related.kind().equals("CORRECTION")) return Optional.empty();
        var after = lines(revised);
        if (after.isEmpty() || after.size() > 12000) return Optional.empty();
        boolean hasItem = after.stream().anyMatch(line -> {
            var rule = GuidanceRuleGrammar.parse(line.text());
            return rule != null && !rule.kind().equals("SCHEDULE");
        });
        List<Change> result = null;
        for (var source : originals) {
            if (!"EXTRACTED".equals(source.status())) continue;
            var before = lines(source);
            if (before.size() != after.size()) continue;
            boolean same = true;
            for (int i = 0; same && i < before.size(); i++) same = before.get(i).text().equals(after.get(i).text());
            if (!same && (!hasItem || after.size() < 2)) continue;
            var changes = same ? List.<Change>of() : compare(before, after, revised, related);
            if (!same && changes.isEmpty()) continue;
            if (result != null && !result.equals(changes)) return Optional.empty();
            result = changes;
        }
        return Optional.ofNullable(result);
    }

    private static List<Change> compare(List<Line> before, List<Line> after, Source source, Related related) {
        var changes = new ArrayList<Change>();
        var fields = new java.util.HashSet<String>();
        var occurrences = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < before.size(); i++) {
            var old = before.get(i);
            var next = after.get(i);
            var oldRule = GuidanceRuleGrammar.parse(old.text());
            if (oldRule != null) occurrences.merge(oldRule.kind() + ":" + oldRule.key(), 1, Integer::sum);
            if (old.text().equals(next.text())) continue;
            var newRule = GuidanceRuleGrammar.parse(next.text());
            if (oldRule == null || newRule == null || oldRule.kind().equals("SCHEDULE")
                || !oldRule.kind().equals(newRule.kind()) || !oldRule.key().equals(newRule.key())) return List.of();
            Value value = value(next.text());
            fields.add(value.field());
            changes.add(new Change(value, old.text(), source, next.location(), next.text(), related.publishedDate(),
                related.relationshipProof()));
        }
        if (fields.stream().anyMatch(field -> occurrences.get(field) != 1)) return List.of();
        return List.copyOf(changes);
    }

    private static List<Line> lines(Source source) {
        var result = new ArrayList<Line>();
        for (var part : source.context()) {
            if (part.text() == null) return List.of();
            part.text().lines().map(String::strip).filter(line -> !line.isBlank())
                .forEach(line -> result.add(new Line(part.location(), line)));
        }
        return result;
    }
}
