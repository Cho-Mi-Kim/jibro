package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PreparationRulesTest {
    @Test
    void serverAndStaticPreviewUseTheSameLiteralContractCasesWithoutMutatingInputs() throws Exception {
        var json = new ObjectMapper();
        var cases = json.readTree(Files.readString(Path.of("../data/contracts/preparation-rules.json")));
        for (var scenario : cases) {
            var input = json.treeToValue(scenario.get("preparation"), Preparation.class);
            String before = json.writeValueAsString(input);
            var actual = PreparationRules.assess(input);
            assertEquals(scenario.get("expected"), json.valueToTree(actual), scenario.get("name").toString());
            assertEquals(before, json.writeValueAsString(input), "Assessment must not change the saved record");
        }
    }

    @Test
    void documentNecessityUsesItsDeclaredQuestionAndAcceptedOptions() {
        var questions = List.of(new Question("route", "신청 유형", "", null,
                List.of(new AnswerOption("STUDENT", "대학생"), new AnswerOption("GRADUATE", "졸업생"),
                        new AnswerOption("JOBSEEKER", "구직자"), new AnswerOption("UNKNOWN", "모름", true))));
        assertEquals("REQUIRED", PreparationRules.necessity(null, List.of("YES"), Map.of(), questions));
        assertEquals("UNKNOWN", PreparationRules.necessity("route", List.of("STUDENT"), Map.of(), questions));
        assertEquals("UNKNOWN", PreparationRules.necessity("route", List.of("STUDENT"), Map.of("route", "UNKNOWN"), questions));
        assertEquals("CONDITIONAL", PreparationRules.necessity("route", List.of("STUDENT", "GRADUATE"), Map.of("route", "GRADUATE"), questions));
        assertEquals("NOT_APPLICABLE", PreparationRules.necessity("route", List.of("STUDENT"), Map.of("route", "JOBSEEKER"), questions));
    }
}
