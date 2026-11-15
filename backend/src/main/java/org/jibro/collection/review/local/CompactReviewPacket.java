package org.jibro.collection.review.local;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import static org.jibro.collection.review.local.LocalRuleBook.*;

/** Exact source context for a bounded, advisory review. A packet never represents an entire notice. */
public record CompactReviewPacket(String id, String policy, List<SourceRef> sources,
                                  List<EvidenceClause> clauses, List<Fact> facts, List<Issue> issues) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String REVIEW_POLICY = "scoped-review-v2";

    public CompactReviewPacket {
        sources = List.copyOf(sources);
        clauses = List.copyOf(clauses);
        facts = List.copyOf(facts);
        issues = List.copyOf(issues);
    }

    public record SourceRef(String id, String url, String sha256) {}
    public record ExpandedSource(String sourceUrl, String location, String text) {}
    public record Issue(String id, String code, List<String> clauseIds) {
        public Issue { clauseIds = List.copyOf(clauseIds); }
    }
    public record EvidenceClause(String id, String sourceId, String location, int start, int end, String quote,
                                 String heading, List<Topic> topics, List<String> subjects, List<String> scopes,
                                 List<Marker> markers, List<Quantity> quantities, List<String> references,
                                 List<String> relatedClauseIds) {
        public EvidenceClause {
            topics = List.copyOf(topics);
            subjects = List.copyOf(subjects);
            scopes = List.copyOf(scopes);
            markers = List.copyOf(markers);
            quantities = List.copyOf(quantities);
            references = List.copyOf(references);
            relatedClauseIds = List.copyOf(relatedClauseIds);
        }
    }

    /** This exact JSON is measured and sent. Fact map ordering is stable across JVM restarts. */
    public String payloadJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("policy", policy);
        payload.put("sources", sources);
        payload.put("clauses", clauses.stream().map(clause -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", clause.id()); value.put("sourceId", clause.sourceId());
            value.put("location", clause.location()); value.put("quote", clause.quote());
            if (!clause.heading().isBlank()) value.put("heading", clause.heading());
            if (!clause.subjects().isEmpty()) value.put("subjects", clause.subjects());
            if (!clause.scopes().isEmpty()) value.put("scopes", clause.scopes());
            if (!clause.markers().isEmpty()) value.put("markers", clause.markers());
            if (!clause.references().isEmpty()) value.put("references", clause.references());
            if (!clause.relatedClauseIds().isEmpty()) value.put("relatedClauseIds", clause.relatedClauseIds());
            return value;
        }).toList());
        payload.put("facts", facts.stream().map(fact -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", fact.type()); value.put("topic", fact.topic());
            value.put("subject", fact.subject()); value.put("values", new TreeMap<>(fact.values()));
            value.put("clauseIds", fact.clauseIds());
            return value;
        }).toList());
        payload.put("issues", issues);
        return JSON.writeValueAsString(payload);
    }

    public int transmittedCharacters() { return payloadJson().length(); }

    /** The calling service must also use this exact packet payload for its provider request. */
    public String cacheKey(String model) { return digest(REVIEW_POLICY + "\n" + model + "\n" + payloadJson()); }

    public List<ExpandedSource> sourceTexts() {
        Map<String, SourceRef> byId = new HashMap<>();
        sources.forEach(source -> byId.put(source.id(), source));
        return clauses.stream().map(clause -> new ExpandedSource(byId.get(clause.sourceId()).url(), clause.location(), clause.quote())).toList();
    }

    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
