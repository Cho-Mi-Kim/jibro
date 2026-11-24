package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.junit.jupiter.api.Test;

class IndividualAnnouncementCompilerTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final String PERIOD = "신청 접수 기간: 2026.03.24 ~ 2026.12.31 18:00\n";
    private static final String DELIVERY = "※ 대상자 선정 및 소명대상 안내 등은 SMS 및 e-mail을 발송할 예정이오니 연락처를 확인하세요.";
    private static final String TEN = "■ 입주대상자 선정안내 : (소득 · 자산확인 필요시) 신청일로부터 최소 10주 소요\n"
            + "※ 수급자, 차상위계층 및 한부모가족(증명서 제출 가능자)은 입주대상자 선정 기간이 단축될 수 있음\n"
            + "※ 제출서류 미비 또는 자격검증 소요기간에 따라 발표 일정이 연기될 수 있음\n" + DELIVERY;

    private Source source(String text) {
        return new Source("공고문.pdf", "https://apply.lh.or.kr/official.pdf", "verified-sha", "EXTRACTED", "",
                List.of(), List.of(new Part("공고문 3쪽", text)));
    }

    private AutomaticScheduleCompiler.ScopedResult compile(String text) {
        return AutomaticScheduleCompiler.compileScoped(List.of(source(PERIOD + text)), DAY);
    }

    @Test
    void retainsOwnDurationConditionsExceptionsAndEvidenceWithoutInventingDate() {
        var result = compile(TEN);
        assertTrue(result.complete(), result.issues().toString());
        var event = result.schedules().stream().filter(s -> s.timingDescription() != null).findFirst().orElseThrow();
        assertEquals("", event.date());
        assertEquals("결과 발표 · 개별 안내", event.title());
        assertTrue(event.timingDescription().contains("소득 · 자산확인 필요시"));
        assertTrue(event.timingDescription().contains("최소 10주"));
        assertTrue(event.timingDescription().contains("증명서 제출 가능자"));
        assertTrue(event.timingDescription().contains("연기될 수 있음"));
        assertTrue(event.timingDescription().contains("SMS 및 e-mail"));
        assertEquals("2026-11-10", event.evidence().checkedAt());
        assertTrue(event.evidence().label().contains("3쪽"));
        assertTrue(result.reviewIssues().isEmpty());
        var four = compile("■ 입주대상자 선정안내 : 신청일로부터 최소 4주 소요\n" + DELIVERY);
        assertTrue(four.schedules().stream().anyMatch(s -> s.timingDescription() != null && s.timingDescription().contains("4주")));
        assertTrue(four.schedules().stream().noneMatch(s -> s.timingDescription() != null && s.timingDescription().contains("10주")));
    }

    @Test
    void jeonjuIndividualTextKeepsThreeMonthEstimateAndVerificationCondition() {
        var events = IndividualAnnouncementCompiler.read(List.of(source("■ 예비입주자 발표\n • 발표일 : 자격검증 결과 적격자에 한하여 개별 문자 통보\n"
                + "(신청일로부터 3개월 이후 발표가 예상되며 자격검증 일정에 따라 발표일은 변동 가능)\n"
                + "• 예비입주자 선정결과는 아래 방법으로 확인할 수 있으며, 발표는 자격검색 처리 기간에 따라 변동\n될 수 있습니다.\n■ 계약안내\n개별 계약 안내")), DAY);
        assertEquals(1, events.size());
        assertEquals("", events.get(0).date());
        assertTrue(events.get(0).timingDescription().contains("개별 문자 통보"));
        assertTrue(events.get(0).timingDescription().contains("3개월 이후 발표가 예상"));
        assertTrue(events.get(0).timingDescription().contains("발표일은 변동 가능"));
        assertFalse(events.get(0).timingDescription().contains("계약"));
    }

    @Test
    void directAnnouncementKeepsEligibilityAndVariableTiming() {
        var events = IndividualAnnouncementCompiler.read(List.of(source("■ 예비입주자 발표\n • 발표일 : 적격자에 한하여 개별통보\n"
                + "• 예비입주자 선정결과는 아래 방법으로 확인할 수 있으며, 발표는 자격검색 처리 기간에 따라 변동될 수 있습니다.\n■ 계약안내\n개별 계약 안내")), DAY);
        assertEquals(1, events.size());
        assertTrue(events.get(0).timingDescription().contains("적격자에 한하여"));
        assertTrue(events.get(0).timingDescription().contains("변동될 수 있습니다"));
        assertFalse(events.get(0).timingDescription().contains("계약"));
    }

    @Test
    void doesNotConfuseContractsDisqualificationOrUnsupportedContextWithResults() {
        for (String text : List.of("당첨자 발표 후 개별 계약 안내합니다. 계약체결까지 최소 2~3개월 소요",
                "소득·자산 부적격자는 개별통보 후 소명기한을 부여합니다.", "결과 발표: 개별 안내하지 않습니다.",
                "계약체결시기 및 방법은 개별 통보", "입주대상자 선정안내 : 신청일로부터 최소 10주 소요")) {
            assertTrue(IndividualAnnouncementCompiler.read(List.of(source(text)), DAY).isEmpty(), text);
        }
        var s = source(TEN);
        assertTrue(IndividualAnnouncementCompiler.read(List.of(new Source(s.label(), s.url(), s.sha256(), "FAILED", "", List.of(), s.context())), DAY).isEmpty());
        assertTrue(IndividualAnnouncementCompiler.read(List.of(new Source(s.label(), s.url(), "", "EXTRACTED", "", List.of(), s.context())), DAY).isEmpty());
    }

    @Test
    void closedNoticesNeverGainIndividualAnnouncement() {
        var result = AutomaticScheduleCompiler.compileScoped(List.of(source(PERIOD.replace("2026.12.31", "2026.10.31") + TEN)), DAY);
        assertTrue(result.complete());
        assertTrue(result.schedules().stream().noneMatch(s -> s.timingDescription() != null));
    }

    @Test
    void contradictoryDurationsAndFixedFinalDateAreIsolatedWithoutRemovingDocuments() {
        for (var sources : List.of(List.of(source(PERIOD + TEN), source(TEN.replace("10주", "4주"))),
                List.of(source(PERIOD + TEN + "\n결과 발표: 2027.03.31\n서류 마감: 2027.04.01")))) {
            var result = AutomaticScheduleCompiler.compileScoped(sources, DAY);
            assertTrue(result.complete(), result.issues().toString());
            assertEquals(1, result.reviewIssues().size());
            assertTrue(result.reviewIssues().get(0).title().startsWith("결과 발표"));
            assertTrue(result.schedules().stream().noneMatch(s -> s.timingDescription() != null || s.id().equals("auto-result")));
            assertFalse(AutomaticScheduleCompiler.compile(sources, DAY).complete());
        }
    }

    @Test
    void identicalSourcesProduceOneEventAndPreliminarySelectionRemainsSeparate() {
        var result = AutomaticScheduleCompiler.compileScoped(List.of(source(PERIOD + TEN), source(TEN)), DAY);
        assertEquals(1, result.schedules().stream().filter(s -> s.timingDescription() != null).count());
        assertTrue(result.reviewIssues().isEmpty());
    }

    @Test
    void structuredTableBindsFinalResultColumnAndPreservesTentativeMonth() {
        String table = "⟦HWPX:TABLE id=1 rows=2 cols=2⟧\n"
                + cell(1, 1, "예비입주자 확정 발표") + cell(1, 2, "계약 안내")
                + cell(2, 1, "자격검증 및 소명완료 후\n개별통보\n(2027.1월예정)") + cell(2, 2, "개별안내") + "⟦HWPX:/TABLE⟧";
        var source = new Source("공고.hwpx", "https://apply.lh.or.kr/official.hwpx", "sha", "EXTRACTED", "", List.of(), List.of(new Part("HWPX Contents/section0.xml (쪽 번호 확인 필요)", table)));
        var events = IndividualAnnouncementCompiler.read(List.of(source), DAY);
        assertEquals(1, events.size());
        assertEquals("", events.get(0).date());
        assertTrue(events.get(0).timingDescription().contains("2027.1월예정"));
        assertTrue(events.get(0).evidence().label().contains("열 1"));
        var broken = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
                List.of(new Part(source.context().get(0).location(), table.replace("⟦HWPX:/TABLE⟧", ""))));
        assertTrue(IndividualAnnouncementCompiler.read(List.of(broken), DAY).isEmpty());
    }

    private String cell(int row, int column, String text) {
        return "⟦HWPX:CELL table=1 row=" + row + " col=" + column + " rowspan=1 colspan=1⟧\n" + text + "\n⟦HWPX:/CELL⟧\n";
    }
}
