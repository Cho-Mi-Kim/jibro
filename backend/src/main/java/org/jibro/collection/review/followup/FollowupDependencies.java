package org.jibro.collection.review.followup;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.jibro.collection.review.followup.OfficialFollowupModels.*;

/** Search disappearance is not official revocation of an already applied correction. */
final class FollowupDependencies {
    private FollowupDependencies() {}

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
