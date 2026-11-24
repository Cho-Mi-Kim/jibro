package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.jibro.preparation.PreparationSnapshots;
import tools.jackson.databind.ObjectMapper;

class QuestionRolesTest {
    @Test
    void documentLinkedAcknowledgementCanStillResolveItsDocumentWithoutAnsweringOtherInformation() {
        var evidence = new Evidence("공식 원문", null, "2026-11-22");
        var choices = List.of(new AnswerOption("ACK", "안내를 확인했어요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true));
        var p = new Preparation();
        p.questions = List.of(new Question("guide", "안내", "절차 안내", evidence, choices),
                new Question("document-check", "서류 확인", "확인 후 제출", evidence, choices));
        p.answers.put("guide", "UNKNOWN");
        var document = new Document();
        document.id = "d";
        document.necessity = "UNKNOWN";
        document.note = "준비 메모";
        p.documents.add(document);
        var snapshot = new Snapshot(p, Map.of("d", "document-check"), Map.of("d", List.of("ACK")));
        assertEquals(List.of("document-check"), QuestionAnswers.applicantQuestions(p.questions, p.documentConditions.values())
                .stream().map(Question::id).toList());
        PreparationSnapshots.applyAnswers(snapshot, Map.of("guide", "UNKNOWN", "document-check", "ACK"));
        assertEquals("CONDITIONAL", document.necessity);
        assertEquals("준비 메모", document.note);
        assertEquals("UNKNOWN", p.answers.get("guide"));
    }

    @Test
    void serverAndFrontendKeepFactsDependenciesAndOfficialReviewInTheApplicantFlow() throws Exception {
        var json = new ObjectMapper();
        var cases = json.readTree(Files.readString(Path.of("../data/contracts/question-roles.json")));
        for (var scenario : cases) {
            var questions = Arrays.asList(json.treeToValue(scenario.get("questions"), Question[].class));
            var documents = List.of(json.treeToValue(scenario.get("documentQuestionIds"), String[].class));
            var before = json.writeValueAsString(questions);
            var applicable = QuestionAnswers.applicantQuestions(questions, documents);
            assertEquals(scenario.get("applicantQuestionIds"), json.valueToTree(applicable.stream().map(Question::id).toList()),
                    scenario.get("name").toString());
            assertEquals(scenario.get("answerableQuestionIds"),
                    json.valueToTree(applicable.stream().filter(q -> !q.reviewRequired()).map(Question::id).toList()),
                    scenario.get("name").toString());
            assertEquals(before, json.writeValueAsString(questions));
        }
    }
}
