package org.jibro.collection.review.followup;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.jibro.collection.review.followup.OfficialFollowupModels.*;

/**
 * Search disappearance is not official revocation of an already applied correction.
 */
final class FollowupDependencies {
    private FollowupDependencies() {
    }

    static boolean missingWindow(org.jibro.collection.review.IntakeChannelDates.Window prior,
                                 java.util.List<org.jibro.collection.review.IntakeChannelDates.Window> current) {
        return current.stream().noneMatch(w -> w.url().equals(prior.url()) && w.channel().equals(prior.channel())
            && (prior.field() == null || prior.field() == w.field())
            && (prior.attachment() == null || w.attachment() != null && w.attachment().name().equals(prior.attachment().name()))
            && w.qualifier().equals(prior.qualifier()) && (prior.start() == null || w.start() != null)
            && (prior.end() == null || w.end() != null) && (prior.opens() == null || w.opens() != null)
            && (prior.office() == null || prior.office().equals(w.office()))
            && (prior.breakStart() == null || w.breakStart() != null) && (prior.breakEnd() == null || w.breakEnd() != null)
            && (prior.closes() == null || w.closes() != null));
    }

    static boolean missing(Discovery previous, Discovery current) {
        if (previous == null || current == null) return true;
        Map<String, Set<String>> available = new HashMap<>();
        current.related().forEach(related -> {
            var urls = available.computeIfAbsent(related.kind() + "\n" + related.url(), ignored -> new HashSet<>());
            related.sources().forEach(source -> urls.add(source.url()));
        });
        return previous.related().stream().anyMatch(related -> {
            var urls = available.get(related.kind() + "\n" + related.url());
            return urls == null || related.sources().stream().anyMatch(source -> !urls.contains(source.url()));
        });
    }
}
