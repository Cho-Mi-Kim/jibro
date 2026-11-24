package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.jibro.collection.extraction.DocumentTextParser;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.ScheduleTemplate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AutomaticRankedScheduleTest {
    private static final LocalDate CHECKED = LocalDate.of(2026, 11, 23);

    @Test
    void originalPdfRowsRetainBothRanksResultsTimesAndPhysicalEvidence() throws Exception {
        var pdf = officialPdf();
        var result = AutomaticScheduleCompiler.compileScoped(List.of(listing(), pdf), CHECKED);
        assertTrue(result.complete(), result.issues().toString());
        assertTrue(result.reviewIssues().isEmpty(), result.reviewIssues().toString());
        assertEquals(8, result.schedules().size());
        var events = results(result);
        assertEquals(List.of("1순위(우선) 결과 발표", "1순위(일반)·2순위 결과 발표"), events.stream().map(ScheduleTemplate::title).toList());
        assertEquals(List.of("2026-12-22T17:00", "2027-01-27T17:00"), events.stream().map(ScheduleTemplate::date).toList());
        assertEquals(List.of("17:00 이후 발표", "17:00 이후 발표"), events.stream().map(ScheduleTemplate::timingDescription).toList());
        assertEquals("공고문 6쪽 · 표 2 행 2 열 4 · 신청순위 표 1 행 2", events.get(0).evidence().label());
        assertEquals("공고문 6쪽 · 표 4 행 2 열 4 · 신청순위 표 3 행 2", events.get(1).evidence().label());
        assertTrue(AutomaticScheduleCompiler.compileIntake(List.of(pdf), CHECKED).schedules().stream()
                .noneMatch(event -> event.title().contains("결과 발표")));
    }

    @Test
    void onlyTheUncertainOrConflictingResultIsQuarantinedAndIntakeRemains() throws Exception {
        var pdf = officialPdf();
        for (String replacement : List.of("추후 안내", "확인 중", "검토 후 2027.1.27 예정", "‘27.1.28(수)")) {
            boolean conflicting = replacement.startsWith("‘");
            var changed = change(pdf, "‘27.1.27(수)", replacement);
            var inputs = conflicting ? List.of(listing(), pdf, changed) : List.of(listing(), changed);
            var result = AutomaticScheduleCompiler.compileScoped(inputs, CHECKED);
            assertTrue(result.complete(), result.issues().toString());
            assertEquals(LocalDate.of(2026, 11, 23), result.startDate());
            assertEquals(LocalDate.of(2026, 12, 15), result.endDate());
            assertEquals(List.of("2026-12-22T17:00"), results(result).stream().map(ScheduleTemplate::date).toList());
            assertEquals(1, result.reviewIssues().size(), result.reviewIssues().toString());
            assertTrue(result.reviewIssues().get(0).title().contains("1순위(일반)·2순위"));
            assertTrue(result.reviewIssues().get(0).evidence().stream().allMatch(e -> e.label().contains("표 4 행 2 열 4")));
        }
        var invalid = AutomaticScheduleCompiler.compileScoped(List.of(change(pdf, "‘27.1.27(수)", "‘27.2.30(수)")), CHECKED);
        assertFalse(invalid.complete());
        assertTrue(invalid.issues().contains("SCHEDULE_INVALID_DATE:RESULT"));
        assertTrue(invalid.reviewIssues().isEmpty());
    }

    @Test
    void resultOrderUsesTheSameTableCohortAndIndividualNoticeCannotMasqueradeAsASecondFinalResult() throws Exception {
        var pdf = officialPdf();
        var firstCohort = change(pdf, "‘26.12.22(화)", "‘26.12.1(화)");
        var beforeLaterIntake = AutomaticScheduleCompiler.compileScoped(List.of(firstCohort), CHECKED);
        assertTrue(beforeLaterIntake.complete(), beforeLaterIntake.issues().toString());
        assertTrue(beforeLaterIntake.reviewIssues().isEmpty());
        assertEquals(List.of("2026-12-01T17:00", "2027-01-27T17:00"), results(beforeLaterIntake).stream().map(ScheduleTemplate::date).toList());

        var impossible = change(pdf, "‘26.12.22(화)", "‘26.11.22(화)");
        var beforeOwnIntake = AutomaticScheduleCompiler.compileScoped(List.of(impossible), CHECKED);
        assertTrue(beforeOwnIntake.complete(), beforeOwnIntake.issues().toString());
        assertEquals(List.of("2027-01-27T17:00"), results(beforeOwnIntake).stream().map(ScheduleTemplate::date).toList());
        assertEquals(1, beforeOwnIntake.reviewIssues().size());
        assertTrue(beforeOwnIntake.reviewIssues().get(0).title().contains("1순위(우선)"));

        var individual = new Source("추가 공식 안내", "https://apply.lh.or.kr/individual", "b".repeat(64), "EXTRACTED", "",
                List.of(), List.of(new Part("본문 1", "결과 발표: 개별 안내")));
        var unresolvedScope = AutomaticScheduleCompiler.compileScoped(List.of(pdf, individual), CHECKED);
        assertTrue(unresolvedScope.complete(), unresolvedScope.issues().toString());
        assertEquals(2, results(unresolvedScope).size());
        assertEquals(1, unresolvedScope.reviewIssues().size());
        assertTrue(unresolvedScope.schedules().stream().noneMatch(event -> event.date().isBlank()));
    }

    @Test
    void independentCommonAnnouncementAndDifferentTimeQualifierCannotDisappear() throws Exception {
        var pdf = officialPdf();
        var independent = new Source("추가 공식 안내", "https://apply.lh.or.kr/additional", "b".repeat(64), "EXTRACTED", "",
                List.of(), List.of(new Part("본문 1", "결과 발표: 2026.12.22")));
        var independentResult = AutomaticScheduleCompiler.compileScoped(List.of(pdf, independent), CHECKED);
        assertTrue(independentResult.complete(), independentResult.issues().toString());
        assertEquals(2, results(independentResult).size());
        assertEquals(1, independentResult.reviewIssues().size());
        assertTrue(independentResult.reviewIssues().get(0).evidence().stream().anyMatch(e -> e.url().equals(independent.url())));

        var exactTime = change(pdf, "(17:00 이후)", "(17:00)");
        var conflict = AutomaticScheduleCompiler.compileScoped(List.of(pdf, exactTime), CHECKED);
        assertTrue(conflict.complete(), conflict.issues().toString());
        assertTrue(results(conflict).isEmpty());
        assertEquals(2, conflict.reviewIssues().size());
    }

    private static List<ScheduleTemplate> results(AutomaticScheduleCompiler.ScopedResult result) {
        return result.schedules().stream().filter(event -> event.title().contains("결과 발표")).toList();
    }

    private Source officialPdf() throws Exception {
        try (var input = getClass().getResourceAsStream("/extraction/lh-bukgu-69296405.pdf")) {
            assertNotNull(input);
            return new Source("공고문.pdf", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69296405", "a".repeat(64), "EXTRACTED", "",
                    List.of(), new DocumentTextParser().parse(input.readAllBytes(), "application/pdf"));
        }
    }

    private Source listing() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        try (var input = getClass().getResourceAsStream("/review/bukgu-source.json")) {
            assertNotNull(input);
            return json.treeToValue(json.readTree(input).get("draft").get("sources").get(0), Source.class);
        }
    }

    private Source change(Source source, String before, String after) {
        assertTrue(source.context().stream().anyMatch(part -> part.text().contains(before)), before);
        return new Source(source.label(), source.url() + "&version=other", "c".repeat(64), source.status(), source.message(), List.of(),
                source.context().stream().map(part -> new Part(part.location(), part.text().replace(before, after))).toList());
    }
}
