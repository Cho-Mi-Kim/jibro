package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.global.common.ApiException;
import org.jibro.notice.EvidenceSourceReader.Source;
import org.jibro.notice.EvidenceSourceReader.Snapshot;
import org.junit.jupiter.api.Test;

class EvidenceCheckServiceTest {
    private final UUID id = UUID.randomUUID();
    private final EvidenceSourceReader sources = mock(EvidenceSourceReader.class);
    private final AttachmentFingerprintClient transport = mock(AttachmentFingerprintClient.class);
    private final EvidenceCheckService checks = new EvidenceCheckService(sources, transport);

    @Test
    void groupsSameAddressButPreservesSourceLocationsAndDates() throws Exception {
        when(sources.read(id, 7)).thenReturn(new Snapshot(7, 3, List.of(
                source("https://apply.lh.or.kr/file#page=2"), source("https://apply.lh.or.kr/file#page=4"), source(null))));
        when(transport.probe("https://apply.lh.or.kr/file")).thenReturn(200);
        var report = checks.check(id, 7);
        assertEquals(3, report.version());
        assertEquals(3, report.results().size());
        assertEquals("REACHABLE", report.results().get(0).status());
        assertEquals("2026-11-01", report.results().get(0).checkedAt());
        assertEquals("https://apply.lh.or.kr/file#page=4", report.results().get(1).url());
        assertEquals("MISSING", report.results().get(2).status());
        verify(transport, times(1)).probe("https://apply.lh.or.kr/file");
        verify(sources, times(2)).read(id, 7);
    }

    @Test
    void doesNotTreatRedirectHeadRejectionOrUnknownHostAsVerifiedOrBrokenContent() throws Exception {
        when(sources.read(id, 1)).thenReturn(new Snapshot(1, 1, List.of(source("https://apply.lh.or.kr/redirect"),
                source("https://apply.lh.or.kr/blocked"), source("https://www.gov.kr/"), source("https://apply.lh.or.kr/missing"))));
        when(transport.probe("https://apply.lh.or.kr/redirect")).thenReturn(302);
        when(transport.probe("https://apply.lh.or.kr/blocked")).thenReturn(405);
        when(transport.probe("https://www.gov.kr/")).thenThrow(new IOException("ATTACHMENT_URL_REJECTED"));
        when(transport.probe("https://apply.lh.or.kr/missing")).thenReturn(404);
        assertEquals(List.of("MANUAL", "MANUAL", "UNSUPPORTED", "UNAVAILABLE"),
                checks.check(id, 1).results().stream().map(EvidenceCheckService.Result::status).toList());
    }

    @Test
    void enforcesBoundedRequestsAndDiscardsStaleResults() throws Exception {
        var snapshot = new Snapshot(1, 1, IntStream.range(0, 11).mapToObj(n -> source("https://apply.lh.or.kr/" + n)).toList());
        when(sources.read(id, 1)).thenReturn(snapshot);
        when(transport.probe(anyString())).thenReturn(200);
        assertEquals("DEFERRED", checks.check(id, 1).results().get(10).status());
        verify(transport, times(10)).probe(anyString());
        when(sources.read(id, 1)).thenReturn(snapshot).thenThrow(ApiException.conflict());
        assertEquals(409, assertThrows(ApiException.class, () -> checks.check(id, 1)).status);
    }

    private Source source(String url) {
        return new Source("CONDITION", "공고문 4쪽", url, "2026-11-01");
    }
}
