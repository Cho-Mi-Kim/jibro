package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class SupplementaryFormInstructionsTest {
    @Test
    void realSevenSourcesProducePersonalBranchesAndScopedDifferencesBeforePublication() throws Exception {
        var result = SuwonSupplementarySources.assess(SuwonSupplementarySources.sources());
        assertTrue(result.ready(), result.issues().toString());
        var content = result.candidate();
        assertEquals("applicant-situation", content.questions().get(0).id());
        assertEquals(List.of("STUDENT", "JOBSEEKER", "YOUTH", "EARLY_CAREER", "HOUSING_BENEFIT", "NONE", "UNKNOWN"), content.questions().get(0).options().stream().map(AnswerOption::value).toList());
        var own = content.questions().stream().filter(q -> q.id().equals("applicant-own-home")).findFirst().orElseThrow();
        assertEquals(List.of("STUDENT", "JOBSEEKER", "YOUTH", "EARLY_CAREER"), own.applicability().answerValues());
        var returning = content.questions().stream().filter(q -> q.id().equals("returning-student")).findFirst().orElseThrow();
        assertEquals(List.of("STUDENT"), returning.applicability().answerValues());
        var application = content.documents().stream().filter(d -> d.title().equals("공급신청서")).findFirst().orElseThrow();
        assertEquals("application-channel", application.conditionQuestionId());
        assertEquals(List.of("VISIT"), application.conditionAnswerValues());
        var proxy = content.documents().stream().filter(d -> d.method().contains("위임장은") || d.method().contains("위임인은 자필")).findFirst().orElseThrow();
        assertEquals("application-attendee", proxy.conditionQuestionId());
        assertEquals(List.of("PROXY"), proxy.conditionAnswerValues());
        var privacy = content.documents().stream().filter(d -> d.title().contains("개인정보")).findFirst().orElseThrow();
        assertTrue(privacy.method().contains("민감정보 · 민감정보 동의는 선택"));
        assertTrue(privacy.method().contains("청약서비스 이용에는 제한이 없"));
        assertTrue(privacy.method().contains("해당 목적의 계약자는 영구 보유"));
        var renewal = content.documents().stream().filter(d -> d.id().equals("form-renewal-reference")).findFirst().orElseThrow();
        assertTrue(renewal.method().contains("1회 갱신은 소득·자산을 넘어도 할증 없이"));
        assertTrue(renewal.method().contains("2회는 적격 예비자가 없을 때만"));
        assertTrue(renewal.method().contains("자동차 초과는 이 예외에서 제외"));
        assertEquals(4, content.reviewIssues().size());
        assertTrue(content.reviewIssues().stream().anyMatch(i -> i.id().equals("form-household-declaration") && i.documentIds().contains(application.id())));
        assertTrue(content.schedules().stream().anyMatch(s -> s.title().contains("인터넷") && s.title().contains("시작") && s.date().equals("2026-11-30T10:00")));
        assertTrue(content.schedules().stream().anyMatch(s -> s.title().contains("현장") && s.title().matches(".*(?:종료|마감)$") && s.date().equals("2026-12-02T16:00")));
        assertTrue(content.reviewIssues().stream().anyMatch(i -> i.area().equals("SCHEDULE") && i.detail().contains("17:00") && i.detail().contains("16:00")));
        org.jibro.notice.GuidanceIssues.validate(content);
    }

    @Test
    void changedPrimaryPenaltyOrAnAddedExceptionCannotReuseTheSupplementaryApproval() throws Exception {
        for (String[] change : List.of(new String[]{"위약금이 부과", "위약금이 면제"}, new String[]{"위약금이 부과됩니다.", "위약금이 부과됩니다. 단, 그 돈은 돌려주지 않습니다."},
            new String[]{"재계약 요건", "특별 갱신 조건"})) {
            var files = new ArrayList<>(SuwonSupplementarySources.sources());
            for (int i : List.of(1, 2))
                files.set(i, SuwonSupplementarySources.replace(files.get(i), change[0], change[1]));
            assertNotEquals(SuwonSupplementarySources.sources().get(2).context(), files.get(2).context());
            var result = SuwonSupplementarySources.assess(files);
            assertFalse(result.ready(), Arrays.toString(change));
            assertTrue(result.issues().stream().anyMatch(i -> i.startsWith("SUPPLEMENTARY_PRIMARY_")), result.issues().toString());
        }
    }

    @Test
    void listingNormsRequirePrimaryComparisonEvenWhenThereIsNoSupplementaryForm() throws Exception {
        var files = new ArrayList<>(SuwonSupplementarySources.sources().subList(0, 5));
        assertTrue(SuwonSupplementarySources.assess(files).ready());
        for (int i : List.of(1, 2)) files.set(i, SuwonSupplementarySources.replace(files.get(i), "위약금이 부과", "위약금이 면제"));
        var result = SuwonSupplementarySources.assess(files);
        assertFalse(result.ready());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.startsWith("SUPPLEMENTARY_PRIMARY_")), result.issues().toString());
    }

    @Test
    void additionalNormsOutsideTheOldDomainSectionCannotDisappear() throws Exception {
        for (String note : List.of("※ 단순 변심으로 계약을 해지해도 위약금은 면제합니다.", "※ 위의 경우에도 그 돈은 돌려주지 않습니다.", "※ 학생은 입주를 포기하여도 납입액 전액을 지급받습니다.")) {
            var files = new ArrayList<>(SuwonSupplementarySources.sources());
            for (int i : List.of(1, 2)) {
                var source = files.get(i);
                var parts = new ArrayList<>(source.context());
                var last = parts.get(parts.size() - 1);
                parts.set(parts.size() - 1, new org.jibro.collection.extraction.ExtractionModels.Part(last.location(), last.text() + "\n" + note));
                files.set(i, new org.jibro.collection.extraction.ExtractionModels.Source(source.label(), source.url(), source.sha256(), source.status(), source.message(), source.excerpts(), parts));
            }
            var result = SuwonSupplementarySources.assess(files);
            assertFalse(result.ready(), note);
            assertTrue(result.issues().contains("SUPPLEMENTARY_PRIMARY_OUTSIDE_CLAUSE_UNPARSED"), result.issues().toString());
        }
    }

    @Test
    void aReadableDifferentReturnDeadlineIsBoundToOnlyTheAffectedForm() throws Exception {
        var files = new ArrayList<>(SuwonSupplementarySources.sources());
        files.set(6, SuwonSupplementarySources.replace(files.get(6), "복학 후 1개월 이내", "복학 후 2개월 이내"));
        var result = SuwonSupplementarySources.assess(files);
        assertTrue(result.ready(), result.issues().toString());
        var issue = result.candidate().reviewIssues().stream().filter(i -> i.id().equals("form-return-deadline")).findFirst().orElseThrow();
        assertEquals(List.of("form-return-study"), issue.documentIds());
        assertTrue(issue.detail().contains("2개월"));
        assertTrue(result.candidate().documents().stream().filter(d -> d.id().equals("form-return-study")).findFirst().orElseThrow().method().contains("복학 후 2개월"));
    }

    @Test
    void newSupplementaryClausesAndUnknownNegationsRemainBlocked() throws Exception {
        for (String[] change : List.of(new String[]{"청약서비스 이용에는 제한이 없으나", "청약서비스 이용에는 제한이 있으나"},
            new String[]{"보완서류 미제출, 계약자의 단순변심", "보완서류 미제출은 위약금을 면제하며, 계약자의 단순변심"},
            new String[]{"위임인(신청자)이 자필로 작성", "수임인(대리인)이 자필로 작성"})) {
            var files = SuwonSupplementarySources.sources().stream().map(s -> SuwonSupplementarySources.replace(s, change[0], change[1])).toList();
            var result = SuwonSupplementarySources.assess(files);
            assertFalse(result.ready(), Arrays.toString(change));
            assertTrue(result.issues().stream().anyMatch(i -> i.startsWith("SUPPLEMENTARY_FORM_")), result.issues().toString());
        }
    }

    @Test
    void ratingsAndCostsPreserveTheirOwnComplexAndDoNotBecomeEligibilityAnswers() throws Exception {
        var result = SuwonSupplementarySources.assess(SuwonSupplementarySources.sources()).candidate();
        var first = result.questions().stream().filter(q -> q.title().startsWith("수원당수1단지 · 주택 성능등급")).findFirst().orElseThrow();
        var second = result.questions().stream().filter(q -> q.title().startsWith("수원당수2단지 · 주택 성능등급")).findFirst().orElseThrow();
        assertTrue(first.reason().contains("에너지성능 · ★★★"));
        assertTrue(second.reason().contains("에너지성능 · ★★★★"));
        assertNotEquals(first.applicability().answerValues(), second.applicability().answerValues());
        assertEquals(List.of("ACK", "UNKNOWN"), first.options().stream().map(AnswerOption::value).toList());
        assertTrue(result.questions().stream().filter(q -> q.title().startsWith("수원당수2단지 · 시설")).anyMatch(q -> q.reason().contains("영구배수공법을 적용할 때")));
        assertFalse(result.questions().stream().filter(q -> q.title().startsWith("수원당수1단지 · 시설")).anyMatch(q -> q.reason().contains("일체비용은입주자")));
        var files = new ArrayList<>(SuwonSupplementarySources.sources());
        files.set(4, SuwonSupplementarySources.replace(files.get(4), "입주자가 부담하여야 함", "입주자가 부담할 필요가 없음"));
        assertFalse(SuwonSupplementarySources.assess(files).ready());
    }
}
