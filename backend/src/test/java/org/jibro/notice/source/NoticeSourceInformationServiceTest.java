package org.jibro.notice.source;

import static org.jibro.notice.source.NoticeSourceInformationModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.source.NoticeSourceInformationRepository.Snapshot;
import org.junit.jupiter.api.Test;

class NoticeSourceInformationServiceTest {
    @Test void changingSourceDuringReadDiscardsAllOldTextBeforeReturning() {
        var repository = mock(NoticeSourceInformationRepository.class);
        UUID id = UUID.randomUUID();
        var source = new Source("공고.pdf", "https://apply.lh.or.kr/notice.pdf", "a".repeat(64), "EXTRACTED", "", List.of(),
            List.of(new Part("공고문 1쪽", "신청 자격 원문")));
        var snapshot = new Snapshot(id, 2, UUID.randomUUID(), "f".repeat(64), Instant.now(), List.of(source));
        when(repository.publishedRevision(id)).thenReturn(2);
        when(repository.latest(id, 2)).thenReturn(Optional.of(snapshot));
        when(repository.current(snapshot)).thenReturn(true, false);
        var result = new NoticeSourceInformationService(repository, "apply.lh.or.kr,www.lh.or.kr").get(id);
        assertEquals(Information.pending(id), result);
        verify(repository).publishedRevision(id);
        verify(repository).latest(id, 2);
        verify(repository, times(2)).current(snapshot);
        verifyNoMoreInteractions(repository);
    }

    @Test void noCompletedCurrentExtractionReturnsPendingWithoutAnyOtherWork() {
        var repository = mock(NoticeSourceInformationRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.publishedRevision(id)).thenReturn(3);
        when(repository.latest(id, 3)).thenReturn(Optional.empty());
        assertEquals(State.PENDING, new NoticeSourceInformationService(repository, "apply.lh.or.kr").get(id).state());
        verify(repository).publishedRevision(id);
        verify(repository).latest(id, 3);
        verifyNoMoreInteractions(repository);
    }
}
