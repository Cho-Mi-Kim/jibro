package org.jibro.collection.review;

import java.time.Instant;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.FollowupReviewRepository.PriorGuide;
import org.jibro.notice.HousingModels.*;
import org.jibro.notice.source.NoticeSourceInformationModels.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreparationItemComparisonTest {
    final String host = "https://apply.lh.or.kr/";
    final Evidence source = new Evidence("공고문", host + "notice", "2026-11-17");
    final DocumentTemplate doc = new DocumentTemplate("resident", "주민등록등본", null, "온라인 발급", "정부24", null, source);

    Content guide(List<Question> q, List<DocumentTemplate> d) {
        return new Content("안내", source, List.of(), q, d, List.of());
    }

    Question question(String id, String value) {
        return new Question(id, "거주 기준", value, source);
    }

    Draft draft(String value) {
        return new Draft("d", "n", 1, "READY", Instant.now(), "", List.of(new Source("공식 상세 본문", host + "notice", "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("본문", value)))));
    }

    PriorGuide previous(Content guide) {
        return new PriorGuide(UUID.randomUUID(), 1, guide, null, null, "token", List.of());
    }

    @Test
    void readsEachAreaSeparatelyAndIgnoresDocumentRoutingQuestions() {
        var conditions = new PreparationItemComparison(draft("거주 기준: 서울 거주 2년 이상"), null, List.of()).coverage();
        assertEquals(1, conditions.conditions());
        assertEquals(0, conditions.documents());
        var docs = new PreparationItemComparison(draft("주민등록등본 제출 대상: 신청자 전원"), guide(List.of(question("s1-docs-1", "해당 서류 선택")), List.of(doc)), List.of()).coverage();
        assertEquals(0, docs.conditions());
        assertEquals(1, docs.documents());
    }

    @Test
    void correctionKeepsChangedCriterionAndDoesNotCopyAnUnconfirmedDocument() {
        var check = new PreparationItemComparison(null, guide(List.of(question("q2", "서울 거주 2년 이상")), List.of()), List.of());
        check.compare(previous(guide(List.of(question("q", "서울 거주 1년 이상")), List.of(doc))), Optional.empty());
        assertEquals(List.of("UPDATED", "RECHECK"), check.coverage().comparisons().stream().map(PreparationItemComparison.Check::state).toList());
        assertEquals(List.of("서울 거주 2년 이상"), check.sections(true).stream().map(Section::text).toList());
        assertTrue(check.requiresScopedPublication());
    }

    @Test
    void independentlyPairedLatestInstructionIncludesTheNewAudienceAndSource() {
        var latest = new Section("paired-registry", Area.DOCUMENT, doc.title(), "배우자가 다른 등본에 있으면 제출", source);
        var check = new PreparationItemComparison(null, null, List.of(latest));
        check.compare(previous(guide(List.of(), List.of(doc))), Optional.empty());
        assertEquals("CONFIRMED_IN_LATEST", check.coverage().comparisons().get(0).state());
        assertEquals(List.of(latest), check.sections(true));
    }

    @Test
    void exemptionMustHaveALatestSourceAndCannotRecreateARequiredChecklistDocument() {
        var latest = new Section("paired-registry", Area.DOCUMENT, doc.title(), "신청자 전원 제출 면제", source);
        var check = new PreparationItemComparison(null, null, List.of(latest));
        check.compare(previous(guide(List.of(), List.of(doc))), Optional.empty());
        assertEquals("NO_LONGER_REQUIRED", check.coverage().comparisons().get(0).state());
        assertEquals(latest.evidence(), check.coverage().comparisons().get(0).latestEvidence());
        assertTrue(check.requiresScopedPublication());
    }

    @Test
    void reissueBlocksUnpairedChangedGoverningPdfEvenIfTextLayerLooksIdentical() {
        var base = draft("원문");
        Source detail = base.sources().get(0);
        var page = new Part("공고문 1쪽", "신청 자격");
        var pdf = new Source("모집공고.pdf", host + "old.pdf", "a".repeat(64), "EXTRACTED", "", List.of(), List.of(page));
        var changed = new Source(pdf.label(), host + "new.pdf", "b".repeat(64), "EXTRACTED", "", List.of(), List.of(page));
        assertTrue(VerifiedReissueFiles.compare(new Draft("1", "a", 1, "READY", Instant.now(), "", List.of(detail, pdf)),
                new Draft("2", "b", 1, "READY", Instant.now(), "", List.of(detail, changed)), detail.url()).isEmpty());
    }

    @Test
    void reviewedScheduleRebindingReadsBorderMetadataThroughTheSharedTableReader() {
        Source detail = draft("원문").sources().get(0);
        String header = "■ 주택단지별 모집일정\n⟦HWPX:TABLE id=1 rows=1 cols=4⟧\n"
                + scheduleCell(1, 1, "단지명") + scheduleCell(1, 2, "주택형")
                + scheduleCell(1, 3, "신청접수") + scheduleCell(1, 4, "신청순위") + "⟦HWPX:/TABLE⟧\n";
        String body = "⟦HWPX:TABLE id=2 rows=1 cols=4⟧\n" + scheduleCell(2, 1, "가단지")
                + scheduleCell(2, 2, "26 36") + scheduleCell(2, 3, "11월 30일")
                + scheduleCell(2, 4, "1순위") + "⟦HWPX:/TABLE⟧\n거주 기준은 2년 이상입니다.";
        String pdf = "■ 주택단지별 모집일정 가단지 주택형 26 36 신청접수 11월30일 신청순위 1순위";
        java.util.function.BiFunction<String, String, Source> oldFile = (name, text) -> new Source("모집공고." + name,
                host + "old." + name, "a".repeat(64), "EXTRACTED", "", List.of(),
                List.of(new Part(name.equals("pdf") ? "공고문 1쪽" : "HWPX Contents/section0.xml (쪽 번호 확인 필요)", text)));
        var oldHwpx = oldFile.apply("hwpx", header + body);
        var oldPdf = oldFile.apply("pdf", pdf);
        var newHwpx = changedFile(oldHwpx, header + body.replace("26 36", "36 26"));
        var newPdf = changedFile(oldPdf, pdf.replace("26 36", "36 26"));
        var before = new Draft("1", "n", 1, "READY", Instant.now(), "", List.of(detail, oldHwpx, oldPdf));
        var after = new Draft("2", "n", 1, "READY", Instant.now(), "", List.of(detail, newHwpx, newPdf));
        assertTrue(VerifiedReissueFiles.compare(before, after, detail.url()).isPresent());
        var changedRule = new Draft("3", "n", 1, "READY", Instant.now(), "", List.of(detail,
                changedFile(newHwpx, newHwpx.context().get(0).text().replace("2년", "3년")), newPdf));
        assertTrue(VerifiedReissueFiles.compare(before, changedRule, detail.url()).isEmpty());
    }

    private static String scheduleCell(int table, int column, String value) {
        return "⟦HWPX:CELL table=" + table + " row=1 col=" + column
                + " rowspan=1 colspan=1 top=NONE bottom=DRAWN doublebottom=0.5mm⟧\n"
                + value + "\n⟦HWPX:/CELL⟧\n";
    }

    private Source changedFile(Source old, String text) {
        return new Source(old.label(), old.url().replace("old", "new"), "b".repeat(64), "EXTRACTED", "", List.of(),
                List.of(new Part(old.context().get(0).location(), text)));
    }

}
