package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** A short view of the reviewed document catalog, not a personal document requirement decision. */
public final class NoticeDocumentSummary {
    private NoticeDocumentSummary() {}

    private static final int PREVIEW_LIMIT = 3;

    public record Item(String id, String title, Evidence evidence) {}
    public record Group(List<Item> items, int remainingCount) {}
    public record Overview(Group common, Group conditional, int reviewRequiredCount) {}

    public static Overview from(Content content) {
        var common = new ArrayList<DocumentTemplate>();
        var conditional = new ArrayList<DocumentTemplate>();
        int pending = 0;
        var questions = content.questions() == null ? List.<Question>of() : content.questions();
        for (DocumentTemplate document : content.documents() == null
                ? List.<DocumentTemplate>of() : content.documents()) {
            if (document == null || document.title() == null || document.title().isBlank()) continue;
            var condition = document.conditionQuestionId();
            boolean unclearCondition = condition != null && questions.stream().noneMatch(q ->
                q != null && Objects.equals(q.id(), condition) && !Boolean.TRUE.equals(q.reviewRequired()));
            if (unclearCondition || GuidanceIssues.documentRequiresReview(content.reviewIssues(), document.id(), condition)
                    || document.evidence() == null || document.evidence().url() == null
                    || document.evidence().url().isBlank()) {
                pending++;
                continue;
            }
            (condition == null ? common : conditional).add(document);
        }
        return new Overview(preview(common), preview(conditional), pending);
    }

    private static Group preview(List<DocumentTemplate> documents) {
        // Prefer core identity/family and application forms to exceptional supporting material.
        // Stable sorting retains the reviewed order within each category; nothing is removed from the catalog.
        var items = documents.stream().sorted(Comparator.comparingInt(NoticeDocumentSummary::priority))
            .limit(PREVIEW_LIMIT).map(d -> new Item(d.id(), shortTitle(d.title()), d.evidence())).toList();
        return new Group(items, Math.max(0, documents.size() - items.size()));
    }

    private static int priority(DocumentTemplate document) {
        String title = document.title();
        if (document.conditionQuestionId() == null) {
            if (title.contains("주민등록") || title.contains("가족관계증명서")) return 0;
            if (title.contains("신청서") || title.startsWith("개인정보")) return 1;
        } else {
            if (title.matches(".*(혼인관계증명서|재학|수급자 증명|차상위|한부모가족 증명|신생아가구).*")) return 0;
            if (title.contains("가족관계증명서") || title.startsWith("금융정보") || title.startsWith("자산")) return 1;
        }
        return 2;
    }

    private static String shortTitle(String title) {
        return title.strip().replace("개인정보 수집·이용 및 제3자 제공 동의서", "개인정보 동의서")
            .replace("주민등록표등본", "주민등록등본").replace("주민등록표초본", "주민등록초본");
    }
}
