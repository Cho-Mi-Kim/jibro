package org.jibro.collection.review.local;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.junit.jupiter.api.Test;

class LocalRuleInterpreterHwpxTest {
    private static final String URL = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1";

    @Test
    void editorMemoIsExcludedWithoutRemovingItsAnnotatedTextOrNormativeFootnotes() {
        String text = "신청자는 무주택자이어야 합니다.\n⟦HWPX:MEMO⟧\n"
                + "총자산 기준을 999만원으로 바꿀지 검토\n"
                + table(1, 1, 1, cell(1, 1, 1, 1, 1, "가족관계증명서 제출 삭제 검토"))
                + "⟦HWPX:/MEMO⟧\n"
                + "⟦HWPX:FOOTNOTE⟧\n다만 소형 주택 예외는 원문 기준을 적용합니다.\n⟦HWPX:/FOOTNOTE⟧\n"
                + "⟦HWPX:ENDNOTE⟧\n자동차 가액은 4,542만원 이하입니다.\n⟦HWPX:/ENDNOTE⟧\n";
        var part = new Part("HWPX Contents/section0.xml (쪽 번호 확인 필요)", text);
        var source = new Source("모집공고.hwpx", URL, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(part));
        var book = LocalRuleInterpreter.interpret(List.of(source));
        assertTrue(book.clauses().stream().anyMatch(c -> c.quote().contains("무주택자")));
        assertTrue(book.clauses().stream().anyMatch(c -> c.quote().contains("소형 주택 예외")));
        assertTrue(book.clauses().stream().anyMatch(c -> c.quote().contains("4,542만원")));
        assertTrue(book.clauses().stream().noneMatch(c -> c.quote().contains("검토")));
        for (var clause : book.clauses()) assertEquals(text.substring(clause.start(), clause.end()), clause.quote());
        var explanation = GuidanceTableProjection.explanation(source, part, ignored -> false);
        assertTrue(explanation.complete());
        assertFalse(explanation.text().contains("검토"));
        assertTrue(explanation.text().contains("소형 주택 예외"));
        var normative = DocumentContextReader.normative(source);
        assertFalse(normative.context().get(0).text().contains("MEMO"));
        assertFalse(normative.excerpts().stream().anyMatch(e -> e.text().contains("검토")));
        assertEquals(text, source.context().get(0).text());
    }

    @Test
    void generatedMarkersDoNotBecomeRulesAndOffsetsStillPointToExactOriginalText() {
        String text = table(1, 1, 2, cell(1, 1, 1, 1, 1, "자동차 가액")
                + cell(1, 1, 2, 1, 1, "4,542만원 이하"));
        var book = read(text);
        assertEquals(2, book.clauses().size());
        assertTrue(book.clauses().stream().noneMatch(c -> c.quote().contains("⟦HWPX:")));
        for (var clause : book.clauses()) {
            assertEquals(text.substring(clause.start(), clause.end()), clause.quote());
            for (var quantity : clause.quantities())
                assertEquals(text.substring(quantity.start(), quantity.end()), quantity.literal());
        }
        var amount = clause(book, "4,542만원");
        assertTrue(amount.heading().contains("표 1 행 1 열 2 병합 1×1"));
        assertEquals(text.length(), book.sourceCharacters());
        assertEquals(book, read(text));
    }

    @Test
    void compactExceptionPacketContainsPhysicalRowAndColumnScopeWithoutOtherRows() {
        String text = table(1, 3, 3,
                cell(1, 1, 1, 1, 1, "항목") + cell(1, 1, 2, 1, 1, "세대구성원 전원") + cell(1, 1, 3, 1, 1, "예외")
                        + cell(1, 2, 1, 1, 1, "자동차 가액") + cell(1, 2, 2, 1, 1, "4,542만원 이하")
                        + cell(1, 2, 3, 1, 1, "다만 장애인 사용 자동차는 원문 기준에 따라 제외합니다.")
                        + cell(1, 3, 1, 1, 1, "총자산") + cell(1, 3, 2, 1, 1, "별도의 금액 기준") + cell(1, 3, 3, 1, 1, "재계약 시 별도 확인"));
        var book = read(text);
        var amount = clause(book, "4,542만원");
        assertTrue(amount.relatedClauseIds().contains(clause(book, "자동차 가액").id()));
        assertTrue(amount.relatedClauseIds().contains(clause(book, "세대구성원 전원").id()));
        assertTrue(amount.relatedClauseIds().contains(clause(book, "다만 장애인").id()));
        assertFalse(amount.relatedClauseIds().contains(clause(book, "별도의 금액").id()));
        var plan = CompactReviewPlanner.plan(book);
        assertFalse(plan.packets().isEmpty());
        assertTrue(plan.packets().stream().anyMatch(p -> p.clauses().stream().anyMatch(c -> c.quote().contains("세대구성원 전원"))));
        assertTrue(plan.packets().stream().flatMap(p -> p.clauses().stream()).noneMatch(c -> c.quote().contains("재계약 시")));
        assertTrue(plan.packets().stream().allMatch(p -> p.transmittedCharacters() <= CompactReviewPlanner.MAX_PACKET_CHARACTERS));
    }

    @Test
    void verticallyMergedLabelDoesNotBridgeIndependentRowsIntoOneCondition() {
        String text = table(1, 2, 2,
                cell(1, 1, 1, 2, 1, "세대구성원 전원") + cell(1, 1, 2, 1, 1, "자동차 가액을 확인하세요.")
                        + cell(1, 2, 2, 1, 1, "소득은 별도 확인하세요."));
        var book = read(text);
        var shared = clause(book, "세대구성원 전원");
        var car = clause(book, "자동차 가액");
        var income = clause(book, "소득은");
        assertTrue(car.relatedClauseIds().contains(shared.id()));
        assertFalse(shared.relatedClauseIds().contains(income.id()));
        assertFalse(car.relatedClauseIds().contains(income.id()));
        assertTrue(shared.heading().contains("병합 2×1"));
    }

    @Test
    void nestedCellAndFootnoteKeepContainingScopeWithoutFabricatingACombinedQuote() {
        String nested = table(2, 1, 1, cell(2, 1, 1, 1, 1, "자동차 예외는 아래를 확인하세요."));
        String text = table(1, 1, 1, cell(1, 1, 1, 1, 1,
                "세대구성원 전원\n" + nested + "⟦HWPX:FOOTNOTE⟧\n다만 보유 차량별로 확인합니다.\n⟦HWPX:/FOOTNOTE⟧\n"));
        var book = read(text);
        var inner = clause(book, "자동차 예외");
        var scope = clause(book, "세대구성원 전원");
        var note = clause(book, "다만 보유");
        assertTrue(inner.heading().contains("표 1 행 1 열 1"));
        assertTrue(inner.heading().contains("표 2 행 1 열 1"));
        assertTrue(inner.relatedClauseIds().contains(scope.id()));
        assertTrue(inner.relatedClauseIds().contains(note.id()));
        assertTrue(note.heading().contains("FOOTNOTE"));
        for (var clause : book.clauses()) assertEquals(text.substring(clause.start(), clause.end()), clause.quote());
    }

    @Test
    void unknownCoordinatesOrIncompleteMarkerNestingCannotBecomeACompleteApiQuestion() {
        String text = "⟦HWPX:TABLE id=1 rows=unknown cols=unknown⟧\n"
                + "⟦HWPX:CELL table=1 row=unknown col=unknown rowspan=unknown colspan=unknown⟧\n"
                + "소득요건 배제. 다만 예외는 별도 확인합니다.\n⟦HWPX:/CELL⟧\n⟦HWPX:/TABLE⟧\n";
        var book = read(text);
        assertTrue(book.unresolved().stream().anyMatch(u -> u.code().equals("DOCUMENT_TABLE_CONTEXT_INCOMPLETE")));
        assertTrue(CompactReviewPlanner.plan(book).packets().isEmpty());
        var broken = read(text.replace("⟦HWPX:/TABLE⟧", ""));
        assertTrue(broken.clauses().isEmpty());
        assertTrue(broken.facts().isEmpty());
        assertTrue(broken.unresolved().stream().anyMatch(u -> u.code().equals("DOCUMENT_STRUCTURE_INCOMPLETE")));
    }

    @Test
    void largeRowsAreBlockedInsteadOfSilentlyTruncatingTheirDependencies() {
        var cells = new StringBuilder();
        for (int column = 1; column <= 66; column++)
            cells.append(cell(1, 1, column, 1, 1, "자동차 예외 " + column + "번째 항목"));
        var book = read(table(1, 1, 66, cells.toString()));
        assertEquals(66, book.clauses().size());
        assertEquals(66, book.unresolved().stream().filter(u -> u.code().equals("DOCUMENT_TABLE_CONTEXT_INCOMPLETE")).count());
        assertTrue(CompactReviewPlanner.plan(book).packets().isEmpty());
    }

    @Test
    void plainPdfTextCannotBeStrippedAsTrustedHwpxMetadata() {
        String text = "⟦HWPX:TABLE id=1 rows=1 cols=1⟧\n자동차 예외\n⟦HWPX:/TABLE⟧\n";
        var source = new Source("공고.pdf", URL, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("공고문 1쪽", text)));
        var book = LocalRuleInterpreter.interpret(List.of(source));
        assertTrue(book.clauses().stream().anyMatch(c -> c.quote().contains("⟦HWPX:TABLE")));
    }

    @Test
    void repeatedTableNumbersInAnotherSectionDoNotLinkAnExceptionToThePreviousSection() {
        var source = new Source("모집공고.hwpx", URL, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(
                new Part("HWPX Contents/section0.xml (쪽 번호 확인 필요)", table(1, 1, 1, cell(1, 1, 1, 1, 1, "소득요건 배제"))),
                new Part("HWPX Contents/section1.xml (쪽 번호 확인 필요)", table(1, 1, 1, cell(1, 1, 1, 1, 1, "다만 자동차는 별도 확인합니다.")))));
        var book = LocalRuleInterpreter.interpret(List.of(source));
        var income = clause(book, "소득요건 배제");
        var exception = clause(book, "다만 자동차");
        assertFalse(exception.relatedClauseIds().contains(income.id()));
        assertTrue(book.unresolved().stream().anyMatch(u -> u.code().equals("EXCEPTION_PARENT_UNRESOLVED") && u.clauseIds().contains(exception.id())));
    }

    private static LocalRuleBook read(String text) {
        var source = new Source("모집공고.hwpx", URL, "a".repeat(64), "EXTRACTED", "", List.of(),
                List.of(new Part("HWPX Contents/section0.xml (쪽 번호 확인 필요)", text)));
        return LocalRuleInterpreter.interpret(List.of(source));
    }

    private static LocalRuleBook.Clause clause(LocalRuleBook book, String text) {
        return book.clauses().stream().filter(c -> c.quote().contains(text)).findFirst().orElseThrow();
    }

    private static String table(int id, int rows, int columns, String text) {
        return "⟦HWPX:TABLE id=" + id + " rows=" + rows + " cols=" + columns + "⟧\n" + text + "⟦HWPX:/TABLE⟧\n";
    }

    private static String cell(int table, int row, int column, int rowSpan, int columnSpan, String text) {
        return "⟦HWPX:CELL table=" + table + " row=" + row + " col=" + column + " rowspan=" + rowSpan
                + " colspan=" + columnSpan + "⟧\n" + text + "\n⟦HWPX:/CELL⟧\n";
    }
}
