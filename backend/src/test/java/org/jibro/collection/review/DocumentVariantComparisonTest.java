package org.jibro.collection.review;

import java.text.Normalizer;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class DocumentVariantComparisonTest {
    @Test
    void officialSuwonVariantsKeepProceduresSplitDocumentsAndRenewalExceptions() throws Exception {
        try (var input = getClass().getResourceAsStream("/review/suwon-main-variants.json")) {
            var fixture = new ObjectMapper().readValue(input, SplitTableFixture.class);
            assertTrue(DocumentVariantComparison.equivalent(fixture.whole(), fixture.fragments()));
            var source = fixture.fragments();
            for (String[] change : List.of(new String[]{"→", "←"}, new String[]{"17시", "18시"},
                new String[]{"※ 현장 접수", "※ 인터넷 접수"}, new String[]{"130%", "120%"}, new String[]{"↓", "↑"})) {
                var changed = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
                    source.context().stream().map(p -> new Part(p.location(), p.text().replace(change[0], change[1]))).toList());
                assertNotEquals(source.context(), changed.context());
                assertFalse(DocumentVariantComparison.equivalent(fixture.whole(), changed));
            }
        }
    }

    @Test
    void blankNameAndSignatureRowsCannotDisappearEvenWithOpenSides() {
        String whole = "⟦HWPX:TABLE id=1 rows=4 cols=2⟧\n";
        for (int r = 1; r <= 4; r++)
            for (int c = 1; c <= 2; c++)
                whole += cell("HWPX", 1, r, c, r == 1 ? (c == 1 ? "ApplicantName" : "ApplicantSign")
                    : r == 3 ? (c == 1 ? "ProxyName" : "ProxySign") : "")
                    .replace("colspan=1⟧", "colspan=1 left=NONE right=NONE top=DRAWN bottom=DRAWN⟧");
        whole += "⟦HWPX:/TABLE⟧";
        String first = "⟦PDF:TABLE id=1 rows=1 cols=2⟧\n" + cell("PDF", 1, 1, 1, "ApplicantName")
            + cell("PDF", 1, 1, 2, "ApplicantSign") + "⟦PDF:/TABLE⟧";
        String second = first.replace("ApplicantName", "ProxyName").replace("ApplicantSign", "ProxySign");
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", whole), source(new Part("1쪽", first), new Part("2쪽", second))));
    }

    @Test
    void officialSeoulIncomeGridRetainsAllItemsAcrossDetachedHeaderAndPageSeams() throws Exception {
        try (var input = getClass().getResourceAsStream("/review/seoul-income-split.json")) {
            var fixture = new ObjectMapper().readValue(input, SplitTableFixture.class);
            assertTrue(DocumentVariantComparison.equivalent(fixture.whole(), fixture.fragments()));
            assertTrue(DocumentVariantComparison.equivalent(fixture.fragments(), fixture.whole()));
            var source = fixture.fragments();
            var changed = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
                source.context().stream().map(p -> new Part(p.location(), p.text().replace("상시근로소득", "일용근로소득"))).toList());
            assertFalse(DocumentVariantComparison.equivalent(fixture.whole(), changed));
            var incomplete = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
                source.context().subList(0, source.context().size() - 1));
            assertFalse(DocumentVariantComparison.equivalent(fixture.whole(), incomplete));
        }
    }

    @Test
    void repeatedHeaderCannotReassignAProofEvenWhenBodyColumnsStillMatch() {
        String header = cell("HWPX", 1, 1, 1, "대학생").replace("colspan=1", "colspan=3")
            + cell("HWPX", 1, 1, 4, "청년") + cell("HWPX", 1, 1, 5, "고령자");
        String row = cell("HWPX", 1, 2, 1, "첫 증빙").replace("colspan=1", "colspan=3")
            + cell("HWPX", 1, 2, 4, "둘째 증빙") + cell("HWPX", 1, 2, 5, "셋째 증빙");
        String whole = "⟦HWPX:TABLE id=1 rows=3 cols=5⟧\n" + header + row
            + cell("HWPX", 1, 3, 1, "공통증빙").replace("colspan=1", "colspan=2") + cell("HWPX", 1, 3, 3, "재학증명서")
            + cell("HWPX", 1, 3, 4, "기타증빙").replace("colspan=1", "colspan=2") + "⟦HWPX:/TABLE⟧";
        String first = "⟦PDF:TABLE id=1 rows=2 cols=5⟧\n" + (header + row).replace("HWPX:", "PDF:") + "⟦PDF:/TABLE⟧";
        String last = "⟦PDF:TABLE id=1 rows=2 cols=3⟧\n" + cell("PDF", 1, 1, 1, "대학생")
            + cell("PDF", 1, 1, 2, "청년") + cell("PDF", 1, 1, 3, "고령자")
            + cell("PDF", 1, 2, 1, "공통증빙") + cell("PDF", 1, 2, 2, "재학증명서")
            + cell("PDF", 1, 2, 3, "기타증빙") + "⟦PDF:/TABLE⟧";
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", whole), source(new Part("1쪽", first), new Part("2쪽", last))));
    }

    @Test
    void fullWidthCaptionsDoNotEraseDocumentOwnershipBetweenColumns() {
        String table = "⟦HWPX:TABLE id=1 rows=3 cols=2⟧\n"
            + cell("HWPX", 1, 1, 1, "왼쪽은 대학생, 오른쪽은 청년의 서류입니다").replace("colspan=1", "colspan=2")
            + cell("HWPX", 1, 2, 1, "재학증명서 졸업증명서") + cell("HWPX", 1, 2, 2, "근로증명서")
            + cell("HWPX", 1, 3, 1, "각자 해당하는 서류만 제출").replace("colspan=1", "colspan=2") + "⟦HWPX:/TABLE⟧";
        String moved = table.replace("HWPX:", "PDF:").replace("재학증명서 졸업증명서", "재학증명서")
            .replace("근로증명서", "졸업증명서 근로증명서");
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", table), named("공고.pdf", moved)));
    }

    @Test
    void captionedDirectionsRetainCompleteStepOrder() {
        String table = "⟦HWPX:TABLE id=1 rows=2 cols=3⟧\n"
            + cell("HWPX", 1, 1, 1, "선정 순서").replace("colspan=1", "colspan=3")
            + cell("HWPX", 1, 2, 1, "높은 점수 우선") + cell("HWPX", 1, 2, 2, "→")
            + cell("HWPX", 1, 2, 3, "동점일 때 추첨") + "⟦HWPX:/TABLE⟧";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", table), named("공고.pdf", "선정 순서 높은 점수 우선 → 동점일 때 추첨")));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", table), named("공고.pdf", "선정 순서 동점일 때 추첨 → 높은 점수 우선")));
    }

    @Test
    void repeatedPageHeadersDoNotHideChangedOrMissingRows() {
        String header = cell("HWPX", 1, 1, 1, "대상") + cell("HWPX", 1, 1, 2, "서류");
        String table = "⟦HWPX:TABLE id=1 rows=3 cols=2⟧\n" + header
            + cell("HWPX", 1, 2, 1, "대학생") + cell("HWPX", 1, 2, 2, "재학증명서")
            + cell("HWPX", 1, 3, 1, "청년") + cell("HWPX", 1, 3, 2, "근로증명서. 무직이면 제외") + "⟦HWPX:/TABLE⟧";
        String first = "⟦PDF:TABLE id=1 rows=2 cols=2⟧\n" + header.replace("HWPX:", "PDF:")
            + cell("PDF", 1, 2, 1, "대학생") + cell("PDF", 1, 2, 2, "재학증명서") + "⟦PDF:/TABLE⟧";
        String second = "⟦PDF:TABLE id=1 rows=2 cols=2⟧\n" + header.replace("HWPX:", "PDF:")
            + cell("PDF", 1, 2, 1, "청년") + cell("PDF", 1, 2, 2, "근로증명서. 무직이면 제외") + "⟦PDF:/TABLE⟧";
        var whole = named("공고.hwpx", table);
        assertTrue(DocumentVariantComparison.equivalent(whole, source(new Part("1쪽", first), new Part("2쪽", second))));
        assertFalse(DocumentVariantComparison.equivalent(whole, source(new Part("1쪽", first), new Part("2쪽", second.replace("무직이면 제외", "무직이어도 필수")))));
        assertFalse(DocumentVariantComparison.equivalent(whole, source(new Part("1쪽", first))));
    }

    private Source source(Part... parts) {
        return new Source("모집공고.pdf", "https://apply.lh.or.kr/recruitment.pdf", "a".repeat(64),
            "EXTRACTED", "", List.of(), List.of(parts));
    }

    @Test
    void repeatedHeaderAllowsOnlyColumnsMergedThroughoutTheContinuedPage() {
        String header = cell("HWPX", 1, 1, 1, "서류").replace("colspan=1", "colspan=2") + cell("HWPX", 1, 1, 3, "설명");
        String whole = "⟦HWPX:TABLE id=1 rows=3 cols=3⟧\n" + header
            + cell("HWPX", 1, 2, 1, "공통") + cell("HWPX", 1, 2, 2, "등본") + cell("HWPX", 1, 2, 3, "전원 제출")
            + cell("HWPX", 1, 3, 1, "초본").replace("colspan=1", "colspan=2") + cell("HWPX", 1, 3, 3, "주소 변경 시 제출") + "⟦HWPX:/TABLE⟧";
        String first = "⟦PDF:TABLE id=1 rows=2 cols=3⟧\n" + header.replace("HWPX:", "PDF:")
            + cell("PDF", 1, 2, 1, "공통") + cell("PDF", 1, 2, 2, "등본") + cell("PDF", 1, 2, 3, "전원 제출") + "⟦PDF:/TABLE⟧";
        String last = "⟦PDF:TABLE id=1 rows=2 cols=2⟧\n" + cell("PDF", 1, 1, 1, "서류") + cell("PDF", 1, 1, 2, "설명")
            + cell("PDF", 1, 2, 1, "초본") + cell("PDF", 1, 2, 2, "주소 변경 시 제출") + "⟦PDF:/TABLE⟧";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", whole), source(new Part("1쪽", first), new Part("2쪽", last))));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", whole), source(new Part("1쪽", first), new Part("2쪽", last.replace("주소 변경 시", "모두")))));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", whole), source(new Part("1쪽", first), new Part("2쪽", last.replace("서류", "조건")))));
    }

    @Test
    void whitespacePageCountersAndUnicodeCompositionAreLosslessLayoutDifferences() {
        var first = source(new Part("공고문 1쪽", "신청자는 무주택자이어야 합니다.\n- 1 -\n"));
        var second = source(new Part("문서 구간 1", Normalizer.normalize("신청자는\n무주택자이어야 합니다.", Normalizer.Form.NFD)));
        assertTrue(DocumentVariantComparison.equivalent(first, second));
    }

    @Test
    void explicitCompletePageNumbersCanRestoreCollectionOrder() {
        var one = new Part("공고문 1쪽", "신청자는 무주택자이어야 합니다.");
        var two = new Part("공고문 2쪽", "단, 아래의 예외를 함께 적용합니다.");
        assertTrue(DocumentVariantComparison.equivalent(source(one, two), source(two, one)));
    }

    @Test
    void arbitraryParagraphsOrDuplicatePageNumbersCannotBeReordered() {
        var first = source(new Part("문서 구간 1", "우선 적용: 소득 100% 이하"), new Part("문서 구간 2", "그 밖에 소득 150% 이하"));
        var reversed = source(new Part("문서 구간 2", "그 밖에 소득 150% 이하"), new Part("문서 구간 1", "우선 적용: 소득 100% 이하"));
        assertFalse(DocumentVariantComparison.equivalent(first, reversed));
        assertFalse(DocumentVariantComparison.equivalent(
            source(new Part("1쪽", "소득 100% 이하"), new Part("1쪽", "자동차 150만원 이하")),
            source(new Part("1쪽", "자동차 150만원 이하"), new Part("1쪽", "소득 100% 이하"))));
    }

    @Test
    void equalCharacterCountsAndSameNumbersDoNotReconcileTableSubjectChanges() {
        var first = source(new Part("1쪽", "신청자 소득 100% 이하\n배우자 소득 150% 이하"));
        var reassigned = source(new Part("1쪽", "신청자 소득 150% 이하\n배우자 소득 100% 이하"));
        assertFalse(DocumentVariantComparison.equivalent(first, reassigned));
        assertFalse(DocumentVariantComparison.equivalent(first,
            source(new Part("1쪽", "신청자 소득 100% 초과\n배우자 소득 150% 이하"))));
    }

    @Test
    void actualIksanMergedTableReadingOrderRequiresCorrespondenceNotASimilarityScore() {
        // Official 2026.11.04 Iksan notice, page 1 (files 69296594/69296595). The PDF
        // renderer moves the left heading into the value. This is not evidence of a contradiction,
        // but the flat streams alone do not prove the subject/exception association either.
        var hwpx = source(new Part("문서 구간 1", "▪ 자동차 가액\n완화요건 미적용\n(세대구성원 전원이 보유하고 있는 개별 자동차가액(4,542)만원 이하)"));
        var pdf = source(new Part("공고문 1쪽", "▪ 완화요건 미적용 자동차 가액\n(세대구성원 전원이 보유하고 있는 개별 자동차가액(4,542)만원 이하)"));
        assertFalse(DocumentVariantComparison.equivalent(hwpx, pdf));
    }

    @Test
    void missingExceptionsOrMissingPagesRemainUnverified() {
        var complete = source(new Part("1쪽", "신청자는 무주택자이어야 합니다."), new Part("2쪽", "소형·저가주택은 예외입니다."));
        assertFalse(DocumentVariantComparison.equivalent(complete, source(new Part("1쪽", "신청자는 무주택자이어야 합니다."))));
        assertFalse(DocumentVariantComparison.equivalent(
            source(new Part("1쪽", "신청자는 무주택자이어야 합니다."), new Part("3쪽", "소형·저가주택은 예외입니다.")),
            source(new Part("3쪽", "소형·저가주택은 예외입니다."), new Part("1쪽", "신청자는 무주택자이어야 합니다."))));
    }

    @Test
    void structuredVariantsCompareCompleteCellMeaningAcrossDocumentSpecificIdsAndLayoutBoxes() {
        String cells = cell("HWPX", 8, 1, 1, "항목") + cell("HWPX", 8, 1, 2, "기준")
            + cell("HWPX", 8, 2, 1, "자동차 가액")
            + cell("HWPX", 8, 2, 2, "완화요건 미적용 (세대구성원 전원이 보유한 개별 자동차가액 4,542만원 이하)");
        String hwpx = "⟦HWPX:TABLE id=8 rows=2 cols=2⟧\n" + cells + "⟦HWPX:/TABLE⟧\n";
        String pdf = hwpx.replace("HWPX", "PDF").replace("id=8", "id=1").replace("table=8", "table=1");
        var original = named("모집공고.hwpx", "신청자격\n" + hwpx + "다만 주택 예외는 아래 기준을 적용합니다.");
        var layout = named("모집공고.pdf", "신청자격\n" + pdf + "다만 주택 예외는 아래 기준을 적용합니다.");
        assertTrue(DocumentVariantComparison.equivalent(original, layout));
        assertFalse(DocumentVariantComparison.equivalent(original,
            named("모집공고.pdf", "신청자격\n" + pdf.replace("4,542", "4,200") + "다만 주택 예외는 아래 기준을 적용합니다.")));
        assertFalse(DocumentVariantComparison.equivalent(original, named("모집공고.pdf", "신청자격\n" + pdf)));
        assertFalse(DocumentVariantComparison.equivalent(original,
            named("모집공고.pdf", "신청자격\n" + pdf.replace("row=2 col=2", "row=1 col=2") + "다만 주택 예외는 아래 기준을 적용합니다.")));

        String boxed = "⟦HWPX:TABLE id=1 rows=1 cols=1⟧\n" + cell("HWPX", 1, 1, 1, "신청자는 무주택자이어야 합니다.") + "⟦HWPX:/TABLE⟧\n";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", boxed), named("공고.pdf", "신청자는 무주택자이어야 합니다.")));
    }

    @Test
    void borderlessRowsMatchOnlyWhenEveryColumnSharesActualBaselinesAndAllTextIsCovered() {
        String h = "⟦HWPX:TABLE id=1 rows=3 cols=2⟧\n"
            + cell("HWPX", 1, 1, 1, "항목") + cell("HWPX", 1, 1, 2, "기준")
            + cell("HWPX", 1, 2, 1, "총자산가액") + cell("HWPX", 1, 2, 2, "345,000,000원 이하")
            + cell("HWPX", 1, 3, 1, "자동차가액") + cell("HWPX", 1, 3, 2, "45,420,000원 이하") + "⟦HWPX:/TABLE⟧\n";
        String p = "⟦PDF:TABLE id=8 rows=2 cols=2⟧\n"
            + cell("PDF", 8, 1, 1, "항목") + cell("PDF", 8, 1, 2, "기준")
            + cell("PDF", 8, 2, 1, "⟦PDF:LINE y0=179.52 y1=179.52⟧\n총자산가액\n⟦PDF:LINE y0=192.00 y1=192.00⟧\n자동차가액")
            + cell("PDF", 8, 2, 2, "⟦PDF:LINE y0=179.52 y1=179.52⟧\n345,000,000원 이하\n⟦PDF:LINE y0=192.00 y1=192.00⟧\n45,420,000원 이하")
            + "⟦PDF:/TABLE⟧\n";
        assertTrue(DocumentVariantComparison.equivalent(named("모집.hwpx", h), named("모집.pdf", p)));
        String displaced = p.replace("y0=179.52 y1=179.52⟧\n345", "y0=182.00 y1=182.00⟧\n345");
        assertFalse(DocumentVariantComparison.equivalent(named("모집.hwpx", h), named("모집.pdf", displaced)));
        assertFalse(DocumentVariantComparison.equivalent(named("모집.hwpx", h), named("모집.pdf", p.replace("345,000,000", "354,000,000"))));
    }

    private Source named(String name, String text) {
        return new Source(name, "https://apply.lh.or.kr/recruitment/" + name, "a".repeat(64),
            "EXTRACTED", "", List.of(), List.of(new Part("문서 구간 1", text)));
    }

    @Test
    void actualAssetTableNoteRemainsCompleteAndBoundToItsParentCell() throws Exception {
        // Complete parent table from official HWPX 68868375 and PDF 68868379, physical page 1.
        try (var stream = getClass().getResourceAsStream("/review/multichild-assets-parent-pair.json")) {
            var mapper = new ObjectMapper();
            var sources = mapper.treeToValue(mapper.readTree(stream).get("sources"), Source[].class);
            var original = sources[0];
            var pdf = sources[1];
            String hwpx = original.context().get(0).text();
            for (String boundary : List.of("DRAWN", "UNKNOWN"))
                assertFalse(DocumentVariantComparison.equivalent(named(original.label(), hwpx.replace(
                    "table=9 row=5 col=1 rowspan=1 colspan=4 left=NONE right=NONE",
                    "table=9 row=5 col=1 rowspan=1 colspan=4 left=" + boundary + " right=NONE")), pdf));
            String text = pdf.context().get(0).text();
            assertTrue(DocumentVariantComparison.equivalent(original, pdf));
            assertFalse(DocumentVariantComparison.equivalent(original, named(pdf.label(),
                text.replace("345,000,000", "354,000,000"))));
            assertFalse(DocumentVariantComparison.equivalent(original, named(pdf.label(),
                text.replace("(단, 주택공급 신청자 또는 동 배우자와 같은 세대별 주민등록표에 등재되어 있는 경우에 한함)", ""))));

            int start = text.indexOf("※ 출생자녀수는"), end = text.indexOf("⟦PDF:/CELL⟧", start);
            String note = text.substring(start, end);
            String withoutNote = text.substring(0, start) + text.substring(end);
            assertFalse(DocumentVariantComparison.equivalent(original, named(pdf.label(), withoutNote)));
            String nextCell = "⟦PDF:CELL table=2 row=4 col=2 rowspan=1 colspan=1⟧";
            assertFalse(DocumentVariantComparison.equivalent(original,
                named(pdf.label(), withoutNote.replace(nextCell, nextCell + "\n" + note))));
        }
    }

    private record SplitTableFixture(String name, Source whole, Source fragments) {
    }

    @Test
    void actualEmptyLogicalRowsRequireEveryRemainingCellToMatchTheCompleteOtherTable() throws Exception {
        try (var stream = getClass().getResourceAsStream("/review/sangok-spacer-table-pairs.json")) {
            var fixtures = new ObjectMapper().readValue(stream, SplitTableFixture[].class);
            var schedule = fixtures[0];
            assertTrue(DocumentVariantComparison.equivalent(schedule.whole(), schedule.fragments()));
            assertTrue(DocumentVariantComparison.equivalent(schedule.fragments(), schedule.whole()));
            // The facility rows contain distinct items separated by actual dotted rules.
            // A PDF extraction that merges those rows is not an empty spacer difference.
            var facility = fixtures[1];
            assertFalse(DocumentVariantComparison.equivalent(facility.whole(), facility.fragments()));
            assertFalse(DocumentVariantComparison.equivalent(facility.fragments(), facility.whole()));
            String text = schedule.whole().context().get(0).text();
            assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", text.replace("bottom=NONE", "bottom=UNKNOWN")), schedule.fragments()));
            assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", text.replace("top=NONE", "top=DRAWN")), schedule.fragments()));
            String nonempty = text.replaceFirst("(row=2 col=1 rowspan=1 colspan=1[^⟧]*⟧)", "$1\n예외 적용");
            assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", nonempty), schedule.fragments()));
            assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", text.replace("16:00", "17:00")), schedule.fragments()));
        }
    }

    @Test
    void signAndAmountNeedExplicitUnruledSidesAndTheSameSpanningParent() {
        String source = "⟦HWPX:TABLE id=1 rows=3 cols=3⟧\n"
            + cell("HWPX", 1, 1, 1, "유형")
            + cell("HWPX", 1, 1, 2, "전환가능 보증금 한도액").replace("colspan=1", "colspan=2")
            + cell("HWPX", 1, 2, 1, "증액")
            + cell("HWPX", 1, 2, 2, "(+)").replace("colspan=1⟧", "colspan=1 left=DRAWN right=NONE⟧")
            + cell("HWPX", 1, 2, 3, "44,000,000").replace("colspan=1⟧", "colspan=1 left=NONE right=DRAWN⟧")
            + cell("HWPX", 1, 3, 1, "감액")
            + cell("HWPX", 1, 3, 2, "(-)").replace("colspan=1⟧", "colspan=1 left=DRAWN right=NONE⟧")
            + cell("HWPX", 1, 3, 3, "70,000,000").replace("colspan=1⟧", "colspan=1 left=NONE right=DRAWN⟧")
            + "⟦HWPX:/TABLE⟧\n";
        String witness = "⟦PDF:TABLE id=8 rows=3 cols=2⟧\n"
            + cell("PDF", 8, 1, 1, "유형") + cell("PDF", 8, 1, 2, "전환가능 보증금 한도액")
            + cell("PDF", 8, 2, 1, "증액") + cell("PDF", 8, 2, 2, "(+)44,000,000")
            + cell("PDF", 8, 3, 1, "감액") + cell("PDF", 8, 3, 2, "(-)70,000,000") + "⟦PDF:/TABLE⟧\n";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", source), named("공고.pdf", witness)));
        assertTrue(DocumentVariantComparison.equivalent(named("공고.pdf", witness), named("공고.hwpx", source)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", source.replace("NONE", "UNKNOWN")), named("공고.pdf", witness)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", source.replace("NONE", "DRAWN")), named("공고.pdf", witness)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", source), named("공고.pdf", witness.replace("44,000,000", "45,000,000"))));
        String separateHeaders = source.replace(
            cell("HWPX", 1, 1, 2, "전환가능 보증금 한도액").replace("colspan=1", "colspan=2"),
            cell("HWPX", 1, 1, 2, "전환가능") + cell("HWPX", 1, 1, 3, "보증금 한도액"));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", separateHeaders), named("공고.pdf", witness)));
    }

    @Test
    void confirmedPrintedPageMetadataDoesNotRemoveAnOrdinaryStandaloneRuleNumber() {
        String text = "서류 제출은 아래와 같습니다.\n1\n가족관계증명서를 제출합니다.\n";
        var pdf = new Source("공고.pdf", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1", "a".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("공고문 1쪽", text + "⟦PDF:PAGE_NUMBER value=1⟧\n")));
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", text), pdf));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", text.replace("\n1\n", "\n")), pdf));
        var explanation = org.jibro.collection.review.local.GuidanceTableProjection.explanation(pdf, pdf.context().get(0), ignored -> false);
        assertTrue(explanation.complete());
        assertEquals(text, explanation.text());
        assertTrue(pdf.context().get(0).text().contains("PAGE_NUMBER value=1"));
        var wrongPage = new Source(pdf.label(), pdf.url(), pdf.sha256(), pdf.status(), "", List.of(),
            List.of(new Part("공고문 2쪽", pdf.context().get(0).text())));
        assertFalse(org.jibro.collection.review.local.DocumentContextReader.read(wrongPage, wrongPage.context().get(0)).valid());
    }

    @Test
    void actualCenteredRentExamplesKeepEveryAmountAndExceptionWithoutInventingASecondField() throws Exception {
        try (var stream = getClass().getResourceAsStream("/review/multichild-rent-example-layout.json")) {
            var fixtures = new ObjectMapper().readValue(stream, SplitTableFixture[].class);
            for (var fixture : fixtures) {
                assertTrue(DocumentVariantComparison.equivalent(fixture.whole(), fixture.fragments()), fixture.name());
                String text = fixture.whole().context().get(0).text();
                String additional = text.replaceFirst("(row=1 col=1 rowspan=1 colspan=1⟧)", "$1\n별도 조건");
                assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", additional), fixture.fragments()));
                assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", text.replace("2.2%", "2.3%")), fixture.fragments()));
            }
            var monthly = fixtures[1];
            String text = monthly.whole().context().get(0).text();
            assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", text.replace("추가 부담", "부담 제외")), monthly.fragments()));

        }
    }

    @Test
    void aFlowRowRequiresActualDirectionAndPreservesEveryStepInOrder() {
        String row = "⟦HWPX:TABLE id=1 rows=1 cols=5⟧\n"
            + cell("HWPX", 1, 1, 1, "입주신청") + cell("HWPX", 1, 1, 2, "→")
            + cell("HWPX", 1, 1, 3, "자격확인") + cell("HWPX", 1, 1, 4, "→")
            + cell("HWPX", 1, 1, 5, "대상자선정") + "⟦HWPX:/TABLE⟧\n";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", row), named("공고.pdf", "입주신청 → 자격확인 → 대상자선정")));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", row), named("공고.pdf", "입주신청 ← 자격확인 → 대상자선정")));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", row), named("공고.pdf", "입주신청 → 대상자선정 → 자격확인")));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", row.replace("→", "")), named("공고.pdf", "입주신청 자격확인 대상자선정")));
        String padded = "⟦PDF:TABLE id=1 rows=1 cols=5⟧\n"
            + cell("PDF", 1, 1, 1, "") + cell("PDF", 1, 1, 2, "임대차계약")
            + cell("PDF", 1, 1, 3, "→") + cell("PDF", 1, 1, 4, "입주") + cell("PDF", 1, 1, 5, "")
            + "⟦PDF:/TABLE⟧\n";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.pdf", padded), named("공고.hwpx", "임대차계약→입주")));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.pdf", padded.replace("→", "")), named("공고.hwpx", "임대차계약→입주")));
    }

    @Test
    void fullHeightDirectionBindsEachStepsTitleAndDateWithoutReassigningDates() {
        String flow = "⟦HWPX:TABLE id=1 rows=2 cols=3⟧\n"
            + cell("HWPX", 1, 1, 1, "신청접수")
            + cell("HWPX", 1, 1, 2, "→").replace("rowspan=1", "rowspan=2")
            + cell("HWPX", 1, 1, 3, "서류대상발표")
            + cell("HWPX", 1, 2, 1, "2026.11.23 ~ 11.25")
            + cell("HWPX", 1, 2, 3, "2026.12.07") + "⟦HWPX:/TABLE⟧\n";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", flow),
            named("공고.pdf", "신청접수 2026.11.23 ~ 11.25 → 서류대상발표 2026.12.07")));
        String firstBox = "⟦PDF:TABLE id=1 rows=2 cols=3⟧\n"
            + cell("PDF", 1, 1, 1, "") + cell("PDF", 1, 1, 2, "신청접수") + cell("PDF", 1, 1, 3, "")
            + cell("PDF", 1, 2, 1, "") + cell("PDF", 1, 2, 2, "2026.11.23 ~ 11.25") + cell("PDF", 1, 2, 3, "")
            + "⟦PDF:/TABLE⟧\n";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", flow),
            named("공고.pdf", firstBox + "→ 서류대상발표 2026.12.07")));
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", flow.replace("→", "▶")),
            named("공고.pdf", firstBox + "▶ 서류대상발표 2026.12.07")));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", flow.replace("→", "▶")),
            named("공고.pdf", firstBox + "◀ 서류대상발표 2026.12.07")));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", flow),
            named("공고.pdf", "신청접수 2026.12.07 → 서류대상발표 2026.11.23 ~ 11.25")));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", flow.replace("→", "")),
            named("공고.pdf", "신청접수 2026.11.23 ~ 11.25 서류대상발표 2026.12.07")));
    }

    @Test
    void editorMemoDiffersFromFootnotesAndCannotHideAChangedPublishedRule() {
        String rule = "신청자는 무주택자이어야 합니다.\n";
        String footnote = "다만 소형 주택 예외는 원문 기준을 적용합니다.\n";
        String original = rule + "⟦HWPX:MEMO⟧\n총자산 한도 999만원 변경 검토\n⟦HWPX:/MEMO⟧\n"
            + "⟦HWPX:FOOTNOTE⟧\n" + footnote + "⟦HWPX:/FOOTNOTE⟧\n";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", original), named("공고.pdf", rule + footnote)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", original), named("공고.pdf", rule)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", original), named("공고.pdf", rule.replace("무주택자", "유주택자") + footnote)));
    }

    @Test
    void actualPageContinuationsRequireExactJoinedProseAndTheWitnessMergedColumns() throws Exception {
        try (var stream = getClass().getResourceAsStream("/review/sangok-page-continuations.json")) {
            var fixtures = new ObjectMapper().readValue(stream, SplitTableFixture[].class);
            for (var fixture : fixtures) {
                var source = fixture.fragments();
                assertTrue(DocumentVariantComparison.equivalent(fixture.whole(), source), fixture.name());
                assertTrue(DocumentVariantComparison.equivalent(source, fixture.whole()), fixture.name());
                var omitted = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
                    source.context().subList(0, source.context().size() - 1));
                assertFalse(DocumentVariantComparison.equivalent(fixture.whole(), omitted), fixture.name());
            }
            var asset = fixtures[0];
            var source = asset.fragments();
            var changed = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
                source.context().stream().map(p -> new Part(p.location(), p.text().replace("보조금을 제외한 금액", "보조금을 포함한 금액"))).toList());
            assertFalse(DocumentVariantComparison.equivalent(asset.whole(), changed));
            var income = fixtures[1];
            var incomplete = new Source(income.fragments().label(), income.fragments().url(), income.fragments().sha256(), "EXTRACTED", "", List.of(),
                income.fragments().context().stream().map(p -> new Part(p.location(), p.text().replace("조사일 현재까지 납부한 금액", "조사일 이후 납부할 금액"))).toList());
            assertFalse(DocumentVariantComparison.equivalent(income.whole(), incomplete));
        }
    }

    @Test
    void actualTablesSplitAcrossPhysicalPagesRetainEveryCellAndMergedScope() throws Exception {
        try (var stream = getClass().getResourceAsStream("/review/multichild-split-tables.json")) {
            var fixtures = new ObjectMapper().readValue(stream, SplitTableFixture[].class);
            for (var fixture : fixtures) {
                var source = fixture.fragments();
                assertTrue(DocumentVariantComparison.equivalent(fixture.whole(), source), fixture.name());
                assertTrue(DocumentVariantComparison.equivalent(source, fixture.whole()), fixture.name());
                var incomplete = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
                    List.of(source.context().get(0)));
                assertFalse(DocumentVariantComparison.equivalent(fixture.whole(), incomplete));
                var independent = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
                    source.context().stream().map(p -> new Part("독립 문서 구간", p.text())).toList());
                assertFalse(DocumentVariantComparison.equivalent(fixture.whole(), independent));
            }
            var documents = fixtures[0];
            var source = documents.fragments();
            var changed = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
                source.context().stream().map(p -> new Part(p.location(), p.text().replace("제출 생략", "제출 필수"))).toList());
            assertFalse(DocumentVariantComparison.equivalent(documents.whole(), changed));
            var relabeled = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
                source.context().stream().map(p -> new Part(p.location(), p.text().replace(
                    "⟦PDF:CELL table=1 row=1 col=1 rowspan=4 colspan=1⟧", "⟦PDF:CELL table=1 row=1 col=1 rowspan=4 colspan=1⟧\n모든 신청자"))).toList());
            assertFalse(DocumentVariantComparison.equivalent(documents.whole(), relabeled));
        }
    }

    private String cell(String format, int table, int row, int column, String text) {
        return "⟦" + format + ":CELL table=" + table + " row=" + row + " col=" + column + " rowspan=1 colspan=1⟧\n"
            + text + "\n⟦" + format + ":/CELL⟧\n";
    }

    @Test
    void detachedColumnsNeedPhysicalRowGroupingAndTheCompleteOppositeGrid() {
        String whole = "⟦HWPX:TABLE id=1 rows=3 cols=3⟧\n"
            + cell("HWPX", 1, 1, 1, "순위")
            + cell("HWPX", 1, 1, 2, "신청기준·접수일").replace("colspan=1", "colspan=2")
            + cell("HWPX", 1, 2, 1, "1순위") + cell("HWPX", 1, 2, 2, "생계·의료급여 수급자")
            + cell("HWPX", 1, 2, 3, "2026.11.23 ~ 11.25")
            + cell("HWPX", 1, 3, 1, "2순위") + cell("HWPX", 1, 3, 2, "소득 100% 이하")
            + cell("HWPX", 1, 3, 3, "2026.12.14 ~ 12.15") + "⟦HWPX:/TABLE⟧\n";
        String left = "⟦PDF:TABLE id=7 rows=3 cols=1 rowgroup=1⟧\n"
            + cell("PDF", 7, 1, 1, "순위") + cell("PDF", 7, 2, 1, "1순위")
            + cell("PDF", 7, 3, 1, "2순위") + "⟦PDF:/TABLE⟧\n";
        String right = "⟦PDF:TABLE id=8 rows=3 cols=2 rowgroup=1⟧\n"
            + cell("PDF", 8, 1, 1, "신청기준·접수일").replace("colspan=1", "colspan=2")
            + cell("PDF", 8, 2, 1, "생계·의료급여 수급자") + cell("PDF", 8, 2, 2, "2026.11.23 ~ 11.25")
            + cell("PDF", 8, 3, 1, "소득 100% 이하") + cell("PDF", 8, 3, 2, "2026.12.14 ~ 12.15") + "⟦PDF:/TABLE⟧\n";
        var witness = named("공고.hwpx", whole);
        assertTrue(DocumentVariantComparison.equivalent(witness, named("공고.pdf", left + right)));
        assertTrue(DocumentVariantComparison.equivalent(witness, source(new Part("공고문 1쪽", left + right))));
        assertTrue(DocumentVariantComparison.equivalent(named("공고.pdf", left + right), witness));
        assertFalse(DocumentVariantComparison.equivalent(witness, named("공고.pdf", (left + right).replace(" rowgroup=1", ""))),
            "Matching row counts without the physical relation cannot attach rank labels to another table.");
        assertFalse(DocumentVariantComparison.equivalent(witness, named("공고.pdf", left + "별도 모집 기준\n" + right)));
        assertFalse(DocumentVariantComparison.equivalent(witness, named("공고.pdf", right + left)));
        String swapped = left.replace("1순위", "임시순위").replace("2순위", "1순위").replace("임시순위", "2순위");
        assertFalse(DocumentVariantComparison.equivalent(witness, named("공고.pdf", swapped + right)),
            "The same words in different rows change who can apply on each date.");
        assertFalse(DocumentVariantComparison.equivalent(witness, named("공고.pdf", left + right.replace("100%", "70%"))));
        assertFalse(DocumentVariantComparison.equivalent(witness, named("공고.pdf", left + right.replace("이하", "초과"))));
        String unmerged = right.replace(cell("PDF", 8, 1, 1, "신청기준·접수일").replace("colspan=1", "colspan=2"),
            cell("PDF", 8, 1, 1, "신청기준") + cell("PDF", 8, 1, 2, "접수일"));
        assertFalse(DocumentVariantComparison.equivalent(witness, named("공고.pdf", left + unmerged)));
    }

    @Test
    void detachedColumnJoinCanWitnessOnlyItsOwnEmptyUnmergedSpacer() {
        String whole = "⟦HWPX:TABLE id=1 rows=2 cols=4⟧\n"
            + cell("HWPX", 1, 1, 1, "순위") + cell("HWPX", 1, 1, 2, "")
            + cell("HWPX", 1, 1, 3, "신청기준") + cell("HWPX", 1, 1, 4, "접수일")
            + cell("HWPX", 1, 2, 1, "2순위") + cell("HWPX", 1, 2, 2, "")
            + cell("HWPX", 1, 2, 3, "소득 100% 이하") + cell("HWPX", 1, 2, 4, "12.14 ~ 12.15")
            + "⟦HWPX:/TABLE⟧";
        String pair = "⟦PDF:TABLE id=7 rows=2 cols=1 rowgroup=1⟧\n"
            + cell("PDF", 7, 1, 1, "순위") + cell("PDF", 7, 2, 1, "2순위") + "⟦PDF:/TABLE⟧\n"
            + "⟦PDF:TABLE id=8 rows=2 cols=2 rowgroup=1⟧\n"
            + cell("PDF", 8, 1, 1, "신청기준") + cell("PDF", 8, 1, 2, "접수일")
            + cell("PDF", 8, 2, 1, "소득 100% 이하") + cell("PDF", 8, 2, 2, "12.14 ~ 12.15")
            + "⟦PDF:/TABLE⟧";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", whole), named("공고.pdf", pair)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", whole.replace(
            cell("HWPX", 1, 2, 2, ""), cell("HWPX", 1, 2, 2, "장애인만"))), named("공고.pdf", pair)));
        String merged = whole.replace(cell("HWPX", 1, 1, 2, ""),
                cell("HWPX", 1, 1, 2, "").replace("rowspan=1", "rowspan=2"))
            .replace(cell("HWPX", 1, 2, 2, ""), "");
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", merged), named("공고.pdf", pair)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", whole),
            named("공고.pdf", pair.replace("100%", "70%"))));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", whole),
            named("공고.pdf", pair.replace(" rowgroup=1", ""))));
    }

    @Test
    void numberedSectionCaptionMatchesOnlyTheSameOrderedCaptionAndProse() {
        String caption = "⟦HWPX:TABLE id=1 rows=1 cols=3⟧\n"
            + cell("HWPX", 1, 1, 1, "3") + cell("HWPX", 1, 1, 2, "")
            + cell("HWPX", 1, 1, 3, "입주자격") + "⟦HWPX:/TABLE⟧\n";
        String rule = "신청자는 무주택이어야 합니다. 다만 공고의 예외를 적용합니다.";
        var pdf = named("공고.pdf", "3 입주자격\n" + rule);
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", caption + rule), pdf));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", caption + rule),
            named("공고.pdf", "3 입주자격\n신청자는 무주택이어야 합니다.")));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", caption + rule),
            named("공고.pdf", rule + "3 입주자격")));
        String amount = caption.replace("입주자격", "100% 이하");
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", amount), named("공고.pdf", "3 100% 이하")));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", caption.replace(
            cell("HWPX", 1, 1, 2, ""), cell("HWPX", 1, 1, 2, "청년만")) + rule), pdf));
    }

    @Test
    void consentChoicesKeepBothEmptyInputsAndTheExactSurroundingRule() {
        String whole = "⟦HWPX:TABLE id=1 rows=1 cols=5⟧\n"
            + cell("HWPX", 1, 1, 1, "동의") + cell("HWPX", 1, 1, 2, "") + cell("HWPX", 1, 1, 3, "")
            + cell("HWPX", 1, 1, 4, "미동의") + cell("HWPX", 1, 1, 5, "") + "⟦HWPX:/TABLE⟧\n";
        String yes = "⟦PDF:TABLE id=2 rows=1 cols=2⟧\n"
            + cell("PDF", 2, 1, 1, "동의") + cell("PDF", 2, 1, 2, "") + "⟦PDF:/TABLE⟧\n";
        String no = "⟦PDF:TABLE id=3 rows=1 cols=2⟧\n"
            + cell("PDF", 3, 1, 1, "미동의") + cell("PDF", 3, 1, 2, "") + "⟦PDF:/TABLE⟧\n";
        String rule = "제3자 제공에 동의하나요?\n", after = "거부할 수 있으나 신청이 제한될 수 있습니다.";
        var original = named("공고.hwpx", rule + whole + after);
        assertTrue(DocumentVariantComparison.equivalent(original, named("공고.pdf", rule + yes + no + after)));
        assertTrue(DocumentVariantComparison.equivalent(original, source(new Part("공고문 1쪽", rule + yes + no + after))));
        assertFalse(DocumentVariantComparison.equivalent(original, named("공고.pdf", rule + no + yes + after)));
        assertFalse(DocumentVariantComparison.equivalent(original, named("공고.pdf", rule + yes.replace(
            cell("PDF", 2, 1, 2, ""), cell("PDF", 2, 1, 2, "✓")) + no + after)));
        assertFalse(DocumentVariantComparison.equivalent(original, named("공고.pdf", rule + yes + "추가 동의\n" + no + after)));
        assertFalse(DocumentVariantComparison.equivalent(original, source(new Part("공고문 1쪽", rule + yes),
            new Part("공고문 2쪽", no + after))));
        assertFalse(DocumentVariantComparison.equivalent(original, named("공고.pdf", rule + "동의 미동의\n" + after)));
        assertFalse(DocumentVariantComparison.equivalent(original, named("공고.pdf", rule + yes + no)));
        String missingInput = "⟦PDF:TABLE id=9 rows=1 cols=3⟧\n"
            + cell("PDF", 9, 1, 1, "동의") + cell("PDF", 9, 1, 2, "미동의") + cell("PDF", 9, 1, 3, "")
            + "⟦PDF:/TABLE⟧\n";
        assertFalse(DocumentVariantComparison.equivalent(original, named("공고.pdf", rule + missingInput + after)),
            "An empty answer field is not evidence of a decorative column.");
    }

    @Test
    void exteriorBlankPaddingNeedsThreeExplicitUnruledOuterSides() {
        String table = "⟦HWPX:TABLE id=1 rows=2 cols=2⟧\n"
            + cell("HWPX", 1, 1, 1, "").replace("colspan=1⟧", "colspan=2 left=NONE right=NONE top=NONE bottom=DRAWN⟧")
            + cell("HWPX", 1, 2, 1, "성명") + cell("HWPX", 1, 2, 2, "서명") + "⟦HWPX:/TABLE⟧";
        String visible = "⟦PDF:TABLE id=1 rows=1 cols=2⟧\n" + cell("PDF", 1, 1, 1, "성명")
            + cell("PDF", 1, 1, 2, "서명") + "⟦PDF:/TABLE⟧";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", table), named("공고.pdf", visible)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", table.replace("top=NONE", "top=DRAWN")), named("공고.pdf", visible)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", table.replace("right=NONE", "right=UNKNOWN")), named("공고.pdf", visible)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", table.replace("bottom=DRAWN⟧", "bottom=DRAWN⟧\n추가 서명")), named("공고.pdf", visible)));
    }

    @Test
    void doubleRuleSpacingNeedsOneCompleteRowAndColumnMapping() {
        String full = "⟦HWPX:TABLE id=1 rows=2 cols=2⟧\n"
            + cell("HWPX", 1, 1, 1, "가족") + cell("HWPX", 1, 1, 2, "기준")
            + cell("HWPX", 1, 2, 1, "배우자") + cell("HWPX", 1, 2, 2, "주소가 달라도 포함")
            + "⟦HWPX:/TABLE⟧";
        full = full.replace("row=1 col=1 rowspan=1 colspan=1⟧", "row=1 col=1 rowspan=1 colspan=1 doubleright=0.5mm doublebottom=0.5mm⟧")
            .replace("row=1 col=2 rowspan=1 colspan=1⟧", "row=1 col=2 rowspan=1 colspan=1 doublebottom=0.5mm⟧")
            .replace("row=2 col=1 rowspan=1 colspan=1⟧", "row=2 col=1 rowspan=1 colspan=1 doubleright=0.5mm⟧");
        String spaced = "⟦PDF:TABLE id=7 rows=3 cols=3 rowbands=2:1.439 colbands=2:1.439⟧\n"
            + cell("PDF", 7, 1, 1, "가족") + cell("PDF", 7, 1, 2, "") + cell("PDF", 7, 1, 3, "기준")
            + cell("PDF", 7, 2, 1, "") + cell("PDF", 7, 2, 2, "") + cell("PDF", 7, 2, 3, "")
            + cell("PDF", 7, 3, 1, "배우자") + cell("PDF", 7, 3, 2, "") + cell("PDF", 7, 3, 3, "주소가 달라도 포함")
            + "⟦PDF:/TABLE⟧";
        assertTrue(DocumentVariantComparison.equivalent(named("공고.hwpx", full), named("공고.pdf", spaced)));
        assertTrue(DocumentVariantComparison.equivalent(named("공고.pdf", spaced), named("공고.hwpx", full)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", full), named("공고.pdf", spaced.replace(" rowbands=2:1.439 colbands=2:1.439", ""))));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", full.replace("0.5mm", "0.3mm")), named("공고.pdf", spaced)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", full.replaceAll(" double(?:right|bottom)=0.5mm", "")), named("공고.pdf", spaced)));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", full), named("공고.pdf", spaced.replace(
            cell("PDF", 7, 2, 3, ""), cell("PDF", 7, 2, 3, "만 19세 이상")))));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", full), named("공고.pdf", spaced.replace(
            cell("PDF", 7, 3, 2, ""), cell("PDF", 7, 3, 2, "제외")))));
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", full), named("공고.pdf", spaced.replace("달라도 포함", "같아야 포함"))));
        String ambiguous = "⟦HWPX:TABLE id=1 rows=4 cols=2⟧\n"
            + cell("HWPX", 1, 1, 1, "가족") + cell("HWPX", 1, 1, 2, "기준")
            + cell("HWPX", 1, 2, 1, "") + cell("HWPX", 1, 2, 2, "")
            + cell("HWPX", 1, 3, 1, "") + cell("HWPX", 1, 3, 2, "")
            + cell("HWPX", 1, 4, 1, "배우자") + cell("HWPX", 1, 4, 2, "주소가 달라도 포함")
            + "⟦HWPX:/TABLE⟧";
        String oneEmptyRow = ambiguous.replace("rows=4", "rows=3")
            .replace(cell("HWPX", 1, 3, 1, "") + cell("HWPX", 1, 3, 2, ""), "").replace("row=4", "row=3");
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", ambiguous), named("공고.pdf", oneEmptyRow.replace("HWPX", "PDF"))));
    }

    @Test
    void centeredCaptionPreservesItsFullWidthBodyAndNestedFamilyScope() {
        String families = "⟦HWPX:TABLE id=2 rows=3 cols=2⟧\n"
            + cell("HWPX", 2, 1, 1, "가족") + cell("HWPX", 2, 1, 2, "심사 범위")
            + cell("HWPX", 2, 2, 1, "배우자") + cell("HWPX", 2, 2, 2, "주소가 달라도 포함")
            + cell("HWPX", 2, 3, 1, "부모") + cell("HWPX", 2, 3, 2, "같은 등본인 경우만 포함")
            + "⟦HWPX:/TABLE⟧\n";
        String rule = "무주택 기준을 확인해요.\n";
        String exception = "만 19세 미만은 공고의 예외를 함께 확인해요.";
        String box = "⟦HWPX:TABLE id=1 rows=3 cols=3⟧\n"
            + cell("HWPX", 1, 1, 1, "")
            + cell("HWPX", 1, 1, 2, "무주택세대구성원이란?").replace("rowspan=1", "rowspan=2")
            + cell("HWPX", 1, 1, 3, "") + cell("HWPX", 1, 2, 1, "") + cell("HWPX", 1, 2, 3, "")
            + cell("HWPX", 1, 3, 1, rule + families + exception).replaceFirst("colspan=1", "colspan=3")
            + "⟦HWPX:/TABLE⟧";
        String unboxed = "무주택세대구성원이란?\n" + rule + families.replace("HWPX", "PDF") + exception;
        var original = named("공고.hwpx", box);
        assertTrue(DocumentVariantComparison.equivalent(original, named("공고.pdf", unboxed)));
        assertFalse(DocumentVariantComparison.equivalent(original, named("공고.pdf", unboxed.replace("19세", "18세"))));
        assertFalse(DocumentVariantComparison.equivalent(original, named("공고.pdf", unboxed.replace(exception, ""))));
        String reassigned = unboxed.replace("배우자", "임시가족").replace("부모", "배우자").replace("임시가족", "부모");
        assertFalse(DocumentVariantComparison.equivalent(original, named("공고.pdf", reassigned)),
            "The same family labels cannot move between different residence requirements.");
        assertFalse(DocumentVariantComparison.equivalent(named("공고.hwpx", box.replace(
            cell("HWPX", 1, 2, 1, ""), cell("HWPX", 1, 2, 1, "청년만 해당"))), named("공고.pdf", unboxed)));
    }

    @Test
    void structuredCellRelationshipsAreNotDiscardedWhenWordsAreUnchanged() {
        String first = "⟦HWPX:TABLE id=1 rows=2 cols=2⟧\n"
            + "⟦HWPX:CELL table=1 row=1 col=1 rowspan=1 colspan=1⟧\n신청자\n⟦HWPX:/CELL⟧\n"
            + "⟦HWPX:CELL table=1 row=1 col=2 rowspan=1 colspan=1⟧\n100% 이하\n⟦HWPX:/CELL⟧\n⟦HWPX:/TABLE⟧";
        String reassigned = first.replace("row=1 col=2", "row=2 col=2");
        assertFalse(DocumentVariantComparison.equivalent(source(new Part("문서 구간 1", first)),
            source(new Part("문서 구간 1", reassigned))));
        assertFalse(DocumentVariantComparison.equivalent(source(new Part("문서 구간 1", first)),
            source(new Part("1쪽", "신청자\n100% 이하"))));
    }
}
