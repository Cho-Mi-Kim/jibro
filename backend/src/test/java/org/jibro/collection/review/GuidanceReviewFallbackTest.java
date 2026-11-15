package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class GuidanceReviewFallbackTest {
    private static final String PAGE = "https://apply.lh.or.kr/notice";
    private static final String PREFIX = "https://apply.lh.or.kr/notice.";

    @Test void unresolvedIncomeDocumentScopeBlocksBothHwpxAndUnrecognizedPdfProfiles() {
        for (String format : List.of("hwpx", "pdf")) {
            var result = assess(format, "입주자격 완화 주요 내용 소득요건 배제",
                "태아를 월평균소득기준 가구원수에 포함하려면 임신진단서를 제출합니다.", "");
            assertFalse(result.ready(), format);
            assertNotNull(result.candidate(), "a source-confirmation draft can still be inspected");
            assertEquals("official-evidence", result.profile());
            assertTrue(result.issues().contains("WAIVED_INCOME_DOCUMENT_SCOPE_UNRESOLVED"));
            assertEquals(PREFIX + format, result.findings().get(0).evidence().url());
        }
    }

    @Test void firstComeScoringConflictDoesNotDependOnTypedTemplateAvailability() {
        var result = assess("hwpx", "거주지역, 순위 및 배점 등에 상관없이 신청접수순번대로 선정합니다.",
            "가족관계증명서: 육아지원세대 배점을 받고자 하는 신청자가 제출합니다.", "");
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(result.candidate().reviewIssues().stream().anyMatch(i -> i.area().equals("DOCUMENT") && i.title().contains("가산점")));
        assertTrue(result.findings().stream().anyMatch(f -> f.code().equals("FIRST_COME_SCORING_DOCUMENT_SCOPE_UNRESOLVED")));
    }

    @Test void fallbackIsolatesContradictoryRecognizedDocumentRules() {
        var result = assess("hwpx", "", "공통 제출서류: 재학증명서", "");
        assertNotNull(result.candidate());
        assertTrue(result.ready(), result.issues().toString());
        assertEquals(2, result.candidate().reviewIssues().size());
        assertTrue(result.candidate().reviewIssues().stream().allMatch(issue -> issue.area().equals("DOCUMENT")));
        assertTrue(result.candidate().reviewIssues().stream().allMatch(issue -> !issue.documentIds().isEmpty()));
        assertTrue(result.candidate().questions().stream().anyMatch(question -> !question.reviewRequired()));
    }

    @Test void finalScheduleGateIsolatesAnAnnouncementBeforeApplicationClosing() {
        for (String event : List.of("결과 발표", "서류 제출 대상자 발표")) {
            var result = assess("hwpx", "", "", event + ": 2026.11.20");
            assertTrue(result.ready(), event + result.issues());
            assertTrue(result.candidate().reviewIssues().stream().anyMatch(i -> i.area().equals("SCHEDULE")));
            assertEquals(java.time.LocalDate.of(2026,11,25),result.startDate());
            assertEquals(java.time.LocalDate.of(2026,11,26),result.endDate());
            assertEquals(2,result.candidate().schedules().size());
        }
    }

    @Test void ordinaryProseAndDocumentsBeforeFinalResultsRemainValidFallbacks() {
        var result = assess("hwpx", "공고에 제시된 세부 예외도 함께 확인하세요.", "",
            "서류 제출 대상자 발표: 2026.11.27\n서류 마감: 2026.11.30\n결과 발표: 2026.12.10");
        assertTrue(result.ready(), result.issues().toString());
        assertEquals(5, result.candidate().schedules().size());
    }

    private static GuidanceReviewCompiler.Assessment assess(String format, String conditions, String documents, String schedules) {
        var evidence = new Evidence("공식 상세 본문", PAGE, "2026-11-04");
        var original = new Content("공고 안내", evidence,
            List.of(new Evidence("모집공고." + format, PREFIX + format, "2026-11-04")), List.of(), List.of(), List.of());
        String body = """
            입주자모집공고일: 2026.11.04
            1. 신청 자격
            신청자는 무주택자이어야 합니다.
            %s
            2. 제출서류
            공통 제출서류: 가족관계증명서
            %s
            3. 모집 일정
            접수기간: 2026.11.25 ~ 2026.11.26
            %s
            """.formatted(conditions, documents, schedules);
        var listing = new Source("공식 상세 본문", PAGE, "a".repeat(64), "EMPTY", "", List.of(),
            List.of(new Part("공식 상세 본문", "공고일:2026.11.04")));
        var attachment = new Source("모집공고." + format, PREFIX + format, "b".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part(format.equals("pdf") ? "공고문 1쪽" : "문서 구간 1", body)));
        var draft = new Draft("extraction", "notice", 1, "PARTIAL", Instant.parse("2026-11-04T00:00:00Z"), "", List.of(listing, attachment));
        return GuidanceReviewCompiler.assess(draft, original);
    }
}
