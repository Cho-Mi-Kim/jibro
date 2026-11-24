package org.jibro.collection.review;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.jibro.collection.extraction.ExtractionModels.*;

import static org.junit.jupiter.api.Assertions.*;

class OfficialPreparationRowsTest {
    @Test
    void explicitApplicantRowsKeepTheCategoryNumbersAndExceptionsTogether() {
        var youth = parse(List.of("계층", "신청자격"), List.of("청년", "만 19세 이상 39세 이하인 미혼자. 소득이 없는 청년도 신청할 수 있습니다."));
        assertEquals("YOUTH", OfficialGuidanceItem.applicantType(youth.title()));
        assertEquals("만 19세 이상 39세 이하인 미혼자. 소득이 없는 청년도 신청할 수 있습니다.", youth.value());
        var industrial = OfficialGuidanceItem.parse("산업단지 근로자 주택요건: 무주택세대구성원이어야 합니다.");
        assertNotNull(industrial);
        assertEquals("INDUSTRIAL", OfficialGuidanceItem.applicantType(industrial.title()));
        var student = parse(List.of("공급대상", "입주자격"), List.of("대학생 계층", "대학교에 재학 중인 미혼자"));
        assertEquals("STUDENT", OfficialGuidanceItem.applicantType(student.title()));
        assertNull(OfficialGuidanceRows.row(List.of("계층", "신청자격"), List.of("청년 또는 신혼부부", "첨부 서류를 확인합니다."), null, false));
        assertNull(OfficialGuidanceRows.row(List.of("계층", "신청자격"), List.of("일반공급", "신청자의 순위를 확인합니다."), null, false));
        assertNull(OfficialGuidanceItem.applicantType("청년 자녀가 있는 세대의 신청자격"));
    }

    @Test
    void keepsVehicleExceptionWithAssetWaiverAndKeepsScoreWithSpecificHousehold() {
        var waiver = parse(List.of("구분", "입주자격 완화 내용"), List.of("총자산 요건", "총자산 요건 배제 (단, 자동차가액 4,542만원 이하 요건 적용)"));
        assertEquals("CONDITION", waiver.kind());
        assertTrue(waiver.value().contains("자동차가액 4,542만원 이하"));
        var rank = parse(List.of("구분", "구분", "구분", "배점", "배점기준"), List.of("당해지역 거주기간", "1년 미만", "1년 미만", "12", "정읍시에 계속 거주한 기간"));
        assertTrue(rank.title().contains("1년 미만"));
        assertTrue(rank.value().contains("배점 12"));
        assertTrue(rank.value().contains("정읍시"));
    }

    @Test
    void preservesAlternativesAndIssuerAndDoesNotSplitOneApplicantsDocuments() {
        var bundle = parse(List.of("대상자", "대상자", "제출서류", "발급처"), List.of("해당자", "지원대상 한부모", "한부모가족증명서 또는 수급자증명서(해당자만)", "행정복지센터"));
        assertEquals("DOCUMENT_ITEM", bundle.kind());
        assertTrue(bundle.title().contains("또는"));
        assertTrue(bundle.value().contains("지원대상 한부모"));
        assertTrue(bundle.value().contains("행정복지센터"));
        var visit = parse(List.of("구 분", "구 비 서 류"), List.of("배우자 대리 신청", "신분증과 위임장 또는 공고에서 정한 대체 증빙"));
        assertTrue(visit.title().contains("배우자 대리 신청"));
        assertTrue(visit.value().contains("또는"));
        assertNull(OfficialGuidanceRows.row(List.of("구분", "구비서류"), List.of("계약 해지", "기타 서류"), null, false));
    }

    @Test
    void parserProducedPdfCellsReachTheExistingOfficialGuidanceConsumer() {
        String text = "⟦PDF:TABLE id=1 rows=2 cols=2⟧\n"
                + pdfCell(1, 1, "계층") + pdfCell(1, 2, "신청자격")
                + pdfCell(2, 1, "청년") + pdfCell(2, 2, "만 19세 이상 39세 이하인 미혼자. 소득이 없는 청년도 신청할 수 있습니다.")
                + "⟦PDF:/TABLE⟧\n⟦PDF:PAGE_NUMBER value=1⟧\n";
        var source = new Source("모집공고.pdf", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1", "a".repeat(64),
                "EXTRACTED", "", List.of(), List.of(new Part("공고문 1쪽", text)));
        var projected = OfficialGuidanceRows.read(source);
        assertEquals("청년 신청자격: 만 19세 이상 39세 이하인 미혼자. 소득이 없는 청년도 신청할 수 있습니다.\n⟦PDF:PAGE_NUMBER value=1⟧\n",
                projected.context().get(0).text());
        var explanation = org.jibro.collection.review.local.GuidanceTableProjection.explanation(projected, projected.context().get(0), ignored -> false);
        assertTrue(explanation.complete());
        assertEquals("청년 신청자격: 만 19세 이상 39세 이하인 미혼자. 소득이 없는 청년도 신청할 수 있습니다.\n", explanation.text());
    }

    @Test
    void consecutivePhysicalPagesCarryOnlyExplicitlyNumberedSubmissionTableColumns() {
        String first = "■ 공통 제출서류\n⟦PDF:TABLE id=1 rows=2 cols=3⟧\n"
                + pdfCell(1, 1, "제출서류") + pdfCell(1, 2, "MyMy서비스 이용가능 여부") + pdfCell(1, 3, "비고")
                + pdfCell(2, 1, "[필수제출 1] 주민등록등본") + pdfCell(2, 2, "O") + pdfCell(2, 3, "전부 표기로 발급")
                + "⟦PDF:/TABLE⟧\n⟦PDF:PAGE_NUMBER value=1⟧\n";
        String second = "⟦PDF:TABLE id=1 rows=2 cols=3⟧\n"
                + pdfCell(1, 1, "[필수제출 2] 가족관계증명서") + pdfCell(1, 2, "O") + pdfCell(1, 3, "배우자가 없는 경우에도 반드시 제출")
                + pdfCell(2, 1, "외국인등록증명서") + pdfCell(2, 2, "O") + pdfCell(2, 3, "배우자가 외국인인 경우만 제출")
                + "⟦PDF:/TABLE⟧\n⟦PDF:PAGE_NUMBER value=2⟧\n";
        var source = new Source("모집공고.pdf", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1", "a".repeat(64),
                "EXTRACTED", "", List.of(), List.of(new Part("공고문 1쪽", first), new Part("공고문 2쪽", second)));
        var text = OfficialGuidanceRows.read(source).context().get(1).text();
        assertTrue(text.contains("가족관계증명서 제출 대상: 모든 신청자"));
        assertTrue(text.contains("배우자가 없는 경우에도 반드시 제출"));
        assertFalse(text.contains("외국인등록증명서 제출 대상: 모든 신청자"));
        for (var next : List.of(new Part("공고문 3쪽", second.replace("PAGE_NUMBER value=2", "PAGE_NUMBER value=3")),
                new Part("공고문 2쪽", second.replace("필수제출 2", "필수제출 3")),
                new Part("공고문 2쪽", "■ 신혼부부 제출서류\n" + second))) {
            var separate = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(), List.of(source.context().get(0), next));
            assertFalse(OfficialGuidanceRows.read(separate).context().get(1).text().contains("가족관계증명서 제출 대상: 모든 신청자"));
        }
    }

    @Test
    void actualCommonListKeepsConditionalFamilyProofsAndWholeSigningInstructions() throws Exception {
        var parser = new org.jibro.collection.extraction.DocumentTextParser();
        List<Part> parts;
        try (var input = getClass().getResourceAsStream("/extraction/lh-bukgu-69296405.pdf")) {
            parts = parser.parse(input.readAllBytes(), "application/pdf");
        }
        var source = new Source("모집공고.pdf", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69296405", "a".repeat(64),
                "EXTRACTED", "", List.of(), parts);
        var projected = OfficialGuidanceRows.read(source);
        var rules = projected.context().stream().filter(part -> part.location().startsWith("공고문 7쪽") || part.location().startsWith("공고문 8쪽"))
                .flatMap(part -> part.text().lines()).map(OfficialGuidanceItem::parse).filter(java.util.Objects::nonNull).toList();
        assertEquals(List.of("매입임대공급 신청서", "개인정보 수집･이용 및 제3자 제공 동의서", "신청자의 주민등록표등본"),
                rules.stream().filter(OfficialGuidanceItem::commonRecipient).map(GuidanceRuleGrammar.Rule::title).toList());
        var consent = rules.stream().filter(rule -> rule.title().contains("제3자 제공")).findFirst().orElseThrow();
        assertTrue(consent.value().contains("세대구성원 전원이 서명"));
        assertTrue(consent.value().contains("만14세 미만의 세대구성원은 보호자(법정대리인)가 서명"));
        var family = rules.stream().filter(rule -> rule.title().equals("신청자의 가족관계증명서")).findFirst().orElseThrow();
        assertFalse(OfficialGuidanceItem.commonRecipient(family));
        assertTrue(family.value().contains("배우자를 확인할 수 없는 경우"));
        assertTrue(family.value().contains("배우자가 외국인인 경우"));
        var history = rules.stream().filter(rule -> rule.title().equals("신청자의 주민등록표초본")).findFirst().orElseThrow();
        assertFalse(OfficialGuidanceItem.commonRecipient(history));
        assertTrue(history.value().contains("모집공고일 이후 주소 또는 세대주 변동"));
        var foreign = rules.stream().filter(rule -> rule.title().contains("동일주소")).findFirst().orElseThrow();
        assertTrue(foreign.value().contains("등재된 경우 제출 생략"));
        var disability = rules.stream().filter(rule -> rule.title().startsWith("장애인 증명서 또는")).findFirst().orElseThrow();
        assertTrue(disability.value().contains("장애인 · 발급처: 행정복지센터"));
        String exclusive = "방문신청만 가능하며, 인터넷 청약 접수는 불가합니다.";
        for (String replacement : List.of("방문신청과 인터넷 청약 접수가 모두 가능합니다.", "고령자는 " + exclusive, "\n■ 고령자 안내\n" + exclusive,
                "\n⟦PDF:TABLE id=999 rows=1 cols=1⟧\n⟦PDF:CELL table=999 row=1 col=1 rowspan=1 colspan=1⟧\n"
                        + exclusive + "\n⟦PDF:/CELL⟧\n⟦PDF:/TABLE⟧\n")) {
            var mixedParts = parts.stream().map(part -> new Part(part.location(), part.text().replace(exclusive, replacement))).toList();
            assertNotEquals(parts, mixedParts);
            var mixed = new Source(source.label(), source.url(), source.sha256(), "EXTRACTED", "", List.of(), mixedParts);
            var mixedRules = OfficialGuidanceRows.read(mixed).context().stream().flatMap(part -> part.text().lines())
                    .map(OfficialGuidanceItem::parse).filter(java.util.Objects::nonNull).toList();
            var visitForm = mixedRules.stream().filter(rule -> rule.title().equals("매입임대공급 신청서")).findFirst().orElseThrow();
            assertFalse(OfficialGuidanceItem.commonRecipient(visitForm));
            assertTrue(visitForm.value().startsWith("방문신청자 · 안내:"));
            assertEquals(2, mixedRules.stream().filter(OfficialGuidanceItem::commonRecipient).count());
        }
    }

    @Test
    void MandatoryCategoryCannotTurnConditionOrExemptionIntoEveryone() {
        var columns = List.of("구분", "제출서류", "내용", "발급기관");
        for (String instruction : List.of("• 기혼인 신청자만 제출", "• 신청자 본인 가족관계증명서 제출 • 미혼은 제출 생략",
                "• 신청자 본인 가족관계증명서 제출 • 미혼은 제출하지 않아도 됩니다",
                "• (동의방법) 기혼인 경우 세대구성원 전원이 서명", "• 주소가 바뀐 세대원", "• 다른 기관의 확인이 필요한 경우")) {
            var rule = OfficialGuidanceItem.parse(OfficialGuidanceRows.row(columns,
                    List.of("필수", "가족관계증명서", instruction, "행정복지센터"), "공통 제출서류", false));
            assertNotNull(rule);
            assertFalse(OfficialGuidanceItem.commonRecipient(rule), instruction);
            assertTrue(rule.value().contains(instruction));
        }
        var notCommon = OfficialGuidanceItem.parse(OfficialGuidanceRows.row(columns,
                List.of("필수", "가족관계증명서", "• 신청자 본인 가족관계증명서 제출", "행정복지센터"), "신혼부부 제출서류", false));
        assertFalse(OfficialGuidanceItem.commonRecipient(notCommon));
    }

    private String pdfCell(int row, int column, String text) {
        return "⟦PDF:CELL table=1 row=" + row + " col=" + column + " rowspan=1 colspan=1⟧\n"
                + "⟦PDF:LINE y0=180.00 y1=180.00⟧\n" + text + "\n⟦PDF:/CELL⟧\n";
    }

    private GuidanceRuleGrammar.Rule parse(List<String> header, List<String> row) {
        String line = OfficialGuidanceRows.row(header, row, null, false);
        assertNotNull(line);
        var rule = OfficialGuidanceItem.parse(line);
        assertNotNull(rule);
        return rule;
    }

    @Test
    void refusingAnApplicationWithoutConsentIsNotASubmissionExemption() {
        String warning = "※ (주의) 동의서를 제출하지 않는 경우 임대주택 입주자 신청·접수가 거부됨";
        assertFalse(OfficialGuidanceRows.limitedSubmission(warning));
        assertFalse(OfficialGuidanceRows.limitedSubmission("동의서를 제출하지 않을 경우 임대주택 입주자 모집 신청･접수가거부됨"));
        var fields = List.of("제출서류", "MyMy서비스 이용가능 여부", "비고");
        var rule = OfficialGuidanceItem.parse(OfficialGuidanceRows.row(fields,
                List.of("[필수제출 2] 금융정보 등 제공 동의서", "X", "세대원 전원이 서명. " + warning), "공통 제출서류", false));
        assertTrue(OfficialGuidanceItem.commonRecipient(rule));
        assertTrue(rule.value().contains(warning));
        assertTrue(OfficialGuidanceRows.limitedSubmission(warning + " 다만 배우자는 제출하지 않아도 됩니다."));
        assertTrue(OfficialGuidanceRows.limitedSubmission("임차보증금일 경우 제출 불필요"));
    }

    @Test
    void mandatoryMarkerRequiresCommonScopeAndCannotEraseAnExplicitRecipientRestriction() {
        var headings = List.of("제출서류", "MyMy서비스 이용가능 여부", "비고");
        for (String restriction : List.of("기혼 신청자만 제출, 미혼은 제출 생략", "예비신혼부부만 제출", "기혼인 경우에만 제출",
                "공고일 이후 주소가 변경된 세대원만 제출", "해당자에 한하여 제출", "해당자에 한해 제출",
                "예비신혼부부는 제출 생략", "해당자는 제출 면제", "증명서를 이미 낸 사람은 제출을 면제",
                "임차보증금 증빙은 해당 계약일 경우 제출 불필요")) {
            String text = "■ 공통 제출서류\n⟦PDF:TABLE id=1 rows=2 cols=3⟧\n"
                    + pdfCell(1, 1, "제출서류") + pdfCell(1, 2, "MyMy서비스 이용가능 여부") + pdfCell(1, 3, "비고")
                    + pdfCell(2, 1, "[필수제출1] 혼인관계증명서") + pdfCell(2, 2, "X") + pdfCell(2, 3, restriction)
                    + "⟦PDF:/TABLE⟧\n";
            var source = new Source("모집공고.pdf", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1", "a".repeat(64),
                    "EXTRACTED", "", List.of(), List.of(new Part("공고문 1쪽", text)));
            assertEquals(text, OfficialGuidanceRows.read(source).context().get(0).text(), restriction);
            assertNull(OfficialGuidanceRows.row(headings,
                            List.of("[필수제출1] 혼인관계증명서 (" + restriction + ")", "X", "상세로 발급"), "공통 제출서류", false),
                    "The document-name cell also qualifies the mandatory marker: " + restriction);
        }
        assertNull(OfficialGuidanceRows.row(headings, List.of("[필수제출1] 혼인관계증명서", "X", "상세 증명서로 발급"), "신혼부부 제출서류", false));
        assertNull(OfficialGuidanceRows.row(headings, List.of("[필수제출1] 혼인관계증명서", "X", "상세 증명서로 발급"), null, false));
        var common = OfficialGuidanceRows.row(headings,
                List.of("[필수제출1] 가족관계증명서", "O", "배우자가 없는 경우에도 반드시 제출"), "공통 제출서류", false);
        assertNotNull(common);
        assertTrue(OfficialGuidanceItem.commonRecipient(OfficialGuidanceItem.parse(common)));
    }
}

