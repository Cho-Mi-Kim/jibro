package org.jibro.collection.review.local;

import java.util.List;
import java.util.Map;

/** A lossless local reading index. Facts are literal findings, never an eligibility verdict. */
public record LocalRuleBook(String policy, List<Clause> clauses, List<Fact> facts,
                            List<Unresolved> unresolved, int sourceCharacters) {
    public LocalRuleBook {
        clauses = List.copyOf(clauses);
        facts = List.copyOf(facts);
        unresolved = List.copyOf(unresolved);
    }

    public enum Topic {
        APPLICANT, HOUSEHOLD, HOUSING, INCOME, ASSETS, VEHICLE, RANK, DOCUMENT, SCHEDULE, EXCEPTION, OTHER
    }

    public enum Marker { ALL_OF, ANY_OF, EXCEPTION, WAIVER, NEGATION, REFERENCE }

    /** Offsets refer to the original Part.text; quote must equal substring(start, end). */
    public record Clause(String id, String sourceUrl, String sourceSha256, String location, int start, int end,
                         String quote, String heading, List<Topic> topics, List<String> subjects,
                         List<String> scopes, List<Marker> markers, List<Quantity> quantities,
                         List<String> references, List<String> relatedClauseIds) {
        public Clause {
            topics = List.copyOf(topics);
            subjects = List.copyOf(subjects);
            scopes = List.copyOf(scopes);
            markers = List.copyOf(markers);
            quantities = List.copyOf(quantities);
            references = List.copyOf(references);
            relatedClauseIds = List.copyOf(relatedClauseIds);
        }
    }

    /** Numeric values retain their source unit; percent tables are not silently converted to income. */
    public record Quantity(String literal, String value, String unit, String comparator, int start, int end) {}

    /** values contains explicitly parsed operands; clauseIds includes supporting scope/exception clauses. */
    public record Fact(String id, String type, Topic topic, String subject, Map<String, String> values,
                       List<String> clauseIds) {
        public Fact {
            values = Map.copyOf(values);
            clauseIds = List.copyOf(clauseIds);
        }
    }

    public record Unresolved(String code, List<String> clauseIds) {
        public Unresolved { clauseIds = List.copyOf(clauseIds); }
    }
}
