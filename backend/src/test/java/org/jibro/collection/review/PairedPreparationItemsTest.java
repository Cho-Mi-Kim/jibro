package org.jibro.collection.review;

import java.time.Instant;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.source.NoticeSourceInformationModels.Area;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PairedPreparationItemsTest {
    private static final String HOST = "https://apply.lh.or.kr/";
    private static final String CONDITION = "모집공고일 현재 서울에 거주하는 무주택 세대구성원으로 미성년 세대주는 예외가 있습니다.";
    private static final String DOCUMENT = "• 배우자가 등본에 없는 신청자는 가족관계증명서를 제출합니다.";
    private static final String CONTRACT = "• 계약자의 배우자는 금융정보 제공동의서를 제출합니다.";
    private static final String NOTICE = CONDITION + "\n6. 제출서류\n" + DOCUMENT + "\n7. 계약안내\n" + CONTRACT;

    private static Source source(String name, String path, String location, String text) {
        return new Source(name, HOST + path, "c".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part(location, text)));
    }

    private static Draft pair(String pdf) {
        return new Draft("x", "y", 1, "READY", Instant.now(), "", List.of(source("공식 상세 본문", "detail", "본문", "모집"),
                source("모집공고.owpml", "original", "한글 원문", NOTICE), source("모집공고.pdf", "lhapply/lhFile.do?fileid=123", "공고문 4쪽", pdf)));
    }

    @Test
    void matchesWholeInstructionIncludingExceptionAndOnlyWithinSubmissionSection() {
        var sections = PairedPreparationItems.read(pair(NOTICE), HOST + "detail");
        assertEquals(2, sections.size());
        assertTrue(sections.stream().allMatch(s -> s.evidence().url().contains("#page=4&sha256=")));
        assertTrue(sections.stream().anyMatch(s -> s.area() == Area.CONDITION && s.text().equals(CONDITION)));
        assertTrue(sections.stream().anyMatch(s -> s.area() == Area.DOCUMENT && s.text().contains("등본에 없는")));
    }

    @Test
    void differingExceptionOrUnmatchedGoverningCorrectionCannotCertifyOldText() {
        assertTrue(PairedPreparationItems.read(pair(NOTICE.replace("예외가 있습니다", "신청할 수 없습니다")), HOST + "detail")
                .stream().noneMatch(s -> s.area() == Area.CONDITION));
        var draft = pair(NOTICE);
        var sources = new java.util.ArrayList<>(draft.sources());
        sources.add(source("자격 정정.pdf", "correction", "공고문 1쪽", NOTICE));
        assertTrue(PairedPreparationItems.read(new Draft("x", "y", 1, "READY", Instant.now(), "", sources), HOST + "detail").isEmpty());
    }

    @Test
    void matchingStructuredRowsKeepTheirWholeScopeDespiteUnrelatedLayoutDifferences() {
        String cells = "⟦HWPX:TABLE id=1 rows=2 cols=2⟧\n"
                + tableCell(1, 1, "계층") + tableCell(1, 2, "신청자격")
                + tableCell(2, 1, "청년") + tableCell(2, 2, "만 19세 이상 39세 이하인 미혼자. 소득이 없는 청년도 신청할 수 있습니다.")
                + "⟦HWPX:/TABLE⟧\n";
        var original = source("모집공고.hwpx", "original", "한글 원문", cells + "안내 전화: 지역 담당 부서");
        var pdf = source("모집공고.pdf", "lhapply/lhFile.do?fileid=123", "공고문 2쪽",
                cells.replace("HWPX", "PDF") + "안내 전화: 지역 담당 부서\n- 2 -");
        var sections = PairedPreparationItems.readPair(Instant.parse("2026-11-22T00:00:00Z"), original, pdf);
        assertEquals(1, sections.size());
        assertEquals("청년 신청자격", sections.get(0).title());
        assertEquals("만 19세 이상 39세 이하인 미혼자. 소득이 없는 청년도 신청할 수 있습니다.", sections.get(0).text());
        var changed = source(pdf.label(), "lhapply/lhFile.do?fileid=123", "공고문 2쪽", pdf.context().get(0).text().replace("없는 청년도 신청할 수", "없는 청년은 신청할 수 없"));
        assertTrue(PairedPreparationItems.readPair(Instant.parse("2026-11-22T00:00:00Z"), original, changed).isEmpty());
    }

    private String tableCell(int row, int column, String text) {
        return "⟦HWPX:CELL table=1 row=" + row + " col=" + column + " rowspan=1 colspan=1⟧\n" + text + "\n⟦HWPX:/CELL⟧\n";
    }

    @Test
    void ambiguousPageIsNotUsedAsEvidence() {
        var raw = pair(NOTICE);
        var sources = new java.util.ArrayList<>(raw.sources());
        var pdf = sources.remove(sources.size() - 1);
        sources.add(new Source(pdf.label(), pdf.url(), pdf.sha256(), pdf.status(), "", List.of(), List.of(new Part("공고문 4쪽", NOTICE), new Part("공고문 8쪽", NOTICE))));
        assertTrue(PairedPreparationItems.read(new Draft("x", "y", 1, "READY", Instant.now(), "", sources), HOST + "detail").isEmpty());
    }
}

