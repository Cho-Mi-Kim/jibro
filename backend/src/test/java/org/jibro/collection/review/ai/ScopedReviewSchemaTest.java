package org.jibro.collection.review.ai;

import java.util.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.jibro.collection.review.ai.ScopedReviewModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ScopedReviewSchemaTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void readsAdvisoryDecisionsWithoutGeneratingRulesOrNoticeApproval() {
        var decisions = ScopedReviewSchema.parse(json.readTree("""
                {"decisions":[
                  {"issueId":"issue-income","disposition":"CONFIRMED_CONFLICT","evidenceClauseIds":["pdf-income","hwp-income"]},
                  {"issueId":"issue-household","disposition":"UNRESOLVED","evidenceClauseIds":["household-definition"]},
                  {"issueId":"issue-waiver","disposition":"NO_CONFLICT","evidenceClauseIds":["waiver"]}
                ]}
                """));
        assertEquals(3, decisions.size());
        assertEquals(Disposition.CONFIRMED_CONFLICT, decisions.get(0).disposition());
        assertEquals(List.of("pdf-income", "hwp-income"), decisions.get(0).evidenceClauseIds());
        assertEquals(Disposition.UNRESOLVED, decisions.get(1).disposition());
        assertEquals(Disposition.NO_CONFLICT, decisions.get(2).disposition());
    }

    @Test
    void rejectsPublicationFieldsUnknownDispositionsAndMissingEvidence() {
        for (String value : List.of(
                "{\"decisions\":[],\"complete\":true}",
                "{\"decisions\":[]}",
                "{\"decisions\":[{\"issueId\":\"i\",\"disposition\":\"PASS\",\"evidenceClauseIds\":[\"c\"]}]}",
                "{\"decisions\":[{\"issueId\":\"i\",\"disposition\":\"no_conflict\",\"evidenceClauseIds\":[\"c\"]}]}",
                "{\"decisions\":[{\"issueId\":\"i\",\"disposition\":true,\"evidenceClauseIds\":[\"c\"]}]}",
                "{\"decisions\":[{\"issueId\":\"i\",\"disposition\":\"NO_CONFLICT\"}]}",
                "{\"decisions\":[{\"issueId\":\"i\",\"disposition\":\"NO_CONFLICT\",\"evidenceClauseIds\":[],\"approved\":true}]}",
                "{\"decisions\":[{\"issueId\":\"i\",\"disposition\":\"NO_CONFLICT\",\"evidenceClauseIds\":[]}]}",
                "{\"decisions\":[{\"issueId\":\"i\",\"disposition\":\"NO_CONFLICT\",\"evidenceClauseIds\":null}]}",
                "{\"decisions\":[{\"issueId\":\"i\",\"disposition\":\"NO_CONFLICT\",\"evidenceClauseIds\":[123]}]}")) {
            assertThrows(IllegalArgumentException.class, () -> ScopedReviewSchema.parse(json.readTree(value)), value);
        }
    }

    @Test
    void rejectsRepeatedIssuesAndRepeatedEvidenceInsteadOfCountingThemTwice() {
        var same = decision("i", List.of("c"));
        assertThrows(IllegalArgumentException.class, () -> parse(List.of(same, same)));
        assertThrows(IllegalArgumentException.class, () -> parse(List.of(decision("i", List.of("c", "c")))));
    }

    @Test
    void boundsResponseAndRejectsWhitespaceOrOversizedIdentifiers() {
        for (String id : List.of("", " ", "issue id", "\ti", "i\n", "i".repeat(161))) {
            assertThrows(IllegalArgumentException.class, () -> parse(List.of(decision(id, List.of("c")))));
            assertThrows(IllegalArgumentException.class, () -> parse(List.of(decision("i", List.of(id)))));
        }
        var tooMany = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 33; i++) tooMany.add(decision("i" + i, List.of("c")));
        assertThrows(IllegalArgumentException.class, () -> parse(tooMany));
        var evidence = new ArrayList<String>();
        for (int i = 0; i < 65; i++) evidence.add("c" + i);
        assertThrows(IllegalArgumentException.class, () -> parse(List.of(decision("i", evidence))));
        assertEquals(1, parse(List.of(decision("i".repeat(160), evidence.subList(0, 64)))).size());
    }

    @Test
    void keepsProviderSchemaSimpleWhileLocalParserEnforcesBounds() {
        var schema = json.valueToTree(ScopedReviewSchema.schema());
        assertFalse(schema.path("additionalProperties").asBoolean());
        var decisions = schema.path("properties").path("decisions");
        assertFalse(decisions.has("maxItems"));
        assertFalse(decisions.path("items").path("additionalProperties").asBoolean());
        assertFalse(decisions.path("items").path("properties").path("evidenceClauseIds").has("maxItems"));
        assertFalse(schema.path("properties").has("complete"));
    }

    private List<Decision> parse(List<Map<String, Object>> decisions) {
        return ScopedReviewSchema.parse(json.valueToTree(Map.of("decisions", decisions)));
    }

    private Map<String, Object> decision(String id, List<String> evidence) {
        return Map.of("issueId", id, "disposition", "NO_CONFLICT", "evidenceClauseIds", evidence);
    }
}
