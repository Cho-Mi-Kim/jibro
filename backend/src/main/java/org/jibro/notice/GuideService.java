package org.jibro.notice;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.jibro.notice.HousingModels.Guide;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GuideService {
    private static final int PAGE_SIZE = 20;
    private final Environment env;
    private final GuideRepository repository;
    private final GuideCatalog catalog;
    private final GuideRefreshRepository refresh;

    @Autowired
    public GuideService(Environment env, GuideRepository repository, GuideCatalog catalog, GuideRefreshRepository refresh) {
        this.env = env;
        this.repository = repository;
        this.catalog = catalog;
        this.refresh = refresh;
    }

    GuideService(Environment env, GuideRepository repository, GuideCatalog catalog) {
        this(env, repository, catalog, null);
    }

    public Map<String, Object> meta() {
        var result = new HashMap<String, Object>();
        result.put("sampleData", false);
        result.put("trialAvailable", env.matchesProfiles("local-sample"));
        if (env.matchesProfiles("local-sample"))
            repository.findTrialNotice().ifPresent(id -> result.put("trialNoticeId", id));
        return result;
    }

    public record GuidePage(List<Guide> items, int page, boolean hasMore, int totalPages) {
    }

    public GuidePage guides(String category, String keyword, int page) {
        if (category == null || keyword == null || page < 0 || page > 10000 || keyword.length() > 200
            || !category.isEmpty() && !GuideCatalog.CATEGORIES.contains(category))
            throw new IllegalArgumentException();
        var terms = List.of(normalize(keyword).strip().split("\\s+"));
        // General explanations come from the reviewed catalogue, never from a notice's local criteria.
        var current = refresh == null ? catalog.items() : refresh.current(catalog.items());
        var matches = current.stream()
            .filter(guide -> category.isEmpty() || guide.category().equals(category))
            .filter(guide -> {
                String text = searchableText(guide);
                return terms.stream().allMatch(text::contains);
            })
            .toList();
        int totalPages = (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int start = Math.min(page * PAGE_SIZE, matches.size());
        var items = matches.subList(start, Math.min(start + PAGE_SIZE, matches.size()));
        return new GuidePage(items, page, page + 1 < totalPages, totalPages);
    }

    private static String searchableText(Guide guide) {
        String labels = Stream.concat(Stream.of(guide.evidence()), guide.additionalEvidence().stream())
            .map(HousingModels.Evidence::label).reduce("", (left, right) -> left + " " + right);
        return normalize(String.join(" ", guide.category(), guide.title(), guide.body(), guide.scope(), labels));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
