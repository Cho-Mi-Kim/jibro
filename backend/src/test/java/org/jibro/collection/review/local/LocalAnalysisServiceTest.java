package org.jibro.collection.review.local;

import java.time.Instant;
import java.util.*;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.review.ai.GeminiReviewService;
import org.jibro.notice.AutomaticReviewRepository;
import org.jibro.notice.AutomaticReviewRepository.ExtractionSnapshot;
import org.jibro.notice.HousingModels.*;
import org.jibro.notice.NoticeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.jibro.collection.review.ai.GeminiReviewModels.*;
import static org.jibro.collection.review.ai.ScopedReviewModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalAnalysisServiceTest {
    private static final String URL = "https://apply.lh.or.kr/recruitment.pdf";
    private final UUID noticeId = UUID.randomUUID();
    private final UUID extractionId = UUID.randomUUID();
    private final LocalAnalysisRepository repository = mock(LocalAnalysisRepository.class);
    private final AutomaticReviewRepository reviews = mock(AutomaticReviewRepository.class);
    private final NoticeService notices = mock(NoticeService.class);
    private final GeminiReviewService gemini = mock(GeminiReviewService.class);
    private final LocalAnalysisService service = new LocalAnalysisService(repository, reviews, notices, gemini, true);
    private final ObjectMapper json = new ObjectMapper();

    @Test void prepareReusesTheExactFingerprintWithoutReadingSourcesOrCallingProvider() {
        var snapshot = snapshot();
        var cached = entry(List.of(), "{}");
        when(repository.find(extractionId, "fingerprint", LocalAnalysisService.POLICY)).thenReturn(Optional.of(cached));

        assertSame(cached, service.prepare(snapshot));

        verify(repository).find(extractionId, "fingerprint", LocalAnalysisService.POLICY);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(reviews, notices, gemini);
    }

    @Test void readableRulesAreStoredEvenWhenOtherRequiredEvidenceIsMissing() {
        var snapshot = snapshot();
        var stored = entry(List.of(), "{}");
        when(repository.find(extractionId, "fingerprint", LocalAnalysisService.POLICY))
            .thenReturn(Optional.empty(), Optional.of(stored));
        when(notices.content(noticeId)).thenReturn(new Content("공식 공고", new Evidence("모집공고", URL, "2026-11-07"),
            List.of(new Evidence("추가 공고문", "https://apply.lh.or.kr/missing.pdf", "2026-11-07")), List.of(), List.of(), List.of()));

        assertSame(stored, service.prepare(snapshot));

        var book = ArgumentCaptor.forClass(LocalRuleBook.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> issues = ArgumentCaptor.forClass(List.class);
        verify(repository).save(eq(snapshot), eq(LocalAnalysisService.POLICY), book.capture(), any(), issues.capture());
        assertTrue(book.getValue().facts().stream().anyMatch(f -> f.type().equals("INCOME_REQUIREMENT_WAIVED")));
        assertTrue(book.getValue().clauses().stream().anyMatch(c -> c.quote().contains("소득요건 배제")));
        assertTrue(issues.getValue().contains("SOURCE_COVERAGE_INCOMPLETE"));
        verifyNoInteractions(gemini, reviews);
    }

    @Test void staleExtractionNeverConsumesAnApiRequestOrStoresAResult() {
        var entry = entry(List.of(packet("p1")), "{}");
        when(repository.pending(LocalAnalysisService.POLICY)).thenReturn(List.of(entry));
        when(repository.current(entry)).thenReturn(false);

        service.tick();

        verify(repository).current(entry);
        verify(repository, never()).result(any(), anyString(), any());
        verifyNoInteractions(gemini, reviews, notices);
    }

    @Test void candidateIsStoredOnlyAsAdvisoryResultAndOnlyOnePacketRunsPerTick() {
        var first = packet("p1");
        var entry = entry(List.of(first, packet("p2")), "{}");
        var result = new ScopedResult(Status.CANDIDATE, "SCOPED_REVIEWED",
            List.of(new Decision("issue1", Disposition.NO_CONFLICT, List.of("clause1"))), new Usage(123, 32));
        when(repository.pending(LocalAnalysisService.POLICY)).thenReturn(List.of(entry));
        when(repository.current(entry)).thenReturn(true);
        when(gemini.reviewPacket(first)).thenReturn(result);

        service.tick();

        verify(gemini).reviewPacket(first);
        verifyNoMoreInteractions(gemini);
        verify(repository).result(entry, "p1", result);
        // No notice content, publication path or review snapshot is changed by an advisory decision.
        verifyNoInteractions(reviews, notices);
    }

    @Test void temporaryQuotaWaitDoesNotPoisonTheCacheAndCanRetryNextTick() {
        var packet = packet("p1");
        var entry = entry(List.of(packet), "{}");
        var wait = new ScopedResult(Status.RATE_LIMITED, "RATE_WAIT", List.of(), new Usage(0, 0));
        var result = new ScopedResult(Status.CANDIDATE, "SCOPED_REVIEWED", List.of(), new Usage(15, 5));
        when(repository.pending(LocalAnalysisService.POLICY)).thenReturn(List.of(entry));
        when(repository.current(entry)).thenReturn(true);
        when(gemini.reviewPacket(packet)).thenReturn(wait, result);

        service.tick();
        verify(repository, never()).result(any(), anyString(), any());
        service.tick();

        verify(gemini, times(2)).reviewPacket(packet);
        verify(repository).result(entry, "p1", result);
        verify(repository, never()).result(entry, "p1", wait);
    }

    @Test void providerDisabledAndAlreadyReviewedPacketsDoNotProduceAnotherSavedResult() {
        var reviewed = packet("p1");
        var pending = packet("p2");
        var entry = entry(List.of(reviewed, pending), "{\"p1\":{\"code\":\"SCOPED_REVIEWED\"}}");
        when(repository.pending(LocalAnalysisService.POLICY)).thenReturn(List.of(entry));
        when(repository.current(entry)).thenReturn(true);
        when(gemini.reviewPacket(pending)).thenReturn(new ScopedResult(Status.DISABLED, "DISABLED", List.of(), new Usage(0, 0)));

        service.tick();

        verify(gemini, never()).reviewPacket(reviewed);
        verify(gemini).reviewPacket(pending);
        verify(repository, never()).result(any(), anyString(), any());
    }

    @Test void disabledSchedulerDoesNoWork() {
        new LocalAnalysisService(repository, reviews, notices, gemini, false).tick();
        verifyNoInteractions(repository, reviews, notices, gemini);
    }

    private ExtractionSnapshot snapshot() {
        var source = new Source("모집공고.pdf", URL, "a".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("공고문 1쪽", "▪ 소득 요건 소득요건 배제\n")));
        return new ExtractionSnapshot(new Draft(extractionId.toString(), noticeId.toString(), 7, "READY",
            Instant.parse("2026-11-07T00:00:00Z"), "", List.of(source)), "fingerprint");
    }

    private CompactReviewPacket packet(String id) {
        return new CompactReviewPacket(id, "test-policy", List.of(), List.of(), List.of(), List.of());
    }

    private LocalAnalysisRepository.Entry entry(List<CompactReviewPacket> packets, String results) {
        var book = new LocalRuleBook(LocalRuleInterpreter.POLICY, List.of(), List.of(), List.of(), 0);
        var metrics = new CompactReviewPlanner.Metrics(0, 0, 0, packets.size(), packets.size(), 0, 0, 0, 0);
        return new LocalAnalysisRepository.Entry(extractionId, "fingerprint", LocalAnalysisService.POLICY, noticeId, 7,
            book, new CompactReviewPlanner.Plan(packets, List.of(), List.of(), metrics), List.of(), json.readTree(results));
    }
}
