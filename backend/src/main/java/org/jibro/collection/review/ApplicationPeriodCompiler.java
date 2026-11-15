package org.jibro.collection.review;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

import org.jibro.collection.extraction.ExtractionModels.Draft;
import org.jibro.notice.ApplicationPeriod;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.HousingModels.ScheduleTemplate;

/**
 * A period can be verified independently of qualification rules, never independently of its sources.
 */
public final class ApplicationPeriodCompiler {
    public static final String POLICY = "application-period-v1";
    private static final Pattern OPEN_ENDED = Pattern.compile("상시\\s*(?:모집|접수)|(?:소진|충원|공급\\s*종료|별도\\s*공고)\\s*(?:시|때|까지)|마감\\s*없이|무기한|도착\\s*순서.*계약|선착순.*(?:계약|공급)");

    public record Result(LocalDate start, LocalDate end, List<ScheduleTemplate> schedules, List<String> issues) {
        public boolean ready() {
            return start != null && end != null && issues.isEmpty();
        }
    }

    private ApplicationPeriodCompiler() {
    }

    public static Result assess(Draft draft, Content content) {
        var coverage = SourceCoveragePolicy.assessScoped(draft, content);
        // Qualification wording differences need not block a period: all variants below are read
        // together by the schedule compiler, so disagreeing dates remain a hard blocker.
        var issues = new ArrayList<>(coverage.issues().stream()
            .filter(issue -> !issue.startsWith("DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:")).toList());
        if (draft != null && draft.sources().stream().flatMap(s -> s.context().stream())
            .anyMatch(part -> openEnded(part.text())))
            issues.add("OPEN_ENDED_APPLICATION_REQUIRES_REVIEW");
        if (!issues.isEmpty()) return new Result(null, null, List.of(), List.copyOf(issues));
        var schedule = AutomaticScheduleCompiler.compileScoped(coverage.scheduleSources(),
            draft.checkedAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
        if (!schedule.complete()) return new Result(null, null, List.of(), schedule.issues());
        var boundaries = schedule.schedules().stream().filter(s -> ApplicationPeriod.basicTitle(s.title(), false)
            || ApplicationPeriod.basicTitle(s.title(), true)).toList();
        if (boundaries.size() != 2) return new Result(null, null, List.of(), List.of("APPLICATION_BOUNDARY_MISSING"));
        return new Result(schedule.startDate(), schedule.endDate(), boundaries, List.of());
    }

    private static boolean openEnded(String text) {
        // Neither a legal definition of an already owned purchase right nor a sales office's
        // opening period describes this recruitment's application window.
        String applicationText = text.replaceAll("선착순의\\s*방법으로\\s*공급받은\\s*분양권", "기취득 분양권");
        applicationText = applicationText.replaceAll("운영기간\\s*[:：][^\\r\\n]*", "");
        return OPEN_ENDED.matcher(applicationText).find();
    }
}
