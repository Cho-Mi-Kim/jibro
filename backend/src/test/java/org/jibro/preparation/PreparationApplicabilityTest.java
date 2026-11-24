package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PreparationApplicabilityTest {
    @Test
    void skippedTypeSpecificAnswerDoesNotKeepProgressAndNextActionUnfinished() throws Exception {
        var json = new ObjectMapper();
        var cases = json.readTree(Files.readString(Path.of("../data/contracts/preparation-applicability.json")));
        var preparation = json.treeToValue(cases.get(0).get("preparation"), Preparation.class);
        preparation.id = "current";
        var reviewed = json.readTree(Files.readString(Path.of("../data/notices/pohang-bluevalley-answer-consistency-20261121.json")));
        preparation.questions = java.util.Arrays.stream(json.treeToValue(reviewed.get("content").get("questions"), Question[].class))
                .filter(q -> q.id().equals("category") || q.id().equals("work")).toList();
        preparation.documents.clear();
        preparation.schedules.clear();
        preparation.documentConditions.clear();
        for (var oldAnswer : new String[]{"UNKNOWN", "DOES_NOT_MEET"}) {
            preparation.answers = new java.util.LinkedHashMap<>(Map.of("category", "YOUTH", "work", oldAnswer));
            var result = PreparationRules.assess(preparation);
            assertEquals(30, result.progress());
            assertTrue(result.warnings().isEmpty());
            assertEquals("/preparations/current/schedule", result.nextAction().path());
            assertEquals(oldAnswer, preparation.answers.get("work"));
            preparation.answers.put("category", "INDUSTRIAL");
            var restored = PreparationRules.assess(preparation);
            assertEquals(15, restored.progress());
            assertEquals("/preparations/current/conditions/edit", restored.nextAction().path());
            assertEquals(oldAnswer, preparation.answers.get("work"));
        }
    }

    @Test
    void typeSpecificUnknownDocumentDoesNotBlockProgressAndReturnsWithItsType() throws Exception {
        var json = new ObjectMapper();
        var cases = json.readTree(Files.readString(Path.of("../data/contracts/preparation-applicability.json")));
        var scenario = java.util.stream.StreamSupport.stream(cases.spliterator(), false)
                .filter(c -> c.get("name").asText().contains("직접 연결된 미입력 서류")).findFirst().orElseThrow();
        var preparation = json.treeToValue(scenario.get("preparation"), Preparation.class);
        preparation.id = "current";
        String before = json.writeValueAsString(preparation);
        var selected = PreparationRules.assess(preparation);
        assertEquals(80, selected.progress());
        assertEquals("/preparations/current/schedule", selected.nextAction().path());
        assertEquals(before, json.writeValueAsString(preparation));
        preparation.answers.put("family-new2-category", "SINGLE_PARENT");
        var restored = PreparationRules.assess(preparation);
        assertEquals(40, restored.progress());
        assertEquals("/preparations/current/conditions/edit", restored.nextAction().path());
        assertEquals("UNKNOWN", preparation.documents.get(1).necessity);
    }

    @Test
    void visibleItemsFollowSharedLiteralContractsWithoutRewritingTheRecord() throws Exception {
        var json = new ObjectMapper();
        var cases = json.readTree(Files.readString(Path.of("../data/contracts/preparation-applicability.json")));
        for (var scenario : cases) {
            var preparation = json.treeToValue(scenario.get("preparation"), Preparation.class);
            var before = json.writeValueAsString(preparation);
            assertEquals(scenario.get("expectedQuestionIds"), json.valueToTree(
                    PreparationApplicability.visibleQuestions(preparation).stream().map(Question::id).toList()), scenario.get("name").toString());
            assertEquals(scenario.get("expectedScheduleIds"), json.valueToTree(
                    PreparationApplicability.visibleSchedules(preparation).stream().map(s -> s.id).toList()), scenario.get("name").toString());
            if (scenario.has("expectedDocumentIds")) assertEquals(scenario.get("expectedDocumentIds"), json.valueToTree(
                    PreparationApplicability.visibleDocuments(preparation).stream().map(d -> d.id).toList()), scenario.get("name").toString());
            assertEquals(before, json.writeValueAsString(preparation));
        }
    }
}
