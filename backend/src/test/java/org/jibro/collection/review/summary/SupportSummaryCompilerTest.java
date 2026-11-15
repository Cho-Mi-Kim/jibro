package org.jibro.collection.review.summary;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class SupportSummaryCompilerTest {
    private static final Instant NOW = Instant.parse("2030-05-01T00:00:00Z");
    private static final LocalDate START = LocalDate.of(2030, 5, 1), END = LocalDate.of(2030, 5, 31);
    private static final Evidence EVIDENCE = new Evidence("공식 공고", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=101", "2026-01-01");
    private static final String JEONSE = """
        2030년 청년 전세임대 1순위 입주자 수시모집
        청년 전세임대란?
        지원한도액 범위 내에서 전세주택을 결정하면 한국토지주택공사가 해당 주택 소유자와 전세계약을 체결하고 재임대하는 제도입니다.
        3. 신청자격
        미혼 무주택자로 대학생, 취업준비생 또는 공고의 연령 기준에 해당하는 청년이 신청할 수 있습니다.
        """;
    private static final String NATIONAL = """
        새마을 국민임대주택 예비입주자 모집공고
        '19.9.27일 이후 국민임대, 영구임대, 행복주택 예비입주 중복선정은 불가합니다.
        3. 임대조건
        임대보증금 및 월 임대료
        4. 신청자격
        공고일 현재 성년자인 무주택세대구성원 중 공고의 소득·자산 기준을 충족하는 가구가 대상입니다.
        공가 발생 시 예비입주자 순번에 따라 계약을 체결합니다.
        """;

    private static Content original() {
        return new Content("공식 목록에서 자동 수집했습니다.", EVIDENCE, List.of(EVIDENCE),
            List.of(new Question("q", "자격 확인", "세부 기준", EVIDENCE)),
            List.of(new DocumentTemplate("d", "등본", null, "발급", "정부24", null, EVIDENCE)),
            List.of(new ScheduleTemplate("end", "신청 접수 마감", END.toString(), EVIDENCE)));
    }
    private static Source source(String text) {
        return new Source("모집공고.pdf", EVIDENCE.url(), "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("공고문 1쪽", text)));
    }
    private static Draft draft(Source... sources) {
        return new Draft("extraction", "notice", 1, "READY", NOW, "", List.of(sources));
    }
    private static Content compile(String text) {
        return SupportSummaryCompiler.apply(draft(source(text)), original(), START, END, NOW);
    }

    @Test void youthAudienceAndActualJeonseMechanismAreTwoSentencesWithoutInventedAmounts() {
        var before = original(); var result = SupportSummaryCompiler.apply(draft(source(JEONSE)), before, START, END, NOW);
        assertTrue(result.summary().contains("청년·대학생·취업준비생"));
        assertTrue(result.summary().contains("1순위 자격"));
        assertTrue(result.summary().contains("LH가 집주인과 계약한 뒤 다시 임대"));
        assertEquals(2, result.summary().split("\\. ").length);
        assertFalse(result.summary().contains("만원"));
        assertEquals(before.questions(), result.questions()); assertEquals(before.documents(), result.documents());
        assertEquals(before.schedules(), result.schedules()); assertEquals(before.attachments(), result.attachments());
    }

    @Test void nationalRentalDoesNotConfuseOtherHousingTypesMentionedInDuplicateApplicationWarning() {
        String summary = compile(NATIONAL).summary();
        assertTrue(summary.contains("무주택세대구성원"));
        assertTrue(summary.contains("국민임대주택을 보증금과 월 임대료로 공급합니다."));
        assertTrue(summary.contains("빈집이 생겼을 때 대기순번"));
        assertEquals(3, summary.split("\\. ").length);
        assertFalse(summary.contains("30년")); assertFalse(summary.contains("중복"));
    }

    @Test void housingWaiverDoesNotBecomeAnAbsoluteNonOwnershipRequirement() {
        String text = NATIONAL.replace("3. 임대조건", "무주택요건 완화\n해당지역 및 연접지역에 주택이 없으면 입주허용, 소형·저가주택 허용\n3. 임대조건");
        String summary = compile(text).summary();
        assertTrue(summary.contains("완화된 주택 보유 기준"));
        assertFalse(summary.contains("무주택세대구성원이 대상"));
        assertFalse(summary.contains("소득·자산"));
    }

    @Test void newlywedAndNewbornCategoriesAreReadFromTheCurrentProgram() {
        String text = JEONSE.replace("청년 전세임대 1순위", "신혼·신생아 전세임대Ⅱ")
            .replace("미혼 무주택자로 대학생, 취업준비생 또는 공고의 연령 기준에 해당하는 청년", "무주택세대구성원으로 신혼부부, 예비신혼부부, 신생아가구");
        String summary = compile(text).summary();
        assertTrue(summary.contains("신혼부부·예비신혼부부·신생아가구"));
        assertFalse(summary.contains("20%")); assertEquals(2, summary.split("\\. ").length);
    }

    @Test void reviewedMultipleChildrenUseShortWordingWithoutRelaxingSourceRequirements() {
        String text = "다자녀 매입임대주택 입주자 모집공고\n3. 입주자격\n두명 이상의 직계비속을 양육하는 가구\n직계비속: 태아를 포함하여 미성년자에 한함\nLH가 매입한 주택을 공급합니다.\n임대보증금 월 임대료";
        String summary = compile(text).summary();
        assertTrue(summary.startsWith("2자녀 이상인 가구"));
        assertFalse(summary.contains("직계비속"));
        assertTrue(summary.contains("LH가 매입한 주택")); assertFalse(summary.contains("30%"));
        assertTrue(summary.contains("LH가 매입한 주택을 보증금과 월 임대료로 공급합니다."));
        assertEquals(original().summary(), compile(text.replace("직계비속: 태아를 포함하여 미성년자에 한함", "")).summary());
    }

    @Test void happinessApplicantsComeFromTheQualificationSectionNotAnUnrelatedExample() {
        String text = "새마을 행복주택 입주자 모집공고\n임대보증금 월 임대료\n3. 신청자격 및 선정방법\n대학생, 청년, 신혼부부 계층을 모집합니다.\n";
        String summary = compile(text).summary();
        assertTrue(summary.startsWith("대학생·청년·신혼부부 등")); assertTrue(summary.contains("행복주택을"));
    }

    @Test void unnamedRentalTypeStaysGenericEvenWhenOtherTypesAppearInWarnings() {
        String summary = compile(NATIONAL.replace("새마을 국민임대주택", "새마을 임대주택")).summary();
        assertTrue(summary.contains("공고에 나온 임대주택을"));
        assertFalse(summary.contains("공고에 나온 영구임대주택"));
    }

    @Test void qualificationListMayEndInANounBeforeTheNextBullet() {
        String text = NATIONAL.replace("공고일 현재 성년자인 무주택세대구성원 중 공고의 소득·자산 기준을 충족하는 가구가 대상입니다.",
            "■ 공고일 현재 해당 지역에 거주하는 성년자인 무주택세대구성원\n• 중복신청은 불가합니다.");
        assertTrue(compile(text).summary().contains("무주택세대구성원이 대상"));
    }

    @Test void missingBenefitOrQualificationAndTruncatedOrOcrEvidenceKeepExistingSummary() {
        for (String text : List.of(JEONSE.replace("재임대", "지원"), JEONSE.replace("무주택자", "참고 사항"),
                JEONSE.replace("전세계약을 체결하고", "전세계약을 체결하지 않으며"),
                JEONSE.replace("미혼 무주택자로 대학생", "무주택자는 신청할 수 없습니다. 대학생"),
                JEONSE + "[발췌 길이 제한]", "청년 전세임대 입주자 모집공고"))
            assertEquals(original().summary(), compile(text).summary());
        Source failed = new Source("공고.pdf", EVIDENCE.url(), null, "FAILED", "", List.of(), List.of());
        assertEquals(original().summary(), SupportSummaryCompiler.apply(draft(failed), original(), START, END, NOW).summary());
        var ocr = new Source("모집공고.pdf", EVIDENCE.url(), "a".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("1쪽 " + org.jibro.collection.extraction.OcrProvenance.MARKER, JEONSE)));
        assertEquals(original().summary(), SupportSummaryCompiler.apply(draft(ocr), original(), START, END, NOW).summary());
    }

    @Test void incompatibleCompleteRecruitmentDocumentsDoNotPickTheFirstAttachment() {
        var before = original();
        assertSame(before, SupportSummaryCompiler.apply(draft(source(JEONSE), source(NATIONAL)), before, START, END, NOW));
    }

    @Test void closedDateExplicitClosureAndDeadlineCrossingSkipSummarizationButUpcomingWorks() {
        var d = draft(source(JEONSE)); var before = original();
        assertSame(before, SupportSummaryCompiler.apply(d, before, START, END, Instant.parse("2030-05-31T15:00:00Z")));
        var timed = new Content(before.summary(), EVIDENCE, before.attachments(), before.questions(), before.documents(),
            List.of(new ScheduleTemplate("end", "신청 접수 마감", "2030-05-01T09:00:00Z", EVIDENCE)));
        assertSame(timed, SupportSummaryCompiler.apply(d, timed, START, START, Instant.parse("2030-05-01T09:00:00Z")));
        var closed = new Content(before.summary(), EVIDENCE, before.attachments(), before.questions(), before.documents(), before.schedules(),
            List.of(), new OfficialApplicationClosure("공식 조기종료", EVIDENCE));
        assertSame(closed, SupportSummaryCompiler.apply(d, closed, START, END, NOW));
        assertNotEquals(before.summary(), SupportSummaryCompiler.apply(d, before, START.plusDays(1), END, NOW).summary());
    }

    @Test void unresolvedAudienceIsNeverSummarizedAsAnUnconditionalEligibilityFact() {
        var before = original();
        var issue = new ReviewIssue("audience", "CONDITION", "대상 확인", "문서 간 대상 차이", List.of("q"), List.of(), List.of(EVIDENCE));
        var content = new Content(before.summary(), EVIDENCE, before.attachments(), before.questions(), before.documents(), before.schedules(), List.of(issue));
        assertSame(content, SupportSummaryCompiler.apply(draft(source(JEONSE)), content, START, END, NOW));
    }

    @Test void summaryRunsOnBothOrdinaryAndFollowupAssessmentWithoutChangingGuideReadiness() {
        String text = """
            국민임대주택 입주자 모집공고
            임대보증금 및 월 임대료
            신청 자격
            신청자는 무주택자이어야 합니다.
            제출 서류
            공통 제출서류: 주민등록등본, 가족관계증명서
            공급 일정
            접수기간: 2030.05.01 ~ 2030.05.31
            """;
        var d = new Draft("extraction", "notice", 1, "READY", Instant.now(), "", List.of(source(text)));
        var before = original();
        var plain = org.jibro.collection.review.GuidanceReviewCompiler.assess(d, before);
        assertTrue(plain.ready(), plain.issues().toString());
        assertTrue(plain.candidate().summary().contains("무주택자가 대상"));
        assertTrue(plain.candidate().summary().contains("국민임대주택을"));
        var followup = org.jibro.collection.review.OfficialFollowupComparison.compare(d, before,
            new org.jibro.collection.review.followup.OfficialFollowupModels.Discovery(NOW, List.of(), List.of())).assessment();
        assertTrue(followup.ready(), followup.issues().toString());
        assertEquals(plain.candidate().summary(), followup.candidate().summary());
        var officiallyClosed = new Content(before.summary(), before.evidence(), before.attachments(), before.questions(),
            before.documents(), before.schedules(), List.of(), new OfficialApplicationClosure("공식 조기마감", EVIDENCE));
        assertEquals(before.summary(), org.jibro.collection.review.GuidanceReviewCompiler.assess(d, officiallyClosed).candidate().summary());
        var unread = new Draft(d.id(), d.noticeId(), d.revision(), "FAILED", NOW, "", d.sources());
        assertFalse(org.jibro.collection.review.GuidanceReviewCompiler.assess(unread, before).ready());
    }
}
