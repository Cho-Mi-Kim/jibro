package org.jibro.collection;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validated query policy; a run snapshots this plan before any network request.
 */
@Component
public class CollectionQueryPlan {
    public static final int MAX_PAGE = 500;
    private static final Set<String> ALLOWED_CATEGORIES = Set.of("06", "13");
    private final List<String> categories;
    private final LocalDate historyStartDate;

    public CollectionQueryPlan(@Value("${app.collection.category-codes:06,13}") String categoryCodes,
                               @Value("${app.collection.history-start-date:2000-01-01}") LocalDate historyStartDate) {
        this.categories = parseCategories(categoryCodes);
        if (historyStartDate == null || historyStartDate.getYear() < 1900 || historyStartDate.isAfter(LocalDate.now(CollectionSchedule.ZONE)))
            throw new IllegalArgumentException("COLLECTION_HISTORY_START_INVALID");
        this.historyStartDate = historyStartDate;
    }

    public List<String> categories() {
        return categories;
    }

    public String categoryCodes() {
        return String.join(",", categories);
    }

    public LocalDate from(LocalDate through) {
        // Historical recovery/tests can replay an older run without manufacturing a reversed interval.
        return historyStartDate.isAfter(through) ? through : historyStartDate;
    }

    static List<String> parseCategories(String value) {
        if (value == null) throw new IllegalArgumentException("COLLECTION_CATEGORIES_INVALID");
        var result = Arrays.stream(value.split(",", -1)).map(String::trim).toList();
        if (result.isEmpty() || result.size() > ALLOWED_CATEGORIES.size()
            || !ALLOWED_CATEGORIES.containsAll(result) || Set.copyOf(result).size() != result.size())
            throw new IllegalArgumentException("COLLECTION_CATEGORIES_INVALID");
        return result;
    }

    public record Query(String categoryCode, LocalDate publishedFrom, LocalDate publishedThrough) {
        public Query {
            if (!ALLOWED_CATEGORIES.contains(categoryCode) || publishedFrom == null || publishedThrough == null
                || publishedFrom.isAfter(publishedThrough) || publishedFrom.getYear() < 1900 || publishedThrough.getYear() > 9999)
                throw new IllegalArgumentException("COLLECTION_QUERY_INVALID");
        }
    }
}
