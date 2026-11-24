package org.jibro.collection.review.ai;

import java.util.*;

import tools.jackson.databind.JsonNode;

import static org.jibro.collection.review.ai.ScopedReviewModels.*;

/**
 * The provider can cite existing issue and clause IDs, but cannot invent rules or publication flags.
 */
final class ScopedReviewSchema {
    static final int MAX_DECISIONS = 32;
    static final int MAX_EVIDENCE_IDS = 64;
    static final int MAX_ID_LENGTH = 160;
    private static final Set<String> ROOT_FIELDS = Set.of("decisions");
    private static final Set<String> DECISION_FIELDS = Set.of("issueId", "disposition", "evidenceClauseIds");

    private ScopedReviewSchema() {
    }

    static Map<String, Object> schema() {
        // Nested array bounds can exceed the provider's decoding-grammar complexity. The
        // bounded body reader and strict local parser enforce all counts and identifier limits.
        var id = Map.of("type", "string");
        var decision = Map.of("type", "object", "properties", Map.of(
                        "issueId", id,
                        "disposition", Map.of("type", "string", "enum", Arrays.stream(Disposition.values()).map(Enum::name).toList()),
                        "evidenceClauseIds", Map.of("type", "array",
                                "items", id)),
                "required", List.of("issueId", "disposition", "evidenceClauseIds"), "additionalProperties", false);
        return Map.of("type", "object", "properties", Map.of(
                        "decisions", Map.of("type", "array", "items", decision)),
                "required", List.of("decisions"), "additionalProperties", false);
    }

    static List<Decision> parse(JsonNode value) {
        requireFields(value, ROOT_FIELDS);
        var items = value.path("decisions");
        if (!items.isArray() || items.isEmpty() || items.size() > MAX_DECISIONS) throw new IllegalArgumentException();
        var decisions = new ArrayList<Decision>();
        var issueIds = new HashSet<String>();
        for (var item : items) {
            requireFields(item, DECISION_FIELDS);
            String issueId = id(item.path("issueId"));
            if (!issueIds.add(issueId) || !item.path("disposition").isString()) throw new IllegalArgumentException();
            var disposition = Disposition.valueOf(item.path("disposition").asText());
            var references = item.path("evidenceClauseIds");
            if (!references.isArray() || references.isEmpty() || references.size() > MAX_EVIDENCE_IDS)
                throw new IllegalArgumentException();
            var evidence = new ArrayList<String>();
            var seen = new HashSet<String>();
            for (var reference : references) {
                String clauseId = id(reference);
                if (!seen.add(clauseId)) throw new IllegalArgumentException();
                evidence.add(clauseId);
            }
            decisions.add(new Decision(issueId, disposition, evidence));
        }
        return List.copyOf(decisions);
    }

    static boolean validId(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_ID_LENGTH
                && value.codePoints().noneMatch(Character::isWhitespace);
    }

    private static String id(JsonNode value) {
        if (!value.isString() || !validId(value.asText())) throw new IllegalArgumentException();
        return value.asText();
    }

    private static void requireFields(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()) throw new IllegalArgumentException();
        for (String field : fields) if (!value.has(field)) throw new IllegalArgumentException();
    }
}
