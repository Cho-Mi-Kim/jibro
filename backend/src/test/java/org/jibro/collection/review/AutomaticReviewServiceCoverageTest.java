package org.jibro.collection.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jibro.collection.extraction.ExtractionStore;
import org.jibro.collection.review.local.LocalAnalysisService;
import org.jibro.notice.AutomaticPublicationService;
import org.jibro.notice.AutomaticReviewRepository;
import org.jibro.notice.NoticeService;
import org.junit.jupiter.api.Test;
import static org.jibro.collection.extraction.ExtractionModels.*;
import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutomaticReviewServiceCoverageTest {
    private static final String URL = "https://apply.lh.or.kr/recruitment.pdf";
    private static final String FINGERPRINT = "f".repeat(64);
    private static final String RULES = """
        신청 자격
        신청자는 무주택자이어야 합니다.
        제출 서류
        공통 제출서류: 가족관계증명서
        접수기간: 2026.11.01 ~ 2026.12.31
        """;
    private final UUID noticeId = UUID.randomUUID();
    private final UUID extractionId = UUID.randomUUID();
    private final AutomaticReviewRepository repository = mock(AutomaticReviewRepository.class);
    private final AutomaticPublicationService publication = mock(AutomaticPublicationService.class);
    private final ExtractionStore extraction = mock(ExtractionStore.class);
    private final NoticeService notices = mock(NoticeService.class);
    private final LocalAnalysisService local = mock(LocalAnalysisService.class);
    private final AutomaticReviewService service = new AutomaticReviewService(repository, publication, extraction, notices, local, true);

    @Test void otherwiseSupportedGrammarCannotOverrideUnverifiedDocumentVariant() {
        var pdf = source("모집공고.pdf", URL, "EXTRACTED", RULES);
        var hwpx = source("모집공고.hwpx", "https://apply.lh.or.kr/recruitment.hwpx", "EXTRACTED",
            RULES + "신청자는 미혼이어야 합니다.");
        var original = original(pdf, List.of(evidence(hwpx)));
        var draft = draft("READY", pdf, hwpx);
        // Both documents fit the grammar, but one representation adds an unverified condition.
        assertTrue(AutomaticGuidanceCompiler.compile(draft, original).ready());
        blocks(draft, original, "DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:");
    }

    @Test void missingOriginalAttachmentCannotReachModelOrPublishPartialGuidance() {
        var pdf = source("모집공고.pdf", URL, "EXTRACTED", RULES);
        var original = original(pdf, List.of(new Evidence("수정 공고", "https://apply.lh.or.kr/missing.pdf", "2026-11-07")));
        blocks(draft("READY", pdf), original, "SOURCE_COVERAGE_INCOMPLETE");
    }

    @Test void failedGoverningRepresentationRetainsItsReasonWithoutCallingModel() {
        var pdf = source("모집공고.pdf", URL, "EXTRACTED", RULES);
        var failed = source("모집공고.hwpx", "https://apply.lh.or.kr/recruitment.hwpx", "FAILED", null);
        blocks(draft("PARTIAL", pdf, failed), original(pdf, List.of(evidence(failed))), "AUTHORITATIVE_SOURCE_INCOMPLETE:");
    }

    @Test void unclassifiedOfficialConditionCannotBeDiscardedAsMetadata() {
        var detail = source("공식 상세 본문", "https://apply.lh.or.kr/detail", "EMPTY", "외국인은 신청할 수 없습니다.");
        var pdf = source("모집공고.pdf", URL, "EXTRACTED", RULES);
        blocks(draft("PARTIAL", detail, pdf), original(detail, List.of(evidence(pdf))), "AUTHORITATIVE_SOURCE_INCOMPLETE:");
    }

    private void blocks(Draft draft, Content original, String expectedIssue) {
        when(repository.snapshot(extractionId)).thenReturn(new AutomaticReviewRepository.ExtractionSnapshot(draft, FINGERPRINT));
        when(notices.content(noticeId)).thenReturn(original);

        assertFalse(service.review(extractionId));

        // The publication boundary receives a recorded HOLD only: no content or dates to publish.
        verify(publication).guidance(eq(noticeId), eq(7), eq(extractionId), eq(FINGERPRINT), isNull(), isNull(), isNull(),
            argThat(issues -> issues != null && issues.stream().anyMatch(issue -> issue.startsWith(expectedIssue))));
        verifyNoMoreInteractions(publication);
        verify(local).prepare(any());
        verifyNoInteractions(extraction);
    }

    private Draft draft(String status, Source... sources) {
        return new Draft(extractionId.toString(), noticeId.toString(), 7, status, Instant.parse("2026-11-07T00:00:00Z"), "", List.of(sources));
    }

    private Source source(String label, String url, String status, String text) {
        return new Source(label, url, text == null ? null : "a".repeat(64), status, "", List.of(),
            text == null ? List.of() : List.of(new Part("1쪽", text)));
    }

    private Evidence evidence(Source source) { return new Evidence(source.label(), source.url(), "2026-11-07"); }

    private Content original(Source main, List<Evidence> attachments) {
        return new Content("공식 모집 공고", evidence(main), attachments, List.of(), List.of(), List.of());
    }
}
