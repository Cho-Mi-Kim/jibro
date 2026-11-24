package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.*;
import org.jibro.notice.QuestionAnswers;
import org.jibro.preparation.PreparationRules;
import org.junit.jupiter.api.Test;

class EvidenceGuidanceCompilerTest {
    private static final String URL = "https://apply.lh.or.kr/notice.pdf";
    private static final Instant CHECKED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Content ORIGINAL = new Content("공식 공고", new Evidence("원문", URL, "2026-01-01"), List.of(), List.of(), List.of(), List.of());

    // Compact regression transcription of the Iksan notice's layout. Amounts/dates are test values;
    // this is not a redistributed full notice or a fixture served as a real announcement.
    private static final List<Part> QUALIFIED_NOTICE = List.of(
            new Part("1쪽", """
                    입주자격 완화 주요내용 (필독)
                    ① 소득 및 총자산 요건 완화
                    소득 요건: 소득요건 배제
                    총자산 요건: 총자산요건 배제
                    자동차 가액은 완화요건 미적용
                    세대구성원 전원이 보유하고 있는 개별 자동차가액 (4,542)만원 이하
                    ② 무주택 요건 완화
                    소형·저가주택은 무주택으로 간주하되 해당지역 또는 연접지역의 주택 여부를 확인합니다.
                    """),
            new Part("2쪽", """
                    4. 신청자격(입주자격 완화조건 1P 적용)
                    ■ 성년자
                    미성년자는 신청할 수 없습니다. 다만 자녀를 양육하거나 부모의 사망으로 형제를 부양하는 미성년자는 가능합니다.
                    ■ 무주택세대구성원
                    배우자와 세대분리한 경우 배우자 세대의 직계존비속을 포함합니다.
                    1. 실종선고 절차가 진행 중인 사람은 세대구성원에서 제외합니다.
                    5. 선정기준
                    순위 | 가구원수 | 월평균 소득 비율
                    1순위 | 1인 | 90%
                    2순위 | 2인 | 80%
                    같은 순위 내 경쟁하면 배점, 다시 동점이면 추첨으로 선정합니다.
                    6. 모집일정
                    접수기간: 2026.01.01 ~ 2026.01.31
                    """),
            new Part("3쪽", """
                    8. 제출서류
                    모든 제출서류는 공고일 이후 발급한 서류에 한합니다.
                    주민등록표등본: 배우자와 세대분리한 경우 배우자 등본 추가 제출
                    가족관계증명서: 등본에서 배우자 관계를 확인할 수 있으면 생략 가능합니다.
                    외국인등록증: 외국인 배우자에 해당하는 경우 추가 제출합니다.
                    별지 제3호 증빙은 해당 사유가 있는 사람만 제출합니다.
                    9. 계약안내
                    계약 장소는 공식 원문에서 확인합니다.
                    11. 공통 유의사항
                    자격완화 입주자는 재계약을 1회 허용하며 이후에는 일반 입주자격을 충족해야 합니다.
                    """));

    @Test
    void preservesWaiverHouseholdRankingAndDocumentExceptionsAsSourceConfirmations() {
        var result = compile(QUALIFIED_NOTICE);
        assertTrue(result.ready(), result.issues().toString());
        String reasons = reasons(result);
        for (String clause : List.of("소득요건 배제", "완화요건 미적용", "4,542)만원", "자녀를 양육", "배우자 세대의 직계존비속",
                "1순위 | 1인 | 90%", "2순위 | 2인 | 80%", "다시 동점이면 추첨", "생략 가능합니다", "재계약을 1회")) {
            assertTrue(reasons.contains(clause), clause);
        }
        result.content().questions().forEach(QuestionAnswers::validateQuestion);
        assertTrue(result.content().questions().stream().allMatch(q -> q.constraints() == null));
        var sourceQuestions = result.content().questions().stream().filter(q -> q.id().startsWith("e-section-")).toList();
        assertTrue(sourceQuestions.stream().allMatch(q -> q.options().get(0).label().equals("기준과 예외를 함께 확인했어요")));
        assertTrue(sourceQuestions.stream().allMatch(q -> q.needsFollowUp("NO") && q.needsFollowUp("UNKNOWN")));
        assertEquals(ORIGINAL.summary(), result.content().summary());
        var vehicle = result.content().questions().stream().filter(q -> q.title().contains("자동차별 가액")).findFirst().orElseThrow();
        assertTrue(vehicle.title().contains("4,542만원"));
        assertTrue(vehicle.reason().contains("총자산요건 배제"));
        assertTrue(vehicle.reason().contains("완화요건 미적용"));
        var rank = result.content().questions().stream().filter(q -> q.options().stream().anyMatch(o -> o.value().equals("RANK_2"))).findFirst().orElseThrow();
        assertTrue(rank.reason().contains("다시 동점이면 추첨"));
    }

    @Test
    void actualBukguStandaloneQualificationHeadingRetainsHouseholdMinorAndRankCriteria() throws Exception {
        List<Part> parts;
        try (var input = getClass().getResourceAsStream("/extraction/lh-bukgu-69296405.pdf")) {
            assertNotNull(input);
            parts = new org.jibro.collection.extraction.DocumentTextParser().parse(input.readAllBytes(), "application/pdf");
        }
        var source = OfficialGuidanceRows.read(new Source("공식 모집공고.pdf", URL, "a".repeat(64), "EXTRACTED", "공식 원형", List.of(), parts));
        var draft = new Draft("bukgu-heading", "notice", 1, "READY", Instant.parse("2026-11-23T00:00:00Z"), "", List.of(source));
        var result = EvidenceGuidanceCompiler.compile(draft, ORIGINAL);
        assertTrue(result.ready(), result.issues().toString());
        String actual = reasons(result).replaceAll("[\\s\\p{Zs}]+", "");
        for (String criterion : List.of("입주자모집공고일(2026.11.11.)현재무주택세대구성원", "형제·자매를부양하여야하는",
                "외국인배우자", "법정대리인의동의또는대리필요", "생계·주거·의료·교육급여", "100%이하인자"))
            assertTrue(actual.contains(criterion), criterion);
        var personalized = ApplicantSituationCompiler.compile(result.content());
        assertEquals("applicant-situation", personalized.questions().get(0).id());
        assertTrue(personalized.documents().stream().anyMatch(d -> d.title().equals("행방불명 신고접수증")));
        assertEquals(List.of("외국인등록증", "그 밖의 공고별 제출서류"), personalized.documents().stream()
                        .filter(d -> d.id().startsWith("e-document-")).map(DocumentTemplate::title).toList(),
                "The extra foreign-spouse proof in the family-certificate row is distinct from the same-address foreign-family row.");
        assertTrue(reasons(result).contains("주민등록번호 13자리 숫자"));
        assertTrue(personalized.documents().stream().anyMatch(d -> d.title().equals("신청자의 주민등록표등본") && d.conditionQuestionId() == null));
        assertTrue(result.content().questions().stream().anyMatch(q -> q.title().startsWith("신청 기준·순위·예외 확인")
                && q.reason().contains("세대구성원의 범위")));
        assertTrue(actual.contains("최장30년거주"), "The later guidance and its renewal exception remain in the condition reference.");
    }

    @Test
    void typedDocumentOwnershipDoesNotEraseAnotherRecipientsAdditionalProof() {
        var result = compile(List.of(new Part("1쪽", """
                3. 신청자격
                신청자와 배우자는 무주택이어야 합니다.
                4. 제출서류
                신청자의 주민등록표등본 제출 대상: 모든 신청자 · 안내: 배우자와 세대분리한 경우 가족관계증명서 추가 제출
                가족관계증명서 제출 대상: 외국인 배우자가 있는 신청자
                이혼한 신청자는 가족관계증명서를 추가 제출합니다.
                """)));
        assertTrue(result.ready(), result.issues().toString());
        assertEquals(1, result.content().documents().stream().filter(d -> d.title().equals("신청자의 주민등록표등본")).count());
        assertFalse(result.content().documents().stream().anyMatch(d -> d.id().startsWith("e-document-") && d.title().equals("주민등록표등본")));
        var extra = result.content().documents().stream().filter(d -> d.id().startsWith("e-document-") && d.title().equals("가족관계증명서")).findFirst().orElseThrow();
        assertNotNull(extra.conditionQuestionId());
        assertTrue(reasons(result).contains("이혼한 신청자는 가족관계증명서를 추가 제출"));
        assertTrue(result.content().documents().stream().anyMatch(d -> d.title().equals("신청자의 주민등록표등본")
                && d.method().contains("배우자와 세대분리한 경우 가족관계증명서 추가 제출")));
    }

    @Test
    void aNamedProofStillRequiresItsCopyWhenTheInstructionSaysToSubmitIt() {
        var result = compile(List.of(new Part("1쪽", """
                3. 신청자격
                신청자는 무주택이어야 합니다.
                4. 제출서류
                대리신청 위임장 제출 대상: 대리인 · 안내: 가족관계증명서에서 관계가 표시된 부분을 복사해 제출합니다.
                """)));
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(result.content().documents().stream().anyMatch(d -> d.title().equals("가족관계증명서") && d.conditionQuestionId() != null));
        assertTrue(result.content().documents().stream().anyMatch(d -> d.title().equals("대리신청 위임장")
                && d.method().contains("관계가 표시된 부분을 복사해 제출")));
    }

    @Test
    void anExampleInAnotherPersonsSubmissionDutyRemainsAnAdditionalProof() {
        var result = compile(List.of(new Part("1쪽", """
                3. 신청자격
                신청자는 무주택이어야 합니다.
                4. 제출서류
                신청자의 가족관계증명서 제출 대상: 모든 신청자
                대리인은 관계를 입증하는 서류(ex. 가족관계증명서)를 추가로 제출합니다.
                """)));
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(result.content().documents().stream().anyMatch(d -> d.title().equals("신청자의 가족관계증명서") && d.conditionQuestionId() == null));
        assertTrue(result.content().documents().stream().anyMatch(d -> d.title().equals("가족관계증명서") && d.id().startsWith("e-document-") && d.conditionQuestionId() != null));
        assertTrue(reasons(result).contains("대리인은 관계를 입증하는 서류(ex. 가족관계증명서)를 추가로 제출"));
    }

    @Test
    void documentMentionNeverBecomesAnUnconditionalRequiredDocument() {
        var result = compile(QUALIFIED_NOTICE);
        var document = result.content().documents().stream().filter(d -> d.title().equals("가족관계증명서")).findFirst().orElseThrow();
        assertNotNull(document.conditionQuestionId());
        assertEquals(List.of("YES"), document.conditionAnswerValues());
        assertNull(document.issueUrl());
        assertEquals("CONDITIONAL", PreparationRules.necessity(document.conditionQuestionId(), document.conditionAnswerValues(), Map.of(document.conditionQuestionId(), "YES"), result.content().questions()));
        assertEquals("NOT_APPLICABLE", PreparationRules.necessity(document.conditionQuestionId(), document.conditionAnswerValues(), Map.of(document.conditionQuestionId(), "NO"), result.content().questions()));
        assertEquals("UNKNOWN", PreparationRules.necessity(document.conditionQuestionId(), document.conditionAnswerValues(), Map.of(document.conditionQuestionId(), "UNKNOWN"), result.content().questions()));
        assertTrue(reasons(result).contains("별지 제3호 증빙"));
        assertTrue(result.content().documents().stream().anyMatch(d -> d.title().equals("그 밖의 공고별 제출서류")));
    }

    @Test
    void retainsNumberedSubclausesAndPageContinuationWithoutTruncatingTailExceptions() {
        var parts = new ArrayList<>(QUALIFIED_NOTICE);
        String longText = "순위별 소득표의 앞뒤 조건을 함께 확인합니다.\n".repeat(90);
        String tail = "2. 다만 상속으로 취득한 경우에는 위 주택소유 기준을 적용하지 않습니다.";
        parts.set(1, new Part("2쪽", "4. 신청자격\n" + longText + tail));
        parts.add(2, new Part("2쪽 계속", "배우자가 외국인인 경우 추가 증빙을 확인합니다."));
        var result = compile(parts);
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(reasons(result).contains(tail));
        assertTrue(reasons(result).contains("배우자가 외국인인 경우 추가 증빙"));
        assertTrue(result.content().questions().stream().allMatch(q -> q.reason().length() <= 2000));
        assertEquals(result.content().questions().size(), result.content().questions().stream().map(Question::id).distinct().count());
    }

    @Test
    void rejectsMissingSectionsAndUnresolvedReferencedPages() {
        assertTrue(compile(List.of(QUALIFIED_NOTICE.get(0))).issues().contains("EVIDENCE_DOCUMENT_SECTION_MISSING"));
        var parts = new ArrayList<>(QUALIFIED_NOTICE);
        parts.set(1, new Part("2쪽", "4. 신청자격\n예외는 공고문 99쪽의 기준을 적용합니다."));
        assertTrue(compile(parts).issues().contains("EVIDENCE_REFERENCED_PAGE_MISSING"));
    }

    @Test
    void continuedRankingTableIsNotReducedToOptionsSeenOnTheFirstPage() {
        var parts = new ArrayList<>(QUALIFIED_NOTICE);
        parts.set(1, new Part("2쪽", "5. 선정기준\n1순위: 해당지역에 거주하는 사람\n2순위: 연접지역에 거주하는 사람"));
        parts.add(2, new Part("2쪽 계속", "3순위: 나머지 지역에 거주하는 사람. 단, 별도 예외에 해당하면 1순위로 봅니다."));
        var result = compile(parts);
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(reasons(result).contains("3순위:"));
        assertTrue(result.content().questions().stream().flatMap(q -> q.options().stream()).noneMatch(o -> o.value().startsWith("RANK_")));
    }

    @Test
    void differentComplexGoverningSourcesAreNotAssumedConsistent() {
        var different = new ArrayList<>(QUALIFIED_NOTICE);
        different.set(0, new Part("1쪽", QUALIFIED_NOTICE.get(0).text().replace("4,542", "3,000")));
        var result = EvidenceGuidanceCompiler.compile(draft(List.of(source(URL, QUALIFIED_NOTICE), source("https://apply.lh.or.kr/other.pdf", different))), ORIGINAL);
        assertFalse(result.ready());
        assertTrue(result.issues().contains("EVIDENCE_MULTIPLE_GOVERNING_SOURCES_REQUIRE_REVIEW"));
    }

    @Test
    void unreadRegisteredAttachmentCannotBeBypassedBySourceBundleFallback() {
        var located = new Content("", new Evidence("공고문 2쪽", URL + "#page=2", "2026-01-01"), List.of(), List.of(), List.of(), List.of());
        assertTrue(EvidenceGuidanceCompiler.compile(draft(List.of(source(URL, QUALIFIED_NOTICE))), located).ready());
        var original = new Content("", ORIGINAL.evidence(), List.of(new Evidence("추가 공고문", "https://apply.lh.or.kr/missing.pdf", "2026-01-01")), List.of(), List.of(), List.of());
        var result = EvidenceGuidanceCompiler.compile(draft(List.of(source(URL, QUALIFIED_NOTICE))), original);
        assertFalse(result.ready());
        assertTrue(result.issues().contains("SOURCE_COVERAGE_INCOMPLETE"));
    }

    @Test
    void retainsLongOfficialExplanationsWithoutTurningThemIntoApplicantInputs() {
        var institutional = new Source("가정어린이집 운영예정자 모집공고", URL, "a".repeat(64), "EXTRACTED", "완료", List.of(), QUALIFIED_NOTICE);
        assertTrue(EvidenceGuidanceCompiler.compile(draft(List.of(institutional)), ORIGINAL).issues().contains("EVIDENCE_INSTITUTIONAL_SCOPE_UNSUPPORTED"));
        var parts = new ArrayList<>(QUALIFIED_NOTICE);
        for (int i = 4; i <= 24; i++) parts.add(new Part(i + "쪽", "추가 유의사항 " + i));
        var result = compile(parts);
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(reasons(result).contains("추가 유의사항 24"));
        assertFalse(QuestionAnswers.applicantQuestions(result.content().questions(), result.content().documents().stream()
                        .map(DocumentTemplate::conditionQuestionId).filter(java.util.Objects::nonNull).toList()).stream()
                .anyMatch(q -> q.reason().contains("추가 유의사항 24")));
        for (int i = 25; i <= 105; i++) parts.add(new Part(i + "쪽", "추가 유의사항 " + i));
        assertTrue(compile(parts).issues().contains("EVIDENCE_GUIDANCE_TOO_MANY_QUESTIONS"));
    }

    @Test
    void checksPrintedBookletReferencesWithoutChangingPhysicalEvidenceLinks() {
        var parts = new ArrayList<>(QUALIFIED_NOTICE);
        parts.set(0, new Part("1쪽", QUALIFIED_NOTICE.get(0).text() + "\n- 1 -\n- 2 -"));
        parts.set(1, new Part("2쪽", QUALIFIED_NOTICE.get(1).text().replace("■ 성년자", "공고문 6쪽의 예외도 확인합니다.\n■ 성년자") + "\n- 3 -\n- 4 -"));
        parts.set(2, new Part("3쪽", QUALIFIED_NOTICE.get(2).text() + "\n- 5 -\n- 6 -"));
        String url = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1";
        var official = new Content("공식 안내", new Evidence("공고문.pdf", url, "2026-01-01"), List.of(), List.of(), List.of(), List.of());
        var source = new Source("공고문.pdf", url, "a".repeat(64), "EXTRACTED", "완료", List.of(),
                parts.stream().map(p -> new Part("공고문 " + p.location(), p.text())).toList());
        var result = EvidenceGuidanceCompiler.compile(draft(List.of(source)), official);
        assertTrue(result.ready(), result.issues().toString());
        var referenced = result.content().questions().stream().filter(q -> q.reason().contains("공고문 6쪽")).findFirst().orElseThrow();
        assertTrue(referenced.evidence().url().contains("page=2"), referenced.evidence().url());
        parts.set(2, new Part("3쪽", QUALIFIED_NOTICE.get(2).text() + "\n- 5 -"));
        assertTrue(compile(parts).issues().contains("EVIDENCE_REFERENCED_PAGE_MISSING"));
    }

    @Test
    void refusesUntrustedIncompleteOrTruncatedSourceRatherThanPublishingSupportedSubset() {
        var spoofed = source("https://apply.lh.or.kr.example.org/notice.pdf", QUALIFIED_NOTICE);
        assertFalse(EvidenceGuidanceCompiler.compile(draft(List.of(spoofed)), ORIGINAL).ready());
        for (String invalid : List.of("", "\n\t\u00a0", "조건 [발췌 길이 제한]")) {
            var parts = new ArrayList<>(QUALIFIED_NOTICE);
            parts.add(new Part("4쪽", invalid));
            var result = compile(parts);
            assertFalse(result.ready());
            assertTrue(result.issues().contains("EVIDENCE_CONTEXT_INCOMPLETE"));
        }
        var failed = new Source("추가 공고", URL, null, "FAILED", "실패", List.of(), List.of());
        assertFalse(EvidenceGuidanceCompiler.compile(draft(List.of(source(URL, QUALIFIED_NOTICE), failed)), ORIGINAL).ready());
    }

    @Test
    void identicalSectionsHaveStableUniqueQuestionsAndSourceLinksRemainAvailable() {
        var result = EvidenceGuidanceCompiler.compile(draft(List.of(source(URL, QUALIFIED_NOTICE), source("https://apply.lh.or.kr/copy.pdf", QUALIFIED_NOTICE))), ORIGINAL);
        var single = compile(QUALIFIED_NOTICE);
        assertTrue(result.ready(), result.issues().toString());
        assertTrue(result.content().attachments().stream().anyMatch(a -> a.url().endsWith("copy.pdf")));
        assertEquals(single.content().questions().stream().map(Question::id).toList(), result.content().questions().stream().map(Question::id).toList());
        assertEquals(single.content().questions().stream().map(Question::id).toList(), compile(QUALIFIED_NOTICE).content().questions().stream().map(Question::id).toList());
    }

    @Test
    void keepsNoticeSpecificOverviewInsteadOfReplacingItWithProcessingStatistics() {
        String summary = "남원노암 영구임대 예비입주자를 모집합니다. 소득·총자산은 배제하되 자동차 기준은 유지합니다.";
        var original = new Content(summary, ORIGINAL.evidence(), ORIGINAL.attachments(), List.of(), List.of(), List.of());
        var result = EvidenceGuidanceCompiler.compile(draft(List.of(source(URL, QUALIFIED_NOTICE))), original);
        assertTrue(result.ready(), result.issues().toString());
        assertEquals(summary, result.content().summary());
        assertFalse(result.content().summary().contains("원문 구간"));
    }

    private static AutomaticGuidanceCompiler.Result compile(List<Part> parts) {
        return EvidenceGuidanceCompiler.compile(draft(List.of(source(URL, parts))), ORIGINAL);
    }

    @Test
    void aPhysicalFirstRowIsNotInventedAsAHeaderInPublicExplanations() {
        var part = new Part("공고문 1쪽", """
                ⟦PDF:TABLE id=1 rows=2 cols=2⟧
                ⟦PDF:CELL table=1 row=1 col=1 rowspan=1 colspan=1⟧
                총자산
                ⟦PDF:/CELL⟧
                ⟦PDF:CELL table=1 row=1 col=2 rowspan=1 colspan=1⟧
                34,500만원
                ⟦PDF:/CELL⟧
                ⟦PDF:CELL table=1 row=2 col=1 rowspan=1 colspan=1⟧
                자동차
                ⟦PDF:/CELL⟧
                ⟦PDF:CELL table=1 row=2 col=2 rowspan=1 colspan=1⟧
                4,542만원
                ⟦PDF:/CELL⟧
                ⟦PDF:/TABLE⟧
                """);
        var source = new Source("공고문.pdf", URL, "a".repeat(64), "EXTRACTED", "완료", List.of(), List.of(part));
        var explanation = org.jibro.collection.review.local.GuidanceTableProjection.explanation(source, part,
                headings -> OfficialGuidanceRows.header(String.join(" : ", headings)));
        assertTrue(explanation.complete());
        assertEquals("표 안내 · 총자산 / 34,500만원\n표 안내 · 자동차 / 4,542만원", explanation.text().strip());
    }

    private static String reasons(AutomaticGuidanceCompiler.Result result) {
        return result.content().questions().stream().map(Question::reason).reduce("", (left, right) -> left + "\n" + right);
    }

    private static Draft draft(List<Source> sources) {
        return new Draft("run", "notice", 1, "READY", CHECKED, "완료", sources);
    }

    private static Source source(String url, List<Part> parts) {
        return new Source("공고문", url, "a".repeat(64), "EXTRACTED", "완료", List.of(), parts);
    }
}
