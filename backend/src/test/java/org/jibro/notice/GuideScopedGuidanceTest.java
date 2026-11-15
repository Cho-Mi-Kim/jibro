package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class GuideScopedGuidanceTest {
    @Test void uncertainItemsDoNotReappearAsOrdinaryGuideAnswers() {
        var evidence = new Evidence("공식 원문", "https://apply.lh.or.kr/", "2026-11-01");
        var issue = new ReviewIssue("scope", "CONDITION", "소득 예외 확인", "기관 확인 필요",
            List.of("income"), List.of(), List.of(evidence));
        var content = new Content("확인된 개요", evidence, List.of(),
            List.of(new Question("age", "연령", "확인된 기준", evidence),
                new Question("income", "소득", "충돌 기준", evidence)),
            List.of(new DocumentTemplate("common", "공통 서류", null, "발급 방법", "기관", null, evidence),
                new DocumentTemplate("income-doc", "소득 서류", "income", "확인이 필요한 방법", "기관", null, evidence)),
            List.of(new ScheduleTemplate("end", "신청 마감", "2026-12-31", evidence)), List.of(issue));
        var repository = mock(GuideRepository.class);
        when(repository.findPublishedSources(0, 32, false, List.of())).thenReturn(List.of(
            new GuideRepository.Source(UUID.randomUUID(), "공고", "LH", true, content)));
        var guides = new GuideService(mock(Environment.class), repository).guides("", "", 0).items();
        assertTrue(guides.stream().anyMatch(g -> g.title().equals("연령")));
        assertTrue(guides.stream().anyMatch(g -> g.title().equals("공통 서류")));
        assertTrue(guides.stream().anyMatch(g -> g.title().equals("신청 마감")));
        assertFalse(guides.stream().anyMatch(g -> g.title().equals("소득") || g.title().equals("소득 서류")));
    }
}
