package org.jibro.collection.review;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Draft;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.HousingModels.ScheduleTemplate;

/**
 * A period can be verified independently of qualification rules, never independently of its sources.
 */
public final class ApplicationPeriodCompiler {
    public static final String POLICY = "application-period-v8-reading-failures";
    private static final Pattern OPEN_ENDED = Pattern.compile("상시\\s*(?:모집|접수)|(?:소진|충원|모집\\s*완료|공급\\s*종료|별도\\s*공고)\\s*(?:시|때|까지)|마감\\s*없이|무기한|도착\\s*순서.*계약|선착순.*(?:계약|공급)");

    public record Result(LocalDate start, LocalDate end, List<ScheduleTemplate> schedules, List<String> issues,
                         List<org.jibro.notice.HousingModels.ReviewIssue> reviewIssues) {
        public Result(LocalDate start, LocalDate end, List<ScheduleTemplate> schedules, List<String> issues) {
            this(start, end, schedules, issues, List.of());
        }

        public boolean ready() {
            return (start != null || end != null || schedules.stream().anyMatch(ApplicationPeriodCompiler::explanation)) && issues.isEmpty();
        }
    }

    private ApplicationPeriodCompiler() {
    }

    public static Result assess(Draft draft, Content content) {
        var coverage = SourceCoveragePolicy.assessIntake(draft, content);
        // Qualification wording differences need not block a period: all variants below are read
        // together by the schedule compiler, so disagreeing dates remain a hard blocker.
        var issues = coverage.issues().stream().filter(issue -> !issue.startsWith("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:")).toList();
        if (!issues.isEmpty()) return new Result(null, null, List.of(), issues);
        return fromVerifiedSources(coverage.scheduleSources(), draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
    }

    static Result fromVerifiedSources(List<Source> sources, LocalDate checked) {
        var schedule = AutomaticScheduleCompiler.compileIntake(sources, checked);
        if (schedule.endDate() != null && sources.stream().flatMap(s -> s.context().stream())
                .anyMatch(part -> openEnded(part.text())))
            return new Result(null, null, List.of(), List.of("OPEN_ENDED_APPLICATION_REQUIRES_REVIEW"));
        return new Result(schedule.startDate(), schedule.endDate(), schedule.schedules(), schedule.issues(), schedule.reviewIssues());
    }

    public static boolean disputed(Result result) {
        return result.issues().stream().anyMatch(i -> i.startsWith("SCHEDULE_CONFLICT:"))
                || result.schedules().stream().anyMatch(s -> s.title().equals(org.jibro.notice.HousingModels.ApplicationTiming.UNDATED_HOURS_SESSION));
    }

    public static boolean missingBoundary(Result result) {
        if (result.issues().stream().anyMatch(i -> !i.equals("APPLICATION_PERIOD_MISSING")
                && !i.equals("SCHEDULE_SCOPED_PERIOD_MISSING"))) return false;
        return result.start() == null || result.end() == null || result.schedules().stream()
                .anyMatch(s -> (org.jibro.notice.ApplicationPeriod.basicTitle(s.title(), false)
                        || org.jibro.notice.ApplicationPeriod.basicTitle(s.title(), true) || session(s) && !s.id().startsWith("auto-visit-"))
                        && s.date() != null && s.date().matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    public static Result reconcile(Draft draft, Content content, List<IntakeChannelDates.Window> windows) {
        var coverage = SourceCoveragePolicy.assessIntake(draft, content);
        if (coverage.issues().stream().anyMatch(i -> !i.startsWith("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:")))
            return null;
        return AutomaticScheduleCompiler.reconcile(coverage.scheduleSources(),
                draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate(), windows, List.of());
    }

    public static Content withIntake(Content before, List<ScheduleTemplate> intake,
                                     List<org.jibro.notice.HousingModels.ReviewIssue> issues) {
        var schedules = new java.util.ArrayList<>(before.schedules().stream()
                .filter(s -> !org.jibro.notice.ApplicationPeriod.basicTitle(s.title(), false)
                        && !org.jibro.notice.ApplicationPeriod.basicTitle(s.title(), true) && !session(s)).toList());
        schedules.addAll(intake);
        var review = new java.util.ArrayList<>(before.reviewIssues().stream()
                .filter(issue -> !issue.id().startsWith("intake-review-auto-")).toList());
        review.addAll(issues);
        return new Content(before.summary(), before.evidence(), before.attachments(), before.questions(), before.documents(),
                List.copyOf(schedules), List.copyOf(review), before.applicationClosure());
    }

    public static boolean session(ScheduleTemplate schedule) {
        return schedule.id().startsWith("auto-scoped-") || schedule.id().startsWith("auto-visit-") || explanation(schedule);
    }

    public static boolean explanation(ScheduleTemplate schedule) {
        return schedule.id().equals("auto-until-filled") || schedule.id().startsWith("auto-undated-");
    }

    private static boolean openEnded(String text) {
        // Neither a legal definition of an already owned purchase right nor a sales office's
        // opening period describes this recruitment's application window.
        String applicationText = text.replaceAll("선착순의\\s*방법으로\\s*공급받은\\s*분양권", "기취득 분양권");
        applicationText = applicationText.replaceAll("운영기간\\s*[:：][^\\r\\n]*", "");
        return OPEN_ENDED.matcher(applicationText).find();
    }
}
