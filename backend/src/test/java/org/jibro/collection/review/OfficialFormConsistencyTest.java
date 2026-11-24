package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class OfficialFormConsistencyTest {
    private static final String PAGE = "https://apply.lh.or.kr/notice";
    private static final String FILE = "https://apply.lh.or.kr/notice.hwpx";
    private static final String PUBLICATION = "공고일:2026.11.04";

    @Test
    void verifiedEmptyListingStillBlocksAnInconsistentHwpxOnlyForm() {
        var draft = draft("EMPTY", PUBLICATION, "26.10.08");
        var result = GuidanceReviewCompiler.assess(draft, original());
        assertFalse(result.ready());
        assertNotNull(result.candidate());
        assertEquals(List.of("FORM_PUBLICATION_DATE_MISMATCH"), result.issues());
        assertEquals(FILE, result.findings().get(0).evidence().url());
        assertTrue(result.findings().get(0).message().contains("2026-11-04"));
        assertTrue(result.findings().get(0).message().contains("2026-10-08"));
    }

    @Test
    void matchingHwpxFormDoesNotBecomeAConflictMerelyBecauseListingHasNoExcerpt() {
        var result = GuidanceReviewCompiler.assess(draft("EMPTY", PUBLICATION, "26.11.04"), original());
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void failedUnknownAndUnverifiedEmptyBodiesCannotSupplyPublicationAnchors() {
        for (String status : List.of("FAILED", "UNKNOWN")) {
            var draft = draft(status, PUBLICATION, "26.10.08");
            assertTrue(OfficialFormConsistency.inspect(draft).isEmpty(), status);
            assertFalse(GuidanceReviewCompiler.assess(draft, original()).ready(), status);
        }
        var unknownBody = draft("EMPTY", PUBLICATION + "\n신청 자격은 추가 확인이 필요합니다.", "26.10.08");
        assertTrue(OfficialFormConsistency.inspect(unknownBody).isEmpty());
        assertFalse(GuidanceReviewCompiler.assess(unknownBody, original()).ready());
    }

    @Test
    void numberedApplicationFormMustReferToTheSameRecruitmentDate() {
        var original = draft("EMPTY", PUBLICATION, "26.11.04");
        for (String day : List.of("26.11.04", "26.10.08")) {
            var form = new Source("추가서류.hwpx", "https://apply.lh.or.kr/form.hwpx", "c".repeat(64), "EXTRACTED", "", List.of(),
                List.of(new Part("문서 구간 1", "예비신혼부부 신청 확인서\n[ 신청주택 : 행복주택 예비입주자 모집(‘" + day + ".공고) ]")));
            var findings = OfficialFormConsistency.inspect(original, List.of(form));
            assertEquals(day.equals("26.11.04") ? List.of() : List.of("FORM_PUBLICATION_DATE_MISMATCH"),
                findings.stream().map(GuidanceFinding::code).toList());
        }
    }

    @Test
    void genericFormDoesNotOverrideSpecificRecruitmentExemption() {
        var evidence = new Evidence("모집공고", FILE, "2026-11-04");
        var question = new Question("e-applies-certificate", "서류 제출 대상", "예비신혼부부는 제출 생략 (공사양식 3)", evidence,
            List.of(new AnswerOption("YES", "제출 대상이에요"), new AnswerOption("NO", "해당하지 않아요"), new AnswerOption("UNKNOWN", "잘 모르겠어요", true)));
        var document = new DocumentTemplate("certificate", "가족관계증명서", question.id(), question.reason(), "공식 기관", null, evidence);
        var content = new Content("안내", evidence, List.of(), List.of(question), List.of(document), List.of());
        var form = new Source("공통서식.hwpx", "https://apply.lh.or.kr/form.hwpx", "c".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("문서 구간 1", "서식")));
        var block = new VerifiedPreparationForms.Block("3", "가족관계증명서", "신청자 전원 필수 제출", "", List.of("본인이 서명합니다."), form, "문서 구간 1");
        var result = OfficialFormConsistency.instructions(content, List.of(block), "2026-11-04");
        assertEquals(question.id(), result.content().documents().get(0).conditionQuestionId());
        assertTrue(result.content().questions().stream().anyMatch(q -> q.id().equals(question.id()) && q.reason().equals(question.reason()) && q.reviewRequired()));
        assertEquals(List.of("certificate"), result.content().reviewIssues().get(0).documentIds());
        assertTrue(result.content().documents().get(0).method().contains("제출 생략"));
    }

    @Test
    void numberedFormsPreserveContinuedInstructionsAndRejectUnaccountedQualificationPreface() {
        String text = "□ 자산보유사실확인서 작성 시 유의사항\n본인이 직접 서명합니다.\n\n"
            + "□ 자산보유사실확인서 작성 시 유의사항\n배우자의 증빙도 첨부합니다.\n\n"
            + "[양식 3] 자산보유사실확인서 (신청자 전원 필수 제출)\n신청인 성명 (서명)\n";
        var source = new Source("서식.hwpx", FILE, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("문서 구간 1", text)));
        var blocks = VerifiedPreparationForms.blocks(source);
        assertEquals(List.of("본인이 직접 서명합니다.", "배우자의 증빙도 첨부합니다."), blocks.get(0).instructions());
        var changed = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(),
            List.of(new Part("문서 구간 1", "입주자는 이 지역에 거주하는 사람으로 제한합니다.\n" + text)));
        assertTrue(VerifiedPreparationForms.blocks(changed).isEmpty());
    }

    @Test
    void matchedFormSigningAgeBoundaryAndNegationConflictsStayOnThatDocument() {
        var evidence = new Evidence("모집공고", FILE, "2026-11-04");
        String primary = "14세 미만의 자는 보호자(법정대리인)가 서명함";
        var document = new DocumentTemplate("privacy", "개인정보 동의서", null, primary, "LH", null, evidence);
        var unrelated = new DocumentTemplate("identity", "주민등록등본", null, "공통 제출", "행정복지센터", null, evidence);
        var content = new Content("안내", evidence, List.of(), List.of(), List.of(document, unrelated), List.of());
        for (String instruction : List.of("만 16세 미만의 세대원은 보호자(법정대리인)가 서명함",
            "만 14세 이하의 세대원은 보호자(법정대리인)가 서명함",
            "만 14세 미만의 세대원은 보호자(법정대리인)가 서명하지 않습니다",
            "만 16세 미만의 세대원은 보호자가 서명해야 합니다(필수)",
            "만 14세 미만의 세대원은 보호자가 서명하지 않아도 됩니다")) {
            var source = new Source("개인정보동의서.hwpx", "https://apply.lh.or.kr/privacy.hwpx", "c".repeat(64), "EXTRACTED", "", List.of(),
                List.of(new Part("공고문 2쪽", instruction)));
            // No bullet: the actual numbered-form producer must retain this operative clause.
            String raw = "[양식 1] 개인정보 동의서 (신청자 전원 필수 제출)\n신청인 성명 (서명)\n\n" + instruction;
            source = new Source(source.label(), source.url(), source.sha256(), source.status(), "", List.of(), List.of(new Part("문서 구간 1", raw)));
            var block = VerifiedPreparationForms.blocks(source).get(0);
            var result = OfficialFormConsistency.instructions(content, List.of(block), "2026-11-04");
            assertTrue(result.ready());
            assertEquals(1, result.content().reviewIssues().size(), instruction);
            var issue = result.content().reviewIssues().get(0);
            assertEquals(List.of("privacy"), issue.documentIds());
            assertEquals(List.of(), issue.questionIds());
            assertEquals(List.of(FILE, source.url()), issue.evidence().stream().map(Evidence::url).toList());
            assertTrue(result.content().documents().get(0).method().contains(primary));
            assertTrue(result.content().documents().get(0).method().contains(instruction));
            assertEquals(unrelated, result.content().documents().get(1));
            org.jibro.notice.GuidanceIssues.validate(result.content());
        }
    }

    @Test
    void matchingGuardianRuleDoesNotCompareOtherAgesOrSelfSignatureWithGuardian() {
        var evidence = new Evidence("모집공고", FILE, "2026-11-04");
        var document = new DocumentTemplate("privacy", "개인정보 동의서", null,
            "14세 미만의 자는 보호자(법정대리인)가 서명함", "LH", null, evidence);
        var content = new Content("안내", evidence, List.of(), List.of(), List.of(document), List.of());
        var source = new Source("개인정보동의서.hwpx", "https://apply.lh.or.kr/privacy.hwpx", "c".repeat(64), "EXTRACTED", "", List.of(), List.of());
        var instructions = List.of("만 14세 이상의 세대원은 본인이 직접 서명하고, 만 14세 미만의 세대원은 보호자(법정대리인)가 서명함",
            "신청자는 19세 이상인 자입니다.", "동의서의 유효기간은 6개월입니다.");
        var block = new VerifiedPreparationForms.Block("1", "개인정보 동의서", "신청자 전원 필수 제출", "", instructions, source, "문서 구간 1");
        var result = OfficialFormConsistency.instructions(content, List.of(block), "2026-11-04");
        assertTrue(result.ready());
        assertTrue(result.content().reviewIssues().isEmpty());
    }

    private static Content original() {
        return new Content("공고 안내", new Evidence("공식 상세 본문", PAGE, "2026-11-04"),
            List.of(new Evidence("모집공고.hwpx", FILE, "2026-11-04")), List.of(), List.of(), List.of());
    }

    private static Draft draft(String listingStatus, String listingText, String formDate) {
        var listing = new Source("공식 상세 본문", PAGE, "a".repeat(64), listingStatus, "", List.of(),
            List.of(new Part("공식 상세 본문", listingText)));
        String body = """
            입주자모집공고일 : 2026.11.04
            1. 신청 자격
            신청자는 무주택자이어야 합니다.
            2. 제출서류
            공통 제출서류: 가족관계증명서
            3. 모집 일정
            접수기간: 2026.11.25 ~ 2026.11.26
            확약서
            공고('%s)에 따른 입주자로서 위 사실을 확인합니다.
            """.formatted(formDate);
        var attachment = new Source("모집공고.hwpx", FILE, "b".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("문서 구간 1", body)));
        return new Draft("extraction", "notice", 1, "PARTIAL", Instant.parse("2026-11-04T00:00:00Z"), "", List.of(listing, attachment));
    }
}
