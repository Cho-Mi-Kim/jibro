package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.junit.jupiter.api.Test;

class ConcurrentDocumentSubmissionTest {
    private static final LocalDate CHECKED = LocalDate.of(2026, 11, 10);
    private static final String PERIOD = "접수 기간: 2026.11.01 ~ 2026.12.31 18:00 (조기 중단 가능)";
    private static final String INSTRUCTION = "■ 신청 시 제출 서류 : 신청서 접수 시 스캔하여 첨부";

    private Source source(String url, String dates, String instruction) {
        return new Source("공식 공고문", url, "a".repeat(64), "EXTRACTED", "", List.of(),
                List.of(new Part("공고문 3쪽", dates), new Part("공고문 4쪽", instruction)));
    }

    private AutomaticScheduleCompiler.ScopedResult compile(String dates, String instruction) {
        return AutomaticScheduleCompiler.compileScoped(List.of(source("https://apply.lh.or.kr/a.pdf", dates, instruction)), CHECKED);
    }

    private long documents(AutomaticScheduleCompiler.ScopedResult result) {
        return result.schedules().stream().filter(s -> s.id().equals("auto-documents-end")).count();
    }

    @Test
    void bindsGeneralSubmissionToSameSourceAndPreservesTimeEarlyClosingAndBothLocations() {
        var result = compile(PERIOD, INSTRUCTION);
        assertTrue(result.complete(), result.issues().toString());
        var doc = result.schedules().stream().filter(s -> s.id().equals("auto-documents-end")).findFirst().orElseThrow();
        assertEquals("2026-12-31T18:00", doc.date());
        assertTrue(doc.title().contains("조기 중단 가능"));
        assertTrue(doc.evidence().label().contains("3쪽"));
        assertTrue(doc.evidence().label().contains("4쪽"));
        assertEquals("https://apply.lh.or.kr/a.pdf", doc.evidence().url());
    }

    @Test
    void recognizesOfficialOnlineAndGeneralPhysicalInstructionsWithoutInventingTime() {
        for (String instruction : List.of(INSTRUCTION,
                "■ 신청방법 : LH 청약플러스(https://apply.lh.or.kr)에서 신청(서류 스캔 첨부)",
                "■ 신청방법 : 제출서류 스캔 후 첨부하여 LH 청약플러스에서 신청",
                "신청 시 제출서류를 함께 제출해야 합니다.",
                "신청서류를 구비하지 않고 방문한 경우 접수가 불가능하오니 서류를 지참해 주세요.")) {
            var result = compile("접수 기간: 2026.11.01 ~ 2026.11.13", instruction);
            assertEquals(1, documents(result), instruction);
            assertEquals("2026-11-13", result.schedules().stream().filter(s -> s.id().equals("auto-documents-end")).findFirst().orElseThrow().date());
        }
    }

    @Test
    void explicitJointPeriodAssignsBothStages() {
        var result = compile("신청접수 및 서류제출 기간: 2026.11.01 ~ 2026.11.13 17:00", "");
        assertTrue(result.complete(), result.issues().toString());
        assertEquals(1, documents(result));
        assertEquals("2026-11-13T17:00", result.schedules().stream().filter(s -> s.id().equals("auto-documents-end")).findFirst().orElseThrow().date());
    }

    @Test
    void readsAnAdjacentStandaloneDocumentLabelButNotAnUnrelatedColumn() {
        for (String field : List.of("서류 제출\n2027.01.05", "서류 접수: 2027.01.05", "서류 제출 기간\n2027.01.02 ~ 2027.01.05")) {
            var result = compile(PERIOD + "\n" + field, "");
            assertEquals("2027-01-05", result.schedules().stream().filter(s -> s.id().equals("auto-documents-end")).findFirst().orElseThrow().date(), field);
        }
        assertEquals(0, documents(compile(PERIOD + "\n서류 제출\n결과 발표\n2027.01.05", "")));
    }

    @Test
    void separateExplicitStageWinsAndUnknownOrConflictingStageIsNotReplaced() {
        var explicit = compile(PERIOD + "\n서류 제출 마감: 2027.01.05", INSTRUCTION);
        assertEquals("2027-01-05", explicit.schedules().stream().filter(s -> s.id().equals("auto-documents-end")).findFirst().orElseThrow().date());
        for (String docs : List.of("서류 제출 마감: 추후 안내", "서류 제출 마감: 2027.01.05\n서류 제출 마감: 2027.01.06")) {
            var result = compile(PERIOD + "\n" + docs, INSTRUCTION);
            assertEquals(0, documents(result));
            assertFalse(result.reviewIssues().isEmpty());
        }
    }

    @Test
    void neverBorrowsAnotherSourcesApplicationDate() {
        var result = AutomaticScheduleCompiler.compileScoped(List.of(
                source("https://apply.lh.or.kr/a.pdf", PERIOD, ""),
                source("https://apply.lh.or.kr/b.pdf", "", INSTRUCTION)), CHECKED);
        assertTrue(result.complete());
        assertEquals(0, documents(result));
    }

    @Test
    void doesNotPromoteAnIndividualFormNegationOrContractStageToGeneralDeadline() {
        for (String instruction : List.of("금융정보 동의서는 신청 시 제출합니다.",
                "신청 시 제출서류를 제출하지 않습니다.", "신청 시 제출서류는 필요 없습니다.",
                "고령자는 신청 시 제출서류를 함께 제출해야 합니다.",
                INSTRUCTION + "\n단지별 서류 제출일은 다릅니다.",
                INSTRUCTION + "\n서류는 동호지정 계약 당일 제출합니다.",
                INSTRUCTION + "\n제출서류 중 일부는 별도로 제출합니다.")) {
            assertEquals(0, documents(compile(PERIOD, instruction)), instruction);
        }
    }

    @Test
    void rankScopedDatesMustNotBecomeASingleDocumentDeadline() {
        var result = compile("6. 모집일정\n1순위\n2026.11.12 10:00~16:00\n2순위\n2026.11.13 10:00~16:00", INSTRUCTION);
        assertEquals(0, documents(result));
    }

    @Test
    void anotherOfficialFileCanPreventIncorrectCommonSubmissionInference() {
        var result = AutomaticScheduleCompiler.compileScoped(List.of(
                source("https://apply.lh.or.kr/a.pdf", PERIOD, INSTRUCTION),
                source("https://apply.lh.or.kr/b.pdf", "", "서류는 계약 당일 제출합니다.")), CHECKED);
        assertEquals(0, documents(result));
    }
}
