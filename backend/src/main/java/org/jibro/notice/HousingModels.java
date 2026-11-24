package org.jibro.notice;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;

public final class HousingModels {
    private HousingModels() {
    }

    public record Evidence(String label, String url, String checkedAt) {
    }

    public record AnswerOption(String value, String label, Boolean requiresFollowUp) {
        public AnswerOption {
            requiresFollowUp = Boolean.TRUE.equals(requiresFollowUp);
        }

        public AnswerOption(String value, String label) {
            this(value, label, false);
        }
    }

    public record InputConstraints(BigDecimal min, BigDecimal max, BigDecimal step, String minDate,
                                   String maxDate, Integer maxSelections, String unit) {
    }

    public record AnswerDependency(String questionId, List<String> answerValues, List<String> conflictingValues,
                                   List<String> requiredDocumentIds, String message) {
    }

    public record QuestionApplicability(String questionId, List<String> answerValues) {
    }

    public record Question(String id, String title, String reason, Evidence evidence, List<AnswerOption> options,
                           String inputKind, InputConstraints constraints, Boolean reviewRequired,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                           List<AnswerDependency> answerDependencies,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                           QuestionApplicability applicability) {
        public Question {
            inputKind = inputKind == null ? "SINGLE" : inputKind;
            reviewRequired = Boolean.TRUE.equals(reviewRequired);
            answerDependencies = answerDependencies == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(answerDependencies));
        }

        public Question(String id, String title, String reason, Evidence evidence, List<AnswerOption> options,
                        String inputKind, InputConstraints constraints, Boolean reviewRequired, List<AnswerDependency> answerDependencies) {
            this(id, title, reason, evidence, options, inputKind, constraints, reviewRequired, answerDependencies, null);
        }

        public Question(String id, String title, String reason, Evidence evidence, List<AnswerOption> options,
                        String inputKind, InputConstraints constraints, Boolean reviewRequired) {
            this(id, title, reason, evidence, options, inputKind, constraints, reviewRequired, List.of());
        }

        public Question(String id, String title, String reason, Evidence evidence, List<AnswerOption> options,
                        String inputKind, InputConstraints constraints) {
            this(id, title, reason, evidence, options, inputKind, constraints, false);
        }

        public Question(String id, String title, String reason, Evidence evidence, List<AnswerOption> options) {
            this(id, title, reason, evidence, options, "SINGLE", null);
        }

        public Question(String id, String title, String reason, Evidence evidence) {
            this(id, title, reason, evidence, null);
        }

        public List<AnswerOption> answerOptions() {
            if (inputKind.equals("NUMBER") || inputKind.equals("DATE"))
                return List.of(new AnswerOption("UNKNOWN", "잘 모르겠어요"));
            return options == null ? List.of(new AnswerOption("YES", "확인했어요"),
                    new AnswerOption("NO", "해당하지 않아요"), new AnswerOption("UNKNOWN", "잘 모르겠어요")) : options;
        }

        public boolean needsFollowUp(Object answer) {
            return QuestionAnswers.needsFollowUp(this, answer);
        }

        public boolean needsFollowUp(Object answer, Map<String, ?> answers, List<Question> questions) {
            return QuestionAnswers.needsFollowUp(this, answer, answers, questions);
        }
    }

    public record DocumentTemplate(String id, String title, String conditionQuestionId, String method, String issuer,
                                   String issueUrl, Evidence evidence, List<String> conditionAnswerValues,
                                   @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                   String issueLabel) {
        public DocumentTemplate(String id, String title, String conditionQuestionId, String method, String issuer,
                                String issueUrl, Evidence evidence, List<String> conditionAnswerValues) {
            this(id, title, conditionQuestionId, method, issuer, issueUrl, evidence, conditionAnswerValues, null);
        }

        public DocumentTemplate(String id, String title, String conditionQuestionId, String method, String issuer,
                                String issueUrl, Evidence evidence) {
            this(id, title, conditionQuestionId, method, issuer, issueUrl, evidence, null);
        }
    }

    public record ScheduleTemplate(String id, String title, String date, Evidence evidence, String timingDescription) {
        public ScheduleTemplate(String id, String title, String date, Evidence evidence) {
            this(id, title, date, evidence, null);
        }
    }

    public record ReviewIssue(String id, String area, String title, String detail, List<String> questionIds,
                              List<String> documentIds, List<Evidence> evidence) {
    }

    /**
     * An observed official closure, independent of an unknown historical closing date.
     */
    public record OfficialApplicationClosure(String reason, Evidence evidence) {
    }

    public record Content(String summary, Evidence evidence, List<Evidence> attachments, List<Question> questions,
                          List<DocumentTemplate> documents, List<ScheduleTemplate> schedules,
                          List<ReviewIssue> reviewIssues,
                          OfficialApplicationClosure applicationClosure) {
        public static final int MAX_QUESTIONS = 100;

        public Content {
            reviewIssues = reviewIssues == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(reviewIssues));
            if (documents != null)
                reviewIssues = GuidanceIssues.withDependentDocuments(reviewIssues, documents, questions);
            if (questions != null && !reviewIssues.isEmpty()) {
                var affected = GuidanceIssues.questionIds(reviewIssues);
                questions = questions.stream().map(q -> q == null ? null : new Question(q.id(), q.title(), q.reason(),
                        q.evidence(), q.options(), q.inputKind(), q.constraints(), q.reviewRequired() || affected.contains(q.id()), q.answerDependencies(), q.applicability())).toList();
            }
        }

        public Content(String summary, Evidence evidence, List<Evidence> attachments, List<Question> questions,
                       List<DocumentTemplate> documents, List<ScheduleTemplate> schedules, List<ReviewIssue> reviewIssues) {
            this(summary, evidence, attachments, questions, documents, schedules, reviewIssues, null);
        }

        public Content(String summary, Evidence evidence, List<Evidence> attachments, List<Question> questions,
                       List<DocumentTemplate> documents, List<ScheduleTemplate> schedules) {
            this(summary, evidence, attachments, questions, documents, schedules, List.of());
        }
    }

    public record NoticeLifecycle(String state, String reason, Evidence evidence, java.time.Instant checkedAt,
                                  int revision) {
        public boolean interrupted() {
            return !"NORMAL".equals(state);
        }
    }

    public record ApplicationTiming(java.time.Instant startsAt, java.time.Instant endsAt,
                                    java.time.Instant nextChangeAt, boolean untilFilled) {
        public static final String UNTIL_FILLED_SESSION = "접수 회차 · 모집 완료 시 마감";
        public static final String VISIT_NOTE = "접수 회차 · 방문 접수 안내";
        public static final String VISIT_CONTACT = "접수 회차 · 방문 접수 사전 연락 안내";
        public static final String UNDATED_HOURS_SESSION = "접수 회차 · 접수일 확인 필요";
    }

    public record Notice(String id, String title, String agency, String region, String type, String status,
                         LocalDate startDate, LocalDate endDate, LocalDate publishedDate, List<Evidence> attachments,
                         String summary, int version, Integer guideVersion, String guideState, boolean canStart,
                         Evidence evidence, List<Question> questions, List<String> documents,
                         List<ScheduleTemplate> schedules,
                         java.time.Instant basicCheckedAt, java.time.Instant guideCheckedAt,
                         java.time.Instant collectedAt, NoticeLifecycle lifecycle,
                         ApplicationTiming applicationTiming, List<ReviewIssue> reviewIssues,
                         List<DocumentTemplate> documentTemplates,
                         NoticeQualificationSummary.Overview qualificationSummary,
                         NoticeDocumentSummary.Overview documentSummary) {
        public Notice {
            reviewIssues = reviewIssues == null ? List.of() : List.copyOf(reviewIssues);
            documentTemplates = documentTemplates == null ? List.of() : List.copyOf(documentTemplates);
        }

        public Notice(String id, String title, String agency, String region, String type, String status,
                      LocalDate startDate, LocalDate endDate, LocalDate publishedDate, List<Evidence> attachments,
                      String summary, int version, Integer guideVersion, String guideState, boolean canStart,
                      Evidence evidence, List<Question> questions, List<String> documents, List<ScheduleTemplate> schedules,
                      java.time.Instant basicCheckedAt, java.time.Instant guideCheckedAt, java.time.Instant collectedAt,
                      NoticeLifecycle lifecycle, ApplicationTiming applicationTiming) {
            this(id, title, agency, region, type, status, startDate, endDate, publishedDate, attachments, summary,
                    version, guideVersion, guideState, canStart, evidence, questions, documents, schedules,
                    basicCheckedAt, guideCheckedAt, collectedAt, lifecycle, applicationTiming, List.of(), List.of(), null, null);
        }

        public Notice(String id, String title, String agency, String region, String type, String status,
                      LocalDate startDate, LocalDate endDate, LocalDate publishedDate, List<Evidence> attachments,
                      String summary, int version, Integer guideVersion, String guideState, boolean canStart,
                      Evidence evidence, List<Question> questions, List<String> documents, List<ScheduleTemplate> schedules,
                      java.time.Instant basicCheckedAt, java.time.Instant guideCheckedAt, java.time.Instant collectedAt, NoticeLifecycle lifecycle) {
            this(id, title, agency, region, type, status, startDate, endDate, publishedDate, attachments, summary,
                    version, guideVersion, guideState, canStart, evidence, questions, documents, schedules,
                    basicCheckedAt, guideCheckedAt, collectedAt, lifecycle, null);
        }
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) {
    }

    public record Review(String id, Notice notice, String basic, String guide, String reason, int revision) {
    }

    public record Guide(String id, String category, String title, String body, Evidence evidence,
                        String scope, List<Evidence> additionalEvidence,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                        GuideFreshness freshness) {
        public Guide {
            additionalEvidence = additionalEvidence == null ? List.of() : List.copyOf(additionalEvidence);
        }

        public Guide(String id, String category, String title, String body, Evidence evidence) {
            this(id, category, title, body, evidence, null, List.of(), null);
        }

        public Guide(String id, String category, String title, String body, Evidence evidence,
                     String scope, List<Evidence> additionalEvidence) {
            this(id, category, title, body, evidence, scope, additionalEvidence, null);
        }
    }

    public record GuideFreshness(String status, String checkedAt, String lastAttemptAt, String message) {
    }

    public record Collection(String id, String status, String startedAt, String message) {
    }

    public enum DocumentState {UNCHECKED, NEEDED, IN_PROGRESS, READY, RECHECK}

    public static class Document {
        public String id, title, necessity, method, issuer, issueUrl, note = "";
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String issueLabel;
        public boolean reviewRequired;
        public DocumentState state = DocumentState.UNCHECKED;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public DocumentState stateBeforeReady;

        public void changeState(DocumentState next) {
            if (next == state) return;
            stateBeforeReady = next == DocumentState.READY ? state : null;
            state = next;
        }

        public Evidence evidence;
        // Internal rule is persisted separately in Preparation.rules, never supplied by the client.
    }

    public static class Schedule {
        public String timingDescription;
        public String id, title, date;
        public boolean recorded;
        public Evidence evidence;
    }

    public record NextAction(String label, String reason, String path,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
                             Boolean completed) {
        public NextAction {
            completed = Boolean.TRUE.equals(completed);
        }

        public NextAction(String label, String reason, String path) {
            this(label, reason, path, false);
        }
    }

    public enum FollowUpResult {WAITING, SELECTED, WAITLIST, NOT_SELECTED}

    public record FollowUp(FollowUpResult result, Integer waitlistNumber, Boolean documentsSubmitted,
                           Boolean contractCompleted, Boolean movedIn) {
        public FollowUp {
            result = result == null ? FollowUpResult.WAITING : result;
            documentsSubmitted = Boolean.TRUE.equals(documentsSubmitted);
            contractCompleted = Boolean.TRUE.equals(contractCompleted);
            movedIn = Boolean.TRUE.equals(movedIn);
        }
    }

    public static class Preparation {
        public String id, noticeId, title, guideState;
        public int version, revision, progress, houseStage;
        public List<Question> questions = new ArrayList<>();
        public Map<String, String> documentConditions = Map.of();
        public Map<String, List<String>> documentConditionValues = Map.of();
        public Map<String, Object> answers = new LinkedHashMap<>();
        public List<Document> documents = new ArrayList<>();
        public List<Schedule> schedules = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<ReviewIssue> reviewIssues = new ArrayList<>();
        public NextAction nextAction;
        public FollowUp followUp;
    }

    public record Snapshot(
            @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"documentConditions", "documentConditionValues"})
            Preparation preparation, Map<String, String> rules, Map<String, List<String>> ruleValues) {
        public Snapshot {
            // Expose the rules from this saved version on the detail response, including older snapshots.
            preparation.documentConditions = rules == null ? Map.of() : rules;
            preparation.documentConditionValues = ruleValues == null ? Map.of() : ruleValues;
        }

        public Snapshot(Preparation preparation, Map<String, String> rules) {
            this(preparation, rules, Map.of());
        }

        public List<String> valuesFor(String documentId) {
            return ruleValues == null ? List.of("YES") : ruleValues.getOrDefault(documentId, List.of("YES"));
        }
    }
}
