package org.jibro.collection.review;

import java.text.Normalizer;
import java.util.List;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.collection.extraction.ExtractionModels.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentVariantComparisonTest {
    private Source source(Part... parts) {
        return new Source("모집공고.pdf", "https://apply.lh.or.kr/recruitment.pdf", "a".repeat(64),
            "EXTRACTED", "", List.of(), List.of(parts));
    }

    @Test void whitespacePageCountersAndUnicodeCompositionAreLosslessLayoutDifferences() {
        var first = source(new Part("공고문 1쪽", "신청자는 무주택자이어야 합니다.\n- 1 -\n"));
        var second = source(new Part("문서 구간 1", Normalizer.normalize("신청자는\n무주택자이어야 합니다.", Normalizer.Form.NFD)));
        assertTrue(DocumentVariantComparison.equivalent(first, second));
    }

    @Test void explicitCompletePageNumbersCanRestoreCollectionOrder() {
        var one = new Part("공고문 1쪽", "신청자는 무주택자이어야 합니다.");
        var two = new Part("공고문 2쪽", "단, 아래의 예외를 함께 적용합니다.");
        assertTrue(DocumentVariantComparison.equivalent(source(one, two), source(two, one)));
    }

    @Test void arbitraryParagraphsOrDuplicatePageNumbersCannotBeReordered() {
        var first = source(new Part("문서 구간 1", "우선 적용: 소득 100% 이하"), new Part("문서 구간 2", "그 밖에 소득 150% 이하"));
        var reversed = source(new Part("문서 구간 2", "그 밖에 소득 150% 이하"), new Part("문서 구간 1", "우선 적용: 소득 100% 이하"));
        assertFalse(DocumentVariantComparison.equivalent(first, reversed));
        assertFalse(DocumentVariantComparison.equivalent(
            source(new Part("1쪽", "소득 100% 이하"), new Part("1쪽", "자동차 150만원 이하")),
            source(new Part("1쪽", "자동차 150만원 이하"), new Part("1쪽", "소득 100% 이하"))));
    }

    @Test void equalCharacterCountsAndSameNumbersDoNotReconcileTableSubjectChanges() {
        var first = source(new Part("1쪽", "신청자 소득 100% 이하\n배우자 소득 150% 이하"));
        var reassigned = source(new Part("1쪽", "신청자 소득 150% 이하\n배우자 소득 100% 이하"));
        assertFalse(DocumentVariantComparison.equivalent(first, reassigned));
        assertFalse(DocumentVariantComparison.equivalent(first,
            source(new Part("1쪽", "신청자 소득 100% 초과\n배우자 소득 150% 이하"))));
    }

    @Test void actualIksanMergedTableReadingOrderRequiresCorrespondenceNotASimilarityScore() {
        // Official 2026.11.04 Iksan notice, page 1 (files 69296594/69296595). The PDF
        // renderer moves the left heading into the value. This is not evidence of a contradiction,
        // but the flat streams alone do not prove the subject/exception association either.
        var hwpx = source(new Part("문서 구간 1", "▪ 자동차 가액\n완화요건 미적용\n(세대구성원 전원이 보유하고 있는 개별 자동차가액(4,542)만원 이하)"));
        var pdf = source(new Part("공고문 1쪽", "▪ 완화요건 미적용 자동차 가액\n(세대구성원 전원이 보유하고 있는 개별 자동차가액(4,542)만원 이하)"));
        assertFalse(DocumentVariantComparison.equivalent(hwpx, pdf));
    }

    @Test void missingExceptionsOrMissingPagesRemainUnverified() {
        var complete = source(new Part("1쪽", "신청자는 무주택자이어야 합니다."), new Part("2쪽", "소형·저가주택은 예외입니다."));
        assertFalse(DocumentVariantComparison.equivalent(complete, source(new Part("1쪽", "신청자는 무주택자이어야 합니다."))));
        assertFalse(DocumentVariantComparison.equivalent(
            source(new Part("1쪽", "신청자는 무주택자이어야 합니다."), new Part("3쪽", "소형·저가주택은 예외입니다.")),
            source(new Part("3쪽", "소형·저가주택은 예외입니다."), new Part("1쪽", "신청자는 무주택자이어야 합니다."))));
    }

    @Test void structuredCellRelationshipsAreNotDiscardedWhenWordsAreUnchanged() {
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
