package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OcrProvenance;
import org.jibro.collection.review.followup.OfficialFollowupModels.*;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class OfficialFollowupComparisonTest {
    static final Instant CHECKED = Instant.parse("2026-11-09T03:00:00Z");
    static final String PRIMARY = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=101";
    static final String OTHER = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=102";
    static final String NEXT = "https://apply.lh.or.kr/lhapply/apply/noti/an/view.do?bbsSn=101";
    static final String LATER = "https://apply.lh.or.kr/lhapply/apply/noti/an/view.do?bbsSn=102";
    static final String VALID = """
        신청 자격
        신청자는 무주택자이어야 합니다.
        신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.
        제출 서류
        공통 제출서류: 주민등록등본, 가족관계증명서
        대학생 제출서류: 재학증명서
        공급 일정
        접수기간: 2026.01.01 ~ 2026.01.31
        결과 발표: 2026.02.07
        서류 마감: 2026.02.15
        """;
    final Content original = new Content("공식 모집", new Evidence("원문", PRIMARY, "2026-11-09"), List.of(), List.of(), List.of(), List.of());

    static Source source(String url, String text) {
        return new Source("공식 공고문", url, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽", text)));
    }

    static Draft draft(Source... sources) {
        return new Draft(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, "READY", CHECKED, "", List.of(sources));
    }

    static Related related(String kind, String url, LocalDate day, String text) {
        return new Related(kind, url, "공식 후속 안내", day, "동일 모집 공고 식별자와 단계가 공식 페이지에서 연결됨", List.of(source(url, text)));
    }

    OfficialFollowupComparison.Result compare(Draft draft, Related... related) {
        return OfficialFollowupComparison.compare(draft, original, new Discovery(CHECKED, List.of(related), List.of()));
    }

    @Test
    void lockedOriginalUsesTheMatchedReadableCopyAndKeepsFormSeparateFromTheRequirement() {
        String parent = "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=123";
        String file = "https://www.myhome.go.kr/hws/com/fms/cvplFileDownload.do?atchFileId=abc&fileSn=1";
        var pdf = new Source("공식 모집공고.pdf", file, "b".repeat(64), "EXTRACTED", "", List.of(), List.of(
            new Part("공고문 4쪽", "■ 신청 자격: 공고일 기준 무주택 세대원 전원입니다.\n혼인확인서 제출 대상: 예비신혼부부"),
            new Part("공고문 7쪽", "혼인확인서\n신청인 서명")));
        var locked = new Source("모집공고.hwpx", OTHER, "c".repeat(64), "FAILED", "암호화", List.of());
        var related = new Related("AGENCY", parent, "공고", LocalDate.of(2026, 11, 2), "동일 LH 모집", List.of(source(parent, "공고"), pdf));
        var found = new Discovery(CHECKED, List.of(related), List.of("REGIONAL_ALTERNATIVE_RETRY"));
        var primary = draft(source(PRIMARY, "목록"), locked);
        var sections = new VerifiedPreparationForms(primary, found).sections(OfficialFollowupComparison.corroboratedItems(primary, original, found));
        assertTrue(sections.stream().anyMatch(s -> s.area().name().equals("CONDITION")));
        var document = sections.stream().filter(s -> s.title().equals("혼인확인서")).findFirst().orElseThrow();
        assertEquals(file + "#page=7", document.form().url());
        assertTrue(document.evidence().url().contains("#page=4"));
        var conflict = draft(source(PRIMARY, "혼인확인서 제출 대상: 모든 신청자"), locked);
        assertTrue(OfficialFollowupComparison.corroboratedItems(conflict, original, found).stream().noneMatch(s -> s.title().equals("혼인확인서")));
    }

    @Test
    void agencyFormConflictsOnlyWithTheDifferentRuleAndAddsConfirmedDocuments() {
        String post = "https://www.kohom.or.kr/web/mainComm/HM001002002.do?mode=view&p_idx=123";
        String form = "https://www.kohom.or.kr/common/download.do?id=11";
        var article = new Source("공식 게시글", post, "b".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("본문", "서식을 첨부합니다.")));
        var attachment = new Source("신청서식.pdf", form, "c".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("공고문 2쪽", "신청자의 월평균 소득은 4,000,000원 이하이어야 합니다.\n혼인관계증명서 제출 대상: 예비신혼부부")));
        var related = new Related("AGENCY", post, "신청 안내", LocalDate.of(2026, 1, 2), "동일 원공고 링크", List.of(article, attachment));
        var result = compare(draft(source(PRIMARY, VALID)), related);
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        var guide = result.assessment().candidate();
        assertEquals(1, guide.reviewIssues().size());
        assertTrue(guide.reviewIssues().get(0).evidence().stream().anyMatch(e -> e.url().startsWith(form)));
        assertTrue(guide.documents().stream().anyMatch(doc -> doc.title().equals("혼인관계증명서")
            && doc.method().contains("예비신혼부부") && doc.evidence().url().startsWith(form)));
        assertTrue(guide.documents().stream().anyMatch(doc -> doc.title().equals("주민등록등본")));
    }

    @Test
    void independentlyConfirmedDocumentSurvivesAnUnreadSiblingButNotAnUncertainPrimary() {
        String url = "https://www.namwon.go.kr/board/post/view.do?id=7";
        String statement = "혼인관계증명서 제출 대상: 예비신혼부부";
        var unread = new Source("다른 서식", url + "&file=2", null, "FAILED", "", List.of());
        var article = source(url, statement);
        var related = new Related("AGENCY", url, "신청 안내", LocalDate.of(2026, 1, 2), "원공고 직접 연결", List.of(article, unread));
        var discovery = new Discovery(CHECKED, List.of(related), List.of());
        var confirmed = OfficialFollowupComparison.corroboratedItems(draft(source(PRIMARY, statement)), original, discovery);
        assertTrue(confirmed.stream().anyMatch(s -> s.title().equals("혼인관계증명서") && s.evidence().url().startsWith(url)));
        var ocr = new Source("스캔", PRIMARY, "a".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("1쪽 " + OcrProvenance.MARKER, statement)));
        assertTrue(OfficialFollowupComparison.corroboratedItems(draft(ocr), original, discovery).isEmpty());
        var conflicting = draft(source(PRIMARY, statement), source(OTHER, "혼인관계증명서 제출 대상: 모든 신청자"));
        assertTrue(OfficialFollowupComparison.corroboratedItems(conflicting, original, discovery).isEmpty());
    }

    @Test
    void explicitAgencyReplacementResolvesOnlyItsOldValue() {
        String url = "https://www.namwon.go.kr/board/post/view.do?id=123";
        var post = new Source("시청 후속 공지", url, "b".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("본문",
            "정정 전: 신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.\n정정 후: 신청자의 월평균 소득은 4,000,000원 이하이어야 합니다.")));
        var agency = new Related("AGENCY", url, "소득 기준 정정", LocalDate.of(2026,1,3), "원모집 직접 참조", List.of(post));
        var result = compare(draft(source(PRIMARY, VALID)), agency);
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertEquals(1, result.resolutions().size());
        assertTrue(result.assessment().candidate().reviewIssues().isEmpty());
        assertTrue(result.assessment().candidate().questions().stream().anyMatch(q -> q.evidence().url().startsWith(url)));
    }

    @Test
    void unreadAgencyAttachmentIsInternalReviewWork() {
        String url = "https://www.namwon.go.kr/board/post/view.do?id=123";
        var pdf = new Source("첨부.pdf", url + "&file=1", "b".repeat(64), "UNREAD", "재시도", List.of(), List.of());
        var post = new Related("AGENCY", url, "모집 안내", LocalDate.of(2026,1,3), "동일 공고일·단지·주택 유형", List.of(source(url,"안내"),pdf));
        var result = compare(draft(source(PRIMARY, VALID)), post);
        assertFalse(result.assessment().ready());
        assertTrue(result.blockers().contains("FOLLOWUP_SOURCE_INCOMPLETE"));
        assertTrue(result.assessment().candidate().reviewIssues().isEmpty());
    }

    Related correction(String text) {
        return related("CORRECTION", NEXT, null, text);
    }

    @Test
    void verifiedOnlineAmendmentOverridesItsOldRangeWithoutMovingTheVisitDates() {
        var primary = draft(source(PRIMARY, "인터넷 접수 기간: 2026.12.01 10:00 ~ 2026.12.02 16:00\n"
            + "현장 접수 기간: 2026.12.01 10:00 ~ 2026.12.02 15:00"));
        var corrected = related("CORRECTION", NEXT, LocalDate.of(2026, 11, 8),
            "정정 전: 인터넷 접수 기간: 2026.12.01 10:00 ~ 2026.12.02 16:00\n"
                + "정정 후: 인터넷 접수 기간: 2026.12.03 09:00 ~ 2026.12.04 17:00");
        var result = compare(primary, corrected);
        assertNotNull(result.intake(), result.blockers().toString());
        assertEquals(LocalDate.of(2026, 12, 4), result.intake().end());
        assertTrue(result.intake().schedules().stream().anyMatch(s -> s.title().contains("현장") && s.date().equals("2026-12-02T15:00")));
        assertTrue(result.intake().schedules().stream().anyMatch(s -> s.date().equals("2026-12-04T17:00") && s.evidence().url().startsWith(NEXT)));
    }

    Related followup(String text) {
        return related("FOLLOWUP", NEXT, LocalDate.of(2026, 2, 1), text);
    }

    static ScheduleTemplate schedule(Content content, String id) {
        return content.schedules().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void correctionVerifiesAnIntakeClockIndependentlyOfUnresolvedQualifications() {
        String before = "접수기간: 2026.12.01 10:00 ~ 2026.12.02 16:00";
        String after = before.replace("16:00", "18:00");
        var result = compare(draft(source(PRIMARY, before + "\n예외는 별도 심사를 따릅니다.")),
            correction("정정 전: " + before + "\n정정 후: " + after));
        assertNotNull(result.intake(), result.blockers().toString());
        var end = result.intake().schedules().stream().filter(s -> s.id().equals("auto-application-end")).findFirst().orElseThrow();
        assertEquals("2026-12-02T18:00", end.date());
        assertEquals(NEXT, end.evidence().url());
        var rolling = compare(draft(source(PRIMARY, before + "\n예외는 별도 심사를 따릅니다.")),
            correction("정정 전: " + before + "\n정정 후: 접수 기간: 모집 완료 시까지"));
        assertNotNull(rolling.intake(), rolling.blockers().toString());
        assertNull(rolling.intake().end());
        var condition = rolling.intake().schedules().stream().filter(s -> s.id().equals("auto-until-filled")).findFirst().orElseThrow();
        assertEquals(NEXT, condition.evidence().url());
        assertFalse(result.assessment().ready());
    }

    @Test
    void exactIncomeAmendmentResolvesOnlyItsFieldAndPreservesOriginalBytesAndProvenance() {
        var primary = draft(source(PRIMARY, VALID), source(OTHER, VALID.replace("3,000,000", "4,000,000")));
        var result = compare(primary, correction("변경 전: 신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.\n변경 후: 신청자의 월평균 소득은 4,000,000원 이하이어야 합니다."));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertTrue(result.assessment().candidate().reviewIssues().isEmpty());
        assertEquals(VALID, primary.sources().get(0).context().get(0).text());
        assertEquals(3, result.assessment().candidate().documents().size());
        var income = result.assessment().candidate().questions().stream().filter(q -> q.id().equals("auto-income")).findFirst().orElseThrow();
        assertTrue(income.reason().contains("4,000,000"));
        assertTrue(result.invalidatesPublishedGuide());
        assertEquals(NEXT, result.resolutions().get(0).replacementEvidence().url());
        assertEquals(PRIMARY, result.resolutions().get(0).previousEvidence().get(0).url());
    }

    @Test
    void explicitCommonDocumentReplacementDoesNotEraseStudentApplicability() {
        var result = compare(draft(source(PRIMARY, VALID)), correction("정정 전: 공통 제출서류: 주민등록등본, 가족관계증명서\n정정 후: 공통 제출서류: 주민등록등본, 주민등록초본"));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        var docs = result.assessment().candidate().documents();
        assertFalse(docs.stream().anyMatch(d -> d.title().equals("가족관계증명서")));
        assertTrue(docs.stream().anyMatch(d -> d.title().equals("주민등록초본")));
        assertEquals("auto-document-student", docs.stream().filter(d -> d.title().equals("재학증명서")).findFirst().orElseThrow().conditionQuestionId());
    }

    @Test
    void anUnknownPrimaryExceptionIsNeverDeletedToApproveTheRecognizedChange() {
        var result = compare(draft(source(PRIMARY, VALID.replace("제출 서류", "예외는 별도 심사를 따릅니다.\n제출 서류"))),
            correction("정정 전: 신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.\n정정 후: 신청자의 월평균 소득은 4,000,000원 이하이어야 합니다."));
        assertFalse(result.assessment().ready());
        assertTrue(result.assessment().issues().contains("FOLLOWUP_QUALIFICATION_GRAMMAR_UNSUPPORTED"));
    }

    @Test
    void actualOfficialAnnouncementLineKeepsItsStageTimeAndAfterQualifier() {
        // Verbatim schedule line from the official 2022.12.16 follow-up; only this parser fixture's scope is claimed.
        String announcement = "- 예비입주자 당첨자  발표: 2022.12.16(금) 14:00 이후";
        var primary = draft(source(PRIMARY, VALID.replace("2026.01.01 ~ 2026.01.31", "2022.08.16 ~ 2022.08.17").replace("2026.02.07", "2022.11.25").replace("2026.02.15", "2022.08.29")));
        var result = compare(primary, related("FOLLOWUP", NEXT, LocalDate.of(2022, 12, 16), announcement));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        var event = schedule(result.assessment().candidate(), "auto-result");
        assertEquals("2022-12-16T14:00", event.date());
        assertTrue(event.title().contains("이후"));
        assertEquals(NEXT, event.evidence().url());
        assertEquals("2022-08-29", schedule(result.assessment().candidate(), "auto-documents-end").date());
    }

    @Test
    void provedRecruitmentIntroductionMetadataGreetingAndContactDoNotCreateFalseBlockers() {
        String introduction = "화성 병점복합타운A1블럭 행복주택 예비입주자 당첨자 발표('22.08.05. 공고)를 알려드립니다.";
        var parts = List.of(new Part("공식 후속 공지 제목", "공식 후속 안내"),
            new Part("공식 후속 공지 게시 정보", "유형 행복주택 담당부서 경기남부지역본부 게시일 2022-12-16"),
            new Part("공식 후속 공지 본문 · 1", introduction),
            new Part("공식 후속 공지 본문 · 2", "- 예비입주자 당첨자 발표: 2022.12.16(금) 14:00 이후\n(* LH청약센터(apply.lh.or.kr) 및 ARS(1661-7700)으로 확인, 당첨자만 개별 문자 통보)\n문의: LH 1600-1004\n감사합니다."));
        var item = new Source("공식 후속 공지", NEXT, "a".repeat(64), "EXTRACTED", "", List.of(), parts);
        var related = new Related("FOLLOWUP", NEXT, "공식 후속 안내", LocalDate.of(2022, 12, 16), "원모집 공고일과 공급 범위 직접 언급: " + introduction, List.of(item));
        var primary = draft(source(PRIMARY, VALID.replace("2026.01.01 ~ 2026.01.31", "2022.08.16 ~ 2022.08.17").replace("2026.02.07", "2022.11.25").replace("2026.02.15", "2022.08.29")));
        var result = compare(primary, related);
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        var injected = new Source(item.label(), NEXT, item.sha256(), "EXTRACTED", "", List.of(), List.of(
            new Part("공식 후속 공지 본문", "당첨자 발표: 2022.12.16\n문의: 소득 기준 예외 적용")));
        assertFalse(compare(primary, new Related("FOLLOWUP", NEXT, related.title(), related.publishedDate(), related.relationshipProof(), List.of(injected))).assessment().ready());
    }

    @Test
    void sentenceWithExplicitEventAndNewDateCanReplaceTheOldDate() {
        var result = compare(draft(source(PRIMARY, VALID)), followup("당첨자 발표가 2026.02.10 17:00으로 변경되었습니다."));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertEquals("2026-02-10T17:00", schedule(result.assessment().candidate(), "auto-result").date());
    }

    @Test
    void documentSelectionCannotReplaceFinalWinnerAnnouncement() {
        var result = compare(draft(source(PRIMARY, VALID)), followup("서류제출대상자 발표: 2026.02.10"));
        assertFalse(result.assessment().ready());
        assertFalse(result.invalidatesPublishedGuide());
        assertTrue(result.blockers().contains("FOLLOWUP_CHANGED_FIELD_NOT_FOUND:SCHEDULE:SELECTION"));
    }

    @Test
    void postponementFollowedByAnExplicitConfirmationDoesNotRetainTheSupersededUnknownDate() {
        var result = compare(draft(source(PRIMARY, VALID)), followup("당첨자 발표: 미정"),
            related("FOLLOWUP", LATER, LocalDate.of(2026, 2, 3), "당첨자 발표: 2026.02.12 16:00"));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertTrue(result.assessment().candidate().reviewIssues().isEmpty());
        assertEquals("2026-02-12T16:00", schedule(result.assessment().candidate(), "auto-result").date());
    }

    @Test
    void unresolvedAnnouncementIsScopedAndNeverCreatesAnInventedEvent() {
        var result = compare(draft(source(PRIMARY, VALID)), followup("당첨자 발표: 미정"));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertEquals(1, result.assessment().candidate().reviewIssues().size());
        assertFalse(result.assessment().candidate().schedules().stream().anyMatch(s -> s.id().equals("auto-result")));
        assertTrue(result.invalidatesPublishedGuide());
    }

    @Test
    void distinctConfirmationsConflictRatherThanChoosingTheNewestTimestamp() {
        var result = compare(draft(source(PRIMARY, VALID)), followup("당첨자 발표: 2026.02.10"),
            related("FOLLOWUP", LATER, LocalDate.of(2026, 2, 3), "당첨자 발표: 2026.02.11"));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertEquals(1, result.assessment().candidate().reviewIssues().size());
        assertFalse(result.assessment().candidate().schedules().stream().anyMatch(s -> s.id().equals("auto-result")));
        var issue = result.assessment().candidate().reviewIssues().get(0);
        assertTrue(issue.detail().contains("2026-02-10"));
        assertTrue(issue.detail().contains("2026-02-11"));
    }

    @Test
    void aRepeatedUnchangedAnnouncementDoesNotInvalidatePublishedGuidance() {
        var result = compare(draft(source(PRIMARY, VALID)), followup("당첨자 발표: 2026.02.07"));
        assertTrue(result.assessment().ready());
        assertFalse(result.invalidatesPublishedGuide());
    }

    @Test
    void unknownFollowupProseBlocksPublicationButRecognizedContraryEvidenceCanWithdrawTheOldGuide() {
        var result = compare(draft(source(PRIMARY, VALID)), followup("당첨자 발표: 2026.02.10\n별도 소득 예외를 적용합니다."));
        assertFalse(result.assessment().ready());
        assertTrue(result.invalidatesPublishedGuide());
        assertTrue(result.blockers().contains("FOLLOWUP_CLAUSE_UNSUPPORTED"));
        var onlyUnknown = compare(draft(source(PRIMARY, VALID)), followup("별도 소득 예외를 적용합니다."));
        assertFalse(onlyUnknown.invalidatesPublishedGuide());
    }

    @Test
    void readFailuresAreMachineBlockersNotAgencyConflictsOrEvidenceOfInvalidation() {
        var unread = new Source("원문", NEXT, null, "FAILED", "일시 오류", List.of());
        var related = new Related("FOLLOWUP", NEXT, "후속", LocalDate.of(2026, 2, 1), "동일 공고 확인", List.of(unread));
        var result = compare(draft(source(PRIMARY, VALID)), related);
        assertFalse(result.assessment().ready());
        assertFalse(result.invalidatesPublishedGuide());
        assertTrue(result.blockers().contains("FOLLOWUP_SOURCE_INCOMPLETE"));
        assertTrue(result.assessment().candidate().reviewIssues().isEmpty());
    }

    @Test
    void unreadOriginalAndOcrFollowupCannotBeCertifiedByTheOtherReadableMaterial() {
        var old = draft(source(PRIMARY, VALID), new Source("첨부", OTHER, null, "FAILED", "", List.of()));
        assertFalse(compare(old, followup("당첨자 발표: 2026.02.10")).assessment().ready());
        var ocr = new Source("원문", NEXT, "a".repeat(64), "EXTRACTED", "", List.of(), List.of(new Part("1쪽 " + OcrProvenance.MARKER, "당첨자 발표: 2026.02.10")));
        var result = compare(draft(source(PRIMARY, VALID)), new Related("FOLLOWUP", NEXT, "후속", LocalDate.of(2026, 2, 1), "동일 공고 확인", List.of(ocr)));
        assertFalse(result.assessment().ready());
        assertFalse(result.invalidatesPublishedGuide());
    }

    @Test
    void exactWholeApplicationPeriodAmendmentCanResolveBothBoundariesWithoutUsingContractDates() {
        var result = compare(draft(source(PRIMARY, VALID)), correction("변경 전: 접수기간: 2026.01.01 ~ 2026.01.31\n변경 후: 접수기간: 2026.01.02 ~ 2026.02.01"));
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertEquals(LocalDate.of(2026, 1, 2), result.assessment().startDate());
        assertEquals(LocalDate.of(2026, 2, 1), result.assessment().endDate());
        assertFalse(compare(draft(source(PRIMARY, VALID)), followup("계약 마감: 2026.02.01")).invalidatesPublishedGuide());
    }

    @Test
    void invalidDateUnknownYearAndChangedSubjectNeverBecomeAnAcceptedReplacement() {
        for (String text : List.of("당첨자 발표: 2026.02.30", "당첨자 발표: 02.10", "1순위 당첨자 발표: 2026.02.10",
            "변경 전: 신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.\n변경 후: 신청자의 총자산은 4,000,000원 이하이어야 합니다.")) {
            var result = compare(draft(source(PRIMARY, VALID)), followup(text));
            assertFalse(result.assessment().ready(), text);
            assertFalse(result.invalidatesPublishedGuide(), text);
        }
    }

    @Test
    void verifiedNativeCorrectionWithANewUnsupportedGoverningClauseWithdrawsWithoutInventingAgencyConflicts() {
        String newClause = "신청자의 월평균 소득은 가구 구성에 따른 별도 완화 기준을 적용합니다.";
        var result = compare(draft(source(PRIMARY, VALID)), correction(VALID.replace(
            "신청자의 월평균 소득은 3,000,000원 이하이어야 합니다.", newClause)));
        assertFalse(result.assessment().ready());
        assertTrue(result.resolutions().isEmpty());
        assertTrue(result.invalidatesPublishedGuide());
        assertEquals(1, result.invalidations().size());
        var invalidation = result.invalidations().get(0);
        assertEquals(newClause, invalidation.statement());
        assertEquals(NEXT, invalidation.evidence().url());
        assertEquals("FOLLOWUP_CORRECTION_REQUIRES_RECOMPILATION", invalidation.code());
        assertFalse(invalidation.relationshipProof().isBlank());
        assertTrue(result.assessment().candidate().reviewIssues().isEmpty());
    }

    @Test
    void unchangedCorrectionClausesAndUnverifiableCorrectionSourcesNeverSupplyInvalidationEvidence() {
        var primary = draft(source(PRIMARY, VALID));
        assertFalse(compare(primary, correction(VALID)).invalidatesPublishedGuide());
        String changed = VALID.replace("3,000,000원 이하이어야 합니다.", "가구 구성에 따른 별도 완화 기준을 적용합니다.");
        var failed = new Source("정정 원문", NEXT, null, "FAILED", "일시 오류", List.of());
        var ocr = new Source("정정 원문", NEXT, "a".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("1쪽 " + OcrProvenance.MARKER, changed)));
        var readable = source(NEXT, changed);
        for (var related : List.of(
            new Related("CORRECTION", NEXT, "정정 공고", null, "정정본 관계 확인", List.of(failed)),
            new Related("CORRECTION", NEXT, "정정 공고", null, "정정본 관계 확인", List.of(ocr)),
            new Related("CORRECTION", NEXT, "정정 공고", null, "", List.of(readable)),
            new Related("CORRECTION", NEXT, "정정 공고", null, "정정본 관계 확인", List.of(readable, failed)))) {
            var result = compare(primary, related);
            assertFalse(result.assessment().ready());
            assertFalse(result.invalidatesPublishedGuide());
            assertTrue(result.invalidations().isEmpty());
        }
    }

    @Test
    void relationshipProofCannotExemptAGoverningChangeOrASecondDateInsideAnIntroduction() {
        for (String introduction : List.of(
            "2026.01.01 공고의 당첨자 발표는 2026.02.10로 변경되었음을 알려드립니다.",
            "2026.01.01 공고의 당첨자 발표가 2026.02.10임을 알려드립니다.",
            "2026.01.01 공고의 당첨자 발표 연기를 알려드립니다.",
            "2026.01.01 공고의 당첨자 발표 미정을 안내드립니다.")) {
            var related = new Related("FOLLOWUP", NEXT, "공식 후속 안내", LocalDate.of(2026, 2, 1),
                "원모집 공고 직접 언급: " + introduction, List.of(source(NEXT, introduction + "\n서류제출대상자 발표: 2026.02.10")));
            var result = compare(draft(source(PRIMARY, VALID)), related);
            assertFalse(result.assessment().ready(), introduction);
            assertTrue(result.blockers().contains("FOLLOWUP_CLAUSE_UNSUPPORTED"), introduction);
        }
    }

    @Test
    void announcementTitleWithAnEffectiveChangeIsNotSilentlyRemovedAsMetadata() {
        String title = "당첨자 발표: 미정";
        var related = new Related("FOLLOWUP", NEXT, title, LocalDate.of(2026, 2, 1), "동일 모집 공고 확인",
            List.of(source(NEXT, title + "\n감사합니다.")));
        var result = compare(draft(source(PRIMARY, VALID)), related);
        assertTrue(result.assessment().ready(), result.assessment().issues().toString());
        assertTrue(result.invalidatesPublishedGuide());
        assertTrue(result.assessment().candidate().schedules().stream().noneMatch(s -> s.id().equals("auto-result")));
        assertEquals(1, result.assessment().candidate().reviewIssues().size());
        String unsupported = "모집 당첨자 발표 연기 안내";
        var blocked = compare(draft(source(PRIMARY, VALID)), new Related("FOLLOWUP", NEXT, unsupported, related.publishedDate(),
            related.relationshipProof(), List.of(source(NEXT, unsupported + "\n당첨자 발표: 2026.02.07"))));
        assertFalse(blocked.assessment().ready());
        assertTrue(blocked.blockers().contains("FOLLOWUP_CLAUSE_UNSUPPORTED"));
    }
}
