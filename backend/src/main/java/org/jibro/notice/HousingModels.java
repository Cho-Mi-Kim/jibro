package org.jibro.notice;

import java.time.LocalDate;
import java.util.*;

public final class HousingModels {
    private HousingModels() {
    }

    public record Evidence(String label, String url, String checkedAt) {
    }

    public record Question(String id, String title, String reason, Evidence evidence) {
    }

    public record DocumentTemplate(String id, String title, String conditionQuestionId, String method, String issuer,
                                   String issueUrl, Evidence evidence) {
    }

    public record ScheduleTemplate(String id, String title, String date, Evidence evidence) {
    }

    public record Content(String summary, Evidence evidence, List<Evidence> attachments, List<Question> questions,
                          List<DocumentTemplate> documents, List<ScheduleTemplate> schedules) {
    }

    public record Notice(String id, String title, String agency, String region, String type, String status,
                         LocalDate startDate, LocalDate endDate, LocalDate publishedDate, List<Evidence> attachments,
                         String summary, int version, Integer guideVersion, String guideState, boolean canStart,
                         Evidence evidence, List<Question> questions, List<String> documents) {
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) {
    }

    public record Review(String id, Notice notice, String basic, String guide, String reason, int revision) {
    }

    public record Guide(String id, String category, String title, String body, Evidence evidence) {
    }

    public record Collection(String id, String status, String startedAt, String message) {
    }

    public enum Answer {YES, NO, UNKNOWN}

    public enum DocumentState {UNCHECKED, NEEDED, IN_PROGRESS, READY, RECHECK}

    public static class Document {
        public String id, title, necessity, method, issuer, issueUrl, note = "";
        public DocumentState state = DocumentState.UNCHECKED;
        public Evidence evidence;
        // Internal rule is persisted separately in Preparation.rules, never supplied by the client.
    }

    public static class Schedule {
        public String id, title, date;
        public boolean recorded;
        public Evidence evidence;
    }

    public record NextAction(String label, String reason, String path) {
    }

    public static class Preparation {
        public String id, noticeId, title, guideState;
        public int version, revision, progress, houseStage;
        public List<Question> questions = new ArrayList<>();
        public Map<String, Answer> answers = new LinkedHashMap<>();
        public List<Document> documents = new ArrayList<>();
        public List<Schedule> schedules = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public NextAction nextAction;
    }

    public record Snapshot(Preparation preparation, Map<String, String> rules) {
    }
}
